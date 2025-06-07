# **Diagnosing and Resolving Persistent MSAL 'Failed to decrypt with key:AdalKey' Errors in Kotlin Android Applications**

## **1\. Executive Summary**

This report addresses the persistent "Failed to decrypt with key:AdalKey" error encountered in Kotlin Android applications utilizing the Microsoft Authentication Library (MSAL). This error is particularly perplexing as it can manifest even when core user sign-in functionalities operate correctly and in the absence of explicit triggers such as operating system updates or application reinstallations.  
Overview of the "Failed to decrypt with key:AdalKey" Error:  
The error message "Failed to decrypt with key:AdalKey" is a critical indicator that MSAL is attempting to perform a cryptographic decryption operation using a key specifically identified as "AdalKey." This nomenclature strongly suggests an interaction with components or artifacts from the legacy Azure Active Directory Authentication Library (ADAL), MSAL's predecessor. Such errors typically surface during background operations involving cached tokens, for instance, when attempting a silent token refresh, even if interactive sign-in (which usually fetches fresh tokens from the server) completes without issue.  
High-Level Summary of Probable Causes:  
The most probable cause of this error is the presence of residual ADAL artifacts within the application's persistent storage. These artifacts could include cached tokens (particularly refresh tokens) or the cryptographic keys ADAL used for their protection. An incomplete migration from ADAL to MSAL, or insufficient cleanup procedures following such a migration, can leave these remnants on user devices, leading to the observed decryption failures when MSAL inadvertently encounters them.  
Recommended Approach:  
The recommended strategy to address this error focuses on comprehensive cache invalidation. This involves not only clearing MSAL's own token cache but also meticulously targeting any potential ADAL remnants. Key actions include the robust removal of all accounts managed by MSAL. In persistent cases, direct manipulation of Android's SharedPreferences, where ADAL was known to store its keys and token metadata, might be necessary, albeit with significant caution.  
The explicit mention of "AdalKey" in the error message is a crucial diagnostic clue. It's not a generic decryption failure; the key's name points directly to ADAL. This specificity suggests that MSAL might possess internal code paths that recognize such legacy identifiers, perhaps as part of a migration assistance feature or due to shared underlying cryptographic components in earlier versions of MSAL or its interaction with a broker. The failure then occurs if the actual key material associated with "AdalKey" is corrupted, inaccessible under MSAL's security context, or requires an ADAL-specific decryption routine that MSAL no longer fully supports or cannot access. The persistence of this error, even without app or OS updates, indicates a latent issue triggered by specific, pre-existing cache states on the device rather than a newly introduced incompatibility. This points to a deep-seated problem related to how ADAL originally stored its cryptographic keys and how MSAL might be misinterpreting these stored artifacts. The challenge is not merely about a stale token, but potentially a stale or incompatible *encryption key* or a token encrypted with such a key.

## **2\. Deconstructing the 'AdalKey' Decryption Error**

A thorough understanding of the error message "Failed to decrypt with key:AdalKey" is paramount to formulating an effective resolution strategy. This section dissects the error, its components, and its strong ties to legacy authentication mechanisms.  
Explanation of the Error Message:  
The message "Failed to decrypt with key:AdalKey" precisely describes the problem: the Microsoft Authentication Library (MSAL) encountered an encrypted piece of data—most likely a cached token (e.g., a refresh token) or associated metadata—and attempted to decrypt it. The critical part of the message is "with key:AdalKey," which specifies the identifier of the decryption key MSAL tried to use. The operation failed, meaning the data could not be decrypted using this particular key.  
The Strong Linkage to Legacy ADAL:  
The term "AdalKey" unequivocally links this error to the Azure Active Directory Authentication Library (ADAL). ADAL was Microsoft's previous-generation library for authenticating against Azure Active Directory and has since been deprecated in favor of MSAL.1 Microsoft officially ended all support and development for ADAL, including security fixes, on June 30, 2023, and no new features had been added since June 30, 2020.2 Applications still using ADAL are encouraged to migrate to MSAL to leverage the latest security features and ensure continued support.1  
ADAL, during its operational lifetime, stored its tokens and the cryptographic keys used to protect them within the application's private storage on the Android device. A common mechanism for storing key metadata and some token information was Android's SharedPreferences system. The "AdalKey" identifier is characteristic of a naming convention that would have been used by ADAL for these cryptographic materials.  
Why MSAL Might Encounter an "AdalKey":  
Several scenarios could lead to MSAL attempting to use an "AdalKey":

* **Scenario 1: Incomplete Cache Migration/Cleanup:** This is the most common scenario. If the application previously used ADAL and was subsequently migrated to MSAL, the migration process might not have thoroughly removed all data persisted by ADAL. Consequently, MSAL, when scanning for cached tokens, might discover these old ADAL entries.  
* **Scenario 2: Shared Cache Identifiers (Less Likely but Possible):** In some specific, perhaps older or transitional, versions of the libraries or configurations, there might have been an unintentional overlap in how cache entries were indexed or identified. This could theoretically lead MSAL to mistakenly pick up an ADAL entry.  
* **Scenario 3: Broker Interaction (If Applicable):** If an authentication broker (like Microsoft Authenticator or Intune Company Portal) is involved in the authentication flow, and this broker was previously used by the same application with ADAL, the broker itself might hold onto ADAL-formatted tokens or keys. However, the error "AdalKey" typically points to issues within the client-side library's local cache rather than a broker-mediated error, though interactions can be complex.

