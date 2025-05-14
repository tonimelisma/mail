Client-Side Email Threading Implementation PlanThis plan outlines the steps to modify the existing
application to support client-side email threading for both Gmail and Microsoft Outlook accounts.
The strategy is to fetch an initial set of messages, identify unique thread/conversation IDs, fetch
all messages belonging to those threads/conversations, and then group them in the UI.Phase 1: Core
Data Model UpdatesObjective: Update existing models and create new ones to represent threads and
their states.File: core-data/src/main/java/net/melisma/core_data/model/Message.ktAction: Add a
nullable threadId property to the Message data class. This will store Gmail's threadId or Outlook's
conversationId.Change:package net.melisma.core_data.model

data class Message(
val id: String,

+ val threadId: String?, // Stores Gmail threadId or Outlook conversationId
  val receivedDateTime: String,
  val subject: String?,
  val senderName: String?,
  File: core-data/src/main/java/net/melisma/core_data/model/MailThread.kt (Create New File)Action:
  Define the MailThread data class. This will hold a collection of messages belonging to the same
  thread/conversation, along with relevant metadata for display.Code:package
  net.melisma.core_data.model

import java.util.Date // For parsed lastMessageDateTime

/**

* Represents a conversation thread, containing a list of messages and summary information.
*
* @property id The unique identifier of the thread (from Gmail's threadId or Outlook's
  conversationId).
* @property messages A list of [Message] objects belonging to this thread, typically sorted by date.
* @property subject The subject line for the thread, often taken from the latest or first message.
* @property snippet A short preview of the latest message or overall thread content.
* @property lastMessageDateTime The parsed [Date] of the last message in the thread, used for
  sorting threads.
* @property participantsSummary A display string summarizing the participants (e.g., "John, Jane, +2
  more").
* @property unreadMessageCount The number of unread messages within this thread.
* @property totalMessageCount The total number of messages within this thread.
* @property accountId The ID of the [Account] this thread belongs to.
  */
  data class MailThread(
  val id: String,
  val messages: List<Message>,
  val subject: String?,
  val snippet: String?,
  val lastMessageDateTime: Date?,
  val participantsSummary: String?,
  val unreadMessageCount: Int = 0,
  val totalMessageCount: Int = 0,
  val accountId: String
  )
  File: core-data/src/main/java/net/melisma/core_data/model/ThreadDataState.kt (Create New File)
  Action: Define a sealed interface for representing the state of thread data fetching and display,
  similar to MessageDataState.kt.Code:package net.melisma.core_data.model

/**

* Represents the various states for fetching and displaying a list of [MailThread]s.
  */
  sealed interface ThreadDataState {
  /** The initial state before any thread data loading has begun. */
  object Initial : ThreadDataState

  /** Indicates that threads are currently being loaded. */
  object Loading : ThreadDataState

  /** Indicates that threads were successfully loaded. */
  data class Success(val threads: List<MailThread>) : ThreadDataState

  /** Indicates that an error occurred while loading threads. */
  data class Error(val error: String?) : ThreadDataState
  }
  Phase 2: API Service Layer ModificationsObjective: Update the MailApiService interface and its
  implementations (GmailApiHelper, GraphApiHelper) to support fetching all messages for a given
  thread/conversation ID.File:
  core-data/src/main/java/net/melisma/core_data/datasource/MailApiService.ktAction: Add a new method
  getMessagesForThread to the interface.Change:// ... (existing imports and interface
  declaration) ...
  interface MailApiService {
  // ... (existing methods: getMailFolders, getMessagesForFolder, etc.) ...

+ /**
+     * Fetches all messages belonging to a specific thread/conversation.
+     *
+     * @param threadId The ID of the thread (for Gmail) or conversation (for Outlook).
+     * @return Result containing the list of [Message] objects in the thread/conversation or an error.
+     */
+ suspend fun getMessagesForThread(threadId: String): Result<List<Message>>
  }
  File: backend-google/src/main/java/net/melisma/backend_google/model/GmailModels.ktAction: Add the
  GmailThread data class to model the response from Gmail's threads.get API.Code (Add this class)://
  Add within GmailModels.kt

