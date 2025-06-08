package net.melisma.data.sync.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.datasource.MailApiServiceFactory
import net.melisma.core_data.datasource.MailApiServiceResolutionException
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.model.ErrorDetails
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.SyncStatus
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AttachmentDao
import net.melisma.core_db.dao.MessageBodyDao
import net.melisma.core_db.dao.MessageDao
import net.melisma.core_db.entity.AttachmentEntity
import net.melisma.core_db.entity.MessageEntity
import timber.log.Timber
import kotlinx.coroutines.CoroutineDispatcher
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

@HiltWorker
class SingleMessageSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mailApiServiceFactory: MailApiServiceFactory,
    private val messageDao: MessageDao,
    private val messageBodyDao: MessageBodyDao,
    private val attachmentDao: AttachmentDao,
    private val appDatabase: AppDatabase,
    @Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_ACCOUNT_ID = "accountId"
        const val KEY_MESSAGE_LOCAL_ID = "messageLocalId"
        const val KEY_MESSAGE_REMOTE_ID = "messageRemoteId"

        fun buildWorkData(accountId: String, messageLocalId: String, messageRemoteId: String?): androidx.work.Data {
            if (messageRemoteId == null) {
                Timber.w("Cannot build work data for SingleMessageSyncWorker without a remote ID. Local ID: $messageLocalId")
                return workDataOf(
                    KEY_ACCOUNT_ID to accountId,
                    KEY_MESSAGE_LOCAL_ID to messageLocalId
                )
            }
            return workDataOf(
                KEY_ACCOUNT_ID to accountId,
                KEY_MESSAGE_LOCAL_ID to messageLocalId,
                KEY_MESSAGE_REMOTE_ID to messageRemoteId
            )
        }
    }

    private fun parseIsoToEpochMillis(dateTimeString: String?): Long? {
        if (dateTimeString.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(dateTimeString).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            Timber.w(e, "Failed to parse ISO 8601 string to Long: $dateTimeString")
            null
        }
    }

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val messageLocalId = inputData.getString(KEY_MESSAGE_LOCAL_ID)
        val messageRemoteId = inputData.getString(KEY_MESSAGE_REMOTE_ID)

        if (accountId == null || messageLocalId == null) {
            Timber.e("SingleMessageSyncWorker: Missing accountId or messageLocalId. Cannot proceed.")
            return@withContext Result.failure()
        }

        val selectedMailApiService: MailApiService
        try {
            selectedMailApiService = mailApiServiceFactory.getService(accountId)
        } catch (e: MailApiServiceResolutionException) {
            Timber.e(e, "Failed to resolve MailApiService for account $accountId. LocalMsgId: $messageLocalId.")
            messageDao.setSyncStatus(messageLocalId, SyncStatus.ERROR)
            val errorMessage = e.message ?: "Failed to resolve API service for account."
            messageDao.updateLastSyncError(messageLocalId, errorMessage)
            return@withContext Result.failure(workDataOf("error" to errorMessage))
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error resolving MailApiService for account $accountId. LocalMsgId: $messageLocalId.")
            messageDao.setSyncStatus(messageLocalId, SyncStatus.ERROR)
            val errorMessage = e.message ?: "Unexpected error resolving API service."
            messageDao.updateLastSyncError(messageLocalId, errorMessage)
            return@withContext Result.failure(workDataOf("error" to errorMessage))
        }

        if (messageRemoteId == null) {
            Timber.w("SingleMessageSyncWorker: Message remoteId is null for localId $messageLocalId. Cannot fetch from API.")
            val errorDetails = ErrorDetails(message = "Message has no remote ID, cannot refresh.", code = "LOCAL_ID_ONLY")
            messageDao.setSyncStatus(messageLocalId, SyncStatus.ERROR)
            messageDao.updateLastSyncError(messageLocalId, errorDetails.message)
            return@withContext Result.failure()
        }

        Timber.d("SingleMessageSyncWorker started for local msg: $messageLocalId, remote msg: $messageRemoteId, acc: $accountId")
        val currentAttemptTimestamp = System.currentTimeMillis()

        try {
            val apiResult = selectedMailApiService.getMessage(messageRemoteId = messageRemoteId)

            if (apiResult.isSuccess) {
                val fetchedMessage = apiResult.getOrThrow()
                Timber.d("Successfully fetched message $messageRemoteId. Subject: ${fetchedMessage.subject}")

                appDatabase.withTransaction {
                    // 1. Update MessageEntity
                    val messageEntity = MessageEntity(
                        id = messageLocalId,
                        messageId = fetchedMessage.remoteId ?: messageRemoteId,
                        accountId = accountId,
                        folderId = fetchedMessage.folderId,
                        threadId = fetchedMessage.threadId,
                        subject = fetchedMessage.subject,
                        snippet = fetchedMessage.bodyPreview,
                        body = null,
                        senderName = fetchedMessage.senderName,
                        senderAddress = fetchedMessage.senderAddress,
                        recipientNames = fetchedMessage.recipientNames,
                        recipientAddresses = fetchedMessage.recipientAddresses,
                        timestamp = fetchedMessage.timestamp,
                        sentTimestamp = parseIsoToEpochMillis(fetchedMessage.sentDateTime),
                        isRead = fetchedMessage.isRead,
                        isStarred = fetchedMessage.isStarred,
                        hasAttachments = fetchedMessage.hasAttachments,
                        isLocallyDeleted = false,
                        isDraft = false,
                        isOutbox = false,
                        syncStatus = SyncStatus.SYNCED,
                        lastSuccessfulSyncTimestamp = currentAttemptTimestamp,
                        lastSyncAttemptTimestamp = currentAttemptTimestamp,
                        lastSyncError = null
                    )
                    messageDao.insertOrUpdateMessages(listOf(messageEntity))

                    // 2. Update MessageBodyEntity
                    val bodyContent = fetchedMessage.body
                    val bodyContentType = fetchedMessage.bodyContentType ?: "text/plain"
                    val bodySizeInBytes = bodyContent?.toByteArray()?.size?.toLong() ?: 0L
                    
                    messageBodyDao.updateBodyContentAndSyncState(
                        messageId = messageLocalId,
                        newContent = bodyContent,
                        newContentType = bodyContentType,
                        newSizeInBytes = bodySizeInBytes,
                        newLastFetchedTimestamp = currentAttemptTimestamp,
                        newSyncStatus = SyncStatus.SYNCED,
                        newLastSuccessfulSyncTimestamp = currentAttemptTimestamp,
                        newLastAttemptTimestamp = currentAttemptTimestamp
                    )
                    Timber.d("Updated/Inserted message body for $messageLocalId. Size: $bodySizeInBytes")

                    // 3. Sync Attachments
                    attachmentDao.deleteAttachmentsForMessage(messageLocalId)
                    val newAttachmentEntities = fetchedMessage.attachments.map { domainAttachment ->
                        AttachmentEntity(
                            attachmentId = domainAttachment.id,
                            messageId = messageLocalId,
                            accountId = accountId,
                            fileName = domainAttachment.fileName,
                            size = domainAttachment.size,
                            mimeType = domainAttachment.contentType,
                            contentId = domainAttachment.contentId,
                            isInline = domainAttachment.isInline,
                            isDownloaded = false,
                            localFilePath = null,
                            downloadTimestamp = null,
                            remoteAttachmentId = domainAttachment.remoteId,
                            syncStatus = SyncStatus.PENDING_DOWNLOAD,
                            lastSyncAttemptTimestamp = null,
                            lastSuccessfulSyncTimestamp = null,
                            lastSyncError = null
                        )
                    }
                    if (newAttachmentEntities.isNotEmpty()) {
                        attachmentDao.insertAttachments(newAttachmentEntities)
                        Timber.d("Inserted ${newAttachmentEntities.size} attachments for message $messageLocalId.")
                    }
                }
                Timber.i("SingleMessageSyncWorker successfully processed message $messageLocalId (Remote: $messageRemoteId)")
                Result.success()
            } else {
                val exception = apiResult.exceptionOrNull()
                val errorCode = (exception as? net.melisma.core_data.errors.ApiServiceException)?.errorDetails?.code ?: "API_FETCH_FAILED"
                val errorDetails = ErrorDetails(
                    message = exception?.message ?: "Unknown error fetching message from API.",
                    code = errorCode
                )
                Timber.e(exception, "Failed to fetch message $messageRemoteId from API. Code: $errorCode")
                messageDao.setSyncStatus(messageLocalId, SyncStatus.ERROR)
                messageDao.updateLastSyncError(messageLocalId, errorDetails.message)
                messageBodyDao.updateSyncStatusAndError(messageLocalId, SyncStatus.ERROR, errorDetails.message, currentAttemptTimestamp)
                Result.failure(workDataOf("error" to errorDetails.message))
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception in SingleMessageSyncWorker for message $messageLocalId.")
            val errorDetails = ErrorDetails(
                message = e.message ?: "Unhandled exception in SingleMessageSyncWorker.",
                code = "WORKER_EXCEPTION"
            )
            try {
                 messageDao.setSyncStatus(messageLocalId, SyncStatus.ERROR)
                 messageDao.updateLastSyncError(messageLocalId, errorDetails.message)
                 messageBodyDao.updateSyncStatusAndError(messageLocalId, SyncStatus.ERROR, errorDetails.message, currentAttemptTimestamp)
            } catch (dbException: Exception) {
                Timber.e(dbException, "Failed to update message sync state after worker exception.")
            }
            Result.failure(workDataOf("error" to errorDetails.message))
        }
    }
} 