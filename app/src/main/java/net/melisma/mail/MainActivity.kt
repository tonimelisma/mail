package net.melisma.mail // Package for the app module

// --- Explicit MSAL Imports --- Needed because :app depends on :feature-auth via 'api'
// -----------------------------

// Import your AuthManager and Result types
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
    var account by remember { mutableStateOf(authManager.currentAccount) }
    var isLoading by remember { mutableStateOf(false) }

    // Observe account changes from the manager
    LaunchedEffect(authManager.currentAccount) {
        if (account != authManager.currentAccount) {
            account = authManager.currentAccount
            Log.d("MainScreen", "Account state updated via LaunchedEffect: ${account?.username}")
        }
    }
    // Observe initialization state
    LaunchedEffect(authManager.isInitialized) {
        if (!authManager.isInitialized && authManager.initializationError != null) {
            Log.e("MainScreen", "MSAL Failed to initialize", authManager.initializationError)
            // Access context directly from the composable scope for the Toast
            showToast(authManager.context, "Authentication library failed to initialize.")
        }
    }

    val activity = findActivity()
    // Get context directly within the composable scope where needed
    val contextForToast = LocalContext.current

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding) // Apply system bar padding
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else if (account == null) {
                    // --- Signed Out State ---
                    Text("Welcome to Melisma Mail")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (!authManager.isInitialized) {
                                showToast(contextForToast, "Auth Service not ready.")
                                return@Button
                            }
                            isLoading = true
                            val scopes = listOf("User.Read", "Mail.Read")
                            authManager.signIn(activity, scopes) { result ->
                                isLoading = false
                                when (result) {
                                    is SignInResult.Success -> {
                                        // account state update handled by LaunchedEffect
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
                        enabled = authManager.isInitialized
                    ) {
                        Text("Sign In with Microsoft")
                    }
                } else {
                    // --- Signed In State ---
                    Text("Signed in as:")
                    Text(
                        account?.username ?: "Unknown User",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        if (!authManager.isInitialized) {
                            showToast(contextForToast, "Auth Service not ready.")
                            return@Button
                        }
                        isLoading = true
                        authManager.signOut { result ->
                            isLoading = false
                            when (result) {
                                is SignOutResult.Success -> {
                                    // account state update handled by LaunchedEffect
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
// Now takes context as a parameter
private fun showToast(context: Context, message: String?) {
    if (message.isNullOrBlank()) return
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

// Previews remain the same as they don't interact with the auth manager directly
@Preview(showBackground = true, name = "Signed Out Preview")
@Composable
fun MainScreenPreview_SignedOut() {
    MailTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Welcome to Melisma Mail")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {}) { Text("Sign In with Microsoft") }
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
