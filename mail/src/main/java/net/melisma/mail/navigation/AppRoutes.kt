package net.melisma.mail.navigation

/**
 * Defines the navigation routes and argument keys used throughout the application.
 */
object AppRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"

    // Message Detail Screen
    const val MESSAGE_DETAIL_ROUTE = "message_detail" // Base route name
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_MESSAGE_ID = "messageId"

    /**
     * The full route for navigating to the message detail screen, including argument placeholders.
     * e.g., "message_detail/{accountId}/{messageId}"
     */
    const val MESSAGE_DETAIL = "$MESSAGE_DETAIL_ROUTE/{$ARG_ACCOUNT_ID}/{$ARG_MESSAGE_ID}"

    // Thread Detail Screen
    const val THREAD_DETAIL_ROUTE = "thread_detail"
    const val ARG_THREAD_ID = "threadId" // Argument name for thread ID
    const val THREAD_DETAIL = "$THREAD_DETAIL_ROUTE/{$ARG_ACCOUNT_ID}/{$ARG_THREAD_ID}"

    /**
     * Helper function to construct the navigation path for the message detail screen
     * with actual argument values.
     *
     * @param accountId The ID of the account for the message.
     * @param messageId The ID of the message.
     * @return The complete navigation path string.
     */
    fun messageDetailPath(accountId: String, messageId: String): String {
        return "$MESSAGE_DETAIL_ROUTE/$accountId/$messageId"
    }

    /**
     * Helper function to construct the navigation path for the thread detail screen
     * with actual argument values.
     *
     * @param accountId The ID of the account for the thread.
     * @param threadId The ID of the thread.
     * @return The complete navigation path string.
     */
    fun threadDetailPath(accountId: String, threadId: String): String {
        return "$THREAD_DETAIL_ROUTE/$accountId/$threadId"
    }
} 