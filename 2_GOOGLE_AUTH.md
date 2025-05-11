# **Modernizing Android Authentication: A Guide to Gmail OAuth with Credential Manager and
AuthorizationClient**

## **1\. Executive Summary: Modernizing Android Authentication for Your Email App**

For Android email applications targeting Gmail OAuth integration, the recommended approach as of May
2025 centers on a two-component strategy. Firstly, for user authentication,
androidx.credentials.CredentialManager coupled with GetSignInWithGoogleOption should be utilized to
facilitate the initial user sign-in and obtain a Google ID Token. Secondly, for authorization and
enabling offline access to Gmail APIs, the
com.google.android.gms.auth.api.identity.AuthorizationClient is employed. This client requests
specific Gmail API scopes, including those necessary for offline access, and retrieves a
serverAuthCode. This code is then dispatched to a backend server, which exchanges it with Google's
services for access and refresh tokens.1  
This methodology aligns with Google's contemporary recommendations, offering a unified sign-in user
experience while clearly demarcating authentication from authorization, thereby enhancing clarity
and security.2 It leverages modern Android Jetpack libraries, ensuring up-to-date practices. A
significant aspect of this approach is the deliberate separation of proving a user's identity (
authentication) from granting permissions to access specific data or APIs (authorization).
Historically, legacy Google Sign-In mechanisms often bundled scope requests with the initial
sign-in.4 However, current Google guidelines and the design of AuthorizationClient advocate for
distinct flows.1 This separation allows applications to request permissions in a more granular and
context-sensitive manner, typically when a user attempts to use a feature that necessitates such
access, rather than presenting a large, potentially overwhelming list of permission requests
upfront. This improves user trust and comprehension of why specific permissions are needed.1
Credential Manager is designated for sign-up and sign-in functionalities 2, whereas
AuthorizationClient is specifically for "authorizing actions that need access to user data stored by
Google".1  
Regarding token management, the Android application bears the responsibility for securely storing
the OAuth refresh and access tokens received from its backend (after the backend has exchanged the
serverAuthCode). The established best practice for this is to encrypt these tokens using the Android
Keystore system and then store the encrypted data in SharedPreferences or a local database.7 It is
important to note that Credential Manager itself does not store these application-specific OAuth
tokens; its role is confined to managing the initial authentication credentials. The deprecation of
older authentication methods further underscores the importance of adopting this modern, bifurcated
approach.2

## **2\. Leveraging Credential Manager for Google Sign-In**

As of May 2025, androidx.credentials.CredentialManager has become the standard for handling user
authentication in Android applications, including "Sign in with Google." Its adoption is driven by
several key advantages over legacy systems.

### **Why Credential Manager is the Standard (May 2025\)**

Credential Manager offers a **unified API**, simplifying development by providing a single interface
for diverse sign-in methods such as traditional username/password, modern passkeys, and federated
identity solutions like "Sign in with Google".9 This unification also contributes to a more *
*consistent user experience** across different authentication mechanisms.  
Crucially, Credential Manager serves as the **successor to several legacy APIs**. It explicitly
replaces the deprecated Google Sign-In for Android library (specifically, the
com.google.android.gms:play-services-auth components used for sign-in), Smart Lock for Passwords,
and the One Tap sign-in mechanism.2 With these older APIs either already shut down or slated for
removal in 2025, migration to Credential Manager is essential for maintaining app functionality and
security.4  
The user experience is enhanced through a **streamlined, often one-tap, sign-in process**.
Credential Manager can intelligently present saved credentials, deduplicate sign-in methods for the
same account, and incorporates support for modern, phishing-resistant passkeys.3 Furthermore, it
exhibits broad **backward compatibility**, supporting username/password and federated sign-in
methods on Android 4.4 (API level 19\) and higher, with passkey functionality available from Android
9 (API level 28\) onwards.9  
To integrate Credential Manager, applications need to include the androidx.credentials:credentials
dependency. For devices running Android 13 and below, the androidx.credentials:
credentials-play-services-auth dependency is also typically required to enable support from Google
Play services.9

### **Integrating Credential Manager in Kotlin & Jetpack Compose**

When building with Kotlin and Jetpack Compose, an instance of CredentialManager is obtained via
CredentialManager.create(context).12 Within a Composable function, it is advisable to use the
remember block to prevent unnecessary recreation of the CredentialManager instance during
recompositions. Asynchronous operations, such as credentialManager.getCredential(), are suspend
functions and should be invoked from a coroutine, typically launched using LaunchedEffect within a
Composable or from a ViewModel's coroutine scope.10 Robust error handling is necessary to manage
specific exceptions that Credential Manager may throw, such as
androidx.credentials.exceptions.NoCredentialException if no suitable credentials are found, or
exceptions indicating user cancellation of the selection prompt.10

### **Implementing Google Sign-In with GetSignInWithGoogleOption**

The GetSignInWithGoogleOption class is specifically designed to initiate the "Sign in with Google"
flow through Credential Manager.  
Its instantiation involves a builder pattern:

