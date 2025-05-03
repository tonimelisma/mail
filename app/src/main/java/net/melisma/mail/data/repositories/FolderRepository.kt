package net.melisma.mail.data.repositories

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import net.melisma.mail.Account
import net.melisma.mail.model.FolderFetchState

/**
 * Interface defining the contract for managing mail folders.
 */
interface FolderRepository {

    /**
     * Observes the state of folders for multiple accounts.
     * The map contains the fetch state for each account ID being observed.
     *
     * @return A Flow emitting a map where the key is the account ID (from [Account.id])
     * and the value is the [FolderFetchState] for that account.
     */
    fun observeFoldersState(): Flow<Map<String, FolderFetchState>>

    /**
     * Starts observing and fetching folders for a specific list of accounts.
     * Call this when the set of active accounts changes. The repository will manage
     * fetching and updating the state exposed by [observeFoldersState].
     *
     * @param accounts The list of [Account] objects to fetch folders for.
     */
    suspend fun manageObservedAccounts(accounts: List<Account>)

    /**
     * Triggers a refresh of folders for all currently observed accounts.
     * The results will be emitted through the [observeFoldersState] flow.
     *
     * @param activity The optional Activity context, required for interactive token flows if needed during refresh.
     */
    suspend fun refreshAllFolders(activity: Activity? = null)

}