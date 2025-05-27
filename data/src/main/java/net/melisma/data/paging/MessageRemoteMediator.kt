package net.melisma.data.paging

import android.util.Log
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
    private val TAG = "MessageRemoteMediator"

    // Define a page size for fetching messages during REFRESH.
    // This should be large enough to get a good initial set, but not excessive.
    // Consistent with DefaultMessageRepository.syncMessagesForFolderInternal
    private val REFRESH_PAGE_SIZE = 100

    override suspend fun initialize(): InitializeAction {
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>
    ): MediatorResult {
        onSyncStateChanged(MessageSyncState.Syncing(accountId, folderId))
        Log.d(
            TAG,
            "load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId, PageSize: ${state.config.pageSize}"
        )

        return try {
            when (loadType) {
                LoadType.REFRESH -> {
                    // Fetch from network
                    Log.d(
                        TAG,
                        "REFRESH: Fetching messages from network. Page size for API: $REFRESH_PAGE_SIZE"
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
                        Log.d(TAG, "REFRESH: Fetched ${messagesFromApi.size} messages from API.")

                        database.withTransaction {
                            Log.d(
                                TAG,
                                "REFRESH: Clearing old messages for $accountId/$folderId and inserting ${messagesFromApi.size} new ones."
                            )
                            messageDao.deleteMessagesForFolder(accountId, folderId)
                            val messageEntities =
                                messagesFromApi.map { it.toEntity(accountId, folderId) }
                            messageDao.insertOrUpdateMessages(messageEntities)
                        }
                        // Since the API doesn't support true pagination with next/prev keys,
                        // after a refresh, we assume we have all we can get via this mediator.
                        // The PagingSource from DAO will serve this data.
                        // If messagesFromApi.size < REFRESH_PAGE_SIZE, it implies end of list.
                        // If messagesFromApi.size == REFRESH_PAGE_SIZE, there *might* be more, but our API doesn't tell us how to get them.
                        // So, for simplicity, always signal end of pagination from RemoteMediator's perspective for APPEND.
                        val endOfPaginationReached = true
                        onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId))
                        return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
                    } else {
                        val exception = apiResponse.exceptionOrNull()
                            ?: IOException("Unknown API error on REFRESH")
                        Log.e(TAG, "REFRESH: API call failed: ${exception.message}", exception)
                        onSyncStateChanged(
                            MessageSyncState.SyncError(
                                accountId,
                                folderId,
                                exception.message ?: "API Error"
                            )
                        )
                        return MediatorResult.Error(exception)
                    }
                }

                LoadType.PREPEND -> {
                    // Prepending not supported with the current API structure
                    Log.d(
                        TAG,
                        "PREPEND: Not supported, returning success with endOfPaginationReached = true"
                    )
                    onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId)) // Or Idle
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    // Appending from network not supported with the current API structure via RemoteMediator
                    // The PagingSource from DAO handles serving already fetched data.
                    Log.d(
                        TAG,
                        "APPEND: Not supported by RemoteMediator with current API, returning success with endOfPaginationReached = true"
                    )
                    onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId)) // Or Idle
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException during load ($loadType): ${e.message}", e)
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Network Error"
                )
            )
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception during load ($loadType): ${e.message}", e)
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Unknown Error"
                )
            )
            return MediatorResult.Error(e)
        }
    }
} 