Kotlin

val serverClientId \= "YOUR\_WEB\_CLIENT\_ID" // Obtain from Google Cloud Console  
val nonce \= generateNonce() // Implement a function to generate a secure random nonce

val googleIdOption \= GetSignInWithGoogleOption.Builder(serverClientId)  
.setNonce(nonce) // Recommended for replay attack prevention \[14\]  
//.setFilterByAuthorizedAccounts(true) // Initially filter by accounts already used with your app
\[14\]  
//.setAutoSelectEnabled(false) // Or true, based on desired UX for automatic sign-in \[14\]  
.build()

val getCredentialRequest \= GetCredentialRequest.Builder()  
.addCredentialOption(googleIdOption)  
.build()

The serverClientId parameter is critical: it must be the OAuth 2.0 Client ID of your **Web
Application** (as configured in the Google Cloud Console), not the Android client ID.15 This ID is
used to identify your backend server. The setNonce method takes a unique, cryptographically random
string, which is vital for mitigating replay attacks by ensuring each sign-in request is fresh.14  
The setFilterByAuthorizedAccounts(boolean) method controls the accounts displayed to the user.
Setting it to true shows only Google accounts previously used to sign into the app. If false, all
Google accounts on the device are presented. A common best practice is to first attempt the request
with true; if no credentials are found (i.e., NoCredentialException), a second request can be made
with false to allow new users to sign up or existing users to choose a different account.14 The
setAutoSelectEnabled(boolean) method can facilitate automatic sign-in if specific conditions are
met, such as a single matching credential and no explicit user sign-out.14

### **Obtaining GoogleIdTokenCredential and the ID Token**

Upon a successful invocation of credentialManager.getCredential(getCredentialRequest, context), the
GetCredentialResponse object will contain a Credential. For a "Sign in with Google" flow, this will
be an instance of CustomCredential. The type property of this CustomCredential will be
GoogleIdTokenCredential.TYPE\_GOOGLE\_ID\_TOKEN\_CREDENTIAL.14  
This CustomCredential must then be converted into a GoogleIdTokenCredential object using its static
factory method: GoogleIdTokenCredential.createFrom(customCredential.data).14 The primary piece of
information obtained from the GoogleIdTokenCredential is the **ID Token**, accessible via the
idToken property (or getIdToken() method).14 This ID Token is a JSON Web Token (JWT) that asserts
the user's identity and must be sent to your backend server for validation. The
GoogleIdTokenCredential may also contain user profile information (e.g., display name, profile
picture URI), which can be used for enhancing the user interface but should **not** be used for
making authentication or authorization decisions before the ID Token is verified by the backend.14  
It is crucial to understand that the GetSignInWithGoogleOption is designed purely for
authentication. The builder for this option offers methods like setServerClientId and setNonce but
lacks any methods to request arbitrary OAuth scopes (such as those required for Gmail API access) or
to directly obtain a serverAuthCode.14 Consequently, the GoogleIdTokenCredential object resulting
from this flow contains an ID token and associated profile data, but it **does not** include a
serverAuthCode that can be exchanged for refresh and access tokens.9 This distinction necessitates
the subsequent use of AuthorizationClient for any operations requiring specific API permissions or
long-lived offline access.  
The serverClientId provided to GetSignInWithGoogleOption.Builder serves a specific purpose related
to the ID Token itself. Its primary role is to set the aud (audience) claim within the generated ID
Token.14 This claim specifies that the ID Token is intended for your backend server (identified by
that serverClientId), allowing your backend to validate that the token was issued for it. This
should not be confused with requesting a serverAuthCode; the latter is a distinct operation handled
by AuthorizationClient's requestOfflineAccess() method.18

## **3\. Securing Long-Term Gmail API Access: The Role of AuthorizationClient**

Once user authentication is established via Credential Manager and an ID Token is obtained,
accessing specific Google APIs like Gmail, especially with the need for offline access (refresh
tokens), requires a separate authorization step. This is where
com.google.android.gms.auth.api.identity.AuthorizationClient becomes essential.

### **Understanding the Authentication vs. Authorization Distinction**

It's vital to differentiate between authentication and authorization in the context of Google
Identity services:

* **Authentication**, primarily handled by Credential Manager in this scenario, is the process of
  verifying *who the user is*. The successful outcome is typically an ID Token, which provides proof
  of the user's identity.1
* **Authorization**, managed by AuthorizationClient, is the process of granting *permission for your
  application to access specific user data or APIs on their behalf* (e.g., reading the user's Gmail
  messages). This usually results in an Access Token (short-lived, for immediate API calls) and, if
  offline access is requested, a serverAuthCode that can be exchanged for a long-lived Refresh
  Token.1

This separation is a fundamental principle in modern OAuth 2.0 implementations and Google's identity
services, allowing for more granular control and improved user understanding of permissions
requested.1

### **Table: Credential Manager vs. AuthorizationClient Responsibilities for Google APIs**

