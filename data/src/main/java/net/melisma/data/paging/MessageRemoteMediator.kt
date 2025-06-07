package net.melisma.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import net.melisma.core_data.connectivity.NetworkMonitor
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.model.MessageSyncState
import net.melisma.core_data.model.PagedMessagesResponse
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.entity.MessageEntity
import net.melisma.core_db.entity.RemoteKeyEntity
import net.melisma.data.mapper.toEntity
import timber.log.Timber
import java.io.IOException

@OptIn(ExperimentalPagingApi::class)
class MessageRemoteMediator(
    private val accountId: String,
    private val folderId: String,
    private val database: AppDatabase,
    private val mailApiService: MailApiService,
    private val networkMonitor: NetworkMonitor,
    private val ioDispatcher: CoroutineDispatcher,
    private val onSyncStateChanged: (MessageSyncState) -> Unit
) : RemoteMediator<Int, MessageEntity>() {

    private val messageDao = database.messageDao()
    private val remoteKeyDao = database.remoteKeyDao()
    private val folderDao = database.folderDao()

    override suspend fun initialize(): InitializeAction {
        // SKIP_INITIAL_REFRESH means Pager will not call load() with REFRESH
        // until a PagingSource is invalidated. We rely on SyncEngine and its workers
        // to perform the initial sync, not the mediator.
        return InitializeAction.SKIP_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>
    ): MediatorResult {
        Timber.i("load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId, ConfigPageSize: ${state.config.pageSize}")
        onSyncStateChanged(MessageSyncState.Syncing(accountId, folderId))

        try {
            val localFolder = database.withTransaction {
                folderDao.getFolderByIdSuspend(this.folderId)
            }

            if (localFolder == null) {
                Timber.w("load() for $accountId/${this.folderId} ($loadType): Local FolderEntity not found. Folder sync may be pending or failed.")
                onSyncStateChanged(
                    MessageSyncState.SyncError(
                        accountId,
                        this.folderId,
                        "Parent folder metadata missing"
                    )
                )
                return MediatorResult.Error(IllegalStateException("Parent folder ${this.folderId} not found locally. Folder sync needs to complete first."))
            }

            val apiFolderIdToFetch = localFolder.remoteId ?: this.folderId

            if (loadType != LoadType.REFRESH && !networkMonitor.isOnline.first()) {
                Timber.w("load() for $accountId/$apiFolderIdToFetch ($loadType): Network is offline. Returning error.")
                return MediatorResult.Error(IOException("Network unavailable. Cannot load messages for $loadType."))
            }

            val pageTokenToFetch: String? = when (loadType) {
                LoadType.REFRESH -> {
                    // A REFRESH means the PagingSource was invalidated. This is often triggered
                    // by a pull-to-refresh in the UI. The actual data fetching for new items
                    // is handled by FolderContentSyncWorker, which runs before this.
                    // This mediator's role is just to signal success so the Pager displays
                    // the new data from the DB and to determine if pagination should continue.
                    val remoteKey = remoteKeyDao.getRemoteKeyForFolder(this.folderId)
                    Timber.d("load() REFRESH for $accountId/$apiFolderIdToFetch. Data should be fresh from worker. RemoteKey: $remoteKey")
                    return MediatorResult.Success(endOfPaginationReached = remoteKey?.nextPageToken == null)
                }

                LoadType.PREPEND -> {
                    Timber.i("load() PREPEND for $accountId/$apiFolderIdToFetch: Not supported by this mediator. End of pagination.")
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    val remoteKey = database.withTransaction {
                        remoteKeyDao.getRemoteKeyForFolder(this.folderId)
                    }

                    if (remoteKey?.nextPageToken == null) {
                        Timber.i("load() APPEND for $accountId/$apiFolderIdToFetch: No next page token in RemoteKeyEntity. End of pagination.")
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    Timber.d("load() APPEND for $accountId/$apiFolderIdToFetch: Using nextPageToken from RemoteKeyEntity: ${remoteKey.nextPageToken}")
                    remoteKey.nextPageToken
                }
            }

            Timber.d("load() for $accountId/$apiFolderIdToFetch ($loadType): Fetching messages. PageToken to use: '$pageTokenToFetch', PageSize: ${state.config.pageSize}")

            val apiResponseResult: Result<PagedMessagesResponse> =
                mailApiService.getMessagesForFolder(
                    folderId = apiFolderIdToFetch,
                    maxResults = state.config.pageSize,
                    pageToken = pageTokenToFetch
                )

            if (apiResponseResult.isSuccess) {
                val apiResponse = apiResponseResult.getOrThrow()
                val messagesFromApi = apiResponse.messages
                val newNextPageToken = apiResponse.nextPageToken
                val endOfPaginationReached = newNextPageToken == null

                Timber.i("load() for $accountId/$apiFolderIdToFetch ($loadType): API success. Fetched ${messagesFromApi.size} messages. NewNextPageToken: '$newNextPageToken'. EndReached: $endOfPaginationReached")

                database.withTransaction {
                    // REFRESH is handled above and does not run this transaction.
                    // This block only runs for APPEND.
                    remoteKeyDao.insertOrReplace(
                        RemoteKeyEntity(
                            folderId = this.folderId,
                            nextPageToken = newNextPageToken,
                            prevPageToken = if (loadType == LoadType.APPEND) pageTokenToFetch else null
                        )
                    )
                    Timber.d("load() for $accountId/${this.folderId} ($loadType): Upserted RemoteKeyEntity. Next: '$newNextPageToken', Prev: '${if (loadType == LoadType.APPEND) pageTokenToFetch else null}'.")

                    val messageEntities = messagesFromApi.map {
                        it.toEntity(
                            accountId = accountId,
                            folderId = this.folderId
                        )
                    }
                    messageDao.insertOrUpdateMessages(messageEntities)
                    Timber.d("load() ($loadType) for $accountId/${this.folderId}: Inserted/Updated ${messageEntities.size} messages into DB.")
                }

                return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
            } else {
                val exception = apiResponseResult.exceptionOrNull()
                    ?: IOException("Unknown API error during $loadType for $accountId/$apiFolderIdToFetch")
                Timber.e(
                    exception,
                    "load() ($loadType) for $accountId/$apiFolderIdToFetch: API call failed."
                )
                onSyncStateChanged(
                    MessageSyncState.SyncError(
                        accountId,
                        apiFolderIdToFetch,
                        exception.message ?: "Unknown API Error"
                    )
                )
                return MediatorResult.Error(exception)
            }

        } catch (e: IOException) {
            Timber.e(e, "IOException during load ($loadType for $accountId/${this.folderId})")
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    this.folderId,
                    e.message ?: "IO Exception"
                )
            )
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            Timber.e(e, "Generic exception during load ($loadType for $accountId/${this.folderId})")
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    this.folderId,
                    e.message ?: "Generic Exception"
                )
            )
            return MediatorResult.Error(e)
        }
    }
} 