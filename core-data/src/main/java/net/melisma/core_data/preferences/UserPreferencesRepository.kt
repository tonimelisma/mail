package net.melisma.core_data.preferences

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
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

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
            val isThreadMode = preferences[PreferencesKeys.IS_THREAD_VIEW_MODE]
                ?: true // Default to true (Threads)
            val mailViewMode =
                if (isThreadMode) MailViewModePreference.THREADS else MailViewModePreference.MESSAGES
            UserPreferences(mailViewMode)
        }

    override suspend fun updateMailViewMode(mailViewMode: MailViewModePreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_THREAD_VIEW_MODE] =
                (mailViewMode == MailViewModePreference.THREADS)
        }
    }
} 