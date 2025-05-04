package net.melisma.backend_microsoft.di // Or your chosen package like net.melisma.backend_microsoft.auth

import androidx.annotation.RawRes

/**
 * Interface definition for providing backend-specific configuration sourced from the application module.
 * This helps decouple backend implementations from direct knowledge of app-specific resources like R.raw IDs.
 */
interface AuthConfigProvider {
    /**
     * @return The Android raw resource ID for the MSAL configuration JSON file.
     */
    @RawRes
    fun getMsalConfigResId(): Int
}