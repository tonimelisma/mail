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

    @Query("SELECT * FROM accounts")
    fun getAllAccounts(): Flow<List<AccountEntity>>

    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("SELECT * FROM accounts LIMIT 1")
    suspend fun getAnyAccount(): AccountEntity? // Helper for checking if DB has any accounts
} 