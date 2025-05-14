Plan: Add View Mode Setting (Threads vs. Messages)This plan details the steps to add a user setting
that allows switching between a threaded email view and a flat message list view. The chosen
preference will be persisted across app sessions.We will use Jetpack Preferences DataStore for
saving the setting.Phase 1: Preference Persistence Layer (New)Objective: Create a mechanism to store
and retrieve the user's view mode preference.Add DataStore Dependency:File: build.gradle (:app or
your core/data module's build.gradle)Action: Add the Jetpack Preferences DataStore dependency.//
Example for app/build.gradle.kts or data/build.gradle.kts
dependencies {
// ... other dependencies
implementation("androidx.datastore:datastore-preferences:1.1.1") // Use the latest version
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0") // Often used with DataStore
}
Note: Sync Gradle after adding.Create UserPreferencesRepository.kt:File:
core-data/src/main/java/net/melisma/core_data/preferences/UserPreferencesRepository.kt (Create New
Package and File)Action: Define an interface and its implementation to handle reading and writing
the view mode preference using Preferences DataStore.Code:package net.melisma.core_data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Define a DataStore instance at the top level of your Kotlin file
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "
user_settings")

enum class MailViewModePreference {
THREADS, MESSAGES
}

data class UserPreferences(
val mailViewMode: MailViewModePreference
)

interface UserPreferencesRepository {
val userPreferencesFlow: Flow<UserPreferences>
suspend fun updateMailViewMode(mailViewMode: MailViewModePreference)
}