The Decryption Failure Itself:  
MSAL employs its own set of cryptographic mechanisms for securing tokens. If MSAL attempts to use a key explicitly named "AdalKey," or tries to decrypt a token that was originally encrypted by ADAL using ADAL's specific algorithms and key formats, this operation is highly likely to fail. The failure can stem from several reasons:  
\* The format or cryptographic algorithm used by ADAL for the key or token is incompatible with MSAL's decryption routines.  
\* The "AdalKey" material itself might be corrupted on the device.  
\* The key might be present, but MSAL may lack the specific permissions or context (e.g., Keystore alias or protection level) that ADAL used to access and utilize it.  
The error is not simply "token not found" or "invalid token format," although such errors might occur subsequently if decryption were bypassed or handled differently. It is specifically a *decryption failure* involving a *named key*. This implies that MSAL has successfully identified a piece of data it believes to be a token (or related metadata) and has also identified a specific key ("AdalKey") that it presumes should be used for its decryption. The breakdown occurs at the cryptographic operation level itself. This is a more nuanced issue than merely possessing a malformed or expired token; it points directly to an incompatibility or corruption related to the encryption key or its usage. Therefore, any effective solution must address not just the removal of potentially stale tokens but also the neutralization or removal of these legacy cryptographic key references that MSAL cannot correctly process.  
**Table: ADAL vs. MSAL Key Cache Characteristics**  
To further illustrate why MSAL might struggle with ADAL-originated keys, the following table highlights key differences in their caching and cryptographic approaches:

| Feature | ADAL Details | MSAL Details | Potential Conflict Point |
| :---- | :---- | :---- | :---- |
| **Key Storage Mechanism** | Primarily SharedPreferences for key metadata; may use Android Keystore. | Utilizes SharedPreferences, potentially more robust Android Keystore integration for key protection. | MSAL might not recognize or be able to correctly utilize ADAL's specific Keystore aliases or SharedPreferences structures for keys. |
| **Encryption Algorithms** | Used specific symmetric/asymmetric algorithms prevalent at the time. | Employs modern, standardized cryptographic algorithms; may differ from ADAL's choices. | Algorithm mismatch means MSAL cannot decrypt data encrypted by ADAL, even if the raw key material ("AdalKey") is found. |
| **Token Format** | Tokens (especially refresh tokens) stored with ADAL-specific metadata. | Tokens stored with MSAL-specific metadata and potentially different wrapping/encryption. | MSAL might misinterpret the structure of an ADAL-cached token, leading to errors before or during decryption. |
| **Key Naming/Identification** | May use specific prefixes or names like "AdalKey" for SharedPreferences entries or Keystore aliases. | Uses its own internal naming conventions and management for cryptographic keys. | MSAL encountering an entry named "AdalKey" might trigger an attempt to use it, but with MSAL's incompatible decryption logic. |
| **Cache Structure** | Maintained its own cache schema, often in SharedPreferences. | Implements a distinct cache schema, designed for features like multiple accounts and incremental consent. | If MSAL attempts to parse an ADAL cache item due to a lingering entry, schema differences can lead to misinterpretation and subsequent decryption failure. |
| **Broker Interaction Cache** | Interacted with brokers using ADAL protocols. | Interacts with brokers using MSAL protocols, which are more advanced (e.g., for Conditional Access). | While the "AdalKey" error seems client-local, broker caches could also hold legacy ADAL tokens if not properly cleared during migration. |

Understanding these distinctions underscores the fundamental incompatibility. An "AdalKey," being a product of ADAL's ecosystem, is unlikely to be directly usable by MSAL, which operates under a different set of assumptions and cryptographic practices. This reinforces the hypothesis that the error stems from MSAL's attempt to process these incompatible legacy artifacts.

## **3\. Potential Root Causes and Diagnostic Steps**

The "Failed to decrypt with key:AdalKey" error, while specific in its wording, can stem from a few underlying conditions related to token caching and library migration. This section explores the most probable root causes and outlines diagnostic steps for each.  
**A. Lingering ADAL Artifacts (Most Probable)**

* **Explanation:** The most likely cause is that the application, or a library it depends upon, utilized ADAL at some point in its history. During the migration to MSAL, ADAL's cached data—particularly refresh tokens and the encryption keys used to protect them—were not completely purged from the device's persistent storage. Android applications commonly use SharedPreferences or internal files for such storage. MSAL, in its normal operation of trying to find cached tokens (e.g., for silent sign-on), stumbles upon these ADAL remnants.  
* **Evidence:** The error message itself, with the explicit "AdalKey," is the strongest indicator. The deprecation of ADAL and the strong recommendation to migrate to MSAL imply that many applications will have an ADAL history.2 Furthermore, general guidance often points to stale ADAL tokens or cookies as sources of authentication problems, even if the context in some documentation is broader than this specific decryption error.4  
* **Diagnostic Steps:**  
  1. **Review Application History:** Examine the application's version control history and release notes to determine if and when ADAL was used. Identify the version in which MSAL was introduced.  
  2. **Inspect SharedPreferences:** During development, use Android Studio's Device File Explorer to manually inspect the application's SharedPreferences files. Look for keys containing "adal," "AdalKey," or other patterns that might have been used by ADAL (e.g., com.microsoft.aad.adal.cache). This can be challenging without precise knowledge of ADAL's internal key naming conventions for Android, but "AdalKey" provides a significant lead.  
  3. **Examine Migration Code:** If custom code was written to handle the migration from ADAL to MSAL, review it meticulously to ensure it included comprehensive ADAL cache clearing logic.