/**

* Model for a Gmail thread resource, typically returned by threads.get API.
  */
  @Serializable
  data class GmailThread(
  @SerialName("id") val id: String,
  @SerialName("snippet") val snippet: String? = null, // Snippet of the latest message in the thread
  @SerialName("historyId") val historyId: String,
  // Messages are often included directly when fetching a thread,
  // ensure your `GmailMessage` model is compatible.
  @SerialName("messages") val messages: List<GmailMessage> = emptyList()
  )
  Verify GmailMessage: Ensure GmailMessage has the threadId field.@Serializable
  data class GmailMessage(
  @SerialName("id") val id: String,
  @SerialName("threadId") val threadId: String, // This should already exist
  // ... other fields ...
  )
  File: backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.ktAction 1: Modify
  mapGmailMessageToMessage to include threadId.Code Change (inside mapGmailMessageToMessage
  function):// ...
  return Message(
  id = gmailMessage.id,

+ threadId = gmailMessage.threadId,
  subject = subject,
  receivedDateTime = formattedDate,
  // ... rest of the mapping
  )
  Action 2: Implement the getMessagesForThread method from MailApiService.Code (Add this new method
  to the class):override suspend fun getMessagesForThread(threadId: String): Result<List<Message>> {
  Log.d(TAG, "getMessagesForThread (Gmail): Fetching messages for threadId: $threadId")
  return try {
  val response: HttpResponse = httpClient.get("$BASE_URL/threads/$threadId") {
  parameter("format", "FULL") // Request full message details for messages within the thread
  }

      if (!response.status.isSuccess()) {
          val errorBody = response.bodyAsText()
          Log.e(TAG, "Error fetching Gmail thread $threadId: ${response.status} - $errorBody")
          val httpException = IOException("Gmail API Error ${response.status.value} fetching thread $threadId: $errorBody")
          return Result.failure(Exception(errorMapper.mapNetworkOrApiException(httpException)))
      }

      val rawJsonResponse = response.bodyAsText()
      Log.d(TAG, "Thread ID: $threadId --- RAW JSON RESPONSE for THREAD.GET (Gmail): $rawJsonResponse")
      val gmailThread = jsonParser.decodeFromString<GmailThread>(rawJsonResponse)

      // Messages from threads.get with format=FULL should be complete.
      // The existing mapGmailMessageToMessage will handle header recursion if needed.
      val messages = gmailThread.messages.mapNotNull { nestedGmailMessage ->
          mapGmailMessageToMessage(nestedGmailMessage)
      }

      Log.d(TAG, "Successfully mapped ${messages.size} messages for Gmail threadId: $threadId")
      Result.success(messages)

  } catch (e: Exception) {
  Log.e(TAG, "Exception in getMessagesForThread for threadId $threadId (Gmail)", e)
  val errorMessageString = errorMapper.mapNetworkOrApiException(e)
  Result.failure(Exception(errorMessageString))
  }
  }
  File: backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.ktAction 1:
  Create a new private helper function mapGraphMessageToMessage. This function will encapsulate the
  logic to convert a GraphMessage (the private data class defined in this file) to your app's
  net.melisma.core_data.model.Message. It must include mapping GraphMessage.conversationId to
  Message.threadId.Code (Add this new private function):private fun mapGraphMessageToMessage(
  graphMessage: GraphMessage): Message {
  val sender = graphMessage.sender // GraphMessage.sender is already GraphRecipient?
  val from = graphMessage.from // Also GraphRecipient?
  val effectiveSender = from ?: sender // Prefer 'from' if available

  // Date: Prefer sentDateTime for sent items, otherwise receivedDateTime
  // Assuming folder context isn't directly available here to check if it's "Sent Items"
  // A more robust way might involve passing folder context or message properties indicating
  direction.
  // For now, prioritize receivedDateTime as it's more consistently available.
  val dateTimeStr = graphMessage.receivedDateTime ?: graphMessage.sentDateTime
  val date = dateTimeStr?.let { parseOutlookDate(it) } ?: Date() // parseOutlookDate needs to be
  robust

  val outputDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
  outputDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

    return Message(
        id = graphMessage.id,
        threadId = graphMessage.conversationId, // Map conversationId to threadId
        receivedDateTime = outputDateFormat.format(date),
        subject = graphMessage.subject,
        senderName = effectiveSender?.emailAddress?.name,
        senderAddress = effectiveSender?.emailAddress?.address,
        bodyPreview = graphMessage.bodyPreview,
        isRead = graphMessage.isRead ?: true // Default to true if null, as per existing GraphMessage model
    )

}

