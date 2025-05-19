package net.melisma.backend_google.model

// Define the data class for parsed ID token information
data class ParsedIdTokenInfo(
    val userId: String?,
    val email: String?,
    val displayName: String?,
    val picture: String? = null
) 