**B. MSAL Cache Inconsistencies or Misinterpretation**

* **Explanation:** While MSAL is designed to manage its own cache effectively, there could be rare edge cases or bugs (especially in older MSAL versions) where it misinterprets an old ADAL entry as one of its own. This might happen if there's a shared identifier or if the cache structure has some subtle commonality that leads to confusion. The error then arises when MSAL attempts to decrypt this misidentified entry using its standard procedures but with a key reference ("AdalKey") that is incompatible with its cryptographic engine.  
* **Evidence:** MSAL's fundamental behavior involves caching tokens for silent acquisition.5 The error itself indicates that MSAL is actively trying to operate on an item it found within a cache.  
* **Diagnostic Steps:**  
  1. **Implement Enhanced Logging:** Add detailed logging around MSAL's silent token acquisition attempts (acquireTokenSilent). Log the accounts MSAL is aware of (getAccounts()) and the specific account/parameters it's using just before the error typically occurs.  
  2. **Verify MSAL Configuration:** Double-check the MSAL configuration file (e.g., msal\_config.json) and programmatic setup. Ensure that parameters like account\_mode (single vs. multiple), authority URLs, and redirect URIs are correctly defined and consistent, minimizing chances of conflict with any potential old configurations.6

**C. Issues with Token Encryption Keys**

* **Explanation:** This is a deeper aspect of "lingering ADAL artifacts." The "AdalKey" likely refers to an encryption key that ADAL itself used to protect tokens stored on the device (e.g., in SharedPreferences or using the Android Keystore). If this key is corrupted, has had its access permissions altered in a way MSAL cannot handle, or if MSAL is attempting to use it with an incompatible cryptographic algorithm (because it expects an MSAL-format key), decryption will inevitably fail.  
* **Diagnostic Steps:**  
  1. Directly diagnosing the state of a specific ADAL encryption key is very difficult without reverse-engineering ADAL's key storage mechanisms or having access to specific internal documentation from that library.  
  2. The primary diagnostic approach here is indirect: if ADAL remnants are suspected, attempts to remove any SharedPreferences entries that ADAL might have created for storing these keys (or their metadata) become a diagnostic step in themselves. If removal resolves the error, it points to this as the cause.

**D. External Cache Interference (Less Likely for "AdalKey" but worth noting)**

* **Explanation:** In some authentication scenarios, stale system-level credentials or browser cookies used by WebViews or Chrome Custom Tabs during the interactive phase of authentication can cause problems. While the "AdalKey" error points more directly to the on-device library cache (ADAL's or MSAL's interaction with it), broader cache issues can sometimes complicate the overall authentication state.  
* **Evidence:** General troubleshooting for web-based authentication flows often includes clearing browser cache, especially for embedded webviews.9 It's also noted that MSAL's removeAccount function, while clearing library tokens, does not remove session cookies from the browser.5  
* **Diagnostic Steps:**  
  1. As a general troubleshooting measure, programmatically clearing the WebView cache associated with the authentication authority URL might be attempted. However, this is unlikely to be the direct cause of an "AdalKey" *decryption* error, which is more specific to the library's handling of its own persisted encrypted data.

The user's observation that the error appears "without OS updates or app reinstallations" and that "sign-in functions correctly" is particularly telling. Successful interactive sign-in implies that the application can obtain fresh tokens from the identity provider. The "AdalKey" error, therefore, most likely surfaces during a *silent* token acquisition attempt (e.g., when the app calls acquireTokenSilent) or during an automatic background refresh. In these scenarios, MSAL attempts to use a token from its cache. If an old ADAL refresh token, or a token encrypted with the problematic "AdalKey," is present in the cache and MSAL attempts to use or decrypt it, the failure occurs. This strongly suggests that the investigation and solution should heavily focus on the silent authentication flow and the integrity and composition of the token cache that acquireTokenSilent consults. The core problem isn't the inability to acquire new tokens from the server, but rather the incorrect handling of old, incompatible cached ones originating from ADAL.

## **4\. Comprehensive Solutions and Mitigation Strategies**

Addressing the "Failed to decrypt with key:AdalKey" error requires a multi-faceted approach, primarily centered on meticulous cache cleaning. The strategies below aim to remove both MSAL-managed tokens and, crucially, the legacy ADAL artifacts that are the likely culprits.  
**A. Ensuring Complete ADAL Remnant Removal**  
Given that the "AdalKey" error directly points to ADAL, eliminating any lingering artifacts from this legacy library is the most critical step.

