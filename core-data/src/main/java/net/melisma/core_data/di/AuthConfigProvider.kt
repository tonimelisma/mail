// File: core-data/src/main/java/net/melisma/core_data/di/AuthConfigProvider.kt
// NEW LOCATION for the interface
package net.melisma.core_data.di // Package changed to core_data

import androidx.annotation.RawRes

/**
 * Interface definition for providing backend-specific configuration sourced from the application module.
 * This helps decouple backend implementations from direct knowledge of app-specific resources like R.raw IDs.
 * This interface now resides in the :core-data module.
 */
interface AuthConfigProvider {
    /**
     * @return The Android raw resource ID for the MSAL configuration JSON file.
     */
    @RawRes
    fun getMsalConfigResId(): Int
}
