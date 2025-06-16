# Changelog

## [Unreleased] - 2024-07-25

### Added

-   **EPIC 3: Composing & Sending Mail (In Progress)**
    -   **Data Layer:**
        -   `core-data/model/DraftType.kt`: Created new `enum class DraftType` with values `NEW`, `REPLY`, `REPLY_ALL`, `FORWARD` to handle different composition actions.
        -   `core-data/preferences/UserPreferencesRepository.kt`: Added support for a user-defined email signature. This involved updating the `UserPreferences` data class, the repository interface, and the `DefaultUserPreferencesRepository` implementation with a `signature` preference.
    -   **Navigation:**
        -   `mail/navigation/AppRoutes.kt`: Defined a new route `compose` with arguments for `action`, `accountId`, and an optional `messageId` to handle different entry points to the compose screen.
        -   `mail/navigation/AppNavigation.kt`: Added a new `composable` to the navigation graph for the `ComposeScreen`.
    -   **UI & ViewModel Layer:**
        -   `mail/ui/compose/ComposeScreen.kt` & `mail/ui/compose/ComposeViewModel.kt`: Created the foundational files for the new compose screen and its corresponding ViewModel. (Note: Implementation is incomplete and was part of the build break).
        -   `mail/MainViewModel.kt`: Added `updateSignature` function to handle user settings changes and exposed the `signature` in the `MainScreenState`.
        -   `mail/ui/MainAppScreen.kt`: Added a `FloatingActionButton` (FAB) to initiate a new email composition.
        -   `mail/ui/messagedetail/MessageDetailScreen.kt`: Added "Reply" and "Forward" buttons to the `BottomAppBar` to allow users to compose replies/forwards from an existing message.
        -   `mail/ui/settings/SettingsScreen.kt`: Implemented a new "Composing" section with an `OutlinedTextField` for users to view and update their email signature.
        -   `mail/res/values/strings.xml`: Added new string resources for the settings screen (`settings_section_composing`, `settings_signature_label`, etc.) and a content description for the compose FAB.

### Changed

-   **Refactored Data & Domain Layers for Message Composition:**
    -   This was a major, and ultimately problematic, refactoring effort to wire up the backend logic for creating, saving, and sending drafts.
    -   **Initial State & Problem:** The initial implementation attempt failed because of a signature mismatch in `DefaultSendMessageUseCase`. It was calling `messageRepository.sendMessage` with incorrect parameters. This revealed a deeper issue: I had not correctly understood the data flow required for sending a message, specifically how sender information (`name`, `email`) was supplied.
    -   **`MessageDraftMapper.kt`:** This mapper was severely outdated. It was modified to align with the current `MessageEntity` schema. The original mapper was missing many fields (`senderName`, `recipientAddresses`, etc.) and contained obsolete ones. The `toEntity` function was updated to take `senderName` and `senderAddress` as parameters, which became a key dependency for the repositories.
    -   **`MessageRepository.kt` (Interface):** The function signatures for `createDraftMessage`, `updateDraftMessage`, and `sendMessage` were changed multiple times. The final state requires a full `Account` object to be passed in, rather than just an `accountId`. This provides the repository implementations with the necessary `displayName` and `emailAddress` for the sender. I also added a `getMessageById` function to support fetching the original message for replies/forwards.
    -   **`DefaultMessageRepository.kt` (Implementation):** This file saw the most significant churn.
        -   `createDraftMessage`, `updateDraftMessage`, and `sendMessage` were all updated to align with the new interface signatures.
        -   The logic was changed to use the passed-in `Account` object to populate `senderName` and `senderAddress` when calling the `draft.toEntity` mapper.
        -   Calls to `Json.encodeToString` were fixed to explicitly include the `MessageDraft.serializer()`.
        -   Corrected the DAO function call from a non-existent `upsertMessage` to `insertOrUpdateMessages(listOf(entity))`.
        -   Fixed the key for the `PendingAction` payload from `KEY_DRAFT_MESSAGE` to the correct `KEY_DRAFT_DETAILS` from `SyncConstants`.
    -   **Domain Use Cases:**
        -   `CreateDraftUseCase`, `DefaultCreateDraftUseCase`, `SaveDraftUseCase`, `DefaultSaveDraftUseCase`, `DefaultSendMessageUseCase`: All these use cases were updated to pass the full `Account` object down to the `MessageRepository`, conforming to the new interface. Previously, they only passed the `accountId`.

### Fixed

-   **Build Failures (Data/Domain Layers):** The above refactoring fixed a cascade of build errors that originated in the domain layer and propagated through the data layer and mappers. The project's `domain` and `data` modules now compile successfully.

### Technical Debt & Challenges

-   **Lack of Upfront Analysis:** The primary source of all subsequent problems was a failure to read and understand the existing API contracts and data models before starting implementation. My initial assumptions about how message sending worked were wrong, leading to a cascade of reactive, error-prone fixes.
-   **Broken UI Layer:** The `mail` module is currently **not compiling**. My attempts to fix UI-related errors in `MainAppScreen.kt` were unsuccessful and, due to tooling issues or imprecise edits, left the file in a broken state with many unresolved references. The screen needs to be carefully rewritten against the `MainViewModel`'s state.
-   **Inefficient Development Cycle:** The "fix-and-rebuild" approach without a clear understanding of the root cause was highly inefficient and introduced further errors.
-   **Tooling Issues:** The `edit_file` tool struggled with large, multi-part changes in the `MainAppScreen.kt` Compose file, failing to apply diffs correctly and worsening the state of the code. This highlights a need for smaller, more atomic edits when dealing with complex files.
-   **Current State:** The application is in a non-runnable state. While the data and domain layers have been heavily modified and now compile, the UI layer is broken. The immediate next step must be to fix the `mail` module.

### Fixed (subsequent get-well pass)

– Restored build of `mail` module by re-introducing missing composables and fixing enum mismatches.
– Added enum values `UNIFIED_INBOX` and alias `THREAD` to `MailViewModePreference` to unblock compile; a future refactor will clean this redundancy.
– Implemented basic `MessageListScreen` / `UnifiedInboxScreen` wrappers to feed Paging data.
– Patched `MainAppScreen` to use new enum set, correct refresh logic, and show thread placeholders.
– Removed duplicate stub `ComposeScreen` from `AppNavigation`.
– Enhanced `ComposeViewModel` to support Reply / Reply-All / Forward pre-population and safe signature insertion.

### Build

Project now compiles with `./gradlew assembleDebug`.

## [Unreleased] - 2024-07-26

### Added

-   **EPIC 3 Completed** – Compose & Send pipeline finished.
    -   `mail/ui/compose/ComposeScreen.kt`: Attachment picker, AssistChip row, attach/remove UI.
    -   `mail/ui/compose/ComposeViewModel.kt`: Attachment metadata extraction, auto-save draft debounce, Create/Save use-cases wiring.
    -   Settings signature management wired through to Compose.

### Changed

-   `DefaultMessageRepository` already supported attachments; validated send flow.
-   `BACKLOG.md` Epic 3 requirement statuses flipped to 🟢.
-   `ARCHITECTURE.md` updated with new Compose flow diagram & UI notes.

### Fixed

-   Build errors regarding missing AttachFile icon by swapping to generic Add icon.

### Notes

-   Gmail helper currently encodes attachments inline; warn for >5 MB – future improvement.
-   Next increment will focus on Outbox visual cues and advanced attachment preview. 