To clarify their distinct roles, the following table outlines the responsibilities of Credential
Manager (with GetSignInWithGoogleOption) and AuthorizationClient when interacting with Google APIs:

| Feature                      | androidx.credentials.CredentialManager (with GetSignInWithGoogleOption) | com.google.android.gms.auth.api.identity.AuthorizationClient  |
|:-----------------------------|:------------------------------------------------------------------------|:--------------------------------------------------------------|
| **Primary Goal**             | User Authentication (Sign-in/Sign-up)                                   | User Authorization (Granting API permissions)                 |
| **Typical Output**           | GoogleIdTokenCredential (containing ID Token)                           | AuthorizationResult (containing Access Token, serverAuthCode) |
| **Handles OAuth Scopes?**    | No (beyond basic openid, email, profile implied by ID token)            | Yes (explicitly via setRequestedScopes())                     |
| **Requests serverAuthCode?** | No                                                                      | Yes (explicitly via requestOfflineAccess())                   |
| **Handles Refresh Tokens?**  | No                                                                      | Indirectly (provides serverAuthCode to get refresh token)     |
| **When to Use**              | Initial user sign-in to establish identity.                             | After sign-in, when specific API access is needed.            |

### **Step-by-Step: Requesting Gmail Scopes and serverAuthCode for Offline Access**

The process to request Gmail API scopes and a serverAuthCode using AuthorizationClient involves
several steps:

1. Obtain AuthorizationClient Instance:  
   An instance of AuthorizationClient is retrieved using the Identity class:  
   Kotlin  
   val authorizationClient \= Identity.getAuthorizationClient(activity) // or context

   1
2. Build AuthorizationRequest:  
   Construct an AuthorizationRequest object, specifying the required scopes and requesting offline
   access.  
   Kotlin  
   val serverClientId \= "YOUR\_WEB\_CLIENT\_ID" // Must be the Web Application client ID  
   val gmailScopes \= listOf(  
   Scope("https://www.googleapis.com/auth/gmail.readonly"), // Example: permission to read emails  
   // Add other necessary Gmail scopes, e.g.:  
   // Scope("https://www.googleapis.com/auth/gmail.send"),  
   // Scope("https://www.googleapis.com/auth/gmail.modify"),  
   // Scope("https://mail.google.com/") // Broader Gmail access  
   )

   val authRequest \= AuthorizationRequest.builder()  
   .setRequestedScopes(gmailScopes) // Specify all required scopes \[1, 19\]  
   .requestOfflineAccess(serverClientId) // Essential for obtaining serverAuthCode \[18, 19\]  
   //.setAccount(previouslySignedInAccountObject) // Optional: If you have the Google Account
   object  
   .build()

   The setRequestedScopes() method takes a list of Scope objects. It's crucial to request only the
   permissions genuinely needed by the application feature being accessed. Incremental
   authorization—requesting scopes only when a feature requiring them is used—is a recommended
   practice to enhance user trust and understanding.6 For Gmail, specific scopes
   like https://www.googleapis.com/auth/gmail.readonly
   or https://www.googleapis.com/auth/gmail.modify must be used.20  
   The requestOfflineAccess(serverClientId) method is paramount for obtaining a serverAuthCode,
   which is the precursor to a refresh token. The serverClientId provided here must be the OAuth 2.0
   Client ID of your **Web Application** from the Google Cloud Console.5 An overloaded version,
   requestOfflineAccess(serverClientId, forceCodeForRefreshToken), exists. The
   forceCodeForRefreshToken boolean (defaulting to false) should typically only be set to true in
   recovery scenarios, for instance, if the backend server has lost a previously issued refresh
   token and needs to ensure a new one is generated, a process which might re-prompt the user for
   consent.19
3. Initiate Authorization and Handle Result:  
   The authorization flow is started by calling authorize() on the AuthorizationClient instance.  
   Kotlin  
   authorizationClient.authorize(authRequest)  
   .addOnSuccessListener { authorizationResult \-\>  
   if (authorizationResult.hasResolution()) {  
   // User consent is required. Launch the PendingIntent.  
   val pendingIntent \= authorizationResult.pendingIntent  
   // It's recommended to use an ActivityResultLauncher for handling the intent result.  
   // activityResultLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender)
   .build())  
   } else {  
   // Access was already granted, or no further user interaction was needed.  
   // Retrieve the serverAuthCode if offline access was successful.  
   val serverAuthCode \= authorizationResult.serverAuthCode // \[1\]  
   if (serverAuthCode\!= null) {  
   // Securely send this serverAuthCode to your backend server.  
   sendAuthCodeToBackend(serverAuthCode)  
   } else {  
   // Handle cases where serverAuthCode is null,  
   // e.g., user denied offline access or an error occurred.  
   }  
   // An access token might also be available for immediate, short-term use.  
   val accessToken \= authorizationResult.accessToken // \[18, 21\]  
   if (accessToken\!= null) {  
   // Use this access token for immediate API calls if needed.  
   }  
   }  
   }  
   .addOnFailureListener { e \-\>  
   // Handle authorization failure.  
   Log.e("AuthClient", "Authorization request failed", e)  
   }

   If authorizationResult.hasResolution() returns true, it means user interaction (typically a
   consent screen) is required. The application must launch the PendingIntent provided in
   authorizationResult.pendingIntent. The result of this intent launch should be handled in
   onActivityResult or, more modernly, via an ActivityResultLauncher.1 If hasResolution() is false,
   permissions are already granted, and the serverAuthCode (if offline access was requested and
   granted) and/or an accessToken can be retrieved directly from the AuthorizationResult.  
   It's worth noting that if requestOfflineAccess() is *not* called, and the user has previously
   granted the requested scopes, subsequent calls to authorize() for the same scopes might silently
   return a new, valid access token without further user interaction. The Android system may cache
   and refresh this access token to some extent.18 However, for robust, long-term offline access as
   required by an email application for background synchronization, a proper refresh token obtained
   via the serverAuthCode exchange is indispensable.

