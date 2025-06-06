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

    override suspend fun initialize(): InitializeAction {
        Timber.d("initialize() for $accountId/$folderId: Launching initial refresh as per simplified plan.")
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, MessageEntity>
    ): MediatorResult {
        Timber.i("load() called. LoadType: $loadType, Account: $accountId, Folder: $folderId, ConfigPageSize: ${state.config.pageSize}")
        onSyncStateChanged(MessageSyncState.Syncing(accountId, folderId))

        try {
            if (loadType != LoadType.REFRESH && !networkMonitor.isOnline.first()) {
                Timber.w("load() for $accountId/$folderId ($loadType): Network is offline. Returning error.")
                return MediatorResult.Error(IOException("Network unavailable. Cannot load messages for $loadType."))
            }

            val pageTokenToFetch: String? = when (loadType) {
                LoadType.REFRESH -> {
                    Timber.d("load() REFRESH for $accountId/$folderId: No page token needed for initial load.")
                    null
                }

                LoadType.PREPEND -> {
                    Timber.i("load() PREPEND for $accountId/$folderId: Not supported by this mediator. End of pagination.")
                    return MediatorResult.Success(endOfPaginationReached = true)
                }

                LoadType.APPEND -> {
                    val remoteKey = database.withTransaction {
                        remoteKeyDao.getRemoteKeyForFolder(folderId)
                    }

                    if (remoteKey?.nextPageToken == null) {
                        Timber.i("load() APPEND for $accountId/$folderId: No next page token in RemoteKeyEntity. End of pagination.")
                        return MediatorResult.Success(endOfPaginationReached = true)
                    }
                    Timber.d("load() APPEND for $accountId/$folderId: Using nextPageToken from RemoteKeyEntity: ${remoteKey.nextPageToken}")
                    remoteKey.nextPageToken
                }
            }

            Timber.d("load() for $accountId/$folderId ($loadType): Fetching messages. PageToken to use: '$pageTokenToFetch', PageSize: ${state.config.pageSize}")

            val apiResponseResult: Result<PagedMessagesResponse> =
                mailApiService.getMessagesForFolder(
                    folderId = folderId,
                    maxResults = state.config.pageSize,
                    pageToken = pageTokenToFetch
                )

            if (apiResponseResult.isSuccess) {
                val apiResponse = apiResponseResult.getOrThrow()
                val messagesFromApi = apiResponse.messages
                val newNextPageToken = apiResponse.nextPageToken
                val endOfPaginationReached = newNextPageToken == null

                Timber.i("load() for $accountId/$folderId ($loadType): API success. Fetched ${messagesFromApi.size} messages. NewNextPageToken: '$newNextPageToken'. EndReached: $endOfPaginationReached")

                database.withTransaction {
                    if (loadType == LoadType.REFRESH) {
                        Timber.d("load() REFRESH for $accountId/$folderId: Clearing old messages and remote key for folder.")
                        messageDao.deleteMessagesForFolder(accountId, folderId)
                        remoteKeyDao.deleteRemoteKeyForFolder(folderId)
                    }

                    if (loadType == LoadType.APPEND) remoteKeyDao.getRemoteKeyForFolder(folderId) else null
                    remoteKeyDao.insertOrReplace(
                        RemoteKeyEntity(
                            folderId = folderId,
                            nextPageToken = newNextPageToken,
                            prevPageToken = if (loadType == LoadType.APPEND) pageTokenToFetch else null
                        )
                    )
                    Timber.d("load() for $accountId/$folderId ($loadType): Upserted RemoteKeyEntity. Next: '$newNextPageToken', Prev: '${if (loadType == LoadType.APPEND) pageTokenToFetch else null}'.")

                    val messageEntities = messagesFromApi.map {
                        it.toEntity(
                            accountId = accountId,
                            folderId = folderId
                        )
                    }
                    messageDao.insertOrUpdateMessages(messageEntities)
                    Timber.d("load() ($loadType) for $accountId/$folderId: Inserted/Updated ${messageEntities.size} messages into DB.")
                }

                return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
            } else {
                val exception = apiResponseResult.exceptionOrNull()
                    ?: IOException("Unknown API error during $loadType for $accountId/$folderId")
                Timber.e(exception, "load() ($loadType) for $accountId/$folderId: API call failed.")
                onSyncStateChanged(
                    MessageSyncState.SyncError(
                        accountId,
                        folderId,
                        exception.message ?: "Unknown API Error"
                    )
                )
                return MediatorResult.Error(exception)
            }

        } catch (e: IOException) {
            Timber.e(e, "IOException during load ($loadType for $accountId/$folderId)")
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "IO Exception"
                )
            )
            return MediatorResult.Error(e)
        } catch (e: Exception) {
            Timber.e(e, "Generic exception during load ($loadType for $accountId/$folderId)")
            onSyncStateChanged(
                MessageSyncState.SyncError(
                    accountId,
                    folderId,
                    e.message ?: "Generic Exception"
                )
            )
            return MediatorResult.Error(e)
        }
    }
} 