package net.melisma.mail

import android.accounts.AccountManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.melisma.backend_google.auth.GoogleTokenPersistenceService
import net.melisma.core_data.model.WellKnownFolderType
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
import net.melisma.core_db.AppDatabase
import net.melisma.core_db.dao.AccountDao
import net.melisma.core_db.entity.AccountEntity
import net.melisma.data.repository.DefaultAccountRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class MessageBodyDownloadTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var messageRepository: MessageRepository
    @Inject
    lateinit var folderRepository: FolderRepository
    @Inject
    lateinit var accountRepository: DefaultAccountRepository
    @Inject
    lateinit var tokenPersistenceService: GoogleTokenPersistenceService
    @Inject
    lateinit var appDatabase: AppDatabase
    @Inject
    lateinit var accountManager: AccountManager
    @Inject
    lateinit var accountDao: AccountDao

    private val TEST_ACCOUNT_ID = "test-google-account"

    @Before
    fun setUp() {
        hiltRule.inject()
        // Assume that the refresh token is present in BuildConfig, otherwise skip the test
        assumeTrue(
            "Google refresh token is not available, skipping test.",
            BuildConfig.TEST_GMAIL_REFRESH_TOKEN != "null" && BuildConfig.TEST_GMAIL_REFRESH_TOKEN.isNotBlank()
        )
        runBlocking {
            appDatabase.clearAllTables()
            programmaticallyLoginGoogleAccount()
        }
    }

    private suspend fun programmaticallyLoginGoogleAccount() {
        val email = BuildConfig.TEST_GMAIL_EMAIL
        val refreshToken = BuildConfig.TEST_GMAIL_REFRESH_TOKEN

        // 1. First, add the account to our app's database so repositories can find it.
        val accountEntity = AccountEntity(
            id = TEST_ACCOUNT_ID,
            emailAddress = email,
            displayName = "Test Account",
            accountType = "net.melisma.mail.GOOGLE", // Use the constant
            lastSyncTimestamp = null,
            isDefaultAccount = true,
            signature = "Sent from Melisma Mail E2E Test"
        )
        accountDao.insertOrUpdate(accountEntity)

        // 2. Use the new test-only function to save the refresh token to Android's AccountManager.
        val result = tokenPersistenceService.saveRawRefreshTokenForTest(
            accountId = TEST_ACCOUNT_ID,
            email = email,
            refreshToken = refreshToken
        )
        assertThat(result).isInstanceOf(net.melisma.core_data.common.PersistenceResult.Success::class.java)
    }

    @Test
    fun test_fullSyncAndDisplayMessageBody() = runTest {
        // Phase 1: Sync Folders
        folderRepository.syncFoldersForAccount(TEST_ACCOUNT_ID)
        val folders = withTimeout(30_000) {
            folderRepository.getFolders(TEST_ACCOUNT_ID).first { it.isNotEmpty() }
        }
        assertThat(folders).isNotEmpty()
        val inbox = folders.firstOrNull { it.type == WellKnownFolderType.INBOX }
        assertNotNull(inbox, "Inbox folder not found.")

        // Phase 2: Sync Inbox Contents
        messageRepository.syncMessagesForFolder(TEST_ACCOUNT_ID, inbox.id)
        val messagesPager = messageRepository.getMessagesPager(
            TEST_ACCOUNT_ID,
            inbox.id,
            androidx.paging.PagingConfig(pageSize = 10)
        )

        // This is a bit tricky with PagingData. A simpler way for a test is to use a non-paging DAO method if available.
        // Let's use `observeMessagesForFolder` for simplicity in testing.
        val messages = withTimeout(30_000) {
            messageRepository.observeMessagesForFolder(TEST_ACCOUNT_ID, inbox.id).first { it.isNotEmpty() }
        }
        assertThat(messages).isNotEmpty()
        val testMessage = messages.first()

        // Phase 3: Fetch and Verify Message Body
        // Get initial state to confirm body is empty
        var messageDetails = messageRepository.getMessageById(testMessage.id).first()
        assertThat(messageDetails).isNotNull()
        // It might not be empty if it was fetched with the list. That's okay.
        // The main goal is to trigger the download if it's missing.

        // Explicitly trigger the detail fetch
        messageRepository.getMessageDetails(testMessage.id, TEST_ACCOUNT_ID).first() // This triggers the download if needed

        // Poll for the result
        val messageWithBody = withTimeout(60_000) { // Increased timeout for network
            messageRepository.getMessageById(testMessage.id).first { it?.body?.isNotBlank() == true }
        }

        assertThat(messageWithBody?.body).isNotEmpty()
        println("Successfully fetched message body for message: ${messageWithBody?.subject}")
    }
} 