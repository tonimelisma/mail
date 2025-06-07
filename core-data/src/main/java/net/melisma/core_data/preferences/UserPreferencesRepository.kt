package net.melisma.core_data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

enum class CacheSizePreference(val bytes: Long) {
    MB_500(500L * 1024L * 1024L),
    GB_1(1L * 1024L * 1024L * 1024L),
    GB_2(2L * 1024L * 1024L * 1024L),
    GB_5(5L * 1024L * 1024L * 1024L);

    companion object {
        fun fromBytes(bytes: Long): CacheSizePreference {
            return entries.find { it.bytes == bytes } ?: MB_500 // Default to 500MB
        }
    }
}

enum class InitialSyncDurationPreference(val durationInDays: Long, val displayName: String) {
    DAYS_30(30L, "30 Days"),
    DAYS_90(90L, "90 Days"), // Default
    MONTHS_6(180L, "6 Months"),
    ALL_TIME(Long.MAX_VALUE, "All Time"); // Represents indefinite history

    companion object {
        fun fromDays(days: Long): InitialSyncDurationPreference {
            // For ALL_TIME, Long.MAX_VALUE might be stored.
            // Find exact match, or default if no match (e.g., if stored value is legacy/corrupt)
            return entries.find { it.durationInDays == days } ?: DAYS_90
        }

        fun defaultPreference(): InitialSyncDurationPreference = DAYS_90
    }
}

enum class DownloadPreference(val displayName: String) {
    ALWAYS("Always"),
    ON_WIFI("On Wi-Fi only"),
    ON_DEMAND("On demand only");

    companion object {
        fun fromString(value: String?, default: DownloadPreference): DownloadPreference {
            return entries.find { it.name == value } ?: default
        }
    }
}

data class UserPreferences(
    val mailViewMode: MailViewModePreference,
    val cacheSizeLimitBytes: Long,
    val initialSyncDurationDays: Long,
    val bodyDownloadPreference: DownloadPreference,
    val attachmentDownloadPreference: DownloadPreference
)

interface UserPreferencesRepository {
    val userPreferencesFlow: Flow<UserPreferences>
    suspend fun updateMailViewMode(mailViewMode: MailViewModePreference)
    suspend fun updateCacheSizeLimit(cacheSizePreference: CacheSizePreference)
    suspend fun updateInitialSyncDuration(durationPreference: InitialSyncDurationPreference)
    suspend fun updateBodyDownloadPreference(preference: DownloadPreference)
    suspend fun updateAttachmentDownloadPreference(preference: DownloadPreference)
}

@Singleton
class DefaultUserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : UserPreferencesRepository {

    private object PreferencesKeys {
        // Store as boolean: true for THREADS, false for MESSAGES (or use string if more modes anticipated)
        val IS_THREAD_VIEW_MODE = booleanPreferencesKey("is_thread_view_mode")
        val CACHE_SIZE_LIMIT_BYTES = longPreferencesKey("cache_size_limit_bytes")
        val INITIAL_SYNC_DURATION_DAYS = longPreferencesKey("initial_sync_duration_days")
        val BODY_DOWNLOAD_PREFERENCE = stringPreferencesKey("body_download_preference")
        val ATTACHMENT_DOWNLOAD_PREFERENCE = stringPreferencesKey("attachment_download_preference")
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

            val cacheLimit = preferences[PreferencesKeys.CACHE_SIZE_LIMIT_BYTES]
                ?: CacheSizePreference.MB_500.bytes // Default to 500MB

            val initialSyncDays = preferences[PreferencesKeys.INITIAL_SYNC_DURATION_DAYS]
                ?: InitialSyncDurationPreference.defaultPreference().durationInDays

            val bodyPrefString = preferences[PreferencesKeys.BODY_DOWNLOAD_PREFERENCE]
            val bodyDownloadPreference = DownloadPreference.fromString(bodyPrefString, DownloadPreference.ALWAYS)

            val attachmentPrefString = preferences[PreferencesKeys.ATTACHMENT_DOWNLOAD_PREFERENCE]
            val attachmentDownloadPreference = DownloadPreference.fromString(attachmentPrefString, DownloadPreference.ON_WIFI)

            UserPreferences(
                mailViewMode,
                cacheLimit,
                initialSyncDays,
                bodyDownloadPreference,
                attachmentDownloadPreference
            )
        }

    override suspend fun updateMailViewMode(mailViewMode: MailViewModePreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_THREAD_VIEW_MODE] =
                (mailViewMode == MailViewModePreference.THREADS)
        }
    }

    override suspend fun updateCacheSizeLimit(cacheSizePreference: CacheSizePreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CACHE_SIZE_LIMIT_BYTES] = cacheSizePreference.bytes
        }
    }

    override suspend fun updateInitialSyncDuration(durationPreference: InitialSyncDurationPreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.INITIAL_SYNC_DURATION_DAYS] = durationPreference.durationInDays
        }
    }

    override suspend fun updateBodyDownloadPreference(preference: DownloadPreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.BODY_DOWNLOAD_PREFERENCE] = preference.name
        }
    }

    override suspend fun updateAttachmentDownloadPreference(preference: DownloadPreference) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ATTACHMENT_DOWNLOAD_PREFERENCE] = preference.name
        }
    }
} 