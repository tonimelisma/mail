package net.melisma.mail

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.microsoft.identity.client.IAccount
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.mail.ui.theme.MailTheme

class MainActivity : ComponentActivity() {

    // Lazy initialization of Auth Manager
    private val microsoftAuthManager: MicrosoftAuthManager by lazy {
        MicrosoftAuthManager(
            context = applicationContext,
            configResId = R.raw.auth_config // Reference the config file in app module
        )
    }

    // Get ViewModel instance using activity-ktx delegates and the factory
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory(microsoftAuthManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge() // Enable edge-to-edge display

        setContent {
            MailTheme { // Apply app theme
                // Pass ViewModel to the main UI composable
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

// Main screen Composable
@Composable
fun MainScreen(viewModel: MainViewModel) {
    // Observe state from ViewModel using lifecycle-aware collection
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val activity = findActivity()
    val context = LocalContext.current

    // Show toast messages when they appear in the state
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            showToast(context, message)
            viewModel.toastMessageShown() // Notify ViewModel that toast was shown
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp), // Add some padding around the content
            contentAlignment = Alignment.Center
        ) {
            // Main content column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Show main loading indicator if sign-in/out is in progress
                if (state.isLoading) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // *** FIX for Smart Cast: Assign state.currentAccount to a local variable ***
                val currentAccount = state.currentAccount

                // Signed Out State
                if (currentAccount == null) {
                    SignedOutContent(
                        isAuthInitialized = state.isAuthInitialized,
                        authInitializationError = state.authInitializationError,
                        onSignInClick = { viewModel.signIn(activity) }
                    )
                }
                // Signed In State
                else {
                    // *** Use the local variable 'currentAccount' here ***
                    SignedInContent(
                        account = currentAccount, // Pass the local variable
                        folders = state.folders,
                        isLoadingFolders = state.isLoadingFolders,
                        folderError = state.folderError,
                        onSignOutClick = { viewModel.signOut() },
                        onFetchFoldersClick = { viewModel.fetchMailFolders(activity) }
                    )
                }
            }
        }
    }
}

// Composable for the Signed Out state
@Composable
fun SignedOutContent(
    isAuthInitialized: Boolean,
    authInitializationError: Exception?,
    onSignInClick: () -> Unit
) {
    Text("Welcome to Melisma Mail")
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onSignInClick,
        enabled = isAuthInitialized // Enable button only when initialized
    ) {
        Text("Sign In with Microsoft")
    }

    // Show initialization status
    if (!isAuthInitialized && authInitializationError == null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("(Initializing Auth...)", style = MaterialTheme.typography.bodySmall)
    } else if (authInitializationError != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "(Auth Initialization Failed: ${authInitializationError.message})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// Composable for the Signed In state
@Composable
fun SignedInContent(
    account: IAccount, // Use the non-null account passed in
    folders: List<MailFolder>?,
    isLoadingFolders: Boolean,
    folderError: String?,
    onSignOutClick: () -> Unit,
    onFetchFoldersClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Signed in as:")
        Text(
            // Use methods from IClaimable (inherited by IAccount)
            account.username ?: "Unknown User",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onSignOutClick) {
            Text("Sign Out")
        }
        Spacer(modifier = Modifier.height(32.dp))

        // Folder Section
        Button(onClick = onFetchFoldersClick, enabled = !isLoadingFolders) {
            Text("Fetch Folders")
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Folder Loading Indicator
        if (isLoadingFolders) {
            CircularProgressIndicator()
        }
        // Folder Error Message
        else if (folderError != null) {
            Text(
                "Error loading folders: $folderError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        // Folder List
        else if (folders != null) {
            if (folders.isEmpty()) {
                Text("No mail folders found.")
            } else {
                // Use LazyColumn for potentially long lists
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp) // Limit height
                ) {
                    item {
                        Text("Mail Folders:", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(folders) { folder ->
                        FolderItem(folder)
                        Divider() // Add divider between items
                    }
                }
            }
        }
    }
}

// Composable to display a single folder item
@Composable
fun FolderItem(folder: MailFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(folder.displayName, modifier = Modifier.weight(1f))
        Text(
            "${folder.unreadItemCount}/${folder.totalItemCount}",
            fontWeight = if (folder.unreadItemCount > 0) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


// Helper function to get Activity context from Composable
@Composable
private fun findActivity(): Activity {
    val context = LocalContext.current
    return context as? Activity
        ?: throw IllegalStateException("Composable is not hosted in an Activity context")
}

// Helper function for displaying Toast messages
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// --- Previews ---

// --- FIX for Previews: Update Mock IAccount implementation ---
// Helper function to create a mock IAccount for Previews based on MSAL 6.0.0 docs
@Composable
private fun createPreviewAccount(): IAccount {
    return object : IAccount {
        override fun getId(): String = "mock_oid" // Use getId() as per docs
        override fun getAuthority(): String = "https://login.microsoftonline.com/common"

        // Methods inherited from IClaimable
        override fun getClaims(): MutableMap<String, *> = mutableMapOf("tid" to getTenantId())
        override fun getIdToken(): String? = null // Can be null
        override fun getTenantId(): String = "common"
        override fun getUsername(): String = "user@example.com"

        // Removed getClientInfo, getEnvironment, getHomeAccountId as they aren't in the provided interface def
    }
}

@Preview(showBackground = true, name = "Signed Out Preview")
@Composable
fun MainScreenPreview_SignedOut() {
    MailTheme {
        SignedOutContent(
            isAuthInitialized = true,
            authInitializationError = null,
            onSignInClick = {})
    }
}

@Preview(showBackground = true, name = "Signed In Preview - No Folders")
@Composable
fun MainScreenPreview_SignedIn_No_Folders() {
    val previewAccount = createPreviewAccount() // Use helper function
    MailTheme {
        SignedInContent(
            account = previewAccount,
            folders = null,
            isLoadingFolders = false,
            folderError = null,
            onSignOutClick = {},
            onFetchFoldersClick = {}
        )
    }
}

@Preview(showBackground = true, name = "Signed In Preview - With Folders")
@Composable
fun MainScreenPreview_SignedIn_With_Folders() {
    val previewAccount = createPreviewAccount() // Use helper function
    val previewFolders = listOf(
        MailFolder("id1", "Inbox", 10, 2),
        MailFolder("id2", "Sent Items", 50, 0),
        MailFolder("id3", "Drafts", 5, 1)
    )
    MailTheme {
        SignedInContent(
            account = previewAccount,
            folders = previewFolders,
            isLoadingFolders = false,
            folderError = null,
            onSignOutClick = {},
            onFetchFoldersClick = {}
        )
    }
}