* Programmatic SharedPreferences Cleanup (Requires Extreme Caution):  
  ADAL for Android was known to use DefaultSharedPreferences with specific key patterns, some potentially related to identifiers like com.microsoft.aad.adal.cache or containing the string "adal" or even "AdalKey" itself. If such key patterns can be reliably identified through careful investigation (e.g., by examining an older version of the app that used ADAL, or through specific ADAL documentation if available), one could implement code to remove these specific entries from SharedPreferences. This operation should ideally be a one-time task, perhaps executed during an application update when migrating users or as a specific recovery routine.  
  *Example (Conceptual \- **Use with extreme caution and after thorough validation of key names**):*  
  Kotlin  
  fun removePotentialAdalKeys(context: Context) {  
      val sharedPreferences \= PreferenceManager.getDefaultSharedPreferences(context)  
      val editor \= sharedPreferences.edit()

      // Example: Hypothetical key names or prefixes used by ADAL  
      val adalKeyPatterns \= listOf("AdalKey", "adal.encryption.key", "com.microsoft.aad.adal") 

      sharedPreferences.all.keys.forEach { key \-\>  
          adalKeyPatterns.any { pattern \-\> key.contains(pattern, ignoreCase \= true) }?.let {  
              Log.w("ADAL\_Cleanup", "Removing potential ADAL SharedPreferences key: $key")  
              editor.remove(key)  
          }  
      }  
      editor.apply()  
  }

  **Warning:** This approach is inherently risky. Incorrectly identifying and removing SharedPreferences keys can lead to data loss for other application functionalities or even break MSAL if there are unexpected overlaps. This should only be attempted after exhaustive testing and with a high degree of confidence in the identified ADAL key patterns. No universally safe, programmatic ADAL cache cleanup method is provided by MSAL itself for Android.  
* Manual Cache Clearing (User-Initiated or Developer Testing):  
  The most straightforward, albeit blunt, method to eliminate all local storage, including any ADAL remnants, is to clear the application's data and cache via the Android operating system settings (Settings \-\> Apps \-\> \[Your App\] \-\> Storage \-\> Clear Cache & Clear Data).10 While effective, this is disruptive to the user as it resets the application to a fresh install state. It can be a valuable diagnostic tool for developers or a last-resort solution for users experiencing persistent issues.

**B. Robust MSAL Cache Management**  
Ensuring MSAL's own cache is clean and correctly managed is fundamental. MSAL clears its token cache by removing accounts.5

* Removing All Accounts from MSAL (Kotlin Examples):  
  The method for removing accounts differs slightly depending on whether the application is configured for single or multiple accounts.7  
  * For IMultipleAccountPublicClientApplication:  
    It's necessary to first retrieve all known accounts and then iterate through them, removing each one.  
    Kotlin  
    // Assuming 'mMultipleAccountApp' is your IMultipleAccountPublicClientApplication instance  
    // and 'context' is available.  
    mMultipleAccountApp.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {  
        override fun onTaskCompleted(result: List\<IAccount\>?) {  
            if (result.isNullOrEmpty()) {  
                Log.d("MSAL\_CACHE", "No accounts found in MSAL cache to remove.")  
                return  
            }  
            result.forEach { account \-\>  
                mMultipleAccountApp.removeAccount(account,  
                    object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {  
                        override fun onRemoved() {  
                            Log.i("MSAL\_CACHE", "Successfully removed account: ${account.username}")  
                        }  
                        override fun onError(exception: MsalException) {  
                            Log.e("MSAL\_CACHE", "Error removing account ${account.username}: ${exception.message}", exception)  
                        }  
                    })  
            }  
        }  
        override fun onError(exception: MsalException) {  
            Log.e("MSAL\_CACHE", "Error loading accounts for removal: ${exception.message}", exception)  
        }  
    })

    This approach is derived from the removeAccount functionality shown in MSAL examples 8 and the general principle of iterating through accounts to clear them, similar to logic described for MSAL Java.11  
  * For ISingleAccountPublicClientApplication:  
    The signOut() method is used, which removes the signed-in account and its associated cached tokens from the application.7  
    Kotlin  
    // Assuming 'mSingleAccountApp' is your ISingleAccountPublicClientApplication instance  
    // and 'context' is available.  
    mSingleAccountApp.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {  
        override fun onSignOut() {  
            Log.i("MSAL\_CACHE", "Single account signed out and associated cache cleared.")  
        }  
        override fun onError(exception: MsalException) {  
            Log.e("MSAL\_CACHE", "Error during single account sign out: ${exception.message}", exception)  
        }  
    })

* Clearing Browser/WebView Cache (If Deemed Relevant):  
  While less likely to be the direct cause of an "AdalKey" decryption error within the MSAL library's cache, clearing browser or WebView cache can be a general hygiene step, especially if the authentication flow involves web components. MSAL's removeAccount does not clear browser session cookies.5 General discussions on authentication issues sometimes recommend clearing browser cache.9  
  Kotlin  
  import android.webkit.CookieManager  
  import android.webkit.WebView  
  //...  
  fun clearWebViewCache(context: Context) {  
      // This is a general approach; specific WebView instances might need direct handling.  
      val webView \= WebView(context) // Temporary instance for global actions if applicable  
      webView.clearCache(true)  
      webView.clearHistory()

      val cookieManager \= CookieManager.getInstance()  
      cookieManager.removeAllCookies(null) // Clears all cookies  
      cookieManager.flush() // Ensures changes are written to persistent storage  
      Log.i("CACHE\_CLEANUP", "WebView cache and cookies cleared.")  
  }

