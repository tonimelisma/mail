package net.melisma.data.repository

import android.app.Activity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.MessageEntity
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import androidx.room.withTransaction

@Singleton
class DefaultThreadRepository @Inject constructor(
    private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>,
    private val accountRepository: AccountRepository,
    private val messageDao: MessageDao,
    private val appDatabase: AppDatabase
) : ThreadRepository {

    private val TAG = "DefaultThreadRepo"
    private val _threadDataState = MutableStateFlow<ThreadDataState>(ThreadDataState.Initial)
    override val threadDataState: StateFlow<ThreadDataState> = _threadDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    
    private var collectionJob: Job? = null
    private var networkFetchJob: Job? = null

    // Max messages to fetch from API in one go for a folder refresh.
    private val FOLDER_REFRESH_PAGE_SIZE = 150

    init {
        Timber.d(
            "Initializing DefaultThreadRepository. Injected maps: mailApiServices keys: ${mailApiServices.keys}, errorMappers keys: ${errorMappers.keys}"
        )
    }

    override suspend fun setTargetFolderForThreads(
        account: Account?,
        folder: MailFolder?,
        activityForRefresh: Activity?
    ) {
        Timber.d(
            "setTargetFolderForThreads: Account=${account?.username}, Folder=${folder?.displayName}, collectionJob Active: ${collectionJob?.isActive}, networkJob Active: ${networkFetchJob?.isActive}"
        )

        if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id && _threadDataState.value !is ThreadDataState.Initial) {
            Timber.d("setTargetFolderForThreads: Same target. If data is already loaded, not forcing reload unless refreshThreads() is called.")
            if (_threadDataState.value is ThreadDataState.Success) {
                ensureNetworkFetch(account!!, folder!!, isExplicitRefresh = false, isBackgroundCheck = true)
            }
            return
        }

        collectionJob?.cancel(CancellationException("New target folder set for threads: ${folder?.displayName}"))
        networkFetchJob?.cancel(CancellationException("New target folder set for threads: ${folder?.displayName}, cancelling network job too"))
        
        currentTargetAccount = account
        currentTargetFolder = folder

        if (account == null || folder == null) {
            _threadDataState.value = ThreadDataState.Initial
            Timber.d("setTargetFolderForThreads: Cleared target, state set to Initial.")
            return
        }
        
        // Shadowing for non-null access within coroutine scopes
        val currentAccount = account
        val currentFolder = folder

        _threadDataState.value = ThreadDataState.Loading
        
        collectionJob = externalScope.launch {
            Timber.d("[${currentFolder.displayName}] Starting to observe MessageDao for account ${currentAccount.id}, folder ${currentFolder.id}")
            messageDao.getMessagesForFolder(currentAccount.id, currentFolder.id)
                .map { messageEntities ->
                    Timber.d("[${currentFolder.displayName}] MessageDao emitted ${messageEntities.size} entities. Grouping into threads.")
                    groupAndMapMessagesToMailThreads(messageEntities, currentAccount.id, currentFolder.id)
                }
                .distinctUntilChanged()
                .catch { e ->
                    Timber.e(e, "[${currentFolder.displayName}] Error collecting messages from DAO for threads.")
                    _threadDataState.value = ThreadDataState.Error("Error loading threads from local cache: ${e.message}")
                }
                .collectLatest { mailThreads ->
                    Timber.i("[${currentFolder.displayName}] Successfully grouped ${mailThreads.size} MailThreads from DB. Emitting Success.")
                    if (mailThreads.isNotEmpty() || networkFetchJob?.isActive != true) {
                        _threadDataState.value = ThreadDataState.Success(mailThreads)
                    } else if (_threadDataState.value !is ThreadDataState.Loading && mailThreads.isEmpty()) {
                        _threadDataState.value = ThreadDataState.Loading
                    }
                    ensureNetworkFetch(currentTargetAccount!!, currentTargetFolder!!, isExplicitRefresh = false, isBackgroundCheck = false)
                }
        }
    }
    
    private fun ensureNetworkFetch(account: Account, folder: MailFolder, isExplicitRefresh: Boolean, isBackgroundCheck: Boolean) {
        if (networkFetchJob?.isActive == true) {
            Timber.d("[${folder.displayName}] Network fetch job already active. Skipping duplicate call.")
            return
        }
        Timber.d("[${folder.displayName}] Ensuring network fetch. Explicit: $isExplicitRefresh, Background: $isBackgroundCheck")
        if (!isExplicitRefresh && !isBackgroundCheck && _threadDataState.value is ThreadDataState.Success) {
             Timber.d("[${folder.displayName}] Data already shown from cache, network fetch will be background.")
        } else if (!isBackgroundCheck) {
            _threadDataState.value = ThreadDataState.Loading 
        }

        networkFetchJob = launchThreadFetchJobInternal(
            account = account,
            folder = folder,
            isRefresh = isExplicitRefresh
        )
    }

    override suspend fun refreshThreads(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        if (account == null || folder == null) {
            Timber.w("refreshThreads: No target account or folder set. Skipping.")
            _threadDataState.value = ThreadDataState.Error("Cannot refresh: No folder selected.")
            return
        }
        Timber.d("refreshThreads called for folder: ${folder.displayName}, Account: ${account.username}")
        networkFetchJob?.cancel(CancellationException("User triggered refresh for ${folder.displayName}"))
        
        _threadDataState.value = ThreadDataState.Loading 
        
        networkFetchJob = launchThreadFetchJobInternal(account, folder, isRefresh = true)
    }

    private fun launchThreadFetchJobInternal(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean
    ): Job {
        Timber.i("[${folder.displayName}] Launching Network Thread Fetch Job. isRefresh: $isRefresh")

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (apiService == null || errorMapper == null) {
            val errorMsg = "Unsupported account type: $providerType or missing services for thread fetching."
            Timber.e(errorMsg)
            _threadDataState.value = ThreadDataState.Error(errorMsg)
            return Job().apply { complete() }
        }

        return externalScope.launch(ioDispatcher) {
            Timber.i("[${folder.displayName}] Network thread fetch job started. isRefresh: $isRefresh")
            try {
                val messagesFromApiResult = apiService.getMessagesForFolder(
                    folderId = folder.id,
                    selectFields = listOf("id", "threadId", "subject", "snippet", "senderName", "senderAddress", "recipientNames", "recipientAddresses", "timestamp", "sentTimestamp", "isRead", "isStarred", "hasAttachments", "conversationId", "parentFolderId", "from", "toRecipients", "ccRecipients", "bccRecipients", "receivedDateTime"),
                    maxResults = FOLDER_REFRESH_PAGE_SIZE 
                )
                ensureActive()

                if (messagesFromApiResult.isSuccess) {
                    val apiMessages = messagesFromApiResult.getOrThrow()
                    Timber.i("[${folder.displayName}] Fetched ${apiMessages.size} messages from API for folder ${folder.id}.")

                    if (apiMessages.isNotEmpty()) {
                        val messageEntities = apiMessages.map { it.toEntity(account.id, folder.id) }
                        
                        appDatabase.withTransaction {
                            if (isRefresh) {
                                Timber.i("[${folder.displayName}] Explicit refresh: Clearing old messages from DB for this folder.")
                                messageDao.deleteMessagesForFolder(account.id, folder.id)
                            }
                            Timber.d("[${folder.displayName}] Upserting ${messageEntities.size} messages into MessageDao.")
                            messageDao.insertOrUpdateMessages(messageEntities)
                        }
                        Timber.i("[${folder.displayName}] Messages saved to DB. DAO Flow will update UI.")
                    } else {
                         Timber.i("[${folder.displayName}] No messages returned from API for this folder. If local cache was also empty, UI will show empty state.")
                    }
                    if (_threadDataState.value is ThreadDataState.Loading) {
                        Timber.d("[${folder.displayName}] Network fetch finished. Trusting DAO flow to update state from Loading if needed.")
                    }

                } else {
                    val exception = messagesFromApiResult.exceptionOrNull()
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(exception ?: Throwable("Unknown API error"))
                    Timber.e(exception, "[${folder.displayName}] Error fetching messages from network: ${errorDetails.message}")
                    if (_threadDataState.value !is ThreadDataState.Success) {
                         _threadDataState.value = ThreadDataState.Error("Network error: ${errorDetails.message}")
                    } else {
                        Timber.w("[${folder.displayName}] Network error but already showing cached data. Error: ${errorDetails.message}")
                    }
                }
            } catch (e: CancellationException) {
                Timber.i("[${folder.displayName}] Network thread fetch job was cancelled. Reason: ${e.message}")
            } catch (e: Exception) {
                Timber.e(e, "[${folder.displayName}] Exception during network thread fetch.")
                if (_threadDataState.value !is ThreadDataState.Success) {
                    val errorDetails = errorMapper.mapExceptionToErrorDetails(e)
                    _threadDataState.value = ThreadDataState.Error("Failed to fetch threads: ${errorDetails.message}")
                } else {
                     Timber.w("[${folder.displayName}] Exception during network fetch but already showing cached data. Error: ${e.message}")
                }
            } finally {
                Timber.i("[${folder.displayName}] Network thread fetch job finished (was ${if(isActive) "active" else "cancelled/completed"}).")
            }
        }
    }

    private fun groupAndMapMessagesToMailThreads(
        messageEntities: List<MessageEntity>,
        accountId: String,
        folderIdForContext: String
    ): List<MailThread> {
        if (messageEntities.isEmpty()) return emptyList()

        val threadsMap = messageEntities
            .filter { !it.threadId.isNullOrBlank() }
            .groupBy { it.threadId }

        return threadsMap.mapNotNull { (threadId, messagesInThread) ->
            if (messagesInThread.isEmpty() || threadId == null) return@mapNotNull null

            val sortedMessages = messagesInThread.sortedByDescending { it.timestamp }
            val latestMessage = sortedMessages.first()
            
            val domainMessages = sortedMessages.map { it.toDomainModel() }

            // Create a summary of participants
            val participantsList = domainMessages
                .flatMap { 
                    val senders = listOfNotNull(it.senderName, it.senderAddress?.substringBefore('@'))
                    val recipients = it.recipientNames.orEmpty() + it.recipientAddresses.orEmpty().mapNotNull { addr -> addr?.substringBefore('@') }
                    (senders + recipients).filterNotNull().filter { name -> name.isNotBlank() }
                }
                .distinct()
            
            val participantsSummary = if (participantsList.size > 2) {
                "${participantsList.take(2).joinToString()}, +${participantsList.size - 2}"
            } else {
                participantsList.joinToString()
            }

            MailThread(
                id = threadId,
                accountId = accountId,
                messages = domainMessages,
                subject = latestMessage.subject ?: domainMessages.mapNotNull { it.subject }.firstOrNull { it.isNotBlank() } ?: "No Subject",
                snippet = latestMessage.snippet ?: "",
                lastMessageDateTime = Date(latestMessage.timestamp),
                totalMessageCount = domainMessages.size,
                unreadMessageCount = domainMessages.count { !it.isRead },
                participantsSummary = participantsSummary
            )
        }.sortedByDescending { it.lastMessageDateTime?.time }
    }

    // Methods from ThreadRepository interface - STUB IMPLEMENTATIONS
    override suspend fun markThreadRead(account: Account, threadId: String, isRead: Boolean): Result<Unit> {
        Timber.d("markThreadRead called for account ${account.id}, threadId $threadId, isRead $isRead - NOT IMPLEMENTED")
        // TODO: Implement actual logic:
        // 1. Get all messages for the threadId from messageDao.
        // 2. Update their isRead status.
        // 3. Save them back via messageDao.insertOrUpdateMessages (ensure this handles updates correctly).
        // 4. Optionally, make an API call to sync this change to the server.
        return Result.failure(NotImplementedError("markThreadRead is not yet implemented."))
    }

    override suspend fun deleteThread(account: Account, threadId: String): Result<Unit> {
        Timber.d("deleteThread called for account ${account.id}, threadId $threadId - NOT IMPLEMENTED")
        // TODO: Implement actual logic:
        // 1. Mark messages in thread as locally deleted / move to trash via API.
        // 2. Update local DB: messageDao.deleteMessagesByThreadId(accountId, threadId) or update flag.
        return Result.failure(NotImplementedError("deleteThread is not yet implemented."))
    }

    override suspend fun moveThread(
        account: Account,
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Timber.d("moveThread called for account ${account.id}, threadId $threadId from $currentFolderId to $destinationFolderId - NOT IMPLEMENTED")
        // TODO: Implement actual logic:
        // 1. Call API to move the thread/messages.
        // 2. Update local DB: update folderId for messages in threadId from currentFolderId to destinationFolderId.
        return Result.failure(NotImplementedError("moveThread is not yet implemented."))
    }

    companion object {
        private fun parseIso8601DateTime(dateTimeString: String?): Long? {
            if (dateTimeString.isNullOrBlank()) return null
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(dateTimeString)?.time
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse date-time string: $dateTimeString")
                null
            }
        }
    }
} 