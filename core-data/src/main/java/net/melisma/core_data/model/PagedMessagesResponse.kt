package net.melisma.core_data.model

/**
 * Represents a paged response of messages from a mail API service.
 *
 * @property messages The list of messages for the current page.
 * @property nextPageToken A token that can be used to fetch the next page of messages.
 *                         Null if there are no more pages.
 */
data class PagedMessagesResponse(
    val messages: List<Message>,
    val nextPageToken: String?
) 