**C. Implementing a "Clear Cache and Restart" Option**  
Consider providing a mechanism within the application, perhaps in a debug menu for developers or as a guided recovery flow if the "AdalKey" error is detected programmatically. This function would:

1. Execute the MSAL account removal logic (as shown above).  
2. If deemed safe and patterns are known, attempt the ADAL SharedPreferences cleanup.  
3. Perform WebView cache clearing.  
4. Prompt the user to restart the application or guide them through a fresh sign-in process.

**D. Logging and Monitoring**  
After implementing any cache clearing mechanisms, it is crucial to have robust logging in place:

* Log the outcome of all cache clearing operations.  
* Log details around acquireTokenSilent calls, including the account being used.  
* Log the list of accounts returned by getAccounts() before attempting silent acquisition to see if unexpected or malformed accounts are present.  
* Capture and log any exceptions from MSAL, paying close attention to error codes and messages.12 This will help determine if the "AdalKey" error persists and under what conditions.

The explicit reference to "AdalKey" underscores that MSAL's standard removeAccount or signOut functions, which primarily target MSAL's own cache structure 5, might not be sufficient if the root problem lies in ADAL-specific artifacts stored outside of MSAL's direct control (e.g., in uniquely named SharedPreferences files or entries that MSAL does not manage). Therefore, a comprehensive solution must adopt a multi-pronged cache clearing strategy that addresses:

1. MSAL's current accounts and tokens.  
2. Potentially, ADAL's old tokens and, critically, encryption keys or key references residing in SharedPreferences or other app-private storage not automatically managed or cleaned up by MSAL's standard cache clearing routines.  
3. The state of browser or WebView components used during authentication, as a secondary but potentially complicating factor. This implies that relying solely on MSAL's built-in cache clearing mechanisms might not resolve the issue if the core of the problem is truly ADAL remnants. A more targeted, and potentially more invasive (if not executed with precision), cleanup of ADAL-specific storage locations may be required.

**Table: Cache Clearing Methods and Their Scope**  
This table provides a clearer understanding of what each cache clearing action achieves and its limitations, particularly concerning ADAL versus MSAL artifacts.

| Method/Action | Target | Mechanism | Notes/Limitations |
| :---- | :---- | :---- | :---- |
| IMultipleAccountPublicClientApplication.removeAccount() | MSAL tokens (access, refresh, ID) for the specified account. | MSAL API call. | Does not clear ADAL-specific artifacts from SharedPreferences or browser cookies.5 Effective for resetting MSAL's state for that account. |
| ISingleAccountPublicClientApplication.signOut() | MSAL tokens for the single signed-in account. | MSAL API call. | Similar to removeAccount for multi-account; does not clear ADAL artifacts or browser cookies.7 |
| Programmatic SharedPreferences Removal (Targeted ADAL Keys) | Specific ADAL key/token entries in SharedPreferences (e.g., "AdalKey"). | Android SharedPreferences API (edit().remove().apply()). | **Highly targeted, potentially risky if patterns are incorrect or overly broad.** No direct MSAL API for this. Must be implemented with extreme care by the app developer. |
| Manual App Data Clear (via Android Settings) | All app data: MSAL & ADAL caches, SharedPreferences, databases, files, etc. | OS-level action by the user. | Drastic but comprehensive. Guarantees removal of all local library artifacts.10 User loses all app settings and data. |
| WebView Cache/Cookie Clearing | Cookies, cached pages, history within WebView/Custom Tabs. | Android WebView and CookieManager APIs. | Addresses potential issues from the web-based part of the auth flow. Unlikely to directly fix "AdalKey" decryption error but good for general auth hygiene.9 |

## **5\. Best Practices for MSAL Implementation in Kotlin/Android**

Adhering to best practices during MSAL integration and usage can prevent many common issues, including those related to cache inconsistencies, although the "AdalKey" error is specifically tied to legacy ADAL interactions.  
**A. Proper MSAL Initialization and Configuration**

* **Configuration File:** Ensure the msal\_config.json file is correctly structured and contains accurate information. This includes the client\_id, redirect\_uri (which must match one registered in the Azure portal), and authorities array.6 The redirect\_uri is particularly sensitive to signature hash mismatches, especially after publishing to the Google Play Store, as Google may re-sign the app with a different certificate.14 While a signature hash mismatch typically presents a different error, correct configuration is foundational.  
* **Account Mode:** Carefully choose between SINGLE and MULTIPLE for the account\_mode in your msal\_config.json. This dictates whether you use ISingleAccountPublicClientApplication or IMultipleAccountPublicClientApplication and affects how accounts are managed and cached.7 Consistency between configuration and the PublicClientApplication type instantiated is crucial.  
* **Signature Hashes:** When registering the Android platform in your Azure app registration, ensure the signature hash(es) are correctly generated and added. If issues arise after Play Store deployment, it's often due to Google Play App Signing changing the signature. A new hash needs to be generated from the Google Play Console and added to the Azure app registration.14 While this usually causes redirect URI mismatch errors rather than "AdalKey" decryption failures, it's a common point of failure in MSAL Android setup.