@Singleton
class DefaultUserPreferencesRepository @Inject constructor(
@ApplicationContext private val context: Context
) : UserPreferencesRepository {

    private object PreferencesKeys {
        // Store as boolean: true for THREADS, false for MESSAGES (or use string if more modes anticipated)
        val IS_THREAD_VIEW_MODE = booleanPreferencesKey("is_thread_view_mode")
    }

    override val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                // Handle error reading preferences
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val isThreadMode = preferences[PreferencesKeys.IS_THREAD_VIEW_MODE] ?: true // Default to true (Threads)
            val mailViewMode = if (isThreadMode) MailViewModePreference.THREADS else MailViewModePreference.MESSAGES
            UserPreferences(mailViewMode)
        }

    override suspend fun updateMailViewMode(mailViewMode: MailViewModePreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_THREAD_VIEW_MODE] = (mailViewMode == MailViewModePreference.THREADS)
        }
    }

}
Provide UserPreferencesRepository via Hilt:File:
core-data/src/main/java/net/melisma/core_data/di/PreferencesModule.kt (Create New File in :
core-data's DI package)Action: Create a Hilt module to provide the UserPreferencesRepository.Code:
package net.melisma.core_data.di // Or your common DI module location

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.preferences.DefaultUserPreferencesRepository
import net.melisma.core_data.preferences.UserPreferencesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesRepositoryModule { // Changed to abstract class for @Binds
@Binds
@Singleton
abstract fun bindUserPreferencesRepository(
impl: DefaultUserPreferencesRepository
): UserPreferencesRepository
}
Phase 2: ViewModel IntegrationObjective: Modify MainViewModel.kt to load, observe, and save the view
mode preference.File: app/src/main/java/net/melisma/mail/MainViewModel.ktAction 1: Inject
UserPreferencesRepository.Code Change (in constructor):@HiltViewModel
class MainViewModel @Inject constructor(
@ApplicationContext private val applicationContext: Context,
private val accountRepository: AccountRepository,
private val folderRepository: FolderRepository,
private val messageRepository: MessageRepository,
private val threadRepository: ThreadRepository, // Assuming this was added from previous plan

+ private val userPreferencesRepository: UserPreferencesRepository // <-- ADD THIS
  ) : ViewModel() {
  Action 2: Update ViewMode enum to align with MailViewModePreference.Code Change:// In
  MainViewModel.kt

- enum class ViewMode { THREADS, MESSAGES }

+ typealias ViewMode = net.melisma.core_data.preferences.MailViewModePreference // Use the one from
  core-data
  (Alternatively, keep your local ViewMode and map between them, but using the shared one is
  cleaner).Action 3: Initialize currentViewMode in MainScreenState from userPreferencesRepository
  and observe changes.Code Change (in init block):// In init block of MainViewModel
  init {
  Log.d(TAG, "ViewModel Initializing")
  observeAccountRepository()
  observeFolderRepository()
  observeMessageRepository()
  observeThreadRepository() // Assuming this was added
+ observeUserPreferences() // <-- ADD THIS CALL

  // ... rest of init ...
  }

+private fun observeUserPreferences() {

+ viewModelScope.launch {
+        userPreferencesRepository.userPreferencesFlow.collect { preferences ->
+            Log.d(TAG, "User preference for ViewMode loaded: ${preferences.mailViewMode}")
+            val currentUiStateViewMode = _uiState.value.currentViewMode
+            if (currentUiStateViewMode != preferences.mailViewMode) {
+                _uiState.update { it.copy(currentViewMode = preferences.mailViewMode) }
+                // If a folder is already selected, re-trigger fetch for the new view mode
+                _uiState.value.selectedFolder?.let { folder ->
+                    _uiState.value.accounts.find { it.id == _uiState.value.selectedFolderAccountId }?.let { account ->
+                        Log.d(TAG, "Preference changed, re-selecting folder ${folder.displayName} for new view mode ${preferences.mailViewMode}")
+                        selectFolder(folder, account)
+                    }
+                }
+            }
+        }
+ }
  +}
  Modify MainScreenState default for currentViewMode:data class MainScreenState(
  // ...

- val currentViewMode: ViewMode = ViewMode.THREADS,

+ val currentViewMode: ViewMode = MailViewModePreference.THREADS, // Initial default, will be
  updated from DataStore
  // ...
  )
  Action 4: Modify toggleViewMode() (or create setViewModePreference) to save the preference.Code
  Change (rename and modify toggleViewMode or create new):// Keep toggleViewMode if you have a quick
  toggle button in the UI,
  // but also add a specific setter for clarity from settings.
  fun setViewModePreference(newMode: ViewMode) {
  Log.i(TAG, "setViewModePreference called with new mode: $newMode")
  if (_uiState.value.currentViewMode == newMode) return // No change

  viewModelScope.launch {
  userPreferencesRepository.updateMailViewMode(newMode)
  // userPreferencesFlow will automatically update _uiState.value.currentViewMode
  // and trigger re-selection of folder if needed (as per observeUserPreferences logic)
  }
  }

// If you keep toggleViewMode for a UI button:
fun toggleViewMode() {
val newMode = if (_uiState.value.currentViewMode == ViewMode.THREADS) ViewMode.MESSAGES else
ViewMode.THREADS
setViewModePreference(newMode)
}
Phase 3: Settings Screen UIObjective: Add a UI element in SettingsScreen.kt to change the view
mode.File: app/src/main/java/net/melisma/mail/ui/settings/SettingsScreen.ktAction: Add a new section
for "View Preferences" with a Switch or RadioGroup to select the view mode. We'll use a ListItem
with a Switch for simplicity.Code Changes:// ... imports ...
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import net.melisma.core_data.preferences.MailViewModePreference // Import the enum

// ... inside SettingsScreen Composable ...
LazyColumn(
// ... modifier ...
) {
// ... existing "Manage Accounts" section ...

    item {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = stringResource(R.string.settings_view_preferences_header), // New String Resource
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    item {
        val isThreadMode = state.currentViewMode == MailViewModePreference.THREADS
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_view_mode_title)) }, // New String
            supportingContent = {
                Text(
                    if (isThreadMode) stringResource(R.string.settings_view_mode_threads_desc) // New String
                    else stringResource(R.string.settings_view_mode_messages_desc) // New String
                )
            },
            trailingContent = {
                Switch(
                    checked = isThreadMode,
                    onCheckedChange = { wantsThreadMode ->
                        val newMode = if (wantsThreadMode) MailViewModePreference.THREADS else MailViewModePreference.MESSAGES
                        viewModel.setViewModePreference(newMode)
                    }
                )
            },
            modifier = Modifier.clickable {
                val newMode = if (isThreadMode) MailViewModePreference.MESSAGES else MailViewModePreference.THREADS
                viewModel.setViewModePreference(newMode)
            }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
    }


    item { Spacer(modifier = Modifier.height(120.dp)) }

} // End LazyColumn
Add New String Resources (e.g., in
app/src/main/res/values/strings.xml):<string name="settings_view_preferences_header">View
Preferences</string>
<string name="settings_view_mode_title">Email Display Mode</string>
<string name="settings_view_mode_threads_desc">Group emails by conversation (threaded)</string>
<string name="settings_view_mode_messages_desc">Show individual emails in a flat list</string>
Summary of Changes:Data Persistence: Added UserPreferencesRepository using Jetpack DataStore to save
the view mode.ViewModel: MainViewModel now loads this preference on startup, updates its
currentViewMode state accordingly, and saves the preference when changed by the user.Settings UI:
SettingsScreen.kt now includes a Switch to allow the user to choose their preferred view mode, which
calls the updated ViewModel function.View Switching: The existing logic in
MainViewModel.selectFolder (which re-fetches data based on currentViewMode) and the UI observation
of currentViewMode in MainActivity.kt will handle the actual switch between displaying threads or
messages.This plan provides a robust way to implement the view mode setting. The junior developer
should focus on creating the new UserPreferencesRepository, integrating it into the MainViewModel,
and then adding the UI elements to SettingsScreen.kt.
