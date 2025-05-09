# Google Backend Integration - Interim Report

This document summarizes the changes made to implement MailApiService abstraction across both
Microsoft and Google backend modules, and to adapt the repositories to use these services through
multi-binding.

## Changes Made

### 1. GraphApiHelper Implementation (backend-microsoft/src/main/java/net/melisma/backend_microsoft/GraphApiHelper.kt)

- Added implementation of MailApiService interface
- Updated import statements to include necessary Ktor and JSON dependencies
- Modified the class declaration to implement MailApiService
- Added override annotations to existing methods to satisfy interface requirements
- Implemented three new interface methods:
    - `markMessageRead()`: Uses PATCH to update a message's read status
    - `deleteMessage()`: Uses DELETE to remove a message
    - `moveMessage()`: Uses POST to move a message to a different folder

### 2. BackendMicrosoftModule Updates (backend-microsoft/src/main/java/net/melisma/backend_microsoft/di/BackendMicrosoftModule.kt)

- Added necessary imports for multi-binding annotations
- Updated the `bindTokenProvider` method to use `@IntoMap` and `@StringKey("MS")` annotations
- Added a `bindMailApiService` method to provide GraphApiHelper as a MailApiService implementation
  with the key "MS"

### 3. GmailApiHelper Implementation (backend-google/src/main/java/net/melisma/backend_google/GmailApiHelper.kt)

- Added implementation of MailApiService interface
- Updated import statements to include necessary Ktor and JSON dependencies
- Modified the class declaration to implement MailApiService
- Renamed `getLabels()` to `getMailFolders()` with override annotation
- Adapted `getMessagesForLabel()` to `getMessagesForFolder()` with signature matching the interface
- Implemented three new interface methods:
    - `markMessageRead()`: Uses label modification to add/remove the UNREAD label
    - `deleteMessage()`: Uses Gmail API's trash endpoint to move messages to trash
    - `moveMessage()`: Implements label management for moving messages between labels/folders

### 4. BackendGoogleModule Updates (backend-google/src/main/java/net/melisma/backend_google/di/BackendGoogleModule.kt)

- Added necessary imports for multi-binding annotations
- Updated the `provideGoogleTokenProvider` method to use `@IntoMap` and `@StringKey("GOOGLE")`
  annotations
- Added a `provideGmailApiAsMailService` method to provide GmailApiHelper as a MailApiService
  implementation with the key "GOOGLE"
- Removed duplicate definition of TokenProviderType

### 5. Enabled backend-google Dependency (data/build.gradle.kts)

- Uncommented the dependency on `:backend-google` module:
  ```kotlin
  implementation(project(":backend-google"))
  ```

### 6. DefaultFolderRepository Update (data/src/main/java/net/melisma/data/repository/DefaultFolderRepository.kt)

- Updated imports to include MailApiService
- Changed constructor parameters from direct dependencies:
  ```kotlin
  private val tokenProvider: TokenProvider,
  private val graphApiHelper: GraphApiHelper,
  ```
  to map-based dependencies:
  ```kotlin
  private val tokenProviders: Map<String, @JvmSuppressWildcards TokenProvider>,
  private val mailApiServices: Map<String, @JvmSuppressWildcards MailApiService>,
  ```
- Updated `manageObservedAccounts()` to support multiple provider types
- Refactored `launchFolderFetchJob()` to retrieve appropriate providers from maps based on
  account.providerType

### 7. DefaultMessageRepository Update (data/src/main/java/net/melisma/data/repository/DefaultMessageRepository.kt)

- Updated imports to include MailApiService
- Changed constructor parameters from direct dependencies to map-based dependencies
- Updated `setTargetFolder()` to support multiple provider types
- Renamed Microsoft-specific `refreshMicrosoftMessages()` to generic `refreshMessagesForProvider()`
- Replaced Microsoft-specific `launchMicrosoftMessageFetchJob()` with generic
  `launchMessageFetchJob()`
- Updated implementation to use correct providers from maps based on account.providerType

### 8. Added MultiBindingModule (data/src/main/java/net/melisma/data/di/MultiBindingModule.kt)

- Created new file with module to declare multi-bound maps
- Implemented the `@Multibinds` annotation for TokenProvider and MailApiService maps

## Why MultiBindingModule is Needed

The `MultiBindingModule` is a critical component for Hilt's multi-binding functionality.

When using multi-binding with Hilt, you need to:

1. Declare the maps that will be injected (which is what MultiBindingModule does)
2. Provide the entries for those maps (which is what we do in the backend modules)

Without MultiBindingModule, Hilt doesn't know that it should create a map for the dependencies, even
though we're providing entries for that map in other modules. The `@Multibinds` annotation tells
Hilt "expect a map of this type to be injected somewhere," and then the `@IntoMap` and `@StringKey`
annotations in other modules tell Hilt "put this implementation into that map with this key."

## Failing Tests

Several tests are failing due to our architectural changes:

### backend-microsoft Tests:

1. **GraphApiHelperTest**:
    - `getMailFolders handles malformed JSON response` and
      `getMessagesForFolder handles malformed JSON response`
    - These tests now fail because the GraphApiHelper class signature has changed to implement
      MailApiService.

2. **MicrosoftTokenProviderTest**:
    - `getAccessToken fails if MSAL account not found in manager`
    - This test might be failing due to changes in the TokenProvider binding.

3. **MicrosoftAccountRepositoryTest** and **MicrosoftMessageRepositoryTest**:
    - These tests fail with `TurbineTimeoutCancellationException`
    - This is likely because these tests expect a direct dependency on TokenProvider and
      GraphApiHelper but now the repositories use maps.

### app Tests:

1. **MainViewModelTest**:
    - `initializationError` failing with `NoClassDefFoundError`
    - This is because the ViewModels depend on the repositories, which now require map bindings
      instead of direct dependencies.

### Fixing the Tests

To fix these tests, we would need to:

1. Update test classes to use the new constructor signatures
2. Provide properly mocked maps of services instead of individual services
3. Adjust the test expectations to match the new behavior

For example, a test that previously provided a mock for GraphApiHelper would now need to provide a
map with a single entry where the key is "MS" and the value is a mock GraphApiHelper.

## Next Steps

1. Update the tests to work with the new dependency injection structure
2. Complete the Google Backend Integration by implementing the remaining tasks:
    - Google Cloud & Project Setup - Add google-services.json to app/ directory
    - Google Cloud & Project Setup - Activate Google Services Gradle plugin in app/build.gradle.kts
    - Update MicrosoftErrorMapper to handle Google exceptions
    - Update DefaultAccountRepository to handle Google accounts

These changes have successfully prepared the codebase for Google account integration by implementing
a provider-agnostic abstraction layer for email operations.