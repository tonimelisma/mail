package net.melisma.mail.ui.threaddetail

sealed interface BodyLoadingState {
    /** Initial state before any check or loading has occurred for this specific message in the thread. */
    object Initial : BodyLoadingState

    /** Actively trying to download the message body. */
    object Loading : BodyLoadingState

    /** Message body is successfully loaded and available. */
    data class Loaded(val htmlContent: String) : BodyLoadingState

    /** An error occurred trying to download or process the message body. */
    data class Error(val errorMessage: String) : BodyLoadingState

    /** Message body is not downloaded, will download automatically when on Wi-Fi. */
    object NotLoadedWillDownloadOnWifi : BodyLoadingState

    /** Message body is not downloaded, will download automatically when online (Wi-Fi or mobile). */
    object NotLoadedWillDownloadWhenOnline : BodyLoadingState

    /** Message body is not downloaded, and the device is currently offline. */
    object NotLoadedOffline : BodyLoadingState

    /** 
     * Message body is not downloaded due to user preference (e.g. ON_DEMAND was set and user hasn't explicitly requested it).
     * This state might be less used now that active view implies demand, but kept for completeness or future features.
     */
    object NotLoadedPreferenceAllowsLater : BodyLoadingState
} 