### **Server-Side Responsibility: Exchanging serverAuthCode for Access and Refresh Tokens (
Conceptual)**

The serverAuthCode obtained on the Android client is a temporary, single-use credential. The Android
application's responsibility is to securely transmit this code to its backend server.1  
The backend server then performs the crucial step of exchanging this serverAuthCode for an access
token and, most importantly for long-term access, a refresh token. This exchange involves the
backend server making a secure POST request to Google's token endpoint (
typically https://oauth2.googleapis.com/token). This request must include the serverAuthCode, the
server's Web Application client ID, and its client secret.6 Because this exchange requires the
client secret, which must **never** be embedded within a mobile application due to security risks,
this step must occur on a trusted server environment.7  
Upon successful exchange, Google's token endpoint returns an access token (short-lived) and a
refresh token (long-lived). The backend server should then securely store this refresh token,
typically in a database, associating it with the authenticated user. The access token can be used
immediately by the backend to make API calls on behalf of the user or can be passed back to the
client for short-term use. If the Android client needs to perform background operations or refresh
access tokens independently, the backend would then need a secure mechanism to transmit the obtained
refresh token (and its expiry, if applicable) back to the Android application.

## **4\. Robust Token Management on Android (May 2025\)**

Once the Android application receives OAuth refresh and access tokens (typically the refresh token
from its own backend after the serverAuthCode exchange), it is responsible for their secure storage
and management.

### **Securely Storing OAuth Refresh and Access Tokens**

The cornerstone of secure credential storage on Android is the **Android Keystore system**.8 This
system allows applications to store cryptographic keys in a container, making them significantly
more difficult to extract from the device. Keys stored in the Keystore can be protected such that
their material never enters the application's process and can even be bound to secure hardware like
the Trusted Execution Environment (TEE) or a Secure Element (SE, often referred to as StrongBox),
rendering them non-exportable even if the Android OS is compromised.8  
The recommended process for encrypting and storing tokens is as follows:

1. **Generate a Cryptographic Key in Android Keystore:** The application should generate a symmetric
   key (e.g., AES) or an asymmetric key pair (e.g., RSA, EC) within the Android Keystore. For
   encrypting tokens, an AES key, particularly with a mode like GCM (Galois/Counter Mode), is often
   preferred due to its efficiency and built-in authentication. The KeyGenParameterSpec class is
   used to define the properties of the key being generated, including its alias, intended
   purposes (e.g., KeyProperties.PURPOSE\_ENCRYPT, KeyProperties.PURPOSE\_DECRYPT), block mode (
   e.g., KeyProperties.BLOCK\_MODE\_GCM), padding scheme (e.g.,
   KeyProperties.ENCRYPTION\_PADDING\_NONE for GCM), and any user authentication requirements for
   key usage.8
