package net.melisma.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.entity.MessageEntity
import net.melisma.data.mapper.toEntity
import java.io.IOException
import timber.log.Timber

// Define a simple class to hold API response for paged messages
data class PagedMessageResponse(
    val messages: List<Message>,
    val nextPageKey: String?, // Could be an offset, a timestamp, or a specific token
    val prevPageKey: String? // For bi-directional paging, if supported
)

// Assume MailApiService has an updated method like this:
// suspend fun getMessagesForFolderPaged(
//     folderId: String,
//     pageSize: Int,
//     pageKey: String? // Could be null for the first page
// ): Result<PagedMessageResponse>


@OptIn(ExperimentalPagingApi::class)
class MessageRemoteMediator(
    private val accountId: String,
    private val folderId: String,
    private val database: AppDatabase,
    private val mailApiService: MailApiService,
    private val ioDispatcher: CoroutineDispatcher,
    private val onSyncStateChanged: (MessageSyncState) -> Unit
) : RemoteMediator<Int, MessageEntity>() {

    private val messageDao = database.messageDao()

    // Define a page size for fetching messages during REFRESH.
    // This should be large enough to get a good initial set, but not excessive.
    // Consistent with DefaultMessageRepository.syncMessagesForFolderInternal
    private val REFRESH_PAGE_SIZE = 100

    override suspend fun initialize(): InitializeAction {
        // LAUNCH_INITIAL_REFRESH will call load() with LoadType.REFRESH upon initialization of PagingData.
        Timber.d(
            "initialize() called for $accountId/$folderId. Returning LAUNCH_INITIAL_REFRESH."
        )
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>
    ): MediatorResult {
        Timber.i(
            "load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId, PageSize: ${state.config.pageSize}, Prefetch: ${state.config.prefetchDistance}, InitialSize: ${state.config.initialLoadSize}"
        )
        onSyncStateChanged(MessageSyncState.Syncing(accountId, folderId))
        // Timber.d(
        //     "load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId, PageSize: ${state.config.pageSize}"
        // )

        return try {
            when (loadType) {
                LoadType.REFRESH -> {
                    // Fetch from network
                    Timber.i(
                        "REFRESH for $accountId/$folderId: Fetching messages from network. API page size: $REFRESH_PAGE_SIZE"
                    )
                    val apiResponse = withContext(ioDispatcher) {
                        mailApiService.getMessagesForFolder(
                            folderId = folderId,
                            // selectFields = emptyList(), // Use API default or specify if needed
                            maxResults = REFRESH_PAGE_SIZE
                        )
                    }

                    if (apiResponse.isSuccess) {
                        val messagesFromApi = apiResponse.getOrThrow()
                        Timber.i(
                            "REFRESH for $accountId/$folderId: Fetched ${messagesFromApi.size} messages from API."
                        )

                        val dbLogPrefix = "REFRESH DB for $accountId/$folderId:"
                        database.withTransaction {
                            Timber.d(
                                "$dbLogPrefix Clearing old messages and inserting ${messagesFromApi.size} new ones."
                            )
                            // It's important that this deletion is specific to the accountId and folderId
                            messageDao.deleteMessagesForFolder(accountId, folderId)
                            val messageEntities =
                                messagesFromApi.map { it.toEntity(accountId, folderId) }
                            messageDao.insertOrUpdateMessages(messageEntities)
                            Timber.d("$dbLogPrefix Database transaction completed.")
                        }
                        // Since the API doesn't support true pagination with next/prev keys,
                        // after a refresh, we assume we have all we can get via this mediator for APPEND/PREPEND.
                        val endOfPaginationReached = true
                        Timber.i(
                            "REFRESH for $accountId/$folderId: Success. endOfPaginationReached=$endOfPaginationReached. Notifying SyncSuccess."
                        )
                        onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId))
                        return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
                    } else {
                        val exception = apiResponse.exceptionOrNull()
                            ?: IOException("Unknown API error on REFRESH for $accountId/$folderId")
                        Timber.e(
                            exception,
                            "REFRESH for $accountId/$folderId: API call failed: ${exception.message}"
                        )
                        onSyncStateChanged(
                            MessageSyncState.SyncError(
                                accountId,
                                folderId,
                                exception.message ?: "API Error on REFRESH"
                            )
                        )
                        return MediatorResult.Error(exception)
                    }
                }

                LoadType.PREPEND -> {
                    // Prepending not supported with the current API structure
                    Timber.i(
                        "PREPEND for $accountId/$folderId: Not supported, returning success with endOfPaginationReached = true. Notifying SyncSuccess (as no error occurred)."
                    )
                    onSyncStateChanged(
                        MessageSyncState.SyncSuccess(
                            accountId,
                            folderId
                        )
                    ) // No actual sync error
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    // Appending from network not supported with the current API structure via RemoteMediator
                    // The PagingSource from DAO handles serving already fetched data.
                    Timber.i(
                        "APPEND for $accountId/$folderId: Not supported by RemoteMediator, returning success with endOfPaginationReached = true. Notifying SyncSuccess."
                    )
                    onSyncStateChanged(
                        MessageSyncState.SyncSuccess(
                            accountId,
                            folderId
                        )
                    ) // No actual sync error
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
            }
        } catch (e: IOException) {
            Timber.e(
                e,
                "IOException during load ($loadType for $accountId/$folderId): ${e.message}"
            )
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Network Error during $loadType"
                )
            )
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            Timber.e(
                e,
                "Generic exception during load ($loadType for $accountId/$folderId): ${e.message}"
            )
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Unknown Error during $loadType"
                )
            )
            return MediatorResult.Error(e)
        }
    }
} 