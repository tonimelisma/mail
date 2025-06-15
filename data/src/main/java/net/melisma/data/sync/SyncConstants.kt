package net.melisma.data.sync.workers

/**
 * Legacy constants previously defined in the ActionUploadWorker.  The actual upload logic has
 * been moved into the SyncController, but these constants are still used to define
 * the types and payloads of pending actions stored in the database.
 */
object SyncConstants {
    const val KEY_ACCOUNT_ID = "ACCOUNT_ID"
    const val KEY_ENTITY_ID = "ENTITY_ID"
    const val KEY_ACTION_TYPE = "ACTION_TYPE"
    const val KEY_ACTION_PAYLOAD = "ACTION_PAYLOAD"

    const val KEY_DRAFT_DETAILS = "draft_json"
    const val KEY_IS_READ = "isRead"
    const val KEY_IS_STARRED = "isStarred"
    const val KEY_OLD_FOLDER_ID = "oldFolderId"
    const val KEY_NEW_FOLDER_ID = "newFolderId"

    const val ACTION_MARK_AS_READ = "mark_as_read"
    const val ACTION_MARK_AS_UNREAD = "MARK_AS_UNREAD"
    const val ACTION_STAR_MESSAGE = "STAR_MESSAGE"
    const val ACTION_DELETE_MESSAGE = "DELETE_MESSAGE"
    const val ACTION_MOVE_MESSAGE = "MOVE_MESSAGE"
    const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"
    const val ACTION_CREATE_DRAFT = "CREATE_DRAFT"
    const val ACTION_UPDATE_DRAFT = "UPDATE_DRAFT"

    const val ACTION_MARK_THREAD_AS_READ = "MARK_THREAD_AS_READ"
    const val ACTION_MARK_THREAD_AS_UNREAD = "MARK_THREAD_AS_UNREAD"
    const val ACTION_DELETE_THREAD = "DELETE_THREAD"
    const val ACTION_MOVE_THREAD = "MOVE_THREAD"
} 