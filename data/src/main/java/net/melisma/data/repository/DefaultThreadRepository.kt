package net.melisma.data.repository

import android.app.Activity
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.EntitySyncStatus
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.ThreadRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.MessageEntity
import net.melisma.data.mapper.toDomainModel
import net.melisma.data.sync.SyncController
import net.melisma.data.sync.workers.ActionUploadWorker
import net.melisma.core_data.model.SyncJob
import net.melisma.core_db.dao.PendingActionDao
import net.melisma.core_db.entity.PendingActionEntity
import net.melisma.core_db.model.PendingActionStatus
import timber.log.Timber
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
    private val accountRepository: AccountRepository,
    private val messageDao: MessageDao,
    private val appDatabase: AppDatabase,
    private val pendingActionDao: PendingActionDao,
    private val syncController: SyncController
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
            "setTargetFolderForThreads: Account=${account?.emailAddress}, Folder=${folder?.displayName}, collectionJob Active: ${collectionJob?.isActive}, networkJob Active: ${networkFetchJob?.isActive}"
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
                        _threadDataState.value = ThreadDataState.Success(mailThreads)
                    }
                    // Use the local, non-null shadowed variables currentAccount and currentFolder
                    ensureNetworkFetch(
                        currentAccount,
                        currentFolder,
                        isExplicitRefresh = false,
                        isBackgroundCheck = false
                    )
                }
        }
    }

    private fun ensureNetworkFetch(
        account: Account,
        folder: MailFolder,
        isExplicitRefresh: Boolean,
        isBackgroundCheck: Boolean
    ) {
        if (networkFetchJob?.isActive == true) {
            Timber.d("[${folder.displayName}] Network fetch job already active. Skipping duplicate call.")
            return
        }
        Timber.d("[${folder.displayName}] Ensuring network fetch. Explicit: $isExplicitRefresh, Background: $isBackgroundCheck")
        syncController.submit(SyncJob.ForceRefreshFolder(folder.id, account.id))
    }

    override suspend fun refreshThreads(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        if (account == null || folder == null) {
            Timber.w("refreshThreads: No target account or folder set. Skipping.")
            _threadDataState.value = ThreadDataState.Error("Cannot refresh: No folder selected.")
            return
        }
        Timber.d("refreshThreads called for folder: ${folder.displayName}, Account: ${account.emailAddress}")

        syncController.submit(SyncJob.ForceRefreshFolder(folder.id, account.id))
    }

    private fun launchThreadFetchJobInternal(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean
    ): Job {
        // This entire method should be removed as its functionality is replaced by SyncEngine
        Timber.w("[${folder.displayName}] launchThreadFetchJobInternal was called but should be DEPRECATED. Sync is handled by SyncEngine.")
        return Job().apply { complete() } // Return a completed dummy job
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

    override suspend fun markThreadRead(account: Account, threadId: String, isRead: Boolean): Result<Unit> {
        Timber.d("markThreadRead: threadId=$threadId, isRead=$isRead, account=${account.id}")
        try {
            appDatabase.withTransaction {
                val messageIds = messageDao.getMessageIdsByThreadId(threadId)
                if (messageIds.isEmpty()) {
                    Timber.w("$TAG: No messages found for threadId $threadId and account ${account.id} to mark read state.")
                }
                messageDao.updateReadStateForMessages(messageIds, isRead)
                Timber.i("$TAG: Optimistically updated isRead=$isRead for ${messageIds.size} messages in thread $threadId")
            }

            val actionType = if (isRead) {
                ActionUploadWorker.ACTION_MARK_THREAD_AS_READ
            } else {
                ActionUploadWorker.ACTION_MARK_THREAD_AS_UNREAD
            }

            queuePendingAction(account.id, threadId, actionType, mapOf(ActionUploadWorker.KEY_IS_READ to isRead.toString()))
            syncController.submit(
                SyncJob.UploadAction(
                    accountId = account.id,
                    actionType = actionType,
                    entityId = threadId,
                    payload = mapOf(ActionUploadWorker.KEY_IS_READ to isRead.toString())
                )
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error in markThreadRead for threadId $threadId")
            return Result.failure(e)
        }
    }

    override suspend fun deleteThread(account: Account, threadId: String): Result<Unit> {
        Timber.d("deleteThread: threadId=$threadId, account=${account.id}")
        try {
            appDatabase.withTransaction {
                val messageIds = messageDao.getMessageIdsByThreadId(threadId)
                if (messageIds.isEmpty()) {
                    Timber.w("$TAG: No messages found for threadId $threadId to delete.")
                }
                messageDao.markMessagesAsLocallyDeleted(messageIds)
                Timber.i("$TAG: Optimistically marked ${messageIds.size} messages in thread $threadId as locally deleted.")
            }

            queuePendingAction(account.id, threadId, ActionUploadWorker.ACTION_DELETE_THREAD, emptyMap())
            syncController.submit(
                SyncJob.UploadAction(
                    accountId = account.id,
                    actionType = ActionUploadWorker.ACTION_DELETE_THREAD,
                    entityId = threadId,
                    payload = emptyMap()
                )
            )

            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error deleting thread $threadId")
            return Result.failure(e)
        }
    }

    override suspend fun moveThread(
        account: Account,
        threadId: String,
        currentFolderId: String,
        destinationFolderId: String
    ): Result<Unit> {
        Timber.d("moveThread: threadId=$threadId, newFolderId=$destinationFolderId, account=${account.id}")
        try {
            appDatabase.withTransaction {
                val messageIds = messageDao.getMessageIdsByThreadIdAndFolder(threadId, currentFolderId)
                if (messageIds.isEmpty()) {
                    Timber.w("$TAG: No messages found for threadId $threadId in folder $currentFolderId for account ${account.id} to move.")
                } else {
                    messageDao.updateFolderIdForMessages(messageIds, destinationFolderId)
                    Timber.i("$TAG: Optimistically moved ${messageIds.size} messages in thread $threadId to folder $destinationFolderId.")
                }
            }

            val payloadMove = mapOf(
                ActionUploadWorker.KEY_NEW_FOLDER_ID to destinationFolderId,
                ActionUploadWorker.KEY_OLD_FOLDER_ID to currentFolderId
            )
            queuePendingAction(account.id, threadId, ActionUploadWorker.ACTION_MOVE_THREAD, payloadMove)
            syncController.submit(
                SyncJob.UploadAction(
                    accountId = account.id,
                    actionType = ActionUploadWorker.ACTION_MOVE_THREAD,
                    entityId = threadId,
                    payload = payloadMove
                )
            )

            return Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error moving thread $threadId to folder $destinationFolderId")
            return Result.failure(e)
        }
    }

    private suspend fun queuePendingAction(
        accountId: String,
        entityId: String,
        actionType: String,
        payload: Map<String, String?> = emptyMap()
    ): Long {
        val action = PendingActionEntity(
            accountId = accountId,
            entityId = entityId,
            actionType = actionType,
            payload = payload,
            status = PendingActionStatus.PENDING
        )
        return pendingActionDao.insertAction(action)
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