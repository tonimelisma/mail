package net.melisma.core_db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.melisma.core_db.entity.AccountEntity

@Dao
interface AccountDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAccounts(accounts: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    fun getAccountById(accountId: String): Flow<AccountEntity?>

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    suspend fun getAccountByIdSuspend(accountId: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY emailAddress ASC")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("UPDATE accounts SET needsReauthentication = :needsReauth WHERE id = :accountId")
    suspend fun setNeedsReauthentication(accountId: String, needsReauth: Boolean)

    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("SELECT * FROM accounts LIMIT 1")
    suspend fun getAnyAccount(): AccountEntity? // Helper for checking if DB has any accounts

    // New methods for folder list sync status
    @Query("UPDATE accounts SET lastFolderListSyncTimestamp = :timestamp, lastFolderListSyncError = NULL, lastGenericSyncError = NULL WHERE id = :accountId")
    suspend fun updateFolderListSyncSuccess(accountId: String, timestamp: Long)

    @Query("UPDATE accounts SET lastFolderListSyncError = :errorMessage WHERE id = :accountId")
    suspend fun updateFolderListSyncError(accountId: String, errorMessage: String)

    @Query("UPDATE accounts SET folderListSyncToken = :syncToken WHERE id = :accountId")
    suspend fun updateFolderListSyncToken(accountId: String, syncToken: String?)

    // Generic sync error update for the account
    @Query("UPDATE accounts SET lastGenericSyncError = :errorMessage WHERE id = :accountId")
    suspend fun updateAccountSyncError(accountId: String, errorMessage: String)

    @Query("SELECT latestDeltaToken FROM accounts WHERE id = :accountId")
    suspend fun getLatestDeltaToken(accountId: String): String?

    @Query("UPDATE accounts SET latestDeltaToken = :token WHERE id = :accountId")
    suspend fun updateLatestDeltaToken(accountId: String, token: String?)
} 