package net.melisma.core_data.repository

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account // Updated import
import net.melisma.core_data.model.FolderFetchState // Updated import
import net.melisma.core_data.model.MailThread // Added import
import net.melisma.core_data.model.Message // Added import

/**
 * Interface defining the contract for managing mail folders for user accounts.
 * Implementations will handle fetching folder lists from specific backends (e.g., Microsoft Graph).
 */
interface FolderRepository {

    /**
     * Observes the state of folders for multiple accounts simultaneously.
     * The emitted map contains the fetch state ([FolderFetchState]) for each account ID
     * currently being observed by the repository.
     *
     * @return A [Flow] emitting a map where the key is the account ID (from [Account.id])
     * and the value is the [FolderFetchState] for that account (Loading, Success, or Error).
     */
    fun observeFoldersState(): Flow<Map<String, FolderFetchState>>

    /**
     * Informs the repository about the current set of active accounts.
     * The repository will start observing and fetching folders for these accounts,
     * managing internal fetch jobs and cleaning up resources for accounts no longer present.
     * Call this when the application's active account list changes (e.g., after login/logout).
     *
     * @param accounts The list of [Account] objects currently active and needing folder data.
     */
    suspend fun manageObservedAccounts(accounts: List<Account>)

    /**
     * Triggers a background refresh of the folder list for all currently observed accounts.
     * Updates will be emitted through the [Flow] provided by [observeFoldersState].
     *
     * @param activity The optional [Activity] context, which might be required
     * for interactive authentication if tokens have expired.
     */
    suspend fun refreshAllFolders(activity: Activity? = null)

    /**
     * Triggers a background refresh of the folder list for a specific account.
     * Updates will be emitted through the [Flow] provided by [observeFoldersState].
     *
     * @param accountId The ID of the account for which to refresh folders.
     * @param activity The optional [Activity] context, which might be required
     * for interactive authentication if tokens have expired.
     */
    suspend fun refreshFoldersForAccount(accountId: String, activity: Activity? = null)

    // New methods
    suspend fun syncFolderContents(accountId: String, folderId: String): Result<Unit>
    fun getThreadsInFolder(accountId: String, folderId: String): Flow<List<MailThread>>
    fun getMessagesInFolder(accountId: String, folderId: String): Flow<List<Message>>
    fun getFolderById(folderId: String): Flow<net.melisma.core_data.model.MailFolder?>
    suspend fun getFolderByIdSuspend(folderId: String): net.melisma.core_data.model.MailFolder?
}
