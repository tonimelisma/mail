# Melisma Mail - UX and Architecture Implementation Guide

**Version:** 1.0
**Date:** May 24, 2025

## 0. Introduction

This document provides a practical guide for developers to implement the architectural and user
experience changes required to move from the current codebase to the future state defined in
`DESIGN.md`. The goal is to address the findings from the recent code audit, specifically by
implementing a robust navigation system and a clean domain layer.

## I. Screen & Navigation Redesign

The current system of using a single ViewModel and conditional rendering will be replaced by a
scalable architecture using Jetpack Navigation for Compose. This aligns with the mock-ups and
`DESIGN.md`.

### 1. Home Screen (`route: home`)

This is the central screen of the application.

* **Components:**
    * **Top App Bar:** As seen in the mock-up, it will display the current folder name ("Inbox"), a
      navigation icon (hamburger menu), and a search icon.
    * **Navigation Drawer:** The hamburger menu will open a `ModalNavigationDrawer`. As per the
      mock-up, this drawer will contain the list of mail folders for the currently selected
      account (`Inbox`, `Starred`, `Sent`, etc.).
    * **Content Area:** This area will display the list of messages or threads for the selected
      folder. The list items should match the design in the mock-up, showing a sender avatar, sender
      name, subject, and a snippet of the message body.
    * **Floating Action Button (FAB):** A "New mail" FAB will be present for initiating the compose
      flow.
* **ViewModel:** `HomeViewModel` will be responsible for this screen.

### 2. Message Detail Screen (`route: message_detail/{messageId}`)

This screen is for reading a single email.

* **Components:**
    * **Top App Bar:** Will display the folder name ("Inbox"), a back arrow to navigate to the
      `home` screen, and a "more" (three-dot) menu for actions like delete/move.
    * **Content Area:** Displays the email subject prominently, followed by the sender's avatar,
      name, and timestamp. The main body of the email follows below.
* **ViewModel:** `MessageDetailViewModel` will manage the state for this screen.
* **Requirement:** Fulfills "View Single Message" (Req 1.2) from the backlog.

### 3. Settings Screen (`route: settings`)

This screen will be a single destination for all settings and account management.

* **Components:**
    * **Top App Bar:** A simple app bar with a back arrow and the title "Settings".
    * **Content Area:** This area will contain general app settings (e.g., theme toggles) and a
      dedicated section for account management. The account section will list all signed-in accounts
      and provide options to "Add account" or "Remove" an existing one.
* **ViewModel:** `SettingsViewModel`.
* **Requirement:** Consolidates all settings and account management tasks into one screen as
  requested. Fulfills "Basic Settings" (Req 7.1).

## II. Implementing the Domain Layer (Use Cases)

This is the most critical architectural change, addressing audit findings F-ARCH-02 and F-CODE-01.
The goal is to move all business logic out of ViewModels and into focused, reusable Use Case
classes.

### 1. The "Why"

Currently, `MainViewModel.kt` is responsible for orchestrating repositories, handling complex
logic (like default folder selection), and managing state for multiple unrelated features. This
makes it difficult to test, maintain, and reason about. By creating a domain layer, ViewModels
become simple delegates, and business logic becomes testable in isolation.

### 2. Implementation Steps

1. **Create a `:domain` Module:** If it doesn't exist, create a new Android library module named
   `:domain`. This module will only contain Kotlin/Java code and will have no Android framework
   dependencies. It will depend on `:core-data` (for repository interfaces and models).
2. **Define the Use Case Structure:** A use case is a simple class with one public function,
   typically using the `invoke` operator to be callable like a function.

   **Example: `SignOutUseCase`**

    * **Before (Logic in `MainViewModel.kt`):**
        ```kotlin
        // In MainViewModel.kt
        fun signOutAndRemoveAccount(account: Account) {
            _uiState.update { it.copy(isLoadingAccountAction = true) }
            viewModelScope.launch {
                // Complex logic calling defaultAccountRepository.signOut()
                // and collecting the result...
            }
        }
        ```

    * **After (New Use Case Class):**
        ```kotlin
        // In :domain module's new file: SignOutUseCase.kt
        class SignOutUseCase @Inject constructor(
            private val accountRepository: AccountRepository
        ) {
            suspend operator fun invoke(account: Account): Flow<GenericSignOutResult> {
                return accountRepository.signOut(account)
            }
        }
        ```

    * **Simplified ViewModel:**
        ```kotlin
        // In the new AccountManagementViewModel.kt
        fun signOut(account: Account) {
            viewModelScope.launch {
                signOutUseCase(account).collect { result ->
                    // update UI state based on the simple result
                }
            }
        }
        ```

### 3. List of Use Cases to Create

The developer should create the following use cases, breaking down the logic from the existing
`MainViewModel` and implementing new features from the backlog.

#### Account & Authentication

* **`ObserveAuthStateUseCase`**: Provides the `OverallApplicationAuthState`. (Depends on:
  `AccountRepository`)
* **`GetAccountsUseCase`**: Provides the list of signed-in accounts. (Depends on:
  `AccountRepository`)
* **`SignInUseCase`**: Manages the sign-in flow. (Depends on: `AccountRepository`)
* **`SignOutUseCase`**: Manages the sign-out flow. (Depends on: `AccountRepository`)

#### Data Fetching & Observation

* **`GetFoldersForAccountUseCase`**: Fetches the folder list for an account. (Depends on:
  `FolderRepository`)
* **`ObserveMessagesForFolderUseCase`**: Observes the message list for a folder. (Depends on:
  `MessageRepository`)
* **`ObserveThreadsForFolderUseCase`**: Observes the thread list for a folder. (Depends on:
  `ThreadRepository`)
* **`GetMessageDetailsUseCase`**: Fetches the content of a single message. (Depends on:
  `MessageRepository`)

#### Mail Actions (from Backlog EPIC 2 & 6)

* **`MarkAsReadUseCase`**: Marks a message/thread as read. (Depends on: `MessageRepository`,
  `ThreadRepository`)
* **`MarkAsUnreadUseCase`**: Marks a message/thread as unread. (Depends on: `MessageRepository`,
  `ThreadRepository`)
* **`DeleteMessageUseCase`**: Deletes a message. (Depends on: `MessageRepository`)
* **`MoveMessageUseCase`**: Moves a message to another folder. (Depends on: `MessageRepository`)
* **`SendEmailUseCase`**: Sends a composed email. (Depends on: `MessageRepository` or a new
  `CompositionRepository`)
