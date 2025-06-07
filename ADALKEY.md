## Investigation of MSAL "AdalKey" Decryption Errors

This document summarizes the findings regarding the "Failed to decrypt with key:AdalKey" errors
observed in the application logs during MSAL operations.

### Observed Behavior

The application logs show recurring warnings related to MSAL's inability to decrypt a cache entry
associated with "AdalKey". Despite these warnings, both interactive and silent user authentication
processes complete successfully.

### Relevant Log Snippets

The core errors observed are:

```
2025-06-06 22:38:26.174 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.175 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.clientId.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.176 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.176 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.target.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.177 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.177 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.homeAccountId.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.178 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.178 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.givenName.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.179 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.179 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.familyName.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.180 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.181 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.uid.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.181 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.182 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.utid.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.182 MSAL_Stora...r#:decrypt net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt with key:AdalKey thumbprint : v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
2025-06-06 22:38:26.183 MSAL_Share...:getString net.melisma.mail W ContainsPII: false - Message: [2025-06-07 05:38:26 - thread_id: 2110, ...] Failed to decrypt value with name: User.username.v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA
```

### Analysis and Probable Cause

1. **Benign Warning:** The key finding is that these "Failed to decrypt value" messages,
   specifically concerning "AdalKey", appear to be **benign warnings** in the context of this
   application. This is strongly supported by the fact that both interactive and silent
   authentication flows complete successfully.

2. **MSAL Cache Handling:**
    * MSAL (Microsoft Authentication Library) for Android stores its token cache and account
      information in `SharedPreferences`, encrypted for security.
    * The error message `MSAL_StorageEncrypter:decrypt` indicates that MSAL's internal
      `StorageEncrypter` component is attempting to decrypt a piece of data from this cache.
    * The log message `MSAL_SharedPrefManager:getString` confirms that this operation is part of
      retrieving a string value from the shared preferences cache.

3. **The "AdalKey" Connection:**
    * The term "AdalKey" strongly points towards a connection with **ADAL (Azure Active Directory
      Authentication Library)**, which is the predecessor to MSAL.
    * It's highly probable that "AdalKey" refers to an encryption key or a set of cached items that
      were either:
        * Created by a previous version of ADAL if the app was migrated from ADAL to MSAL.
        * Part of MSAL's internal mechanism to check for or migrate legacy ADAL cache items.
        * An internal MSAL cache key that, for historical or compatibility reasons, uses the "Adal"
          prefix.

4. **Graceful Error Handling by MSAL:**
    * Research into MSAL's behavior (specifically referencing discussions around GitHub issue #495
      in the `AzureAD/microsoft-authentication-library-for-android` repository and a related fix in
      `microsoft-authentication-library-common-for-android#481`) indicates that newer versions of
      MSAL are designed to handle such decryption failures more gracefully.
    * The intended behavior is: **"Values which cannot be successfully decrypted should be removed
      with a warning logged."**
    * This aligns perfectly with the observed behavior: a warning is logged, but authentication
      proceeds, suggesting MSAL discards the unreadable "AdalKey"-related entry and continues with
      other valid cached data or fetches new tokens as needed.

5. **Why Decryption Might Fail for "AdalKey":**
    * **Cache from a Different App/SDK Version:** If the "AdalKey" entry was written by an older
      version of ADAL, or a very different version of MSAL, the encryption method or key might be
      incompatible.
    * **Corrupted Cache Entry:** The specific cache entry associated with "AdalKey" might have
      become corrupted.
    * **No Actual ADAL Data:** MSAL might be probing for ADAL data (as part of a migration check)
      using a default "AdalKey," find no such data or an incompatible placeholder, and log a failure
      for that specific check before determining no actual migration is needed.
    * **KeyStore Issues (Less Likely if Only AdalKey Fails):** While Android KeyStore issues can
      cause widespread decryption problems, the fact that only "AdalKey" decryption is failing (and
      other MSAL operations like silent token refresh work) makes a general KeyStore failure less
      probable. It points to an issue with this specific cache entry/key.

6. **Thumbprint:** The "thumbprint" (`v9V1PzVnYnRG1JZQEJBzGPtzoYG5uKkNfD3iKHJGytA` in the logs)
   associated with "AdalKey" is likely an identifier for the specific key or data MSAL is trying to
   access or decrypt. The repeated failure with the same thumbprint indicates a persistent issue
   with that particular cache entry.

### Conclusion

The "Failed to decrypt with key:AdalKey" errors are warnings logged by MSAL when it encounters a
specific, non-critical cache entry (likely related to legacy ADAL data or migration checks) that it
cannot decrypt. Because primary authentication functionalities (interactive and silent sign-in)
remain unaffected, it indicates that the version of MSAL in use is handling this scenario by logging
the issue, potentially discards the problematic entry, and continues normal operations.

The issue is likely self-contained to this legacy or auxiliary cache key and does not impede current
user authentication. No direct debugging action seems necessary *for authentication to work*, as
MSAL appears to be managing the situation correctly. If one wanted to be absolutely thorough,
clearing the app's cache *once* might remove these legacy entries, but this is generally not
required if functionality is not impaired.

Further investigation into the precise definition or origin of "AdalKey" within MSAL's source code
could provide more definitive answers, but from a functional perspective, the issue appears to be
cosmetic in this instance. 