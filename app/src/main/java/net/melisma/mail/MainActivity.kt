// File: app/src/main/java/net/melisma/mail/MainActivity.kt
package net.melisma.mail

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.melisma.core_data.model.Account
import net.melisma.mail.navigation.MailAppNavigationGraph
import net.melisma.mail.ui.theme.MailTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val TAG = "MainActivity_AppAuth"

    private lateinit var appAuthLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Setting up appAuthLauncher for AppAuth (Google) Intent results")
        appAuthLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.i(
                TAG,
                "appAuthLauncher (Google): Result received. ResultCode: ${result.resultCode}"
            )
            viewModel.handleAuthenticationResult(
                providerType = Account.PROVIDER_TYPE_GOOGLE,
                resultCode = result.resultCode,
                data = result.data
            )
        }

        Log.d(TAG, "Setting up observation of pendingAuthIntent flow from ViewModel")
        lifecycleScope.launch {
            viewModel.pendingAuthIntent.collect { intent ->
                intent?.let {
                    Log.i(
                        TAG,
                        "Received pending Intent from ViewModel. Action: ${it.action}, Data: ${it.dataString}"
                    )
                    try {
                        Log.d(TAG, "Launching pending Intent with appAuthLauncher.")
                        appAuthLauncher.launch(it)
                        viewModel.authIntentLaunched()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching pending Intent via appAuthLauncher", e)
                        val errorPrefix = getString(R.string.error_google_signin_failed_generic)
                        Toast.makeText(
                            this@MainActivity,
                            "$errorPrefix: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.authIntentLaunched()
                    }
                }
            }
        }

        Log.d(TAG, "Setting up UI with enableEdgeToEdge and content")
        enableEdgeToEdge()
        setContent {
            Log.d(TAG, "Content composition started with Jetpack Navigation")
            MailTheme {
                val navController = rememberNavController()
                MailAppNavigationGraph(navController = navController, mainViewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(
            TAG,
            "onNewIntent: Intent received. Action: ${intent.action}, Data: ${intent.dataString}, Flags: ${intent.flags}"
        )
    }
}

