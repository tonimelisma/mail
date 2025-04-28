package net.melisma.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge // Keeps edge-to-edge enabled
import androidx.compose.foundation.layout.Box // Import Box layout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment // Import Alignment for centering
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import net.melisma.mail.ui.theme.MailTheme // Assuming MailTheme is defined in your project

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge allows the app to draw behind system bars (status, navigation)
        // Scaffold's innerPadding will account for this.
        enableEdgeToEdge()
        setContent {
            // Apply your app's theme (handles colors, typography, shapes)
            MailTheme {
                // Scaffold provides standard layout structure (app bars, FABs, etc.)
                // and provides padding (`innerPadding`) needed for edge-to-edge.
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Use a Box to contain the main content.
                    // Apply the innerPadding here to prevent content from drawing
                    // under the system bars.
                    Box(
                        modifier = Modifier
                            .padding(innerPadding) // Apply padding calculated by Scaffold
                            .fillMaxSize(),       // Fill the remaining space
                        contentAlignment = Alignment.Center // Center content within the Box (optional)
                    ) {
                        // Call your composable function to display the greeting
                        Greeting("Melisma Mail!")
                        // Or simply: Text("Hello World!")
                    }
                }
            }
        }
    }
}

// A simple composable function that displays the greeting text
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
        // You can add styling here later, e.g., style = MaterialTheme.typography.headlineMedium
    )
}

// Preview function for Android Studio's design pane
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    // Apply the theme to the preview as well
    MailTheme {
        // Preview the Greeting composable directly
        Greeting("Android Preview")
    }
}