**B. Effective Error Handling**

* **Comprehensive Callbacks:** Implement the onError methods within all MSAL AuthenticationCallback, LoadAccountsCallback, RemoveAccountCallback, and SignOutCallback interfaces.6 These callbacks provide MsalException objects containing valuable diagnostic information.  
* **Distinguish Error Types:** MSAL throws various specific exceptions (e.g., MsalUiRequiredException, MsalClientException, MsalServiceException).12 Code should differentiate between errors that require user interaction (like MsalUiRequiredException, indicating silent token acquisition failed and interactive auth is needed) and more severe or unexpected errors.  
* **Detailed Logging:** Log the error codes (e.g., INVALID\_GRANT, NO\_ACCOUNT\_FOUND), messages, and stack traces provided by MsalException. This is invaluable for troubleshooting.12

**C. Managing Token Lifecycles**

* **Prioritize Silent Acquisition:** Always attempt to acquire tokens silently using acquireTokenSilent before resorting to interactive acquisition (acquireToken). This leverages cached tokens, provides a better Single Sign-On (SSO) experience, and reduces unnecessary user prompts.5  
* **Handle MsalUiRequiredException:** When acquireTokenSilent fails with an MsalUiRequiredException, it's a signal that interactive authentication is necessary (e.g., user consent needed, token expired and unrefreshable silently, conditional access policy requires interaction). The application should gracefully fall back to calling acquireToken.  
* **Token Expiration and Refresh:** MSAL generally handles the complexity of access token expiration and refresh token usage. However, the "AdalKey" error might be surfacing precisely when MSAL attempts to use a cached refresh token that originated from ADAL and is encrypted with the incompatible "AdalKey."

**D. Thorough Migration from ADAL (If Applicable)**

* **Complete API Replacement:** If the application has a history with ADAL, the migration to MSAL must involve replacing all ADAL API calls with their MSAL equivalents. Microsoft has provided migration guides for various platforms.2  
* **Explicit Cache Invalidation:** Crucially, the migration process should have included steps to invalidate or remove ADAL's cached data. ADAL's deprecation means its artifacts are no longer supported and can cause conflicts.1 Official migration guides often focus on API mapping, but the responsibility for cleaning up old ADAL-specific persistent storage (like particular SharedPreferences entries) might fall to the application developer, as MSAL's standard cache clearing might not target these legacy stores.

Many applications might only implement cache clearing as part of a signOut function, which typically clears the cache for the currently active user. However, the nature of the "AdalKey" error suggests that problematic ADAL artifacts might persist even across user sessions, or if a previous sign-out process was incomplete or did not target ADAL-specific storage. This points towards the potential need for a more proactive cache management strategy. For instance, a one-time cleanup routine could be implemented to run upon application startup immediately after an update that introduces MSAL for the first time, or after an update designed to address this specific "AdalKey" issue. Such a routine would specifically target known or suspected ADAL cache locations. This proactive "deep clean" approach, executed conditionally, could be more effective at eradicating deeply embedded legacy artifacts than relying solely on reactive cache clearing tied to user sign-out events.

## **6\. Troubleshooting and Further Investigation**

When the "Failed to decrypt with key:AdalKey" error persists despite initial remediation attempts, a more profound investigation is warranted. The following steps can help pinpoint the issue's specifics.  
**A. Enhanced Logging**

* **MSAL Internal Logging:** Check the documentation for the specific version of MSAL for Android being used to see if it offers a mechanism for enabling more verbose internal logging. This can sometimes provide deeper insights into the library's operations leading up to the error.  
* **Account State Logging:** Before any call to acquireTokenSilent, log the complete list of accounts returned by mMultipleAccountApp.getAccounts() or the current account from mSingleAccountApp.getCurrentAccount(). Examine this output for any unexpected, null, or seemingly malformed account entries that might correspond to the problematic ADAL artifact.  
* **Parameter Logging:** Log the exact parameters being passed to acquireTokenSilent, including the scopes and the IAccount object. Ensure these are as expected.

**B. Analyzing SharedPreferences**

* **Direct Inspection (Development/Debug Builds):** During development, leverage Android Studio's Device File Explorer to navigate to the application's data directory (/data/data/YOUR\_PACKAGE\_NAME/shared\_prefs/). Open the XML files representing the SharedPreferences and meticulously search for any keys that:  
  * Contain the string "adal" (case-insensitive).  
  * Contain the string "AdalKey" (case-insensitive).  
  * Match known ADAL caching patterns (e.g., com.microsoft.aad.adal.cache).  
  * Appear to store encrypted data or key material that doesn't align with MSAL's expected patterns.  
