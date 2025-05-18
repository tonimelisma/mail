package net.melisma.backend_microsoft.common

enum class PersistenceErrorType {
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED,
    STORAGE_FAILED, // e.g., AccountManager.addAccountExplicitly failed
    ACCOUNT_NOT_FOUND,
    MISSING_DATA, // e.g., essential field missing from AccountManager
    OPERATION_FAILED, // General failure for remove/clear
    INVALID_ARGUMENT, // e.g. null account ID
    UNKNOWN_ERROR
} 