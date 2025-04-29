package net.melisma.mail // Package for the app module

// MSAL imports

// Import your AuthManager, Listener, and Result types
// Import your app's theme
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.exception.MsalException
import net.melisma.feature_auth.AuthStateListener
import net.melisma.feature_auth.MicrosoftAuthManager
import net.melisma.feature_auth.SignInResult
import net.melisma.feature_auth.SignOutResult
import net.melisma.mail.ui.theme.MailTheme

class MainActivity : ComponentActivity() {

    private lateinit var microsoftAuthManager: MicrosoftAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the Auth Manager from the feature-auth module
        microsoftAuthManager = MicrosoftAuthManager(
            context = applicationContext,
            configResId = R.raw.auth_config // Reference the config file in app module
        )

        enableEdgeToEdge() // Enable edge-to-edge display

        setContent {
            MailTheme { // Apply app theme
                // Pass manager instance to the main UI composable
                MainScreen(authManager = microsoftAuthManager)
            }
        }
    }
}

// Main screen Composable
@Composable
fun MainScreen(authManager: MicrosoftAuthManager) {
    // --- Local Composable State ---
    var isInitialized by remember { mutableStateOf(authManager.isInitialized) }
    var currentAccountState by remember { mutableStateOf<IAccount?>(authManager.currentAccount) } // Renamed state variable
    var initializationError by remember { mutableStateOf<MsalException?>(authManager.initializationError) }
    // -----------------------------

    var isLoading by remember { mutableStateOf(false) }

    // Use DisposableEffect to register/unregister the listener safely
    DisposableEffect(authManager) {
        Log.d("MainScreen", "Registering AuthStateListener")
        val listener = object : AuthStateListener {
            override fun onAuthStateChanged(
                initialized: Boolean,
                account: IAccount?, // Parameter name from interface
                error: MsalException?
            ) {
                Log.d(
                    "MainScreenListener",
                    "onAuthStateChanged: init=$initialized, account=${account?.username}, error=$error"
                )
                isInitialized = initialized
                currentAccountState = account // Update the state variable
                initializationError = error
            }
        }
        authManager.setAuthStateListener(listener)

        onDispose {
            Log.d("MainScreen", "Disposing AuthStateListener")
            authManager.setAuthStateListener(null)
        }
    }

    // Optional: Show toast on initialization error change
    LaunchedEffect(initializationError) {
        if (initializationError != null) {
            Log.e("MainScreen", "MSAL Failed to initialize", initializationError)
            showToast(authManager.context, "Authentication library failed to initialize.")
        }
    }

    val activity = findActivity()
    val contextForToast = LocalContext.current

    // Add log to see the value of isInitialized during composition
    Log.d(
        "MainScreen",
        "Composing MainScreen - isInitialized: $isInitialized, Account: ${currentAccountState?.username}"
    )

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Capture the current state value into a local immutable variable
            val account = currentAccountState

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else if (account == null) { // Check the local variable
                    // --- Signed Out State ---
                    Text("Welcome to Melisma Mail")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (!isInitialized) {
                                showToast(contextForToast, "Auth Service not ready.")
                                return@Button
                            }
                            isLoading = true
                            val scopes = listOf("User.Read", "Mail.Read")
                            authManager.signIn(activity, scopes) { result ->
                                isLoading = false
                                // UI state (currentAccountState) updated via listener
                                when (result) {
                                    is SignInResult.Success -> {
                                        showToast(
                                            contextForToast,
                                            "Sign in Success: ${result.account.username}"
                                        )
                                    }
                                    is SignInResult.Error -> {
                                        showToast(
                                            contextForToast,
                                            "Sign In Error: ${result.exception.message}"
                                        )
                                        Log.e("MainScreen", "Sign In Error", result.exception)
                                    }
                                    is SignInResult.Cancelled -> {
                                        showToast(contextForToast, "Sign in Cancelled")
                                    }
                                    is SignInResult.NotInitialized -> {
                                        showToast(contextForToast, "Auth Service not ready.")
                                    }
                                }
                            }
                        },
                        enabled = isInitialized
                    ) {
                        Text("Sign In with Microsoft")
                    }
                    if (!isInitialized && initializationError == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("(Initializing...)", style = MaterialTheme.typography.bodySmall)
                    } else if (initializationError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "(Initialization Failed)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                } else { // Smart cast works here because 'account' is a local val
                    // --- Signed In State ---
                    Text("Signed in as:")
                    // Use the local 'account' variable which can now be smart-cast
                    Text(
                        account.username ?: "Unknown User",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        if (!isInitialized) {
                            showToast(contextForToast, "Auth Service not ready.")
                            return@Button
                        }
                        isLoading = true
                        authManager.signOut { result ->
                            isLoading = false
                            // UI state (currentAccountState) updated via listener
                            when (result) {
                                is SignOutResult.Success -> {
                                    showToast(contextForToast, "Signed Out")
                                }
                                is SignOutResult.Error -> {
                                    showToast(
                                        contextForToast,
                                        "Sign Out Error: ${result.exception.message}"
                                    )
                                    Log.e("MainScreen", "Sign Out Error", result.exception)
                                }
                                is SignOutResult.NotInitialized -> {
                                    showToast(contextForToast, "Auth Service not ready.")
                                }
                            }
                        }
                    }) {
                        Text("Sign Out")
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {
                        showToast(contextForToast, "View Mail - Not Implemented")
                    }) {
                        Text("View Mail (Not Implemented)")
                    }
                }
            }
        }
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
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

// --- Previews ---
@Preview(showBackground = true, name = "Signed Out Preview")
@Composable
fun MainScreenPreview_SignedOut() {
    MailTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome to Melisma Mail")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {}, enabled = true) { Text("Sign In with Microsoft") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("(Initializing...)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Signed In Preview")
@Composable
fun MainScreenPreview_SignedIn() {
    MailTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Signed in as:")
                    Text("user@example.com", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {}) { Text("Sign Out") }
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = {}) { Text("View Mail (Not Implemented)") }
                }
            }
        }
    }
}
