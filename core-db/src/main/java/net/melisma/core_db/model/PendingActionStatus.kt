package net.melisma.core_db.model

enum class PendingActionStatus {
    PENDING,    // Action is waiting to be processed
    // PROCESSING, // Decided against this state to avoid stuck actions; worker processes PENDING/RETRY
    RETRY,      // Action failed and is scheduled for a retry
    FAILED,     // Action has failed all retry attempts
    // SUCCESS actions will be deleted from the queue
} 