2. **Encrypt the Tokens:** Using the generated Keystore key, the application encrypts the OAuth
   refresh token (and potentially the access token, though its shorter lifespan might make its
   encrypted storage less critical if it's frequently refreshed).
3. **Store Encrypted Data:** The resulting ciphertext (encrypted token) and any necessary
   cryptographic parameters (like the Initialization Vector (IV) if using AES/CBC or the
   authentication tag if using AES/GCM) are then stored in a persistent location, such as
   SharedPreferences or a local Room database.

To decrypt the tokens:

1. Retrieve the Keystore key using its alias.
2. Fetch the encrypted token and associated parameters (e.g., IV) from storage.
3. Use the Keystore key and parameters to decrypt the ciphertext.

It is important to note that the androidx.security:security-crypto library, which previously offered
convenient wrappers for Android Keystore operations (including EncryptedSharedPreferences), is *
*deprecated** as of May 2025\.25 Developers are now guided to use the platform's AndroidKeyStore
provider and standard java.security and javax.crypto APIs directly. An example of direct Keystore
usage for encryption and decryption can be found in official Android documentation.24  
Best practices for using the Android Keystore include 7:

* Preferring hardware-backed Keystore (TEE or StrongBox) by using
  KeyGenParameterSpec.Builder.setIsStrongBoxBacked(true) when available.
* Considering user authentication for key use (setUserAuthenticationRequired(true)) for highly
  sensitive tokens, which links key usage to device unlock or biometric authentication. This adds a
  strong layer of security but needs careful consideration for background token refresh scenarios.
* Employing strong cryptographic algorithms, secure block modes (e.g., AES-GCM is generally
  recommended over CBC for authenticated encryption), and appropriate padding schemes.

### **Ensuring Token Persistence Across App Restarts**

Storing the encrypted tokens in SharedPreferences or a local database ensures their persistence
across application restarts. The decryption key itself remains securely managed by the Android
Keystore. Upon application launch, the app should attempt to retrieve and decrypt any stored refresh
token. If successful, the user can be considered "signed in" from an OAuth perspective, and the app
can proceed to use the refresh token to obtain a new access token for API calls.

### **Credential Manager's "Restore Credentials" Feature: Relevance and Limitations for OAuth Tokens
**

Credential Manager includes a "Restore Credentials" feature, documented in 26 and.26 This feature is
designed to assist users in restoring their application accounts when they set up a **new Android
device**. It operates using "restore keys," which are a type of WebAuthn credential. When a user
signs into an app that supports this, the app can create a RestoreCredential. This credential is
stored locally on the device and can be synced to Google Backup if the user has enabled it and
end-to-end encryption is available (apps can opt out of cloud sync). When the user sets up a new
device, the app can request this RestoreCredential from Credential Manager, potentially allowing for
automatic sign-in without requiring manual re-entry of credentials.  
However, this feature has specific limitations concerning the OAuth tokens discussed:

* It exclusively supports the restoration of these "restore keys." It is **not designed** to store
  or restore arbitrary OAuth tokens that an application has obtained and manages itself.
* Its primary use case is new device setup. It does not address general token persistence across app
  restarts on the *same device* after a user has signed out, nor is it a mechanism for managing
  active session tokens.
* The documentation explicitly states, "Restore Credentials does not handle the scenario where an
  app is reinstalled on the same device".26

Therefore, while "Restore Credentials" enhances the new device experience for account setup, it does
not replace the need for the application to securely manage its own OAuth refresh and access tokens
using the Android Keystore for persistence and security on a given device. Credential Manager's role
is to manage *authentication credentials* (like passwords, passkeys, or the initial Google ID token)
for the sign-in and sign-up process. It does not function as a generic secure vault for arbitrary
sensitive data, such as OAuth refresh tokens that the application obtains independently
post-authentication. The responsibility for the secure storage of these application-specific
authorization tokens rests with the application itself, leveraging platform capabilities like the
Android Keystore.

## **5\. Credential Manager: Beyond Google Sign-In**

While the primary focus has been on Google Sign-In, Credential Manager's capabilities extend to
other authentication scenarios relevant to an email application.

### **Handling IMAP Logins (Username/Password)**

For email accounts that use traditional IMAP/SMTP protocols requiring a username and password,
Credential Manager is well-suited for managing these credentials securely.

* **Saving Credentials:** After a user successfully enters their IMAP username and password, the
  application can use CreatePasswordRequest(id \= username, password \= password) to create a
  request object. This request is then passed to credentialManager.createCredential() to save the
  credentials. Credential Manager will typically prompt the user to save these details with their
  chosen password manager (e.g., Google Password Manager).9
* **Retrieving Credentials:** When the user needs to sign in again, or when the app needs to
  authenticate to the IMAP server, it can construct a GetCredentialRequest containing a
  GetPasswordOption(). Calling credentialManager.getCredential() with this request will prompt the
  user with a system dialog (e.g., a bottom sheet) displaying saved password credentials for the
  app. If the user selects a credential, the result will be a PasswordCredential object containing
  the username (via credential.id) and password.9

It's important to understand that Credential Manager facilitates the secure storage and retrieval of
these username/password pairs. It does not, however, handle the IMAP connection, protocol
negotiation, or the actual authentication against the IMAP server. The application receives the
credentials from Credential Manager and must then use them in its IMAP client logic to establish a
connection and authenticate.

### **Microsoft Logins with MSAL 6.0.0**

For integrating Microsoft account logins (e.g., Outlook.com, Microsoft 365\) into an Android
application, the standard approach involves using the Microsoft Authentication Library (MSAL) for
Android.27 MSAL is Microsoft's recommended library for authenticating users with Microsoft Entra
ID (formerly Azure AD), personal Microsoft accounts, and Azure AD B2C. It handles the complexities
of the OAuth 2.0 and OpenID Connect protocols specific to Microsoft's identity platform and is
responsible for acquiring tokens to access Microsoft Graph and other protected APIs. The current
version, such as MSAL 6.0.0 or 6.0.1, should be used.28  
Regarding the integration of MSAL with Android Credential Manager, the available information **does
not indicate any direct, explicit API interoperability or built-in integration** between the two for
handling Microsoft logins.27 Credential Manager supports "federated sign-in solutions" in a general
sense.9 However, its specific, deep support for identity providers beyond Google often depends on
those providers actively adopting Credential Manager's APIs (e.g., by implementing a
CredentialProviderService) or by using open standards like OpenID Connect in a way that Credential
Manager can generically interface with. The current documentation and examples for Credential
Manager are heavily focused on Google Sign-In, passkeys, and username/password credentials.  
MSAL has its own established mechanisms for authentication, including brokered authentication
through apps like Microsoft Authenticator or the Intune Company Portal.27 This brokered approach
enables Single Sign-On (SSO) across applications and devices within the Microsoft ecosystem. There
is no indication in the provided materials that MSAL currently delegates its credential handling or
UI prompts to Android Credential Manager, or that Credential Manager offers a specific
CredentialOption tailored for MSAL flows in the same way GetSignInWithGoogleOption exists for
Google. While Firebase Authentication offers an abstraction layer for Microsoft OAuth, this is
separate from Credential Manager's direct capabilities.31  
Therefore, as of May 2025, if an application requires Microsoft account login functionality,
developers should integrate and use MSAL directly according to Microsoft's guidelines. Credential
Manager is unlikely to be directly involved in the MSAL authentication flow unless Microsoft
releases specific integration components or MSAL itself begins to leverage Credential Manager APIs
internally for storing or retrieving the credentials it manages. While Credential Manager aims to be
a unified API for various authentication types, its out-of-the-box support for federated identity
providers other than Google, particularly for providers with their own mature SDKs like MSAL, is not
as deeply integrated or explicitly detailed.