* **Caution with Deletion:** While direct inspection can reveal potential culprits, programmatic deletion of broad SharedPreferences patterns in production code must be approached with extreme caution. Hardcoding the deletion of, for example, all keys containing "adal" could inadvertently remove legitimate SharedPreferences used by other parts of the app or by other libraries, leading to data loss or unintended side effects. Any such cleanup should be highly targeted and thoroughly tested.

**C. Reviewing MSAL Library Version**

* Ensure the application is using a recent and stable version of the MSAL for Android library (com.microsoft.identity.client:msal). Older versions might have had different behaviors, bugs related to cache management, or less robust handling of interactions with environments where ADAL was previously used. Check the MSAL Android GitHub repository for release notes and known issues.

**D. Checking for Multi-Process Issues (If Applicable)**

* If the application utilizes multiple Android processes that all interact with MSAL, ensure that token caching is handled correctly and is process-safe. MSAL's default in-memory cache is typically per PublicClientApplication instance. If multiple instances are created in different processes without a properly configured shared caching strategy (if such a feature is supported and explicitly configured for the MSAL version in use), cache inconsistencies can arise. While this is a more advanced and generally less likely scenario to directly cause an "AdalKey" decryption error (which points to a specific legacy key), complex multi-process interactions can sometimes exacerbate underlying cache corruption issues. General MSAL error handling documentation covers a range of exceptions but may not dive deep into multi-process specifics for Android cache.4

A significant challenge in definitively resolving the "AdalKey" issue is the somewhat opaque nature of ADAL's historical cache implementation on Android. The available documentation and research snippets do not provide a definitive, exhaustive map of ADAL's internal cache structure or its exact key naming conventions within SharedPreferences for all its versions and configurations. This makes any programmatic cleanup of ADAL remnants somewhat speculative, often relying on educated guesses based on the "AdalKey" string itself, general knowledge of ADAL's behavior, or patterns observed in isolated test environments. This "black box" aspect means that if direct programmatic cleanup of ADAL-specific SharedPreferences entries is attempted, it must be predicated on very careful investigation—such as examining the SharedPreferences of a device known to have used the application with ADAL, or by analyzing old ADAL source code if accessible. Otherwise, relying on comprehensive MSAL account removal (which cleans MSAL's portion of the cache) and, as a more drastic measure, a full application data clear by the user, are safer, albeit potentially less targeted, approaches to ensure the problematic "AdalKey" is no longer encountered.

## **7\. Conclusion and Recommendations**

The "Failed to decrypt with key:AdalKey" error in a Kotlin Android application using MSAL is a strong indicator of a conflict with legacy components from the Azure Active Directory Authentication Library (ADAL). The persistence of this error, even with functional sign-in, points to issues within the token caching mechanism, specifically when MSAL encounters and attempts to process cryptographic artifacts (tokens or keys) originating from ADAL.  
**Recap of Key Findings:**

* The error message "Failed to decrypt with key:AdalKey" is almost certainly caused by MSAL attempting to decrypt a cached token or key metadata using an encryption key or token format associated with the deprecated ADAL library.  
* This issue typically manifests during silent token acquisition (acquireTokenSilent) or background token refresh operations, where MSAL consults its cache. Interactive sign-in may work correctly because it fetches fresh tokens from the identity provider, bypassing the problematic cached ADAL artifact.  
* The root cause is likely lingering ADAL data in the application's persistent storage (e.g., SharedPreferences) due to an incomplete migration from ADAL to MSAL or insufficient cleanup of old ADAL cache entries.

**Most Effective Solutions:**

1. **Comprehensive MSAL Account Removal:** The first and primary step is to thoroughly remove all accounts managed by MSAL.  
   * For applications using IMultipleAccountPublicClientApplication, retrieve all accounts via getAccounts() and then iterate through the list, calling removeAccount() for each one.  
   * For applications using ISingleAccountPublicClientApplication, call signOut(). This ensures MSAL's own cache is cleared of any accounts it actively manages.  
2. **Cautious, Targeted ADAL Remnant Cleanup (If Necessary and Feasible):** If the "AdalKey" error persists after clearing MSAL accounts, and if there's strong evidence or reliable identification of ADAL's specific SharedPreferences key patterns (e.g., those containing "AdalKey" or known ADAL prefixes), a *cautious, one-time* programmatic cleanup of these specific SharedPreferences entries could be considered. This is a high-risk operation and must be preceded by thorough investigation and testing to avoid unintended data loss.  
3. **Manual App Data Clearing (Fallback):** As a last resort for users, or a definitive diagnostic step for developers, guiding users to clear the application's data and cache via Android system settings will eradicate all local storage, including any ADAL and MSAL artifacts. This is disruptive but effective.

**Emphasis on Proactive Cache Management and Thorough ADAL Migration:**

* **Robust Sign-Out Procedures:** Implement sign-out logic that diligently removes all relevant MSAL accounts, ensuring the MSAL cache is appropriately cleaned for the departing user.  
* **Thorough ADAL Migration:** If the application has a history of using ADAL, any migration process to MSAL should have included explicit steps to clean up ADAL's persistent storage. This goes beyond simply changing API calls and involves actively removing ADAL-specific cache files or SharedPreferences entries.  
* **Library Updates:** Regularly review and update the MSAL for Android library to the latest stable version. Newer versions often include bug fixes, performance improvements, and enhanced security, which might also cover more robust cache handling or better isolation from legacy library states.

