# Refactoring Plan: Introducing Repositories to Melisma Mail

**Version:** 1.0 (2025-05-02)

## 1. Introduction & Objectives

### Current State
The current implementation centers significant logic within `MainViewModel.kt`. This includes:
* Managing authentication state via direct interaction with `MicrosoftAuthManager`.
* Orchestrating data fetching (folders, messages) via direct calls to `GraphApiHelper`.
* Handling token acquisition logic (`acquireTokenAndExecute`).
* Managing asynchronous job states (`folderFetchJobs`, `FolderFetchState`, `DataState`).
* Mapping API errors.
* Holding UI selection state.

This leads to a large, complex ViewModel that violates the Single Responsibility Principle and is tightly coupled to Microsoft-specific components (MSAL, Microsoft Graph).

### Objectives
Based on the project goals outlined in `DESIGN_DOC.md` (v0.3), this refactoring aims to:
1.  **Improve Maintainability & Testability:** Break down `MainViewModel` into smaller, more focused components by separating data handling concerns.
2.  **Abstract Provider Specifics:** Decouple the core application logic from Microsoft components (MSAL, Graph) to facilitate adding support for other providers like Google (`EPIC 9`).
3.  **Enable Caching & Offline Support:** Establish a data layer where caching logic (`Requirement 5.6`) can be implemented transparently to the UI layer.
4.  **Adhere to Architecture:** Implement the Repository pattern and align with the proposed modular structure (`:app`, `:core-data`, `:backend-*`, `:feature-auth`) from the design document.

## 2. Chosen Approach: Repository Pattern

We will introduce **Repositories** as the primary architectural pattern to address the objectives.

* **What:** Repositories act as mediators between the application's data sources (network APIs, local cache, authentication managers) and the rest of the app (primarily ViewModels).
* **Why:**
    * **Separation of Concerns:** ViewModels focus on managing UI state and reacting to user input, while Repositories handle *how* and *where* to get data.
    * **Abstraction:** Repositories expose a clean API (often using interfaces and generic data models) for data access, hiding the underlying implementation details (e.g., whether data comes from Microsoft Graph, Gmail API, or a local cache). This is crucial for multi-provider support.
    * **Single Source of Truth (SSoT):** Repositories manage the flow of data, deciding whether to fetch from the network, serve from cache, or combine sources. This simplifies data consistency.
    * **Testability:** Repositories can be tested independently, and ViewModels can be tested using mock Repositories.
* **Alignment:** This pattern directly supports the modular architecture by defining clear boundaries for data access (`:core-data`) and specific data source interactions (`:backend-*`).

## 3. Proposed Repositories

We will introduce the following core repositories:

* **`AccountRepository`:**
    * **Role:** Manages user accounts and authentication state across different providers.
    * **Responsibilities:** Interacts with `MicrosoftAuthManager` (and future auth managers), loads accounts, handles add/remove operations, exposes current auth state and account list (using a generic `Account` model).
    * **Exposed (Example):** `observeAccounts(): Flow<List<Account>>`, `observeAuthState(): Flow<AuthState>`, `addAccount(...): Flow<Result<Account>>`, `removeAccount(...): Flow<Result<Unit>>`
* **`FolderRepository`:**
    * **Role:** Manages fetching and potentially caching mail folders for specific accounts.
    * **Responsibilities:** Takes an account identifier, fetches folders from the appropriate backend (Microsoft Graph initially), manages fetch states (`FolderFetchState`), handles refresh logic.
    * **Exposed (Example):** `observeFolders(accountId): Flow<FolderFetchState>`, `refreshFolders(accountId): Flow<Result<Unit>>`, `refreshAllFolders(): Flow<Result<Unit>>`
* **`MessageRepository`:**
    * **Role:** Manages fetching and potentially caching messages for specific folders.
    * **Responsibilities:** Takes account/folder identifiers, fetches messages from the appropriate backend, handles pagination (future), searching (future), manages fetch states (`DataState`), handles refresh logic.
    * **Exposed (Example):** `observeMessages(accountId, folderId): Flow<List<Message>>`, `observeMessageState(accountId, folderId): Flow<DataState>`, `refreshMessages(accountId, folderId): Flow<Result<Unit>>`, `setSelectedFolderTarget(accountId, folderId)`

## 4. Refactoring Steps (Incremental)

This refactoring will be performed step-by-step to ensure the application remains functional throughout the process.

1.  **Step 0: Setup Dependency Injection (DI):** Configure Hilt or Koin. Refactor existing `MainViewModel` dependencies (`Context`, `MicrosoftAuthManager`) to use DI. (Prerequisite for easily providing repositories).
2.  **Step 1: Introduce `AccountRepository`:** Create the interface and Microsoft implementation. Refactor `MainViewModel` to use this repository for all account and auth state management, removing direct dependency on `MicrosoftAuthManager`.
3.  **Step 2: Introduce `FolderRepository`:** Create the interface and Microsoft implementation. Move folder fetching logic from `MainViewModel` to the repository. Refactor `MainViewModel` to use the repository for folder data and state. Update default folder selection logic to react to repository state.
4.  **Step 3: Introduce `MessageRepository`:** Create the interface and Microsoft implementation. Move message fetching logic from `MainViewModel` to the repository. Refactor `MainViewModel` to use the repository for message data and state.

**Post-Refactoring Steps (Enabled by this structure):**

* **Implement Caching:** Add local database interaction (e.g., Room) within the repository implementations without changing the ViewModel.
* **Add Google Support:** Create Google-specific implementations for auth/backend interaction and configure repositories (or DI) to use them based on account type.

## 5. Implementation Guidance

* **Interfaces First:** Define the Repository interfaces in a common module (`:core-data` or equivalent) before creating implementations.
* **Dependency Injection:** Leverage Hilt or Koin heavily from Step 0/1 onwards to provide repositories and their dependencies (ApiHelpers, AuthManagers, Context, Dao's).
* **Kotlin Flows:** Use `kotlinx.coroutines.flow.Flow` extensively to expose data streams (accounts, folders, messages, states) from repositories. ViewModels will collect these flows using `collectAsStateWithLifecycle()` or other appropriate collectors.
* **Immutability:** Keep state objects (`MainScreenState`, `Account`, `MailFolder`, `Message`, etc.) immutable (`data class`). Use the `update { }` function on `MutableStateFlow` for atomic state changes in the ViewModel.
* **Error Handling:** Repositories should handle backend/network errors gracefully, potentially mapping them to specific error states or `Result` wrappers to be exposed to the ViewModel. ViewModel then maps these states/results to user-facing messages (toasts, error UI).
* **ViewModel Slimming:** At each step, actively remove the corresponding logic (data fetching, state management, error mapping related to that data) from `MainViewModel` as it moves into the repository. The ViewModel's role transitions towards UI state coordination and action delegation.

By following this plan, we can incrementally refactor the `MainViewModel`, leading to a more robust, maintainable, and extensible architecture aligned with our project goals.
