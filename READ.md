# READ.md - Plan for Message Detail Implementation

## 1. Objectives

* Implement the "Read Message Details" feature, allowing users to navigate from a message list item
  to a dedicated screen showing the full content of a single email.
* Establish a scalable UI architecture using Jetpack Navigation Compose and Material 3 components.
* Ensure the domain and data layers correctly fetch and provide full message content (including
  body, subject, sender, date).
* This increment focuses *only* on viewing message details. Mark read/unread and other actions will
  be deferred.
* No new tests will be written in this increment. Existing failing tests due to these changes will
  be commented out.

## 2. Current State Assessment (Summary)

* **UI (`:app`):** Lacks `MessageDetailScreen`, `MessageDetailViewModel`, and Jetpack Navigation for
  message details. `MainActivity` uses a boolean toggle for `SettingsScreen`. `MainViewModel` is
  monolithic.
* **Domain (`:domain`):** `GetMessageDetailsUseCase` exists and is correctly defined.
* **Data Repository (`:data`):** `DefaultMessageRepository.getMessageDetails` is correctly
  implemented, calling the appropriate `MailApiService`.
* **Core Data (`:core-data`):**
    * `MessageRepository` and `MailApiService` interfaces are correctly defined for
      `getMessageDetails`.
    * **Major Gap:** The `Message` data class is missing a field for the full message body (e.g.,
      `val body: String?`).
* **Backends (`:backend-google`, `:backend-microsoft`):**
    * `GmailApiHelper.getMessageDetails` (via `internalFetchMessageDetails` and
      `fetchRawGmailMessage`) requests the "FULL" message format from Gmail, which includes the
      body. However, `mapGmailMessageToMessage` **does not extract or map this full body** into the
      `Message` object; it only uses the snippet for `bodyPreview`. The existing Gmail models (
      `GmailMessage`, `MessagePayload`, `MessagePart`, `MessagePartBody` in `GmailModels.kt`) *do*
      seem to have the necessary fields (`data` in `MessagePartBody`, `mimeType` in `MessagePart`)
      to support this.
    * `GraphApiHelper.getMessageDetails` requests the `body` field from Microsoft Graph. However,
      the `GraphMessage` model in `GraphApiHelper.kt` **lacks a field to deserialize the
      structured `body` object** (which usually contains `content` and `contentType`) from the API.
      A `TODO` also indicates that `mapGraphMessageToMessage` and the `Message` model need
      enhancement.

## 3. Detailed Plan

### Phase 1: Enhance Data Model and Backend Mappers

1. **Modify `Message` data class (`:core-data`):**
    * Open `core-data/src/main/java/net/melisma/core_data/model/Message.kt`.
    * Add a new nullable field `val body: String? = null` to the `Message` data class. (This will
      store the HTML body if available, otherwise plain text).

2. **Update `GmailApiHelper.kt` (`:backend-google`):**
    * Open `backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt`.
    * Locate the `mapGmailMessageToMessage` function.
    * **Modify this function to extract the actual message body:**
        * Implement a recursive helper function (e.g.,
          `private fun findBodyContent(parts: List<MessagePart>?): String?`) that iterates through
          `gmailMessage.payload.parts`.
        * This helper should prioritize finding a `MessagePart` with `mimeType == "text/html"`. If
          found, decode its `body.data` (Base64) and return it.
        * If no HTML part is found, it should look for a `MessagePart` with
          `mimeType == "text/plain"`. If found, decode its `body.data` and return that.
        * The search should be recursive for nested parts.
        * If `gmailMessage.payload.parts` is null/empty, check `gmailMessage.payload.body.data`
          directly if `gmailMessage.payload.mimeType` is "text/html" or "text/plain".
        * Use `android.util.Base64.decode(encodedString, android.util.Base64.URL_SAFE)` for
          decoding.
    * Populate the new `body` field in the returned `Message` object with the decoded content.

3. **Update `GraphApiHelper.kt` (`:backend-microsoft`):**
    * Open `backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt`.
    * **Define `GraphItemBody` data class:** Add a new serializable data class (e.g., as a private
      inner class):
      ```kotlin
      @Serializable
      private data class GraphItemBody(
          val contentType: String? = null, // "text" or "html"
          val content: String? = null
      )
      ```
    * **Modify `GraphMessage` data class:** Add `val body: GraphItemBody? = null` to it.
    * **Update `getMessageDetails` function:** Ensure the `$select` parameter in the API call
      includes `body` (already present).
    * **Locate and modify `mapGraphMessageToMessage` function:**
        * Access `graphMessage.body.content`.
        * Populate the new `body` field in the returned `Message` object with
          `graphMessage.body.content`.
        * Address the existing `TODO` comment.