## **6\. The Android Authentication Ecosystem in May 2025**

The landscape of authentication on Android has been undergoing significant consolidation, with a
clear push towards modern, unified APIs.

### **Recommended Libraries & Capabilities**

As of May 2025, for building robust and secure authentication flows, particularly for Google Sign-In
and managing other credential types, the following libraries and capabilities are recommended:

* **androidx.credentials:credentials**: This is the core Jetpack library for Credential Manager,
  providing the fundamental APIs for creating and getting credentials.9
* **androidx.credentials:credentials-play-services-auth**: This optional library provides support
  for Google Sign-In and passkeys through Google Play Services. It is particularly important for
  ensuring functionality on devices running Android 13 (API level 33\) and below. This library
  facilitates the use of GetSignInWithGoogleOption and the handling of GoogleIdTokenCredential.9
* **com.google.android.gms:play-services-auth (Identity specific components)**: While the legacy
  Google Sign-In client (GoogleSignInClient) within this SDK is deprecated for sign-in purposes, the
  SDK continues to provide essential components for Google Identity Services. Notably,
  Identity.getAuthorizationClient() is used to obtain the AuthorizationClient needed for requesting
  OAuth scopes and serverAuthCode.1 Developers must ensure they are using the correct,
  non-deprecated parts of this SDK.
* **Android Keystore System (Platform API)**: This is the fundamental platform capability for all
  cryptographic operations and secure key storage. It should be used directly via java.security and
  javax.crypto APIs for encrypting sensitive data like OAuth tokens.8
* **Jetpack Compose**: For building modern UIs, including sign-in screens and handling user
  interactions related to Credential Manager prompts.10
* **Kotlin Coroutines**: For managing asynchronous operations involved in Credential Manager and
  AuthorizationClient calls, ensuring responsive and non-blocking UI.10

### **Table: Deprecated vs. Recommended Authentication APIs (May 2025\)**

The following table summarizes the status of various Android authentication APIs and the recommended
alternatives as of May 2025, highlighting the shift towards Credential Manager:

| Deprecated API / Capability                            | Status (as of May 2025\)                   | Recommended Replacement / Approach                                                                                                                     | Key References       |
|:-------------------------------------------------------|:-------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------|:---------------------|
| Google Sign-In for Android (Legacy GoogleSignInClient) | Deprecated, planned for removal in 2025    | androidx.credentials.CredentialManager with GetSignInWithGoogleOption for authentication.                                                              | 2                    |
| Smart Lock for Passwords API                           | Removed, fully shut down in Q1 2025        | androidx.credentials.CredentialManager for password and passkey management.                                                                            | 3                    |
| One Tap Sign-in (Legacy Google)                        | Deprecated, planned for removal in H2 2025 | androidx.credentials.CredentialManager (offers a similar one-tap user experience).                                                                     | 3                    |
| Credential Saving API (Legacy)                         | Deprecated, planned for removal in H1 2025 | androidx.credentials.CredentialManager (using CreatePasswordRequest, CreatePublicKeyCredentialRequest).                                                | 3                    |
| androidx.security:security-crypto                      | Deprecated                                 | Direct use of Android Keystore system (platform APIs: java.security, javax.crypto). Example:.24                                                        | 25                   |
| Loopback IP Address Flow (for native app OAuth)        | Blocked                                    | Claimed HTTPS scheme redirect URIs or SDKs like Google Identity Services that handle redirects securely. AuthorizationClient uses appropriate methods. | \[18 (comment), 21\] |
| ADAL (Azure Active Directory Auth Library)             | Deprecated                                 | MSAL (Microsoft Authentication Library).                                                                                                               | 27                   |

