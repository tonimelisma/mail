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

    override suspend fun initialize(): InitializeAction {
        // LAUNCH_INITIAL_REFRESH will call load() with LoadType.REFRESH
        // SKIP_INITIAL_REFRESH to not load immediately
        // Consider staleness logic here, e.g., if data is too old, refresh.
        // For now, always refresh on first load if DB is empty or on explicit refresh.
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>
    ): MediatorResult {
        onSyncStateChanged(MessageSyncState.Syncing(accountId, folderId))
        Log.d(TAG, "load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId")

        return try {
            // Determine the page key to load.
            val pageKey: String? = when (loadType) {
                LoadType.REFRESH -> {
                    // New refresh, load from the beginning. Null pageKey means first page.
                    null
                }

                LoadType.PREPEND -> {
                    // Prepending not supported in this simple example.
                    onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId)) // Or Idle
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    // Get the last item loaded from PagingState.
                    // We need a way to get the 'nextPageKey' from the last loaded item or response.
                    // This simplistic RemoteMediator doesn't store nextPageKey from API response with entities.
                    // For a real implementation, you'd store remote keys (e.g., in a separate table or with entities if possible)
                    // or derive it. Let's assume the API returns page numbers or an offset we can calculate.
                    // For this example, we'll simulate this by just trying to load 'something more'.
                    // A more robust way is to store the nextPageKey from the previous fetch.
                    val lastItem = state.lastItemOrNull()
                    if (lastItem == null) {
                        // This can happen if REFRESH failed or if PagingSource is empty.
                        // If REFRESH just happened and it was empty, then we are at end of list.
                        // If PagingSource is empty and this is first APPEND, it means REFRESH resulted in no data.
                        if (loadType == LoadType.APPEND && state.pages.isEmpty()) {
                            Log.d(
                                TAG,
                                "APPEND: PagingSource is empty and no pages loaded yet. End of pagination."
                            )
                            onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId))
                            return MediatorResult.Success(endOfPaginationReached = true)
                        }
                        // Otherwise, REFRESH might be needed or this is an invalid state for APPEND
                        Log.d(
                            TAG,
                            "APPEND: Last item is null, cannot determine next page key. Assuming end for now or needs refresh."
                        )
                        // This could also imply that the DB is empty and REFRESH hasn't completed/succeeded yet.
                        // For a robust solution, RemoteKeys are used.
                        // For now, if lastItem is null on APPEND, we consider it end of list.
                        onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId))
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    // This is a placeholder. Real nextPageKey would come from previous API response.
                    // Let's assume our 'pageKey' for the API is just an offset based on loaded items.
                    // This is NOT how a real RemoteMediator would typically work with token-based paging.
                    (state.anchorPosition
                        ?: 0).toString() // Simplified: using anchor position as pageKey.
                }
            }

            Log.d(TAG, "Attempting to fetch page with key: $pageKey for LoadType: $loadType")

            // This is a placeholder for actual API call structure
            // val apiResponse = mailApiService.getMessagesForFolderPaged(
            //     folderId = folderId,
            //     pageSize = state.config.pageSize,
            //     pageKey = pageKey
            // )
            // Placeholder: Simulate API call
            val apiResponse = withContext(ioDispatcher) {
                // Simulate network delay
                kotlinx.coroutines.delay(1000)
                // Simulate API call that might use pageKey as an offset or token
                // And assume a fixed number of total items for this example (e.g. 250)
                val itemOffset = pageKey?.toIntOrNull() ?: 0
                val pageSize = state.config.pageSize // e.g. 20
                val totalRemoteItems =
                    75 // Simulate a total number of items available remotely for this folder

                val simulatedMessages = mutableListOf<Message>()
                if (itemOffset < totalRemoteItems) {
                    for (i in 0 until pageSize) {
                        val currentItemIndex = itemOffset + i
                        if (currentItemIndex >= totalRemoteItems) break
                        simulatedMessages.add(
                            Message(
                                messageId = "remote_${accountId}_${folderId}_${currentItemIndex}",
                                accountId = accountId,
                                folderId = folderId,
                                subject = "Remote Message ${currentItemIndex + 1}",
                                snippet = "This is a remote message snippet ${currentItemIndex + 1}.",
                                senderName = "Remote Sender",
                                timestamp = System.currentTimeMillis() - (currentItemIndex * 60000), // newer are smaller index
                                isRead = false,
                                isStarred = false,
                                hasAttachments = false,
                                // other fields...
                            )
                        )
                    }
                }
                val nextKey =
                    if (itemOffset + pageSize < totalRemoteItems) (itemOffset + pageSize).toString() else null
                Result.success(PagedMessageResponse(simulatedMessages, nextKey, null))
            }


            if (apiResponse.isSuccess) {
                val pagedResult = apiResponse.getOrThrow()
                val messagesFromApi = pagedResult.messages
                val endOfPaginationReached =
                    pagedResult.nextPageKey == null || messagesFromApi.isEmpty()

                Log.d(
                    TAG,
                    "Fetched ${messagesFromApi.size} messages. End of pagination: $endOfPaginationReached. NextKey: ${pagedResult.nextPageKey}"
                )

                database.withTransaction {
                    if (loadType == LoadType.REFRESH) {
                        Log.d(
                            TAG,
                            "LoadType.REFRESH: Clearing old messages for $accountId/$folderId and inserting ${messagesFromApi.size} new ones."
                        )
                        messageDao.deleteMessagesForFolder(accountId, folderId)
                        // Here you would also clear/reset your remote keys if you store them separately.
                    } else {
                        Log.d(TAG, "LoadType.APPEND: Inserting ${messagesFromApi.size} messages.")
                    }
                    val messageEntities = messagesFromApi.map { it.toEntity(accountId, folderId) }
                    messageDao.insertOrUpdateMessages(messageEntities)
                }
                onSyncStateChanged(MessageSyncState.SyncSuccess(accountId, folderId))
                return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
            } else {
                val exception = apiResponse.exceptionOrNull() ?: IOException("Unknown API error")
                Log.e(TAG, "API call failed: ${exception.message}", exception)
                onSyncStateChanged(
                    MessageSyncState.SyncError(
                        accountId,
                        folderId,
                        exception.message ?: "API Error"
                    )
                )
                return MediatorResult.Error(exception)
            }

        } catch (e: IOException) {
            Log.e(TAG, "IOException during load: ${e.message}", e)
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Network Error"
                )
            )
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            Log.e(TAG, "Generic exception during load: ${e.message}", e)
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