### Phase 2: Implement UI Layer for Message Detail

1. **Create `MessageDetailUIState.kt` (`:app` module):**
    * Create `app/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailUIState.kt`.
    * Define `sealed interface MessageDetailUIState` with `Loading`, `Error(val message: String)`,
      and `Success(val message: net.melisma.core_data.model.Message)`.

2. **Create `MessageDetailViewModel.kt` (`:app` module):**
    * Create `app/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailViewModel.kt`.
    * `@HiltViewModel`, inject `GetMessageDetailsUseCase`, `SavedStateHandle`.
    * Retrieve `accountId: String` and `messageId: String` from `SavedStateHandle` arguments (these
      will be named `ARG_ACCOUNT_ID`, `ARG_MESSAGE_ID`).
    * Expose `StateFlow<MessageDetailUIState>`.
    * In `init`, if IDs are present, call use case and update state.

3. **Create `MessageDetailScreen.kt` (`:app` module):**
    * Create `app/src/main/java/net/melisma/mail/ui/messagedetail/MessageDetailScreen.kt`.
    * Composable
      `MessageDetailScreen(navController: NavHostController, viewModel: MessageDetailViewModel = hiltViewModel())`.
    * Observe `MessageDetailUIState`.
    * **UI (Material 3 `Scaffold`):**
        * **Top App Bar:** `TopAppBar` with back navigation icon (`navController.navigateUp()`) and
          message subject as title. Placeholder overflow menu.
        * **Content Area:**
            * `Loading` -> `CircularProgressIndicator`.
            * `Error` -> `Text(error)`.
            * `Success` -> `Column` or `LazyColumn` with:
                * `Text("From: ${message.senderName ?: message.senderAddress}")`
                * `Text("Subject: ${message.subject}")`
                * Formatted Date:
                  ```kotlin
                  val formatter = remember { java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM) }
                  val parsedDate = remember(message.receivedDateTime) {
                      try { java.time.OffsetDateTime.parse(message.receivedDateTime) } catch (e: Exception) { null }
                  }
                  Text(text = "Received: ${parsedDate?.format(formatter) ?: message.receivedDateTime}")
                  ```
                * Message Body (`message.body`):
                    * Use `AndroidView` with a `WebView` to render HTML:
                      ```kotlin
                      val htmlBody = message.body ?: ""
                      AndroidView(
                          factory = { context ->
                              WebView(context).apply {
                                  settings.javaScriptEnabled = false
                                  settings.loadWithOverviewMode = true
                                  settings.useWideViewPort = true
                                  settings.defaultTextEncodingName = "utf-8"
                                  // Optional: For dark theme investigation later if needed
                                  // if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                                  //    WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                                  // }
                              }
                          },
                          update = { webView ->
                              webView.loadDataWithBaseURL(null, htmlBody, "text/html", "utf-8", null)
                          }
                      )
                      ```

### Phase 3: Setup Navigation

1. **Add/Verify Navigation Dependencies (`app/build.gradle.kts`):**
    * `implementation("androidx.navigation:navigation-compose:...")`

2. **Define Navigation Routes and Arguments (`:app` module, e.g., `navigation/AppRoutes.kt`):**
    * Create `app/src/main/java/net/melisma/mail/navigation/AppRoutes.kt`.
    *   ```kotlin
        object AppRoutes {
            const val HOME = "home"
            const val SETTINGS = "settings"
            const val MESSAGE_DETAIL_ROUTE = "message_detail" // Base route
            const val ARG_ACCOUNT_ID = "accountId"
            const val ARG_MESSAGE_ID = "messageId"
            const val MESSAGE_DETAIL = "$MESSAGE_DETAIL_ROUTE/{$ARG_ACCOUNT_ID}/{$ARG_MESSAGE_ID}"

            fun messageDetailPath(accountId: String, messageId: String) =
                "$MESSAGE_DETAIL_ROUTE/$accountId/$messageId"
        }
        ```