This active consolidation by Google, deprecating numerous older and fragmented APIs in favor of
Credential Manager, signifies a strategic effort to unify and simplify authentication on the Android
platform.3 While this transition requires migration efforts from developers, the aim is to establish
a more secure, maintainable, and consistent authentication framework for the future. Adherence to
these recommendations is crucial to avoid reliance on APIs that may cease to function.

## **7\. Conclusion and Strategic Recommendations**

For developing an Android email application in Kotlin and Jetpack Compose with a focus on Gmail
OAuth login, obtaining refresh/access tokens, and supporting other authentication methods, a modern
and robust approach is essential as of May 2025\.  
The optimal path for Gmail OAuth involves a clear separation of authentication and authorization:

1. **Authentication:** Utilize androidx.credentials.CredentialManager with
   GetSignInWithGoogleOption. This will handle the user sign-in flow and provide a
   GoogleIdTokenCredential containing an ID Token. This ID Token must be sent to your backend for
   verification to confirm the user's identity.
2. **Authorization & Offline Access:** After successful authentication, and when access to Gmail
   APIs is required, use com.google.android.gms.auth.api.identity.AuthorizationClient. Construct an
   AuthorizationRequest specifying the necessary Gmail API scopes (
   e.g., https://www.googleapis.com/auth/gmail.readonly) and explicitly call requestOfflineAccess(
   your\_web\_server\_client\_id). This will yield a serverAuthCode.
3. **Token Exchange:** The serverAuthCode must be securely sent from the Android app to your backend
   server. The backend server then exchanges this code with Google's token endpoint (using its
   client ID and client secret) to obtain an access token and, crucially, a long-lived refresh
   token.

**Token Storage on Android:** The Android application is responsible for securely storing the OAuth
refresh token (and potentially short-lived access tokens) received from its backend. The recommended
method is to encrypt these tokens using a key generated and managed by the **Android Keystore system
** (using platform java.security and javax.crypto APIs directly, as androidx.security:
security-crypto is deprecated) and store the resulting ciphertext in SharedPreferences or a local
database. Credential Manager itself does not store these application-specific OAuth tokens; its "
Restore Credentials" feature is for new device setup using "restore keys" and not for arbitrary
token management.  
**For IMAP Logins:** Credential Manager is well-suited for handling traditional username/password
credentials. Use CreatePasswordRequest to save them and GetPasswordOption (within a
GetCredentialRequest) to retrieve them. The app then uses these credentials for IMAP
authentication.  
**For Microsoft Logins:** Integration with Microsoft accounts should be done using the Microsoft
Authentication Library (MSAL) directly (e.g., MSAL 6.0.0). Current information does not indicate
direct, built-in support or interoperability for MSAL flows within Android Credential Manager beyond
its general "federated sign-in" capability, which is primarily detailed for Google Sign-In.  
**Key Takeaway:** Successfully implementing secure and modern authentication in an Android email app
requires a clear understanding of the distinct roles of CredentialManager (for user authentication
and managing authentication credentials like ID tokens or passwords) and AuthorizationClient (for
obtaining user authorization for specific API scopes and the serverAuthCode necessary for refresh
tokens). Coupled with this is the critical responsibility of the application to implement robust,
secure storage for OAuth tokens using the Android Keystore system directly. The Android
authentication landscape is continuously evolving; therefore, regularly consulting official Android
developer documentation and blogs is crucial for staying abreast of the latest best practices, API
updates, and security recommendations, particularly concerning the expanding capabilities of
Credential Manager.

#### **Works cited**

