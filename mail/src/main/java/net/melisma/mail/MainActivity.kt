// File: app/src/main/java/net/melisma/mail/MainActivity.kt
package net.melisma.mail

import android.content.Intent
import android.os.Bundle
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
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val TAG = "MainActivity_AppAuth"

    private lateinit var appAuthLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.d("onCreate() called")
        super.onCreate(savedInstanceState)

        Timber.d("Setting up appAuthLauncher for AppAuth (Google) Intent results")
        appAuthLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Timber.i(
                "appAuthLauncher (Google): Result received. ResultCode: ${result.resultCode}"
            )
            viewModel.handleAuthenticationResult(
                providerType = Account.PROVIDER_TYPE_GOOGLE,
                resultCode = result.resultCode,
                data = result.data
            )
        }

        Timber.d("Setting up observation of pendingAuthIntent flow from ViewModel")
        lifecycleScope.launch {
            viewModel.pendingAuthIntent.collect { intent ->
                intent?.let {
                    Timber.i(
                        "Received pending Intent from ViewModel. Action: ${it.action}, Data: ${it.dataString}"
                    )
                    try {
                        Timber.d("Launching pending Intent with appAuthLauncher.")
                        appAuthLauncher.launch(it)
                        viewModel.consumePendingAuthIntent()
                    } catch (e: Exception) {
                        Timber.e(e, "Error launching pending Intent via appAuthLauncher")
                        val errorPrefix = getString(R.string.error_google_signin_failed_generic)
                        Toast.makeText(
                            this@MainActivity,
                            "$errorPrefix: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.consumePendingAuthIntent()
                    }
                }
            }
        }

        Timber.d("Setting up UI with enableEdgeToEdge and content")
        enableEdgeToEdge()
        setContent {
            Timber.d("Content composition started with Jetpack Navigation")
            MailTheme {
                val navController = rememberNavController()
                MailAppNavigationGraph(navController = navController, mainViewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.i(
            "onNewIntent: Intent received. Action: ${intent.action}, Data: ${intent.dataString}, Flags: ${intent.flags}"
        )
    }
}

