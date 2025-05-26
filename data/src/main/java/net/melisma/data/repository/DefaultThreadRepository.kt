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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import net.melisma.core_data.repository.AccountRepository
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
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountRepository: AccountRepository // To get account for provider type
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
                // Ensure selectFields for initial discovery includes "id" and "conversationId" (or provider's equivalent for threadId)
                val discoverySelectFields = listOfNotNull(
                    "id",
                    if (account.providerType.equals(
                            "GOOGLE",
                            ignoreCase = true
                        )
                    ) "threadId" else "conversationId",
                    "subject" // Also get subject for logging a more useful initial message
                )

                val initialMessagesResult = apiService.getMessagesForFolder(
                    folderId = folder.id,
                    selectFields = discoverySelectFields,
                    maxResults = initialMessageFetchCountForThreadDiscovery
                )
                ensureActive() // Check for cancellation

                if (initialMessagesResult.isFailure) {
                    val details =
                        errorMapper.mapExceptionToErrorDetails(initialMessagesResult.exceptionOrNull())
                    Log.e(
                        TAG,
                        "[${folder.displayName}] Error fetching initial messages for thread discovery: ${details.message}",
                        initialMessagesResult.exceptionOrNull()
                    )
                    _threadDataState.value =
                        ThreadDataState.Error("Failed to discover threads: ${details.message}")
                    return@launch
                }

                val initialMessages = initialMessagesResult.getOrThrow()
                Log.d(
                    TAG,
                    "[${folder.displayName}] Fetched ${initialMessages.size} initial messages for thread discovery."
                )
                if (initialMessages.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "[${folder.displayName}] First 5 initial messages (or fewer) for discovery: " +
                                initialMessages.take(5)
                                    .joinToString { "MsgID: ${it.id}, ThreadID: ${it.threadId}, Subject: ${it.subject}" })
                }

                val uniqueThreadIds = initialMessages.mapNotNull { it.threadId }.distinct()
                Log.i(
                    TAG,
                    "[${folder.displayName}] Discovered ${uniqueThreadIds.size} unique thread IDs from ${initialMessages.size} initial messages."
                )

                val fetchedMailThreads = mutableListOf<MailThread>()

                if (uniqueThreadIds.isNotEmpty()) {
                    // Step 2: For each unique thread ID, fetch its messages and construct a MailThread
                    val deferredMailThreads = uniqueThreadIds.map { threadId ->
                        async(ioDispatcher) { // Using ioDispatcher for each API call
                            ensureActive() // Check before each API call
                            Log.d(
                                TAG,
                                "[${folder.displayName}] Fetching messages for ThreadID: $threadId"
                            )

                            val threadMessagesSelectFields = listOf(
                                "id", "subject", "sender", "from", "toRecipients",
                                "receivedDateTime", "sentDateTime", "bodyPreview", "isRead",
                                if (account.providerType.equals(
                                        "GOOGLE",
                                        ignoreCase = true
                                    )
                                ) "threadId" else "conversationId"
                            )

                            val messagesInThreadResult = apiService.getMessagesForThread(
                                threadId = threadId,
                                folderId = folder.id, // Pass folderId for context
                                selectFields = threadMessagesSelectFields,
                                maxResults = 100 // Fetch up to 100 messages per thread
                            )
                            ensureActive()

                            if (messagesInThreadResult.isSuccess) {
                                val messagesInThread = messagesInThreadResult.getOrThrow()
                                if (messagesInThread.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "[${folder.displayName}] Fetched ${messagesInThread.size} messages for ThreadID: $threadId."
                                    )
                                    constructMailThread(threadId, messagesInThread, account.id)
                                } else {
                                    Log.w(
                                        TAG,
                                        "[${folder.displayName}] No messages found for ThreadID: $threadId, though ID was discovered. Skipping."
                                    )
                                    null
                                }
                            } else {
                                val errorDetails =
                                    errorMapper.mapExceptionToErrorDetails(messagesInThreadResult.exceptionOrNull())
                                Log.e(
                                    TAG,
                                    "[${folder.displayName}] Error fetching messages for ThreadID $threadId: ${errorDetails.message}",
                                    messagesInThreadResult.exceptionOrNull()
                                )
                                null
                            }
                        }
                    }
                    val results = deferredMailThreads.awaitAll()
                    fetchedMailThreads.addAll(results.filterNotNull())
                    Log.i(
                        TAG,
                        "[${folder.displayName}] Successfully constructed ${fetchedMailThreads.size} MailThread objects."
                    )
                } else {
                    Log.i(
                        TAG,
                        "[${folder.displayName}] No unique thread IDs discovered. No threads to fetch details for."
                    )
                }

                if (!isActive) {
                    Log.i(
                        TAG,
                        "[${folder.displayName}] Job cancelled after fetching all thread details."
                    )
                    return@launch
                }

                if (fetchedMailThreads.isEmpty() && initialMessages.isNotEmpty() && uniqueThreadIds.isEmpty()) {
                    Log.w(
                        TAG,
                        "[${folder.displayName}] No threads constructed. This might be because all discovered messages had null threadIds (e.g. conversationId was null from MS Graph for all items)."
                    )
                }

                _threadDataState.value = ThreadDataState.Success(
                    fetchedMailThreads.sortedByDescending { it.lastMessageDateTime }
                )
                Log.i(
                    TAG,
                    "[${folder.displayName}] Thread fetch job completed. Emitted Success with ${fetchedMailThreads.size} threads."
                )

            } catch (e: CancellationException) {
                Log.i(
                    TAG,
                    "[${folder.displayName}] Thread fetch job explicitly cancelled: ${e.message}"
                )
            } catch (e: Exception) {
                if (isActive) { 
                    val details = errorMapper.mapExceptionToErrorDetails(e)
                    Log.e(
                        TAG,
                        "[${folder.displayName}] Unexpected error in thread fetch job: ${details.message}",
                        e
                    )
                    _threadDataState.value =
                        ThreadDataState.Error("Failed to fetch threads: ${details.message}")
                } else {
                    Log.i(
                        TAG,
                        "[${folder.displayName}] Exception after job cancellation, not updating to error state: ${e.message}"
                    )
                }
            } finally {
                Log.d(TAG, "[${folder.displayName}] Thread fetch job's coroutine scope finished.")
            }
        }
    }

    private fun constructMailThread(
        threadId: String,
        messages: List<Message>,
        accountId: String
    ): MailThread? {
        if (messages.isEmpty()) return null

        val sortedMessages =
            messages.sortedByDescending { parseMessageDate(it.receivedDateTime ?: it.sentDateTime) }
        val latestMessage = sortedMessages.firstOrNull()
            ?: return null // Handle case where sortedMessages might be empty after filtering (though unlikely here)

        val subject = latestMessage.subject ?: sortedMessages.firstNotNullOfOrNull { it.subject }
        ?: "(No Subject)"
        val snippet = latestMessage.bodyPreview ?: ""

        val lastMessageDate =
            parseMessageDate(latestMessage.receivedDateTime ?: latestMessage.sentDateTime)

        val unreadCount = sortedMessages.count { !it.isRead }
        val totalCount = sortedMessages.size

        val participants = sortedMessages
            .asSequence() // Use sequence for potentially better performance on multiple operations
            .mapNotNull { it.senderName?.takeIf { name -> name.isNotBlank() } ?: it.senderAddress }
            .distinct()
            .take(3)
            .toList()
            
        val participantsSummary = when {
            participants.size > 2 -> "${participants.take(2).joinToString(", ")} & more"
            participants.isNotEmpty() -> participants.joinToString(", ")
            else -> null
        }

        return MailThread(
            id = threadId,
            messages = sortedMessages, 
            subject = subject,
            snippet = snippet,
            lastMessageDateTime = lastMessageDate,
            participantsSummary = participantsSummary,
            unreadMessageCount = unreadCount,
            totalMessageCount = totalCount,
            accountId = accountId
        )
    }

    private fun parseMessageDate(dateTimeString: String?): Date? {
        if (dateTimeString.isNullOrBlank()) return null
        val isoFormatters = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        )
        for (formatter in isoFormatters) {
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            try {
                return formatter.parse(dateTimeString)
            } catch (e: ParseException) {
                // Try next format
            }
        }
        Log.w(TAG, "Failed to parse date string for MailThread construction: $dateTimeString")
        return null
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

    override suspend fun markThreadRead(
        account: Account,
        threadId: String,
        isRead: Boolean
    ): Result<Unit> {
        Log.d(
            TAG,
            "markThreadRead called for thread: $threadId, account: ${account.username}, isRead: $isRead"
        )
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("markThreadRead not implemented for account ${account.providerType}"))

        val result = apiService.markThreadRead(threadId, isRead)
        if (result.isSuccess) {
            _threadDataState.update { currentState ->
                if (currentState is ThreadDataState.Success) {
                    val updatedThreads = currentState.threads.map { mailThread ->
                        if (mailThread.id == threadId) {
                            val updatedMessages =
                                mailThread.messages.map { it.copy(isRead = isRead) }
                            val newUnreadCount =
                                if (isRead) 0 else mailThread.totalMessageCount // Simplified
                            mailThread.copy(
                                messages = updatedMessages,
                                unreadMessageCount = newUnreadCount
                                // Removed non-existent isRead = isRead
                            )
                        } else mailThread
                    }
                    currentState.copy(threads = updatedThreads)
                } else {
                    currentState
                }
            }
        }
        return result
    }

    override suspend fun deleteThread(account: Account, threadId: String): Result<Unit> {
        Log.d(TAG, "deleteThread called for thread: $threadId, account: ${account.username}")
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("deleteThread not implemented for account ${account.providerType}"))

        val result = apiService.deleteThread(threadId)
        if (result.isSuccess) {
            _threadDataState.update { currentState ->
                if (currentState is ThreadDataState.Success) {
                    currentState.copy(threads = currentState.threads.filterNot { it.id == threadId })
                } else {
                    currentState
                }
            }
        }
        return result
    }

    override suspend fun moveThread(
        account: Account,
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Log.d(
            TAG,
            "moveThread called for thread: $threadId, from folder: $currentFolderId, to folder: $destinationFolderId, account: ${account.username}"
        )
        val apiService = mailApiServices[account.providerType.uppercase()]
            ?: return Result.failure(NotImplementedError("moveThread not implemented for account ${account.providerType}"))

        if (currentFolderId.isBlank()) {
            Log.e(
                TAG,
                "moveThread: provided currentFolderId is blank. This is required."
            )
            return Result.failure(IllegalArgumentException("currentFolderId cannot be blank."))
        }

        val result = apiService.moveThread(threadId, currentFolderId, destinationFolderId)
        if (result.isSuccess) {
            _threadDataState.update { currentState ->
                if (currentState is ThreadDataState.Success) {
                    currentState.copy(threads = currentState.threads.filterNot { it.id == threadId })
                } else {
                    currentState
                }
            }
        }
        return result
    }
} 