1. Authorize access to Google user data | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
2. Identity | Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity](https://developer.android.com/identity)
3. Streamlining Android authentication: Credential Manager replaces legacy APIs, accessed May 11,
   2025, [https://android-developers.googleblog.com/2024/09/streamlining-android-authentication-credential-manager-replaces-legacy-apis.html](https://android-developers.googleblog.com/2024/09/streamlining-android-authentication-credential-manager-replaces-legacy-apis.html)
4. Integrate Google Sign-In into Your Android App | Identity, accessed May 11,
   2025, [https://developer.android.com/identity/legacy/gsi/legacy-sign-in](https://developer.android.com/identity/legacy/gsi/legacy-sign-in)
5. Enabling Server-Side Access | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/legacy/gsi/offline-access](https://developer.android.com/identity/legacy/gsi/offline-access)
6. Using OAuth 2.0 for Web Server Applications | Authorization \- Google for Developers, accessed
   May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/web-server](https://developers.google.com/identity/protocols/oauth2/web-server)
7. Best Practices | Authorization \- Google for Developers, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/resources/best-practices](https://developers.google.com/identity/protocols/oauth2/resources/best-practices)
8. Android Keystore system | Security \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/privacy-and-security/keystore](https://developer.android.com/privacy-and-security/keystore)
9. Sign in your user with Credential Manager | Identity | Android ..., accessed May 11,
   2025, [https://developer.android.com/identity/sign-in/credential-manager](https://developer.android.com/identity/sign-in/credential-manager)
10. Credential Manager \- pl-coding.com, accessed May 11,
    2025, [https://pl-coding.com/2024/10/28/credential-manager/](https://pl-coding.com/2024/10/28/credential-manager/)
11. Migration overview | Android game development, accessed May 11,
    2025, [https://developer.android.com/games/pgs/migration\_overview](https://developer.android.com/games/pgs/migration_overview)
12. \[Partner Bug\]login with google always got ... \- Issue Tracker, accessed May 11,
    2025, [https://issuetracker.google.com/issues/385925137](https://issuetracker.google.com/issues/385925137)
13. \[Partner Bug\] \[397720952\] \- Issue Tracker \- Google, accessed May 11,
    2025, [https://issuetracker.google.com/issues/397720952](https://issuetracker.google.com/issues/397720952)
14. Authenticate users with Sign in with Google | Identity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/credential-manager-siwg](https://developer.android.com/identity/sign-in/credential-manager-siwg)
15. Credential Manager \- How do I create a "SignInWithGoogle" credential? \- Stack Overflow,
    accessed May 11,
    2025, [https://stackoverflow.com/questions/77627128/credential-manager-how-do-i-create-a-signinwithgoogle-credential](https://stackoverflow.com/questions/77627128/credential-manager-how-do-i-create-a-signinwithgoogle-credential)
16. Add Sign In with Google to Native Android Apps \- Auth0, accessed May 11,
    2025, [https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native](https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native)
17. GetCredentialResponse | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/androidx/credentials/GetCredentialResponse](https://developer.android.com/reference/androidx/credentials/GetCredentialResponse)
18. Google Identity Authorization (Android) : how to get refresh token? \- Stack Overflow, accessed
    May 11,
    2025, [https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token](https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token)
19. AuthorizationRequest.Builder | Google Play services | Google for ..., accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
20. Integrating Gmail API into an Android application: How to proceed? \- Latenode community,
    accessed May 11,
    2025, [https://community.latenode.com/t/integrating-gmail-api-into-an-android-application-how-to-proceed/8981](https://community.latenode.com/t/integrating-gmail-api-into-an-android-application-how-to-proceed/8981)
21. Loopback IP Address flow Migration Guide | Authorization \- Google for Developers, accessed May
    11,
    2025, [https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)
22. Manage OAuth Clients \- Google Cloud Platform Console Help, accessed May 11,
    2025, [https://support.google.com/cloud/answer/15549257?hl=en](https://support.google.com/cloud/answer/15549257?hl=en)
23. SECURE CREDENTIAL STORAGE IN MOBILE APPLICATIONS | Grootan Technologies, accessed May 11,
    2025, [https://www.grootan.com/blogs/secure-credential-storage-in-mobile-applications/](https://www.grootan.com/blogs/secure-credential-storage-in-mobile-applications/)
24. Android Keystore system | Security | Android Developers, accessed May 11,
    2025, [https://developer.android.com/training/articles/keystore](https://developer.android.com/training/articles/keystore)
25. Security | Jetpack | Android Developers, accessed May 11,
    2025, [https://developer.android.com/jetpack/androidx/releases/security](https://developer.android.com/jetpack/androidx/releases/security)
26. Restore app credentials when setting up a new device | Identity ..., accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/restore-credentials](https://developer.android.com/identity/sign-in/restore-credentials)
27. Microsoft Intune App SDK for Android developer integration and testing guide \- MSAL
    Prerequisite, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/mem/intune-service/developer/app-sdk-android-phase2](https://learn.microsoft.com/en-us/mem/intune-service/developer/app-sdk-android-phase2)
28. msal » 6.0.1 \- com.microsoft.identity.client \- Maven Repository, accessed May 11,
    2025, [https://mvnrepository.com/artifact/com.microsoft.identity.client/msal/6.0.1](https://mvnrepository.com/artifact/com.microsoft.identity.client/msal/6.0.1)
29. How to enable cross-app SSO on Android using MSAL | Microsoft Learn, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/entra/msal/android/single-sign-on](https://learn.microsoft.com/en-us/entra/msal/android/single-sign-on)
30. How to enable cross-app SSO on Android using MSAL \- Microsoft identity platform, accessed May
    11,
    2025, [https://learn.microsoft.com/en-us/entra/identity-platform/msal-android-single-sign-on](https://learn.microsoft.com/en-us/entra/identity-platform/msal-android-single-sign-on)
31. Authenticate Using Microsoft on Android \- Firebase, accessed May 11,
    2025, [https://firebase.google.com/docs/auth/android/microsoft-oauth](https://firebase.google.com/docs/auth/android/microsoft-oauth)
32. What is Google Smart Lock and How to Turn It Off？ \- AirDroid, accessed May 11,
    2025, [https://www.airdroid.com/mdm/google-smart-lock/](https://www.airdroid.com/mdm/google-smart-lock/)