// Ensure parseOutlookDate is robust or create it
private fun parseOutlookDate(dateStr: String): Date? {
// Microsoft Graph typically uses ISO 8601 format.
// Examples: "2014-01-01T00:00:00Z" or with milliseconds "2014-01-01T00:00:00.123Z"
val formatters = listOf(
SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", Locale.US), // With up to 7 millis
SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US), // With 3 millis
SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)          // Without millis
)
for (formatter in formatters) {
formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
try {
return formatter.parse(dateStr.trim())
} catch (e: Exception) { /* Try next format */ }
}
Log.w(TAG, "Could not parse Outlook date string: '$dateStr' with known formats.")
return null
}
Action 2: Modify the existing getMessagesForFolder method to use the new mapGraphMessageToMessage
helper.Code Change (inside getMessagesForFolder):// ...
if (response.status.isSuccess()) {
val graphMessages = response.body<GraphCollection<GraphMessage>>().value

- val messages = graphMessages.mapNotNull { graphMsg ->
-       Message(
-           id = graphMsg.id,
-           receivedDateTime = graphMsg.receivedDateTime ?: "",
-           subject = graphMsg.subject,
-           senderName = graphMsg.sender?.emailAddress?.name,
-           senderAddress = graphMsg.sender?.emailAddress?.address,
-           bodyPreview = graphMsg.bodyPreview,
-           isRead = graphMsg.isRead
-       )
- }

+ val messages = graphMessages.mapNotNull { mapGraphMessageToMessage(it) }
  Log.d(TAG, "Successfully fetched ${messages.size} messages for folder $folderId.")
  Result.success(messages)
  } else { // ...
  Action 3: Implement the getMessagesForThread method from MailApiService.Code (Add this new method
  to the class):override suspend fun getMessagesForThread(threadId: String): Result<
  List<Message>> { // For Outlook, threadId is conversationId
  Log.d(TAG, "getMessagesForThread (Outlook): Fetching messages for conversationId: $threadId")
  return try {
  val response: HttpResponse = httpClient.get("$MS_GRAPH_ROOT_ENDPOINT/me/messages") {
  url {
  // Filter messages by conversationId
  parameters.append("\$filter", "conversationId eq '$threadId'")
  // Select necessary fields for the Message model
  parameters.append("\$select", "
  id,conversationId,receivedDateTime,sentDateTime,subject,bodyPreview,sender,from,toRecipients,isRead")
  parameters.append("\$top", "100") // Sensible limit for messages in a single conversation view
  parameters.append("\$orderby", "receivedDateTime asc") // Order messages chronologically
  }
  accept(Json)
  }

      if (response.status.isSuccess()) {
          val graphMessageCollection = response.body<GraphCollection<GraphMessage>>()
          val messages = graphMessageCollection.value.mapNotNull { mapGraphMessageToMessage(it) }
          Log.d(TAG, "Successfully mapped ${messages.size} messages for Outlook conversationId: $threadId")
          Result.success(messages)
      } else {
          val errorBody = response.bodyAsText()
          Log.e(TAG, "Error fetching messages for conversation $threadId (Outlook): ${response.status} - $errorBody")
          Result.failure(errorMapper.mapHttpError(response.status.value, errorBody))
      }
  } catch (e: Exception) {
  Log.e(TAG, "Exception in getMessagesForThread for ID: $threadId (Outlook)", e)
  Result.failure(errorMapper.mapExceptionToError(e))
  }
  }
  Action 4: Ensure the private GraphMessage data class within GraphApiHelper.kt includes
  conversationId and other necessary fields (like from, sentDateTime).Code (Verify/Update
  GraphMessage private data class):@Serializable
  private data class GraphMessage(
  val id: String,
  val conversationId: String?, // <-- ENSURE THIS IS PRESENT AND NULLABLE
  val receivedDateTime: String? = null,
  val sentDateTime: String? = null, // <-- ADD IF MISSING
  val subject: String? = null,
  val sender: GraphRecipient? = null,
  val from: GraphRecipient? = null, // <-- ADD IF MISSING
  val toRecipients: List<GraphRecipient> = emptyList(), // <-- ADD IF MISSING & NEEDED for
  participants
  val isRead: Boolean = true, // Or Boolean? = false, match your source
  val bodyPreview: String? = null
  )
  Phase 3: Repository Layer (New ThreadRepository)Objective: Create a new repository dedicated to
  fetching and managing thread data.File:
  core-data/src/main/java/net/melisma/core_data/repository/ThreadRepository.kt (Create New File)
  Action: Define the ThreadRepository interface.Code:package net.melisma.core_data.repository

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.ThreadDataState

interface ThreadRepository {
val threadDataState: StateFlow<ThreadDataState>

    /**
     * Sets the target account and folder for which threads should be fetched.
     * Triggers an initial fetch.
     */
    suspend fun setTargetFolderForThreads(
        account: Account?,
        folder: MailFolder?,
        activityForRefresh: Activity? = null // For potential auth needs during refresh
    )

    /**
     * Refreshes the threads for the currently set target folder and account.
     */
    suspend fun refreshThreads(activity: Activity? = null)

}
File: data/src/main/java/net/melisma/data/repository/DefaultThreadRepository.kt (Create New File)
Action: Implement the ThreadRepository interface. This involves fetching initial messages to get
thread IDs, then fetching full threads.Code:package net.melisma.data.repository

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import net.melisma.core_data.datasource.MailApiService
import net.melisma.core_data.di.ApplicationScope
import net.melisma.core_data.di.Dispatcher
import net.melisma.core_data.di.MailDispatchers
import net.melisma.core_data.errors.ErrorMapperService
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailFolder
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.Message
import net.melisma.core_data.model.ThreadDataState
import net.melisma.core_data.repository.ThreadRepository
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class DefaultThreadRepository @Inject constructor(
private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
@Dispatcher(MailDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
@ApplicationScope private val externalScope: CoroutineScope,
private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : ThreadRepository {

    private val TAG = "DefaultThreadRepo"
    private val _threadDataState = MutableStateFlow<ThreadDataState>(ThreadDataState.Initial)
    override val threadDataState: StateFlow<ThreadDataState> = _threadDataState.asStateFlow()

    private var currentTargetAccount: Account? = null
    private var currentTargetFolder: MailFolder? = null
    private var fetchJob: Job? = null

    // Number of initial messages to fetch to discover recent thread IDs
    private val initialMessageFetchCountForThreadDiscovery = 50
    // Max messages to show per thread in the summary list (if API limits, actual count might be less)
    private val maxMessagesPerThreadInList = 3

    override suspend fun setTargetFolderForThreads(
        account: Account?,
        folder: MailFolder?,
        activityForRefresh: Activity?
    ) {
        Log.d(TAG, "setTargetFolderForThreads: Account=${account?.username}, Folder=${folder?.displayName}, Job Active: ${fetchJob?.isActive}")

        if (account?.id == currentTargetAccount?.id && folder?.id == currentTargetFolder?.id && _threadDataState.value !is ThreadDataState.Initial) {
            Log.d(TAG, "setTargetFolderForThreads: Same target and data already loaded/loading. To refresh, call refreshThreads().")
            return
        }
        cancelAndClearJob("New target folder for threads: ${folder?.displayName}")
        currentTargetAccount = account
        currentTargetFolder = folder

        if (account == null || folder == null) {
            _threadDataState.value = ThreadDataState.Initial
            Log.d(TAG, "setTargetFolderForThreads: Cleared target, state set to Initial.")
            return
        }
        _threadDataState.value = ThreadDataState.Loading
        launchThreadFetchJobInternal(account, folder, isRefresh = false, activity = activityForRefresh)
    }

    override suspend fun refreshThreads(activity: Activity?) {
        val account = currentTargetAccount
        val folder = currentTargetFolder
        if (account == null || folder == null) {
            Log.w(TAG, "refreshThreads: No target account or folder set. Skipping.")
            _threadDataState.value = ThreadDataState.Error("Cannot refresh: No folder selected.")
            return
        }
        Log.d(TAG, "refreshThreads called for folder: ${folder.displayName}, Account: ${account.username}")
        // Set to loading only if not already loading to avoid UI flicker if refresh is called rapidly
        if (_threadDataState.value !is ThreadDataState.Loading) {
            _threadDataState.value = ThreadDataState.Loading
        }
        launchThreadFetchJobInternal(account, folder, isRefresh = true, activity = activity)
    }

    private fun launchThreadFetchJobInternal(
        account: Account,
        folder: MailFolder,
        isRefresh: Boolean,
        activity: Activity? // Parameter for future use if auth refresh needs activity
    ) {
        cancelAndClearJob("Launching new thread fetch for ${folder.displayName}. Refresh: $isRefresh")

        val providerType = account.providerType.uppercase()
        val apiService = mailApiServices[providerType]
        val errorMapper = errorMappers[providerType]

        if (apiService == null || errorMapper == null) {
            val errorMsg = "Unsupported account type: $providerType or missing services for thread fetching."
            Log.e(TAG, errorMsg)
            _threadDataState.value = ThreadDataState.Error(errorMsg)
            return
        }

        fetchJob = externalScope.launch(ioDispatcher) {
            Log.i(TAG, "[${folder.displayName}] Thread fetch job started. Refresh: $isRefresh")
            try {
                // Step 1: Fetch initial batch of messages to get their thread IDs
                Log.d(TAG, "[${folder.displayName}] Fetching initial messages to discover thread IDs...")
                val initialMessagesResult = apiService.getMessagesForFolder(
                    folderId = folder.id,
                    maxResults = initialMessageFetchCountForThreadDiscovery
                )
                ensureActive() // Check for cancellation

                if (initialMessagesResult.isFailure) {
                    val error = errorMapper.mapNetworkOrApiException(initialMessagesResult.exceptionOrNull())
                    Log.e(TAG, "[${folder.displayName}] Error fetching initial messages: $error", initialMessagesResult.exceptionOrNull())
                    _threadDataState.value = ThreadDataState.Error(error)
                    return@launch
                }

                val initialMessages = initialMessagesResult.getOrThrow()
                if (initialMessages.isEmpty()) {
                    Log.i(TAG, "[${folder.displayName}] No initial messages found. Folder might be empty.")
                    _threadDataState.value = ThreadDataState.Success(emptyList())
                    return@launch
                }

                val uniqueThreadIds = initialMessages.mapNotNull { it.threadId }.distinct()
                Log.i(TAG, "[${folder.displayName}] Discovered ${uniqueThreadIds.size} unique thread IDs from ${initialMessages.size} initial messages.")

                if (uniqueThreadIds.isEmpty()) {
                    Log.i(TAG, "[${folder.displayName}] No unique thread IDs found in initial messages.")
                    _threadDataState.value = ThreadDataState.Success(emptyList())
                    return@launch
                }

                // Step 2: For each unique threadId, fetch all its messages
                // Using supervisorScope to ensure one failing thread fetch doesn't cancel others.
                val mailThreads = supervisorScope {
                    uniqueThreadIds.map { threadId ->
                        async { // Fetch each thread concurrently
                            ensureActive()
                            Log.d(TAG, "[${folder.displayName}] Fetching full details for thread ID: $threadId")
                            val messagesInThreadResult = apiService.getMessagesForThread(threadId)
                            ensureActive()

                            if (messagesInThreadResult.isSuccess) {
                                val messages = messagesInThreadResult.getOrThrow()
                                if (messages.isNotEmpty()) {
                                    assembleMailThread(threadId, messages, account.id)
                                } else {
                                    Log.w(TAG, "[${folder.displayName}] Thread $threadId returned no messages.")
                                    null // Filter this out later
                                }
                            } else {
                                Log.e(TAG, "[${folder.displayName}] Failed to fetch messages for thread $threadId: ${messagesInThreadResult.exceptionOrNull()?.message}")
                                null // Filter this out later
                            }
                        }
                    }.awaitAll().filterNotNull() // awaitAll and filter out nulls from failed fetches
                }
                ensureActive()

                val sortedThreads = mailThreads.sortedByDescending { it.lastMessageDateTime }
                Log.i(TAG, "[${folder.displayName}] Successfully assembled ${sortedThreads.size} threads.")
                _threadDataState.value = ThreadDataState.Success(sortedThreads)

            } catch (e: CancellationException) {
                Log.w(TAG, "[${folder.displayName}] Thread fetch job was cancelled: ${e.message}")
                // If the job was cancelled while loading, it might be appropriate to revert
                // to Initial or a specific "Cancelled" state if the UI needs to react.
                // For now, if it was loading, it might just stop showing loading.
                if (_threadDataState.value is ThreadDataState.Loading && isActive) {
                     _threadDataState.value = ThreadDataState.Error("Fetch cancelled for ${folder.displayName}")
                }
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                val error = errorMapper.mapNetworkOrApiException(e)
                Log.e(TAG, "[${folder.displayName}] Critical exception during thread fetch: $error", e)
                if (isActive) { // Only update state if the coroutine is still active
                    _threadDataState.value = ThreadDataState.Error(error)
                }
            } finally {
                Log.d(TAG, "[${folder.displayName}] Thread fetch job 'finally' block. Current job hash: ${coroutineContext[Job]?.hashCode()}, stored job hash: ${fetchJob?.hashCode()}")
                // Clear the job reference only if this is the job that's completing
                if (fetchJob == coroutineContext[Job]) {
                    fetchJob = null
                    Log.d(TAG, "[${folder.displayName}] Cleared fetchJob reference.")
                }
            }
        }
    }

    private fun assembleMailThread(threadId: String, messages: List<Message>, accountId: String): MailThread? {
        if (messages.isEmpty()) {
            Log.w(TAG, "Cannot assemble MailThread for $threadId: no messages provided.")
            return null
        }

        // Sort messages by date, most recent last to easily get the latest message
        val sortedMessages = messages.sortedWith(compareBy { parseIsoDate(it.receivedDateTime) })
        val latestMessage = sortedMessages.lastOrNull() ?: messages.first() // Fallback to first if sorting fails or list empty after sort (should not happen)

        val subject = latestMessage.subject?.takeIf { it.isNotBlank() }
            ?: sortedMessages.firstNotNullOfOrNull { it.subject?.takeIf { subj -> subj.isNotBlank() } }
            ?: "(No Subject)"

        val participants = sortedMessages
            .mapNotNull { it.senderName?.trim()?.takeIf { name -> name.isNotEmpty() } ?: it.senderAddress }
            .distinct()
        val participantsSummary = when {
            participants.isEmpty() -> "Unknown Participants"
            participants.size <= 2 -> participants.joinToString(", ")
            else -> "${participants.take(2).joinToString(", ")} & ${participants.size - 2} more"
        }

        val lastMessageParsedDate = parseIsoDate(latestMessage.receivedDateTime)

        return MailThread(
            id = threadId,
            messages = sortedMessages, // Store all messages
            subject = subject,
            snippet = latestMessage.bodyPreview?.take(120), // Slightly longer snippet
            lastMessageDateTime = lastMessageParsedDate,
            participantsSummary = participantsSummary,
            unreadMessageCount = sortedMessages.count { !it.isRead },
            totalMessageCount = sortedMessages.size,
            accountId = accountId
        )
    }

    private fun parseIsoDate(dateString: String?): Date? {
        if (dateString.isNullOrBlank()) return null
        // Try common ISO 8601 formats
        val formatStrings = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'", // With 7 millis
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",     // With 3 millis
            "yyyy-MM-dd'T'HH:mm:ss'Z'"          // Without millis
        )
        for (formatString in formatStrings) {
            try {
                val sdf = SimpleDateFormat(formatString, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateString)
            } catch (e: ParseException) {
                // Continue to next format
            }
        }
        Log.w(TAG, "Failed to parse date string: $dateString with common ISO formats.")
        return null // Or throw, or return a default
    }

    private fun cancelAndClearJob(reason: String) {
        fetchJob?.let {
            if (it.isActive) {
                Log.d(TAG, "Cancelling previous thread fetch job. Reason: $reason. Hash: ${it.hashCode()}")
                it.cancel(CancellationException("Job cancelled: $reason"))
            }
        }
        fetchJob = null
        Log.d(TAG, "cancelAndClearJob: Cleared fetchJob. Reason: $reason")
    }

}
Phase 4: ViewModel LayerFile: app/src/main/java/net/melisma/mail/MainViewModel.ktAction 1: Inject
ThreadRepository.Code Change (in constructor):class MainViewModel @Inject constructor(
@ApplicationContext private val applicationContext: Context,
private val accountRepository: AccountRepository,
private val folderRepository: FolderRepository,

- private val messageRepository: MessageRepository

+ private val messageRepository: MessageRepository,
+ private val threadRepository: ThreadRepository // <-- ADD THIS
  ) : ViewModel() { // ...
  Action 2: Add threadDataState and currentViewMode to MainScreenState.Code Change (in
  MainScreenState data class):@Immutable
  data class MainScreenState(
  // ... existing properties ...
  val messageDataState: MessageDataState = MessageDataState.Initial,
+ val threadDataState: ThreadDataState = ThreadDataState.Initial, // <-- ADD THIS
+ val currentViewMode: ViewMode = ViewMode.THREADS, // <-- ADD THIS, default to threads
  val toastMessage: String? = null,
  val pendingGoogleConsentAccountId: String? = null
  ) {
  // ... existing computed properties ...
+ val isThreadLoading: Boolean
+       get() = threadDataState is ThreadDataState.Loading
+ val threads: List<MailThread>?
+       get() = (threadDataState as? ThreadDataState.Success)?.threads
+ val threadError: String?
+       get() = (threadDataState as? ThreadDataState.Error)?.error

}

+// Define ViewMode enum, can be outside or nested if preferred by style guide
+enum class ViewMode { THREADS, MESSAGES }
Action 3: Observe threadRepository.threadDataState in the init block.Code (add this to init block or
a new observeThreadRepository function called from init):// In init block of MainViewModel
observeThreadRepository() // Call this new function

// New function in MainViewModel
private fun observeThreadRepository() {
Log.d(TAG, "MainViewModel: observeThreadRepository() - Setting up flow")
threadRepository.threadDataState
.onEach { newThreadState ->
// Check if state actually changed to avoid unnecessary recompositions if possible
if (_uiState.value.threadDataState != newThreadState) {
Log.d(TAG, "MainViewModel: ThreadRepo State Changed: ${newThreadState::class.simpleName}")
_uiState.update { it.copy(threadDataState = newThreadState) }
Log.d(TAG, "MainViewModel: UI state updated with new thread state.")
}
}.launchIn(viewModelScope)
Log.d(TAG, "MainViewModel: Finished setting up ThreadRepository observation flow.")
}
Action 4: Modify selectFolder to handle currentViewMode.Code Change (replace existing selectFolder):fun selectFolder(folder: MailFolder, account: Account) {
val accountId = account.id
Log.i(TAG, "Folder selected: '${folder.displayName}' from account ${account.username} (ID: $
accountId), Current ViewMode: ${_uiState.value.currentViewMode}")

    val isSameFolderAndAccount = folder.id == _uiState.value.selectedFolder?.id && accountId == _uiState.value.selectedFolderAccountId

    // If it's the same folder and account, and data for the current view mode is already loaded or loading, do nothing to prevent re-fetch.
    // A dedicated refresh action should be used by the user for that.
    if (isSameFolderAndAccount) {
        if (_uiState.value.currentViewMode == ViewMode.THREADS && _uiState.value.threadDataState !is ThreadDataState.Initial) {
            Log.d(TAG, "Folder ${folder.displayName} already selected for THREADS view and data exists/loading. Skipping full re-fetch.")
            // Optionally, update just the selected folder context if it somehow changed without ID change
             _uiState.update { it.copy(selectedFolder = folder, selectedFolderAccountId = accountId) }
            return
        }
        if (_uiState.value.currentViewMode == ViewMode.MESSAGES && _uiState.value.messageDataState !is MessageDataState.Initial) {
            Log.d(TAG, "Folder ${folder.displayName} already selected for MESSAGES view and data exists/loading. Skipping full re-fetch.")
             _uiState.update { it.copy(selectedFolder = folder, selectedFolderAccountId = accountId) }
            return
        }
    }

    _uiState.update { it.copy(selectedFolder = folder, selectedFolderAccountId = accountId) }

    viewModelScope.launch {
        if (_uiState.value.currentViewMode == ViewMode.THREADS) {
            // Clear message state if switching to threads or selecting a new folder for threads
            if (_uiState.value.messageDataState !is MessageDataState.Initial) {
                messageRepository.setTargetFolder(null, null)
            }
            threadRepository.setTargetFolderForThreads(account, folder)
        } else { // ViewMode.MESSAGES
            // Clear thread state if switching to messages or selecting a new folder for messages
            if (_uiState.value.threadDataState !is ThreadDataState.Initial) {
                threadRepository.setTargetFolderForThreads(null, null, null)
            }
            messageRepository.setTargetFolder(account, folder)
        }
    }

}
Action 5: Add toggleViewMode() function.Code (Add this new function):fun toggleViewMode() {
val newMode = if (_uiState.value.currentViewMode == ViewMode.THREADS) ViewMode.MESSAGES else
ViewMode.THREADS
Log.i(TAG, "Toggling view mode from ${_uiState.value.currentViewMode} to: $newMode")
_uiState.update {
it.copy(
currentViewMode = newMode,
// Reset the data state of the view we are LEAVING, so it reloads if we come back.
// The data for the NEW view will be fetched by selectFolder.
messageDataState = if (newMode == ViewMode.THREADS) MessageDataState.Initial else
it.messageDataState,
threadDataState = if (newMode == ViewMode.MESSAGES) ThreadDataState.Initial else it.threadDataState
)
}
// Re-trigger data fetch for the currently selected folder (if any) with the new view mode
_uiState.value.selectedFolder?.let { folder ->
_uiState.value.accounts.find { it.id == _uiState.value.selectedFolderAccountId }?.let { account ->
Log.d(TAG, "Re-selecting folder ${folder.displayName} for new view mode $newMode")
selectFolder(folder, account) // This will use the updated currentViewMode
}
} ?: Log.d(TAG, "No folder selected, view mode toggled but no data fetch triggered for folder.")
}
Action 6: Rename refreshMessages to refreshCurrentView and update its logic.Code Change:- fun
refreshMessages(activity: Activity?) {

+ fun refreshCurrentView(activity: Activity?) {
  val currentSelectedFolder = _uiState.value.selectedFolder
  val currentSelectedAccountId = _uiState.value.selectedFolderAccountId
  if (currentSelectedFolder == null || currentSelectedAccountId == null) {

-       Log.w(TAG, "Refresh messages called but no folder/account selected.")

+       Log.w(TAG, "Refresh current view called but no folder/account selected.")
        tryEmitToastMessage("Select a folder first.")
        return
  }
  // ... (isOnline check remains the same) ...

- Log.d(TAG, "Requesting message refresh via MessageRepository for folder: $
  {currentSelectedFolder.id}")
- viewModelScope.launch { messageRepository.refreshMessages(activity) }

+ if (_uiState.value.currentViewMode == ViewMode.THREADS) {
+       Log.d(TAG, "Requesting thread refresh via ThreadRepository for folder: ${currentSelectedFolder.displayName}")
+       viewModelScope.launch { threadRepository.refreshThreads(activity) }
+ } else {
+       Log.d(TAG, "Requesting message refresh via MessageRepository for folder: ${currentSelectedFolder.displayName}")
+       viewModelScope.launch { messageRepository.refreshMessages(activity) }
+ }
  }
  Phase 5: UI Layer ModificationsObjective: Update existing UI and create new Composables to display
  threads.File: app/src/main/java/net/melisma/mail/MainActivity.kt (within MainApp Composable)
  Action:Modify the PullToRefreshBox and its content to switch between MessageListContent and a new
  ThreadListContent based on state.currentViewMode.Update onRefresh lambda to call
  viewModel.refreshCurrentView(activity).(Optional but recommended) Add a UI element (e.g., an icon
  button in MailTopAppBar) to call viewModel.toggleViewMode().Conceptual Code Snippet (inside
  Scaffold's content lambda Box(modifier = Modifier.padding(innerPadding)) { ... }):// Inside
  MainApp Composable, within the Scaffold's content
  val currentActivity = LocalContext.current as? Activity // For refresh calls

PullToRefreshBox(
isRefreshing = if (state.currentViewMode == MainViewModel.ViewMode.THREADS) {
state.threadDataState is ThreadDataState.Loading && state.threads.isNullOrEmpty() // Show pull
refresh only if list is empty and loading
} else {
state.isMessageLoading && state.messages.isNullOrEmpty() // Original logic for messages
},
onRefresh = { viewModel.refreshCurrentView(currentActivity) },
modifier = Modifier.fillMaxSize()
) {
when (state.currentViewMode) {
MainViewModel.ViewMode.THREADS -> {
ThreadListContent( // New Composable
threadDataState = state.threadDataState,
accountContext = state.accounts.find { it.id == state.selectedFolderAccountId },
// isRefreshing is handled by PullToRefreshBox, but can be passed if needed internally
onThreadClick = { threadId ->
// TODO: Implement navigation to a thread detail view or expand in place
showToast(context, "Thread ID: $threadId clicked (Detail view TBD)")
}
)
}
MainViewModel.ViewMode.MESSAGES -> {
MessageListContent(
messageDataState = state.messageDataState,
accountContext = state.accounts.find { it.id == state.selectedFolderAccountId },
isRefreshing = state.isMessageLoading, // Pass this for internal indicators if any
onRefresh = { viewModel.refreshCurrentView(currentActivity) }, // This onRefresh is for MessageListContent's own refresh if it had one separate from PullToRefreshBox
onMessageClick = { messageId ->
showToast(context, "Message ID: $messageId clicked (Detail view TBD)")
}
)
}
}
}
For MailTopAppBar.kt: Consider adding an actions parameter to MailTopAppBar and then in
MainActivity.kt when calling MailTopAppBar, provide an IconButton that toggles the view mode.//
Example in MainActivity.kt's MainApp, when calling MailTopAppBar:
MailTopAppBar(
title = title,
onNavigationClick = { scope.launch { drawerState.open() } },
actions = {
IconButton(onClick = { viewModel.toggleViewMode() }) {
Icon(
imageVector = if (state.currentViewMode == MainViewModel.ViewMode.THREADS) Icons.Filled.List else
Icons.Filled.Forum, // Example icons
contentDescription = if (state.currentViewMode == MainViewModel.ViewMode.THREADS) "Switch to Message
View" else "Switch to Thread View"
)
}
}
)
(This requires modifying MailTopAppBar.kt to accept an actions: @Composable RowScope.() -> Unit = {}
parameter).File: app/src/main/java/net/melisma/mail/ui/ThreadListContent.kt (Create New File)Action:
Create this Composable to display a list of MailThread objects or relevant status messages.Code:
package net.melisma.mail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum // Example icon for no threads
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator // For explicit loading state if
PullToRefreshBox isn't enough
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.MailThread
import net.melisma.core_data.model.ThreadDataState
import net.melisma.mail.R // Assuming R is your app's resource class

@Composable
fun ThreadListContent(
threadDataState: ThreadDataState,
accountContext: Account?,
onThreadClick: (String) -> Unit
// isRefreshing and onRefresh are handled by the parent PullToRefreshBox
) {
Column(modifier = Modifier.fillMaxSize()) {
accountContext?.let {
AccountContextHeader(account = it) // Re-use existing if suitable
HorizontalDivider()
}

        when (threadDataState) {
            is ThreadDataState.Initial -> {
                FullScreenMessage( // Re-use existing if suitable
                    icon = null,
                    iconContentDescription = null,
                    title = stringResource(R.string.select_a_folder_to_see_threads) // New string resource
                )
            }
            is ThreadDataState.Loading -> {
                 // PullToRefreshBox handles the visual indicator.
                 // If you want a centered spinner when content is empty:
                 // if (threadDataState.threads.isNullOrEmpty()) { // Check if previous success state had items
                 //    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                 //        CircularProgressIndicator()
                 //    }
                 // } else {
                 //    ShowStaleContentWhileLoading(threads = threadDataState.threads) // Display old data
                 // }
                Spacer(Modifier.fillMaxSize()) // Simplest: let PullToRefreshBox show indicator
            }
            is ThreadDataState.Error -> {
                FullScreenMessage(
                    icon = Icons.Filled.CloudOff,
                    iconContentDescription = stringResource(R.string.cd_error_loading_threads), // New string
                    title = stringResource(R.string.error_loading_threads_title), // New string
                    message = threadDataState.error
                )
            }
            is ThreadDataState.Success -> {
                if (threadDataState.threads.isEmpty()) {
                    FullScreenMessage(
                        icon = Icons.Filled.Forum, // Example icon
                        iconContentDescription = stringResource(R.string.cd_no_threads), // New string
                        title = stringResource(R.string.no_threads_title), // New string
                        message = stringResource(R.string.folder_contains_no_threads) // New string
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(threadDataState.threads, key = { it.id }) { mailThread ->
                            ThreadListItem(mailThread = mailThread, onClick = { onThreadClick(mailThread.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

}
Note: You will need to add new string resources like R.string.select_a_folder_to_see_threads,
R.string.cd_error_loading_threads, etc.File:
app/src/main/java/net/melisma/mail/ui/ThreadListItem.kt (Create New File)Action: Create a Composable
for displaying a single thread summary.Code:package net.melisma.mail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.melisma.core_data.model.MailThread
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun ThreadListItem(mailThread: MailThread, onClick: () -> Unit) {
val lastMessageDateFormatted = mailThread.lastMessageDateTime?.let { date ->
// Using the existing formatMessageDate logic if it's adaptable,
// or a simplified version for thread list.
// For simplicity, let's use a basic formatter here.
// Ensure your formatMessageDate in Util.kt can handle java.util.Date or adapt.
// This is a placeholder, ideally reuse or adapt Util.formatMessageDate
val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
sdf.timeZone = TimeZone.getDefault() // Consider UTC or local as appropriate
sdf.format(date)
} ?: "Unknown Date"

    val fontWeight = if (mailThread.unreadMessageCount > 0) FontWeight.Bold else FontWeight.Normal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mailThread.participantsSummary ?: "Unknown Participants",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = fontWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false) // Prevent taking too much space
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = lastMessageDateFormatted,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = fontWeight,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = mailThread.subject ?: "(No Subject)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = mailThread.snippet ?: "",
            style = MaterialTheme.typography.bodySmall, // Slightly smaller for snippet
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (mailThread.totalMessageCount > 1) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "(${mailThread.totalMessageCount} messages${if (mailThread.unreadMessageCount > 0) ", ${mailThread.unreadMessageCount} unread" else ""})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

}
Phase 6: Dependency InjectionFile: app/src/main/java/net/melisma/mail/di/RepositoryModule.kt (or
DataModule.kt in the :data module, based on your project's Hilt setup).Action: Add a Hilt binding
for ThreadRepository to DefaultThreadRepository.Code Change:package net.melisma.mail.di // Or
net.melisma.data.di if module is in :data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.melisma.core_data.repository.AccountRepository
import net.melisma.core_data.repository.FolderRepository
import net.melisma.core_data.repository.MessageRepository
+import net.melisma.core_data.repository.ThreadRepository
import net.melisma.data.repository.DefaultAccountRepository
import net.melisma.data.repository.DefaultFolderRepository
import net.melisma.data.repository.DefaultMessageRepository
+import net.melisma.data.repository.DefaultThreadRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: DefaultAccountRepository): AccountRepository

    @Binds
    @Singleton
    abstract fun bindFolderRepository(impl: DefaultFolderRepository): FolderRepository

    @Binds
    @Singleton
    abstract fun bindMessageRepository(impl: DefaultMessageRepository): MessageRepository

+ @Binds
+ @Singleton
+ abstract fun bindThreadRepository(impl: DefaultThreadRepository): ThreadRepository
  }
  This comprehensive plan, based on the files you've provided, should allow a junior developer to
  implement the client-side threading feature. Key areas of attention will be the new logic in
  DefaultThreadRepository, ensuring the API helpers correctly fetch and map thread/conversation
  data, and building out the new UI components for displaying threads. Thorough testing with both
  Gmail and Outlook accounts will be essential.
