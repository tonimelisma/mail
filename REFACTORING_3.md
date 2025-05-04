Refactoring Plan: Introducing Repositories to Melisma Mail
Version: 1.2 (2025-05-03) - Steps 0-3 Completed

1. Introduction & Objectives
   Current State

The refactoring process has successfully moved significant logic out of MainViewModel.kt and into
dedicated repositories:

Authentication state and account management are handled by AccountRepository.

Folder fetching and state management are handled by FolderRepository.

Message fetching and state management for the selected folder are handled by MessageRepository.

Token acquisition logic is abstracted via TokenProvider and used by the repositories.

MainViewModel now primarily acts as a coordinator, observing state flows from the repositories and
delegating user actions. It still holds UI selection state (selected folder/account).

Objectives

Based on the project goals outlined in DESIGN_DOC.md (v0.3), this refactoring aims to:

Improve Maintainability & Testability: Break down MainViewModel into smaller, more focused
components by separating data handling concerns. [Achieved for Auth, Folders, Messages]

Abstract Provider Specifics: Decouple the core application logic from Microsoft components (MSAL,
Graph) to facilitate adding support for other providers like Google (EPIC
9). [Partially Achieved - Data Layer Abstracted]

Enable Caching & Offline Support: Establish a data layer where caching logic (Requirement 5.6) can
be implemented transparently to the UI layer. [Foundation Laid]

Adhere to Architecture: Implement the Repository pattern and align with the proposed modular
structure (:app, :core-data, :backend-*, :feature-auth) from the design
document. [Pattern Implemented, Modularity Pending]

2. Chosen Approach: Repository Pattern
   (Unchanged - This pattern has been successfully implemented for core data types).

3. Implemented Repositories
   AccountRepository: Manages user accounts and authentication state. Implemented via
   MicrosoftAccountRepository.

FolderRepository: Manages fetching and state for mail folders per account. Implemented via
MicrosoftFolderRepository.

MessageRepository: Manages fetching and state for messages within a selected folder. Implemented via
MicrosoftMessageRepository.

TokenProvider: Abstract interface for acquiring access tokens. Implemented via
MicrosoftTokenProvider.

4. Refactoring Steps (Incremental)
   Step 0: Setup Dependency Injection (DI): [COMPLETED] Hilt configured. MainViewModel uses DI.
   RepositoryModule provides dependencies.

Step 1: Introduce AccountRepository: [COMPLETED] Interface and implementation created. MainViewModel
uses it for auth state and account list. UI updated for generic Account.

Step 2: Introduce FolderRepository: [COMPLETED] Interface and implementation created. TokenProvider
introduced. Folder fetching logic moved from MainViewModel. ViewModel observes folder state from
repository.

Step 3: Introduce MessageRepository: [COMPLETED] Interface and implementation created. Message
fetching logic moved from MainViewModel. ViewModel observes message state from repository. Temporary
helpers (acquireTokenAndExecute, getMsalAccountById) removed from ViewModel.

5. Critical Evaluation of Completed Steps vs. Architecture
   The refactoring steps completed so far have significantly improved the architecture and align
   well with the goals, but there are areas to note:

Maintainability & Testability: Significantly Improved. MainViewModel is much smaller and focused on
UI state coordination. The repositories encapsulate complex data fetching, state management, and
error handling logic. This makes both the ViewModel and the repositories easier to understand,
modify, and test independently (ViewModels can be tested with mock repositories).

Abstraction of Provider Specifics: Good Progress.

The use of interfaces (AccountRepository, FolderRepository, MessageRepository, TokenProvider)
successfully abstracts the what (getting accounts, folders, messages, tokens) from the how (using
MSAL, Graph API).

MainViewModel now depends only on these interfaces and generic models (Account, MailFolder,
Message), making it provider-agnostic.

The provider-specific logic is contained within the Microsoft* implementations (e.g.,
MicrosoftAccountRepository, MicrosoftTokenProvider, MicrosoftFolderRepository,
MicrosoftMessageRepository) and GraphApiHelper. This sets the stage for adding :backend-google
implementations.

Minor Deviation: GraphApiHelper is currently an object directly used by the Microsoft repositories.
For stricter separation, it could be an interface injected into the repositories, allowing different
backend implementations to use different API helpers. However, as it's specific to the Microsoft
backend, direct usage within those repositories is acceptable for now.

Caching & Offline Support: Foundation Established. The repository pattern provides the ideal layer
to introduce caching. The Microsoft*Repository implementations can be modified internally to check a
local cache (e.g., Room database) before hitting the network (GraphApiHelper), without changing the
interface contract or the MainViewModel. The current implementation fetches data live on demand.

Architecture & Modularity: Pattern Implemented, Physical Modularity Pending.

The Repository pattern is now clearly implemented.

Dependency Injection via Hilt is working correctly.

The logical separation aligns with the design doc (UI -> ViewModel -> Repository ->
DataSource/AuthManager).

The physical separation into distinct Gradle modules (:core-data, :backend-microsoft, etc.) has not
yet been done. All repository interfaces and implementations currently reside within the :app
module's package structure (net.melisma.mail.data...). Migrating these to dedicated library modules
would fully realize the modularity goal but is a separate refactoring effort.

Error Handling: Error mapping helpers (mapAuthExceptionToUserMessage,
mapGraphExceptionToUserMessage) are currently duplicated in MicrosoftFolderRepository and
MicrosoftMessageRepository. Centralizing this into a shared utility or error handling module would
improve consistency and reduce redundancy.

Token Acquisition: The introduction of TokenProvider successfully abstracted token fetching from the
repositories needing them. The MicrosoftTokenProvider encapsulates the MSAL-specific
silent/interactive flow logic.

6. Next Steps (Post-Refactoring)
   With the core data flow refactored into repositories, the project is well-positioned for:

Implement Caching: Introduce a Room database and update repository implementations to cache/retrieve
data locally.

Physical Module Separation: Move interfaces, implementations, and models into dedicated Gradle
modules (:core-data, :backend-microsoft, etc.) as outlined in DESIGN_DOC.md.

Implement Remaining Features: Tackle other Epics (Basic Actions, Composing, Attachments, etc.) by
adding methods to the relevant repositories and corresponding logic in the ViewModel/UI.

Add Google Support: Create :backend-google module with implementations for AccountRepository,
FolderRepository, MessageRepository, TokenProvider using Google Sign-In and Gmail API. Update DI to
provide the correct implementations based on account type.

Refine Error Handling: Centralize error mapping utilities.

This refactoring effort has successfully established a cleaner, more robust data layer, aligning the
codebase much more closely with the intended architecture and paving the way for future feature
development and multi-provider support.
