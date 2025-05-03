# Refactoring Plan: Introducing Repositories to Melisma Mail

**Version:** 1.1 (2025-05-02) - Step 1 Completed

## 1. Introduction & Objectives

### Current State

The current implementation centers significant logic within `MainViewModel.kt`. This includes:

* ~~Managing authentication state via direct interaction with `MicrosoftAuthManager`.~~ (Moved to
  AccountRepository)
* Orchestrating data fetching (folders, messages) via direct calls to `GraphApiHelper`.
* Handling token acquisition logic (`acquireTokenAndExecute`).
* Managing asynchronous job states (`folderFetchJobs`, `FolderFetchState`, `DataState`).
* Mapping API errors.
* Holding UI selection state.

This leads to a large ViewModel still handling data fetching details and using provider-specific
helpers (`GraphApiHelper`, `acquireTokenAndExecute`).

### Objectives

Based on the project goals outlined in `DESIGN_DOC.md` (v0.3), this refactoring aims to:

1. **Improve Maintainability & Testability:** Break down `MainViewModel` into smaller, more focused
   components by separating data handling concerns.
2. **Abstract Provider Specifics:** Decouple the core application logic from Microsoft components (
   MSAL, Graph) to facilitate adding support for other providers like Google (`EPIC 9`).
3. **Enable Caching & Offline Support:** Establish a data layer where caching logic (
   `Requirement 5.6`) can be implemented transparently to the UI layer.
4. **Adhere to Architecture:** Implement the Repository pattern and align with the proposed modular
   structure (`:app`, `:core-data`, `:backend-*`, `:feature-auth`) from the design document.

## 2. Chosen Approach: Repository Pattern

(Unchanged - We are implementing this pattern)

## 3. Proposed Repositories

(Unchanged - Definitions remain the same)

* **`AccountRepository`:** (Implemented)
* **`FolderRepository`:** (Next)
* **`MessageRepository`:** (Future)

## 4. Refactoring Steps (Incremental)

This refactoring will be performed step-by-step to ensure the application remains functional
throughout the process.

1. **Step 0: Setup Dependency Injection (DI):** **[COMPLETED]** Configured Hilt. Refactored
   `MainViewModel` dependencies (`Context`) and set up provision for `MicrosoftAuthManager`. Deleted
   `MainViewModelFactory`.
2. **Step 1: Introduce `AccountRepository`:** **[COMPLETED]** Created interface (
   `AccountRepository`) and implementation (`MicrosoftAccountRepository`). Refactored
   `MainViewModel` to inject and use this repository for auth state (`AuthState`) and account list (
   `List<Account>`). Updated `MainScreenState` and UI components accordingly. Removed
   `AuthStateListener` from ViewModel. `addAccount`/`removeAccount` actions delegated to repository.
3. **Step 2: Introduce `FolderRepository`:** **[NEXT STEP]** Create the interface and Microsoft
   implementation. Move folder fetching logic (including related state like `FolderFetchState`, job
   management, and potentially token acquisition via `acquireTokenAndExecute` or similar) from
   `MainViewModel` to the repository. Refactor `MainViewModel` to inject and use the repository for
   folder data and state. Update default folder selection logic to react to repository state.
4. **Step 3: Introduce `MessageRepository`:** Create the interface and Microsoft implementation.
   Move message fetching logic (including `DataState`, pagination/search placeholders) from
   `MainViewModel` to the repository. Refactor `MainViewModel` to use the repository for message
   data and state.

**Post-Refactoring Steps (Enabled by this structure):**

* **Implement Caching:** Add local database interaction (e.g., Room) within the repository
  implementations without changing the ViewModel.
* **Add Google Support:** Create Google-specific implementations for auth/backend interaction and
  configure repositories (or DI) to use them based on account type.
* **Refactor Token Acquisition:** Abstract the `acquireTokenAndExecute` logic away from
  `MainViewModel` and potentially `MicrosoftFolder/MessageRepository` into a dedicated injectable
  `TokenProvider` interface/implementation.

## 5. Implementation Guidance

(Unchanged - General guidelines still apply)

* Interfaces First
* Dependency Injection
* Kotlin Flows
* Immutability
* Error Handling
* ViewModel Slimming

By following this plan, we can incrementally refactor the `MainViewModel`, leading to a more robust,
maintainable, and extensible architecture aligned with our project goals.