3. **Create `AppNavigation.kt` (`:app` module):**
    * Create `app/src/main/java/net/melisma/mail/navigation/AppNavigation.kt`.
    * Composable
      `MailAppNavigationGraph(navController: NavHostController, mainViewModel: MainViewModel)`.
    * Use `NavHost(navController = navController, startDestination = AppRoutes.HOME)`.
    * Destinations:
        * `composable(AppRoutes.HOME) { MainAppScreen(navController, mainViewModel) }` (This will be
          the refactored `MainApp`).
        *
        `composable(AppRoutes.SETTINGS) { SettingsScreen(viewModel = mainViewModel, activity = LocalContext.current as Activity, onNavigateUp = { navController.navigateUp() }) }`
        *
        `composable(route = AppRoutes.MESSAGE_DETAIL, arguments = listOf(navArgument(AppRoutes.ARG_ACCOUNT_ID) { type = NavType.StringType }, navArgument(AppRoutes.ARG_MESSAGE_ID) { type = NavType.StringType })) { MessageDetailScreen(navController) }`

4. **Create `MainAppScreen.kt` (`:app` module):**
    * Create `app/src/main/java/net/melisma/mail/ui/MainAppScreen.kt`.
    * This Composable will contain the current content of `MainApp` from `MainActivity.kt` (
      Scaffold, Drawer, TopAppBar, MessageList/ThreadList).
    * It will take `navController: NavHostController` and `mainViewModel: MainViewModel` as
      parameters.

5. **Update `MainActivity.kt` (`:app` module):**
    * In `setContent`, `val navController = rememberNavController()`.
    * Call `MailAppNavigationGraph(navController = navController, mainViewModel = viewModel)` (where
      `viewModel` is the existing `MainViewModel` instance).
    * Remove old `showSettings` boolean logic and related `MainApp`/`SettingsScreen` conditional
      rendering.

6. **Update Message List Interaction (`:app` module, e.g., `MessageListContent.kt`
   or `MessageListItem.kt`):**
    * Pass `navController` and `selectedFolderAccountId: String?` down to where message items are
      handled.
    * On message item click:
      `navController.navigate(AppRoutes.messageDetailPath(accountId = selectedFolderAccountId!!, messageId = message.id))`.
      Ensure `selectedFolderAccountId` is not null.

7. **Update Drawer and Settings Navigation:**
    * `MailDrawerContent.kt`: on "Settings" click -> `navController.navigate(AppRoutes.SETTINGS)`.
    * `SettingsScreen.kt`: `onNavigateUp` should already call `navController.navigateUp()`
      effectively via the NavGraph setup.

## 4. Soft Spots & Future Research / Considerations

* **`Message.body` Content Type Simplification:** Storing only one string (HTML or plain text) is a
  simplification. Future improvement: separate fields or a sealed class for `body`.
* **Gmail Body Extraction Robustness:** The recursive helper for Gmail needs thorough testing with
  various email structures (multipart, nested, different MIME types).
* **WebView HTML Rendering & Dark Theme:** Complex HTML might have rendering quirks. Dark theme for
  WebView content (`WebViewFeature.FORCE_DARK`) is deferred but noted for future investigation if
  default rendering is poor.
* **`MainViewModel` in `MailAppNavigationGraph`:** Passing the large `MainViewModel` is a temporary
  measure for this increment. Future refactoring should aim for more focused ViewModels per screen
  or per navigation graph.
* **Error Handling in UI:** While states are defined, ensure user-facing error messages in
  `MessageDetailScreen` are clear. Retry mechanisms are out of scope for now.
* **Performance of `MessageDetailScreen`:** For very large email bodies, `WebView` performance and
  memory usage should be monitored in later testing phases.

## 5. Dependencies and Potential Breakages

* **`:core-data` (`Message.kt`):** Change requires recompilation of all dependent modules. Unit
  tests interacting with `Message` objects may need updates.
* **Backend Mappers (`GmailApiHelper`, `GraphApiHelper`):** Unit tests for these mappers will likely
  need updates to reflect the new `Message.body` field.
* **`:app` module:** Significant changes for navigation and new screens. `MainActivity` is
  overhauled.
* **Build System:** Clean builds will be necessary.

## 6. Implementation Start

Proceeding with Phase 1, Step 1: Modifying `Message` data class. 