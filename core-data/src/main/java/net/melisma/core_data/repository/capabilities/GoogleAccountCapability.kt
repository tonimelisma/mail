package net.melisma.core_data.repository.capabilities

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account

/**
 * Defines capabilities specific to Google account management,
 * particularly for handling OAuth consent flows.
 *
 * This interface segregates Google-specific functionality from the common
 * AccountRepository interface, promoting better separation of concerns.
 */
interface GoogleAccountCapability {
    /**
     * A [Flow] emitting the [IntentSender] required to launch Google's OAuth consent UI.
     * Emits `null` when no consent is currently pending.
     *
     * This flow should be observed by the UI layer to know when to launch the consent screen
     * during Google account addition that requires explicit OAuth scope grants.
     */
    val googleConsentIntent: Flow<IntentSender?>

    /**
     * Finalizes the Google OAuth scope consent process after the user has interacted
     * with the consent UI launched via the [IntentSender].
     *
     * @param account The Google [Account] for which consent is being finalized.
     * @param intent The [Intent] data returned from the consent Activity.
     * @param activity The current [Activity] context.
     */
    suspend fun finalizeGoogleScopeConsent(account: Account, intent: Intent?, activity: Activity)
}