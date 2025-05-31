package net.melisma.mail.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.melisma.mail.MainViewModel
import net.melisma.mail.ui.MainAppScreen
import net.melisma.mail.ui.messagedetail.MessageDetailScreen
import net.melisma.mail.ui.settings.SettingsScreen
import net.melisma.mail.ui.threaddetail.ThreadDetailScreen

@Composable
fun MailAppNavigationGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel // Passed from MainActivity, provides global state/events if needed
) {
    NavHost(navController = navController, startDestination = AppRoutes.HOME) {
        composable(AppRoutes.HOME) {
            // MainAppScreen will be the refactored content of the original MainApp Composable
            MainAppScreen(navController = navController, mainViewModel = mainViewModel)
        }

        composable(AppRoutes.SETTINGS) {
            val activity = LocalActivity.current
            if (activity != null) {
                SettingsScreen(
                    viewModel = mainViewModel, // SettingsScreen might use parts of MainViewModel or have its own
                    activity = activity,
                    onNavigateUp = { navController.navigateUp() }
                )
            } else {
                // Handle error: activity context not available. This case should ideally not happen.
            }
        }

        composable(
            route = AppRoutes.MESSAGE_DETAIL,
            arguments = listOf(
                navArgument(AppRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType },
                navArgument(AppRoutes.ARG_MESSAGE_ID) { type = NavType.StringType }
            )
        ) {
            // ViewModel will pick up args from SavedStateHandle
            MessageDetailScreen(navController = navController)
        }

        composable(
            route = AppRoutes.THREAD_DETAIL,
            arguments = listOf(
                navArgument(AppRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType },
                navArgument(AppRoutes.ARG_THREAD_ID) { type = NavType.StringType }
            )
        ) {
            // ViewModel will pick up args from SavedStateHandle
            ThreadDetailScreen(navController = navController)
        }
    }
} 