**Final Recommendation:**  
The recommended course of action is to begin with the least invasive but most MSAL-idiomatic solution: **implementing a thorough removal of all MSAL accounts**. This should be triggered either as part of a sign-out flow or as a specific "reset authentication" feature if the error is detected.  
If the "AdalKey" error continues to appear, the next step involves a more in-depth investigation into **targeted SharedPreferences cleanup for known or strongly suspected ADAL key patterns**. This must be approached with extreme care, ideally with patterns confirmed from an environment where ADAL was active. This cleanup should be designed as a one-off migration or recovery step.  
Throughout this process, **implement robust error logging and monitoring** around MSAL operations. This will provide critical data on when and how the error occurs, and whether the implemented solutions are effective. If the issue remains intractable, engaging with Microsoft support with detailed logs and diagnostic information would be the subsequent step.

#### **Works cited**

1. Get a complete list of apps using ADAL in your tenant, accessed June 6, 2025, [https://docs.azure.cn/en-us/entra/identity-platform/howto-get-list-of-all-auth-library-apps](https://docs.azure.cn/en-us/entra/identity-platform/howto-get-list-of-all-auth-library-apps)  
2. Migrate to the Microsoft Authentication Library (MSAL) | Azure Docs, accessed June 6, 2025, [https://docs.azure.cn/en-us/entra/identity-platform/msal-migration](https://docs.azure.cn/en-us/entra/identity-platform/msal-migration)  
3. Migrate applications to the Microsoft Authentication Library (MSAL), accessed June 6, 2025, [https://learn.microsoft.com/en-us/entra/identity-platform/msal-migration](https://learn.microsoft.com/en-us/entra/identity-platform/msal-migration)  
4. Entra ID authentication troubleshooting: Known problems and solutions \- UW-IT, accessed June 6, 2025, [https://uwconnect.uw.edu/it?id=kb\_article\_view\&sysparm\_article=KB0034064](https://uwconnect.uw.edu/it?id=kb_article_view&sysparm_article=KB0034064)  
5. Acquire and cache tokens using the Microsoft Authentication Library (MSAL), accessed June 6, 2025, [https://learn.microsoft.com/en-us/entra/identity-platform/msal-acquire-cache-tokens](https://learn.microsoft.com/en-us/entra/identity-platform/msal-acquire-cache-tokens)  
6. Azure AD B2C (MSAL Android) | Azure Docs, accessed June 6, 2025, [https://docs.azure.cn/en-us/entra/identity-platform/msal-android-b2c](https://docs.azure.cn/en-us/entra/identity-platform/msal-android-b2c)  
7. hamiltonha/msal-android-tutorial \- GitHub, accessed June 6, 2025, [https://github.com/hamiltonha/msal-android-tutorial](https://github.com/hamiltonha/msal-android-tutorial)  
8. Azure-Samples/ms-identity-android-kotlin: Microsoft Authentication Library sample for Kotlin \- GitHub, accessed June 6, 2025, [https://github.com/Azure-Samples/ms-identity-android-kotlin](https://github.com/Azure-Samples/ms-identity-android-kotlin)  
9. Android: clear cache/cookies of embedded browser (Microsoft 365\) : r/techsupport \- Reddit, accessed June 6, 2025, [https://www.reddit.com/r/techsupport/comments/1jw4q7v/android\_clear\_cachecookies\_of\_embedded\_browser/](https://www.reddit.com/r/techsupport/comments/1jw4q7v/android_clear_cachecookies_of_embedded_browser/)  
10. How to clear the application cache programmatically \- Quora, accessed June 6, 2025, [https://www.quora.com/How-can-I-clear-the-application-cache-programmatically](https://www.quora.com/How-can-I-clear-the-application-cache-programmatically)  
11. Get & remove accounts from the token cache (MSAL4j) \- Microsoft ..., accessed June 6, 2025, [https://learn.microsoft.com/en-us/entra/msal/java/advanced/msal-java-get-remove-accounts-token-cache](https://learn.microsoft.com/en-us/entra/msal/java/advanced/msal-java-get-remove-accounts-token-cache)  
12. Errors and exceptions (MSAL Android) | Azure Docs, accessed June 6, 2025, [https://docs.azure.cn/en-us/entra/identity-platform/msal-android-handling-exceptions](https://docs.azure.cn/en-us/entra/identity-platform/msal-android-handling-exceptions)  
13. Handle errors and exceptions in MSAL for Android \- Learn Microsoft, accessed June 6, 2025, [https://learn.microsoft.com/en-us/entra/msal/android/handling-exceptions](https://learn.microsoft.com/en-us/entra/msal/android/handling-exceptions)  
14. Android App Authentication Fails After Being Published to Google Play Store, accessed June 6, 2025, [https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/app-integration/android-app-authentication-fails-after-published-to-google-play-store?source=recommendations](https://learn.microsoft.com/en-us/troubleshoot/entra/entra-id/app-integration/android-app-authentication-fails-after-published-to-google-play-store?source=recommendations)