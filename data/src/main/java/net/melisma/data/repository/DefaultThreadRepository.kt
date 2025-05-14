package net.melisma.data.repository

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.repository.ThreadRepository
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultThreadRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : ThreadRepository {

    private val TAG = "DefaultThreadRepo"
    private val _threadDataState = MutableStateFlow<ThreadDataState>(ThreadDataState.Initial)
    override val threadDataState: StateFlow<ThreadDataState> = _threadDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var fetchJob: Job? = null

    // Number of initial messages to fetch to discover recent thread IDs
    private val initialMessageFetchCountForThreadDiscovery = 50

    // Max messages to show per thread in the summary list (if API limits, actual count might be less)
    private val maxMessagesPerThreadInList = 3

    init {
        Log.d(
            TAG, "Initializing DefaultThreadRepository. Injected maps:" +
                    " mailApiServices keys: ${mailApiServices.keys}, " +
                    " mailApiServices values: ${mailApiServices.values.joinToString { it.javaClass.name }}, " +
                    " errorMappers keys: ${errorMappers.keys}"
        )
    }

    override suspend fun setTargetFolderForThreads(
        account: Account?,
        folder: MailFolder?,
        activityForRefresh: Activity?
    ) {
        Log.d(
            TAG,
            "setTargetFolderForThreads: Account=${account?.username}, Folder=${folder?.displayName}, Job Active: ${fetchJob?.isActive}"
        )

        if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id && _threadDataState.value !is ThreadDataState.Initial) {
            Log.d(
                TAG,
                "setTargetFolderForThreads: Same target and data already loaded/loading. To refresh, call refreshThreads()."
            )
            return
        }
        cancelAndClearJob("New target folder for threads: ${folder?.displayName}")
        currentTargetAccount = account
        currentTargetFolder = folder

        if (account == null || folder == null) {
            _threadDataState.value = ThreadDataState.Initial
            Log.d(TAG, "setTargetFolderForThreads: Cleared target, state set to Initial.")
            return
        }
        _threadDataState.value = ThreadDataState.Loading
        launchThreadFetchJobInternal(
            account,
            folder,
            isRefresh = false,
            activity = activityForRefresh
        )
    }

    override suspend fun refreshThreads(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        if (account == null || folder == null) {
            Log.w(TAG, "refreshThreads: No target account or folder set. Skipping.")
            _threadDataState.value = ThreadDataState.Error("Cannot refresh: No folder selected.")
            return
        }
        Log.d(
            TAG,
            "refreshThreads called for folder: ${folder.displayName}, Account: ${account.username}"
        )
        // Set to loading only if not already loading to avoid UI flicker if refresh is called rapidly
        if (_threadDataState.value !is ThreadDataState.Loading) {
            _threadDataState.value = ThreadDataState.Loading
        }
        launchThreadFetchJobInternal(account, folder, isRefresh = true, activity = activity)
    }

    private fun launchThreadFetchJobInternal(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean,
        activity: Activity? // Parameter for future use if auth refresh needs activity
    ) {
        cancelAndClearJob("Launching new thread fetch for ${folder.displayName}. Refresh: $isRefresh")
        Log.d(
            TAG,
            "[${folder.displayName}] launchThreadFetchJobInternal for account type: ${account.providerType}"
        )

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        Log.d(
            TAG,
            "[${folder.displayName}] Using ApiService: ${apiService?.javaClass?.name ?: "NULL"} for provider: $providerType"
        )

        if (apiService == null || errorMapper == null) {
            val errorMsg =
                "Unsupported account type: $providerType or missing services for thread fetching."
            Log.e(TAG, errorMsg)
            _threadDataState.value = ThreadDataState.Error(errorMsg)
            return
        }

        fetchJob = externalScope.launch(ioDispatcher) {
            Log.i(TAG, "[${folder.displayName}] Thread fetch job started. Refresh: $isRefresh")
            try {
                // Step 1: Fetch initial batch of messages to get their thread IDs
                Log.d(
                    TAG,
                    "[${folder.displayName}] Fetching initial messages to discover thread IDs..."
                )
                val initialMessagesResult = apiService.getMessagesForFolder(
                    folderId = folder.id,
                    selectFields = listOf("id", "conversationId"),
                    maxResults = initialMessageFetchCountForThreadDiscovery
                )
                ensureActive() // Check for cancellation

                if (initialMessagesResult.isFailure) {
                    val error =
                        errorMapper.mapNetworkOrApiException(initialMessagesResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "[${folder.displayName}] Error fetching initial messages: $error",
                        initialMessagesResult.exceptionOrNull()
                    )
                    _threadDataState.value = ThreadDataState.Error(error)
                    return@launch
                }

                val initialMessages = initialMessagesResult.getOrThrow()
                Log.d(
                    TAG,
                    "[${folder.displayName}] Fetched ${initialMessages.size} initial messages."
                )
                if (initialMessages.isNotEmpty()) {
                    Log.d(
                        TAG, "[${folder.displayName}] First 5 initial messages (or fewer): " +
                                initialMessages.take(5)
                                    .joinToString { "MsgID: ${it.id}, ThreadID: ${it.threadId}" })
                }

                val uniqueThreadIds = initialMessages.mapNotNull { it.threadId }.distinct()
                Log.i(
                    TAG,
                    "[${folder.displayName}] Discovered ${uniqueThreadIds.size} unique thread IDs from ${initialMessages.size} initial messages."
                )
                if (uniqueThreadIds.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "[${folder.displayName}] Unique thread IDs (first 5 or fewer): ${
                            uniqueThreadIds.take(5)
                        }"
                    )
                }

                if (uniqueThreadIds.isEmpty()) {
                    Log.i(
                        TAG,
                        "[${folder.displayName}] No unique thread IDs found in initial messages."
                    )
                    _threadDataState.value = ThreadDataState.Success(emptyList())
                    return@launch
                }

                // Step 2: For each unique threadId, fetch all its messages
                // Using supervisorScope to ensure one failing thread fetch doesn't cancel others.
                val mailThreads = supervisorScope {
                    uniqueThreadIds.map { threadId ->
                        async { // Fetch each thread concurrently
                            ensureActive()
                            Log.d(
                                TAG,
                                "[${folder.displayName}] Fetching full details for thread ID: $threadId"
                            )
                            val messagesInThreadResult = apiService.getMessagesForThread(
                                threadId = threadId,
                                folderId = folder.id
                            )
                            ensureActive()

                            if (messagesInThreadResult.isSuccess) {
                                val messages = messagesInThreadResult.getOrThrow()
                                Log.d(
                                    TAG,
                                    "[${folder.displayName}] Thread $threadId: Fetched ${messages.size} messages."
                                )
                                if (messages.isNotEmpty()) {
                                    assembleMailThread(threadId, messages, account.id)
                                } else {
                                    Log.w(
                                        TAG,
                                        "[${folder.displayName}] Thread $threadId returned no messages."
                                    )
                                    null // Filter this out later
                                }
                            } else {
                                Log.e(
                                    TAG,
                                    "[${folder.displayName}] Failed to fetch messages for thread $threadId: ${messagesInThreadResult.exceptionOrNull()?.message}"
                                )
                                null // Filter this out later
                            }
                        }
                    }.awaitAll()
                        .filterNotNull() // awaitAll and filter out nulls from failed fetches
                }
                ensureActive()

                val sortedThreads = mailThreads.sortedByDescending { it.lastMessageDateTime }
                Log.i(
                    TAG,
                    "[${folder.displayName}] Successfully assembled ${sortedThreads.size} threads."
                )
                if (sortedThreads.isNotEmpty()) {
                    Log.d(
                        TAG, "[${folder.displayName}] First 3 assembled threads (or fewer): " +
                                sortedThreads.take(3)
                                    .joinToString { "ThreadID: ${it.id}, Subject: '${it.subject}', Msgs: ${it.messages.size}" })
                }
                _threadDataState.value = ThreadDataState.Success(sortedThreads)

            } catch (e: CancellationException) {
                Log.w(TAG, "[${folder.displayName}] Thread fetch job was cancelled: ${e.message}")
                // If the job was cancelled while loading, it might be appropriate to revert
                // to Initial or a specific "Cancelled" state if the UI needs to react.
                // For now, if it was loading, it might just stop showing loading.
                if (_threadDataState.value is ThreadDataState.Loading && isActive) {
                    _threadDataState.value =
                        ThreadDataState.Error("Fetch cancelled for ${folder.displayName}")
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                val error = errorMapper.mapNetworkOrApiException(e)
                Log.e(
                    TAG,
                    "[${folder.displayName}] Critical exception during thread fetch: $error",
                    e
                )
                if (isActive) { // Only update state if the coroutine is still active
                    _threadDataState.value = ThreadDataState.Error(error)
                }
            } finally {
                Log.d(
                    TAG,
                    "[${folder.displayName}] Thread fetch job 'finally' block. Current job hash: ${coroutineContext[Job]?.hashCode()}, stored job hash: ${fetchJob?.hashCode()}"
                )
                // Clear the job reference only if this is the job that's completing
                if (fetchJob == coroutineContext[Job]) {
                    fetchJob = null
                    Log.d(TAG, "[${folder.displayName}] Cleared fetchJob reference.")
                }
            }
        }
    }

    private fun assembleMailThread(
        threadId: String,
        messages: List<Message>,
        accountId: String
    ): MailThread? {
        if (messages.isEmpty()) {
            Log.w(TAG, "Cannot assemble MailThread for $threadId: no messages provided.")
            return null
        }

        // Sort messages by date, most recent last to easily get the latest message
        val sortedMessages = messages.sortedWith(compareBy { parseIsoDate(it.receivedDateTime) })
        val latestMessage = sortedMessages.lastOrNull()
            ?: messages.first() // Fallback to first if sorting fails or list empty after sort (should not happen)

        val subject = latestMessage.subject?.takeIf { it.isNotBlank() }
            ?: sortedMessages.firstNotNullOfOrNull { it.subject?.takeIf { subj -> subj.isNotBlank() } }
            ?: "(No Subject)"

        val participants = sortedMessages
            .mapNotNull {
                it.senderName?.trim()?.takeIf { name -> name.isNotEmpty() } ?: it.senderAddress
            }
            .distinct()
        val participantsSummary = when {
            participants.isEmpty() -> "Unknown Participants"
            participants.size <= 2 -> participants.joinToString(", ")
            else -> "${participants.take(2).joinToString(", ")} & ${participants.size - 2} more"
        }

        val lastMessageParsedDate = parseIsoDate(latestMessage.receivedDateTime)

        return MailThread(
            id = threadId,
            messages = sortedMessages, // Store all messages
            subject = subject,
            snippet = latestMessage.bodyPreview?.take(120), // Slightly longer snippet
            lastMessageDateTime = lastMessageParsedDate,
            participantsSummary = participantsSummary,
            unreadMessageCount = sortedMessages.count { !it.isRead },
            totalMessageCount = sortedMessages.size,
            accountId = accountId
        )
    }

    private fun parseIsoDate(dateString: String?): Date? {
        if (dateString.isNullOrBlank()) return null
        // Try common ISO 8601 formats
        val formatStrings = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", // With 7 millis
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",     // With 3 millis
            "yyyy-MM-dd'T'HH:mm:ss'Z'"          // Without millis
        )
        for (formatString in formatStrings) {
            try {
                val sdf = SimpleDateFormat(formatString, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateString)
            } catch (e: ParseException) {
                // Continue to next format
            }
        }
        Log.w(TAG, "Failed to parse date string: $dateString with common ISO formats.")
        return null // Or throw, or return a default
    }

    private fun cancelAndClearJob(reason: String) {
        fetchJob?.let {
            if (it.isActive) {
                Log.d(
                    TAG,
                    "Cancelling previous thread fetch job. Reason: $reason. Hash: ${it.hashCode()}"
                )
                it.cancel(CancellationException("Job cancelled: $reason"))
            }
        }
        fetchJob = null
        Log.d(TAG, "cancelAndClearJob: Cleared fetchJob. Reason: $reason")
    }
} 