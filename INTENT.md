# Architectural Refactor: Handling Google Consent Intent

**Date:** May 10, 2025
**Author:** Gemini AI Architect
**Version:** 1.0

## 1. Introduction

This document outlines the implementation steps for refactoring the Melisma Mail Android application
to cleanly handle Google's OAuth 2.0 consent flow. The primary issue addressed is the
`googleConsentIntent` (an `IntentSender` for displaying Google's consent UI) causing build errors
and architectural impurity by being part of a common `AccountRepository` interface, which affects
non-Google implementations like `MicrosoftAccountRepository`.

The recommended approach is an **Enhanced Delegation Pattern using Capability Interfaces**. This
pattern ensures:

- The common `AccountRepository` interface remains generic and free of provider-specific UI logic.
- Provider-specific functionalities, like Google's consent flow, are exposed through dedicated
  capability interfaces.
- The `DefaultAccountRepository` implements both the common and relevant capability interfaces,
  orchestrating calls to provider-specific managers (e.g., `GoogleAuthManager`).
- The `MainViewModel` can safely access these provider-specific functionalities by checking for and
  using the capability interfaces.
- The UI layer (`MainActivity`) launches the `IntentSender` based on signals from the
  `MainViewModel`.

This approach maintains modularity, improves testability, and provides a scalable way to add more
provider-specific UI interactions in the future.

## 2. Pre-requisites

- Familiarity with the existing Melisma Mail architecture (modules: `:app`, `:core-data`, `:data`,
  `:backend-google`, `:backend-microsoft`).
- Understanding of Kotlin, Coroutines, Flow, Hilt, and Android's `IntentSender` and
  `ActivityResultLauncher`.
- `GoogleAuthManager` in `:backend-google` correctly implemented to return an `IntentSender` when
  Google OAuth consent is required.

## 3. Implementation Steps

### Step 1: Clean the Common `AccountRepository` Interface

The `googleConsentIntent` property must be removed from the common interface to ensure it doesn't
impose Google-specific requirements on all implementers.

**File:** `./core-data/src/main/java/net/melisma/core_data/repository/AccountRepository.kt`

**Modifications:**

```kotlin
package net.melisma.core_data.repository

import android.app.Activity
// REMOVE: import android.content.IntentSender // No longer needed here
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.melisma.core_data.model.Account
import net.melisma.core_data.model.AuthState

interface AccountRepository {
    val authState: StateFlow<AuthState>
    val accounts: StateFlow<List<Account>>
    val isLoadingAccountAction: StateFlow<Boolean>
    val accountActionMessage: Flow<String?>

    // REMOVE this Google-specific property:
    // val googleConsentIntent: Flow<IntentSender?>

    // Ensure this overload exists for provider-specific addition
    suspend fun addAccount(activity: Activity, scopes: List<String>, providerType: String)

    // This overload might be deprecated or removed if providerType is always specified.
    // If kept, its implementation in DefaultAccountRepository should delegate with a default providerType (e.g., "MS").
    suspend fun addAccount(activity: Activity, scopes: List<String>)

    suspend fun removeAccount(account: Account)
    fun clearAccountActionMessage()
}
Rationale: This makes AccountRepository truly generic. MicrosoftAccountRepository will no longer have a compile error regarding googleConsentIntent.

Step 2: Define the GoogleAccountCapability Interface

This new interface will specifically define the contract for operations related to Google's consent flow.

Create New File: ./core-data/src/main/java/net/melisma/core_data/repository/capabilities/GoogleAccountCapability.kt
(Consider placing this within a capabilities sub-package for organization.)

Content:

Kotlin
package net.melisma.core_data.repository.capabilities

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.Flow
import net.melisma.core_data.model.Account

/**
 * Defines capabilities specific to Google account management,
 * particularly for handling OAuth consent flows.
 */
interface GoogleAccountCapability {
    /**
     * A [Flow] emitting the [IntentSender] required to launch Google's OAuth consent UI.
     * Emits `null` when no consent is currently pending.
     */
    val googleConsentIntent: Flow<IntentSender?>

    /**
     * Finalizes the Google OAuth scope consent process after the user has interacted
     * with the consent UI launched via the [IntentSender].
     *
     * @param account The Google [Account] for which consent is being finalized.
     * @param intent The [Intent] data returned from the consent Activity.
     * @param activity The current [Activity] context.
     */
    suspend fun finalizeGoogleScopeConsent(account: Account, intent: Intent?, activity: Activity)
}
Rationale: This interface segregates Google-specific UI interaction logic. It provides a clear contract for components that need to interact with these specific features.

Step 3: Modify DefaultAccountRepository

DefaultAccountRepository will implement both the generic AccountRepository and the new GoogleAccountCapability. It will manage the IntentSender flow internally and delegate to GoogleAuthManager.

File: ./data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt

Modifications:

Update Class Signature & Imports:

Kotlin
package net.melisma.data.repository

import android.app.Activity
import android.content.Intent // Ensure this is imported
import android.content.IntentSender // Ensure this is imported
// ... other existing imports ...
import kotlinx.coroutines.flow.BufferOverflow // Ensure this is imported
import kotlinx.coroutines.flow.MutableSharedFlow // Ensure this is imported
import kotlinx.coroutines.flow.asSharedFlow // Ensure this is imported
import net.melisma.core_data.repository.capabilities.GoogleAccountCapability // Add this import

@Singleton
class DefaultAccountRepository @Inject constructor(
    private val microsoftAuthManager: MicrosoftAuthManager,
    private val googleAuthManager: GoogleAuthManager,
    @ApplicationScope private val externalScope: CoroutineScope,
    private val errorMappers: Map<String, @JvmSuppressWildcards ErrorMapperService>
) : AccountRepository, AuthStateListener, GoogleAccountCapability { // Add GoogleAccountCapability
    // ... (rest of the existing class members like TAG, _authState, etc.)
 Implement GoogleAccountCapability Members:
The existing _googleConsentIntent (MutableSharedFlow) in DefaultAccountRepository.kt (if named differently, adapt it) will now be the backing field for the googleConsentIntent property from the GoogleAccountCapability interface. The existing finalizeGoogleScopeConsent method should already match the capability interface signature.

Kotlin
// Backing field for the Google consent intent
private val _googleConsentIntentInternal = MutableSharedFlow<IntentSender?>(
    replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
)

// Implementation for GoogleAccountCapability
override val googleConsentIntent: Flow<IntentSender?> = _googleConsentIntentInternal.asSharedFlow()

// Implementation for GoogleAccountCapability
// (This method likely already exists; ensure it matches the interface and uses _isLoadingAccountAction correctly)
override suspend fun finalizeGoogleScopeConsent(account: Account, intent: Intent?, activity: Activity) {
    Log.d(TAG, "finalizeGoogleScopeConsent called for account: ${account.username} via capability interface")
    val errorMapper = getErrorMapperForProvider("GOOGLE")
    if (errorMapper == null) {
        Log.e(TAG, "finalizeGoogleScopeConsent: Google Error handler not found.")
        tryEmitMessage("Internal error: Google Error handler not found.")
        _isLoadingAccountAction.value = false // Ensure loading state is reset
        return
    }

    _isLoadingAccountAction.value = true
    val result = googleAuthManager.handleScopeConsentResult(intent) // Delegate to GoogleAuthManager
    Log.d(TAG, "finalizeGoogleScopeConsent: Result from GoogleAuthManager: $result")

    val message = when (result) {
        is GoogleScopeAuthResult.Success -> {
            Log.i(TAG, "Google scope consent successful. Access token received for ${account.username}.")
            // TODO: Securely store token if needed by GmailApiHelper or for background sync.
            // TODO: Update the account's state if it implies full authorization, potentially updating _accounts.
            "Google account access granted for: ${account.username}"
        }
        is GoogleScopeAuthResult.Error -> {
            Log.e(TAG, "Error in Google scope consent for ${account.username}: ${result.exception.message}")
            "Error completing Google account setup for ${account.username}: ${errorMapper.mapAuthExceptionToUserMessage(result.exception)}"
        }
        is GoogleScopeAuthResult.ConsentRequired -> {
            Log.w(TAG, "Additional consent required for ${account.username} even after finalizeGoogleScopeConsent. Emitting intent again.")
            externalScope.launch {
                _googleConsentIntentInternal.emit(result.pendingIntent)
            }
            // This message might be overridden if the UI reacts to the new intent first.
            "Further permissions needed for Gmail access for ${account.username}."
        }
    }
    tryEmitMessage(message)
    _isLoadingAccountAction.value = false
}
 Modify addAccount (specifically addGoogleAccount internal method):
Ensure that when GoogleAuthManager.requestAccessToken returns ConsentRequired, the IntentSender is emitted via _googleConsentIntentInternal.

Kotlin
// In DefaultAccountRepository.kt

// This is the public method from AccountRepository
override suspend fun addAccount(activity: Activity, scopes: List<String>, providerType: String) {
    Log.d(TAG, "addAccount called. ProviderType: $providerType, Scopes: $scopes")
    _isLoadingAccountAction.value = true // Set loading at the beginning of the operation
    when (providerType.uppercase()) {
        "MS" -> addMicrosoftAccountInternal(activity, scopes) // Changed to internal
        "GOOGLE" -> addGoogleAccountInternal(activity, scopes) // Changed to internal
        else -> {
            Log.w(TAG, "Unsupported provider type for addAccount: $providerType")
            tryEmitMessage("Unsupported account provider: $providerType")
            _isLoadingAccountAction.value = false // Reset loading for unsupported type
        }
    }
    // _isLoadingAccountAction will be reset within the internal methods upon completion/error
}

// Consider if this overload is still needed. If so, define its behavior.
// For example, defaulting to Microsoft or requiring UI to specify.
override suspend fun addAccount(activity: Activity, scopes: List<String>) {
    Log.d(TAG, "addAccount (legacy overload) called. Defaulting to Microsoft provider.")
    addAccount(activity, scopes, "MS") // Delegate to the specific provider version
}


private suspend fun addGoogleAccountInternal(activity: Activity, scopes: List<String>) {
    Log.d(TAG, "addGoogleAccountInternal: Attempting with scopes: $scopes")
    val errorMapper = getErrorMapperForProvider("GOOGLE")
    if (errorMapper == null) {
        Log.e(TAG, "addGoogleAccountInternal: Google Error handler not found.")
        tryEmitMessage("Internal error: Google Error handler not found.")
        _isLoadingAccountAction.value = false // Reset loading
        return
    }

    // Actual Google account addition logic
    when (val signInResult = googleAuthManager.signIn(activity, filterByAuthorizedAccounts = false)) {
        is GoogleSignInResult.Success -> {
            Log.i(TAG, "Google sign-in successful: ${signInResult.idTokenCredential.id}")
            val newAccount = googleAuthManager.toGenericAccount(signInResult.idTokenCredential)
            // TODO: Add newAccount to a temporary list or a local "pending" state.
            // It's not fully "added" to _accounts until scopes are granted.

            // Request access token for Gmail API scopes (e.g., readonly)
            // val gmailScopes = listOf("[https://www.googleapis.com/auth/gmail.readonly](https://www.googleapis.com/auth/gmail.readonly)") // Defined in ViewModel or constants
            Log.d(TAG, "Requesting access token for scopes: $scopes for ${newAccount.username}")
            when (val scopeResult = googleAuthManager.requestAccessToken(activity, newAccount.id, scopes)) {
                is GoogleScopeAuthResult.Success -> {
                    Log.i(TAG, "Gmail access token acquired successfully for ${newAccount.username} without needing explicit consent UI launch.")
                    // TODO: Securely store token if necessary.
                    // TODO: Add the newAccount to the main _accounts list and persist it.
                    // Example: updateAccountsListWithNewGoogleAccount(newAccount)
                    tryEmitMessage("Google account added and authorized: ${newAccount.username}")
                    _isLoadingAccountAction.value = false
                }
                is GoogleScopeAuthResult.ConsentRequired -> {
                    Log.i(TAG, "Gmail access requires user consent for ${newAccount.username}. Signaling UI to launch consent intent.")
                    // The account isn't fully "added" yet. UI will call finalizeGoogleScopeConsent.
                    // We keep isLoadingAccountAction = true because the add account flow is still in progress.
                    externalScope.launch {
                        _googleConsentIntentInternal.emit(scopeResult.pendingIntent)
                    }
                    // No explicit message here, as the UI will react to the intent.
                    // _isLoadingAccountAction remains true until finalizeGoogleScopeConsent completes or fails.
                }
                is GoogleScopeAuthResult.Error -> {
                    Log.e(TAG, "Error requesting Gmail access for ${newAccount.username}: ${scopeResult.exception.message}")
                    tryEmitMessage("Error setting up Gmail access for ${newAccount.username}: ${errorMapper.mapAuthExceptionToUserMessage(scopeResult.exception)}")
                    _isLoadingAccountAction.value = false
                }
            }
        }
        is GoogleSignInResult.Error -> {
            Log.e(TAG, "Google sign-in error: ${signInResult.exception.message}")
            tryEmitMessage("Error adding Google account: ${errorMapper.mapAuthExceptionToUserMessage(signInResult.exception)}")
            _isLoadingAccountAction.value = false
        }
        is GoogleSignInResult.Cancelled -> {
            Log.d(TAG, "Google sign-in was cancelled by the user.")
            tryEmitMessage("Google account addition cancelled.")
            _isLoadingAccountAction.value = false
        }
        is GoogleSignInResult.NoCredentialsAvailable -> {
            Log.d(TAG, "No Google credentials available for sign-in.")
            tryEmitMessage("No Google accounts available. Please add a Google account to your device.")
            _isLoadingAccountAction.value = false
        }
    }
    // Note: _isLoadingAccountAction is set to false in most paths.
    // For ConsentRequired, it remains true as the add account operation is ongoing.
    // It will be set to false in finalizeGoogleScopeConsent.
}

// Rename existing addMicrosoftAccount to addMicrosoftAccountInternal or similar private fun
private suspend fun addMicrosoftAccountInternal(activity: Activity, scopes: List<String>) {
    // ... existing Microsoft logic ...
    // Ensure _isLoadingAccountAction.value is set to false on completion/error.
    _isLoadingAccountAction.value = false // Example, ensure this is in all terminal paths
}

// TODO: Ensure removeGoogleAccount and removeMicrosoftAccount set _isLoadingAccountAction.value = false
// in their respective .onEach and .catch blocks or finally blocks.
 Rationale: DefaultAccountRepository now correctly implements the common AccountRepository and the GoogleAccountCapability. It centralizes the logic for interacting with GoogleAuthManager and signals the UI layer appropriately without polluting the common interface.

Step 4: Update MainViewModel to Use the Capability Interface

The MainViewModel will be injected with the generic AccountRepository. It will then check if this instance supports GoogleAccountCapability to access Google-specific consent flows.

File: ./app/src/main/java/net/melisma/mail/MainViewModel.kt

Modifications:

Add Property for Capability and Exposed Flow:

Kotlin
import net.melisma.core_data.repository.capabilities.GoogleAccountCapability // Add this import
import kotlinx.coroutines.flow.emptyFlow // Add this import for a safe default

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val accountRepository: AccountRepository, // Stays as generic AccountRepository
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val TAG = "MainViewModel"
    // ... existing properties ...

    // Hold the capability interface if available
    private val googleAccountCapability = accountRepository as? GoogleAccountCapability

    // Expose the Google consent IntentSender to the UI, defaulting to an empty flow if capability not present
    val googleConsentIntentForUi: StateFlow<android.content.IntentSender?> // Changed from _googleConsentIntentSender
        get() = _googleConsentIntentSender // Keep existing private StateFlow

    // Backing field for the intent sender
    private val _googleConsentIntentSender = MutableStateFlow<android.content.IntentSender?>(null)


    init {
        Log.d(TAG, "ViewModel Initializing")
        // ... existing init block ...

        // Observe Google consent intent from the capability interface
        if (googleAccountCapability != null) {
            Log.d(TAG, "Observing googleConsentIntent from GoogleAccountCapability.")
            googleAccountCapability.googleConsentIntent
                .onEach { intentSender ->
                    if (intentSender != null) {
                        Log.d(TAG, "Google consent intent received from capability. Signaling UI.")
                        _googleConsentIntentSender.value = intentSender
                        // _needGoogleConsent.value = true // This logic might be simplified or removed
                                                       // if UI directly observes _googleConsentIntentSender
                    } else {
                        // If null is emitted, it means the consent flow is done or cleared.
                        _googleConsentIntentSender.value = null
                    }
                }.launchIn(viewModelScope)
        } else {
            Log.w(TAG, "GoogleAccountCapability not available from AccountRepository.")
        }
    }
    // ...
 Update finalizeGoogleScopeConsent to use the capability:

Kotlin
// In MainViewModel.kt
fun finalizeGoogleScopeConsent(account: Account, intent: android.content.Intent?, activity: Activity) {
    if (googleAccountCapability == null) {
        Log.e(TAG, "finalizeGoogleScopeConsent called but GoogleAccountCapability is not available.")
        tryEmitToastMessage("Error: Cannot finalize Google consent.")
        return
    }
    viewModelScope.launch {
        Log.d(TAG, "Delegating finalizeGoogleScopeConsent to GoogleAccountCapability for account: ${account.username}")
        googleAccountCapability.finalizeGoogleScopeConsent(account, intent, activity)
        // _needGoogleConsent.value = false // This state might be managed differently now
        _googleConsentIntentSender.value = null // Clear the intent sender after handling
    }
}
 Review addGoogleAccount method:
The MainViewModel.addGoogleAccount method still calls accountRepository.addAccount(..., "GOOGLE"). This is correct. The DefaultAccountRepository will handle the internal logic, including emitting to its internal _googleConsentIntentInternal flow, which the ViewModel now observes via the googleAccountCapability.googleConsentIntent if the cast is successful.

Kotlin
// In MainViewModel.kt
fun addGoogleAccount(activity: Activity) {
    viewModelScope.launch {
        Log.d(TAG, "Add Google account action triggered via MainViewModel.")
        // Scopes should be appropriate for initial Google setup, e.g., Gmail read-only
        val gmailScopes = listOf("[https://www.googleapis.com/auth/gmail.readonly](https://www.googleapis.com/auth/gmail.readonly)" /*, other essential scopes */)
        accountRepository.addAccount(activity, gmailScopes, "GOOGLE")
    }
}
 Ensure the gmailReadScopes are correctly defined and passed.

Rationale: The ViewModel interacts with the generic AccountRepository for common tasks and checks for GoogleAccountCapability for Google-specific UI flows. This maintains decoupling and allows the ViewModel to adapt if the underlying repository implementation changes or doesn't support Google features.

Step 5: Update MainActivity to Observe the New Flow from ViewModel

MainActivity will observe the googleConsentIntentForUi (or the adapted name googleConsentIntentSender) from the MainViewModel to launch the consent IntentSender.

File: ./app/src/main/java/net/melisma/mail/MainActivity.kt

Modifications:

Kotlin
// In MainActivity.kt

// ... existing imports ...
// import androidx.lifecycle.lifecycleScope // Already there
// import kotlinx.coroutines.launch // Already there

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    googleConsentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val googleAccountToFinalize = viewModel.uiState.value.accounts
            .filter { it.providerType == "GOOGLE" }
            // Attempt to find an account that might be in a "pending consent" state.
            // This logic might need refinement based on how DefaultAccountRepository handles
            // partially added accounts before consent is finalized.
            // For now, we assume any Google account could be the one.
            // A more robust solution might involve the ViewModel tracking the specific account
            // for which consent was initiated.
            .firstOrNull() // This is a simplification. See note below.

        if (googleAccountToFinalize != null) {
            if (result.resultCode == RESULT_OK) {
                Log.d("MainActivity", "Google consent flow successful for ${googleAccountToFinalize.username}. Finalizing...")
                viewModel.finalizeGoogleScopeConsent(googleAccountToFinalize, result.data, this)
            } else {
                Log.d("MainActivity", "Google consent flow cancelled or failed for ${googleAccountToFinalize.username}.")
                Toast.makeText(this, "Google account setup requires your permission.", Toast.LENGTH_LONG).show()
                // Optionally, tell ViewModel to clear any pending state for this account attempt.
                viewModel.finalizeGoogleScopeConsent(googleAccountToFinalize, null, this) // Signal cancellation/failure
            }
        } else {
             Log.e("MainActivity", "Google consent result received, but no matching Google account found in ViewModel state to finalize.")
             if (result.resultCode == RESULT_OK) {
                 Toast.makeText(this, "Permissions granted, but app had an issue. Please try adding the account again.", Toast.LENGTH_LONG).show()
             }
        }
    }

    // Observe the googleConsentIntentSender from ViewModel
    lifecycleScope.launch {
        // The flow exposed by ViewModel (e.g., viewModel.googleConsentIntentForUi or viewModel.googleConsentIntentSender)
        viewModel.googleConsentIntentSender.collect { intentSender -> // Ensure this matches the ViewModel's exposed Flow name
            if (intentSender != null) {
                Log.d("MainActivity", "Received IntentSender from ViewModel. Launching Google consent UI.")
                try {
                    val request = androidx.activity.result.IntentSenderRequest.Builder(intentSender).build()
                    googleConsentLauncher.launch(request)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error launching Google consent intent", e)
                    Toast.makeText(this@MainActivity, "Error initiating Google permission request: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ... rest of onCreate and other MainActivity code ...
}
Important Note for MainActivity.googleConsentLauncher callback:
The logic to find googleAccountToFinalize is simplified. In a robust implementation:

When DefaultAccountRepository.addGoogleAccountInternal initiates the consent flow, it should ideally store the id or a temporary representation of the Google account being processed.
This "pending account" information could be passed along with the IntentSender event or managed by the MainViewModel.
When finalizeGoogleScopeConsent is called from MainActivity, it should use this specific pending account information rather than guessing from the general list. This avoids ambiguity if multiple Google accounts exist or if the account list changes. For now, the provided code assumes finalizeGoogleScopeConsent in the ViewModel/Repository can handle this or that it's an "in-flight" new account.
Rationale: MainActivity remains responsible for Android-specific UI interactions like launching IntentSender results, but the trigger comes from the ViewModel, maintaining a clean separation.

Step 6: Update MicrosoftAccountRepository (If Necessary)

File: ./backend-microsoft/src/main/java/net/melisma/backend_microsoft/repository/MicrosoftAccountRepository.kt

Modifications:
The primary build error should be resolved because googleConsentIntent is no longer in the AccountRepository interface.

Review the addAccount methods:

If MicrosoftAccountRepository still has the old addAccount(activity, scopes) (single provider assumption) and the addAccount(activity, scopes, providerType):
Ensure the latter correctly handles only "MS" providerType and perhaps logs/errors for others.
The former might be removed if DefaultAccountRepository now handles the provider dispatch. If kept, it should be clear it's for Microsoft only.
Kotlin
// Example snippet for MicrosoftAccountRepository if it implements AccountRepository directly
// (which it might not if DefaultAccountRepository is the sole implementer passed around)

// override suspend fun addAccount(activity: Activity, scopes: List<String>) {
//    // This implies Microsoft account addition
//    addMicrosoftAccountInternal(activity, scopes)
// }

// override suspend fun addAccount(activity: Activity, scopes: List<String>, providerType: String) {
//    if (providerType.equals("MS", ignoreCase = true)) {
//        addMicrosoftAccountInternal(activity, scopes)
//    } else {
//        Log.w(TAG, "addAccount called with unsupported provider type: $providerType for MicrosoftAccountRepository")
//        // Potentially emit an error message via its own accountActionMessage if it has one,
//        // or this method shouldn't be callable with a non-MS type if DefaultAccountRepository handles dispatch.
//    }
// }
If DefaultAccountRepository is the primary AccountRepository implementation used throughout the app, then MicrosoftAccountRepository might not even directly implement AccountRepository anymore, but rather be a delegate used by DefaultAccountRepository. The current structure seems to have DefaultAccountRepository as the main implementer, using MicrosoftAuthManager and GoogleAuthManager. So, MicrosoftAccountRepository might not exist as a direct AccountRepository implementer in your Hilt graph. If it does, ensure its addAccount methods are consistent.

4. Testing Considerations
Unit Tests for DefaultAccountRepository:
Verify that googleConsentIntent flow emits the IntentSender correctly when addGoogleAccountInternal encounters a ConsentRequired result from GoogleAuthManager.
Verify finalizeGoogleScopeConsent correctly calls GoogleAuthManager and emits appropriate accountActionMessage.
Verify common AccountRepository methods still function as expected.
Unit Tests for MainViewModel:
Mock AccountRepository and GoogleAccountCapability.
Test that googleConsentIntentForUi (or googleConsentIntentSender) correctly reflects emissions from the capability's googleConsentIntent.
Test that finalizeGoogleScopeConsent calls the capability's method.
Integration/UI Tests:
Perform end-to-end testing of the "Add Google Account" flow, ensuring the Google consent screen appears when necessary and that the flow completes successfully upon granting permission.
Test cancellation and denial of consent.
Test scenarios where consent is already granted (no consent screen should appear).
5. Conclusion
This refactoring using an Enhanced Delegation Pattern with Capability Interfaces will resolve the immediate build error and establish a cleaner, more maintainable, and scalable architecture for handling provider-specific UI interactions like Google's OAuth consent. It keeps common interfaces generic while providing type-safe access to specialized functionalities.
