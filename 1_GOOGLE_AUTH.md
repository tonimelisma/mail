# **Navigating Google Sign-In and Authorization for Backend-less Android Applications**

## **1\. Introduction: Navigating Authentication and Authorization in Backend-less Android Apps**

### **Overview of Modern Android Identity**

The landscape of user identity management on Android has significantly evolved. Historically,
developers relied on various APIs, including the now-deprecated Google Sign-In for Android and Smart
Lock for Passwords.1 The modern approach, as of 2025, consolidates authentication gestures under the
androidx.credentials.CredentialManager Jetpack library. CredentialManager is designed to streamline
the sign-in and sign-up experience for users by providing a unified interface for multiple
credential types, including passwords, passkeys, and federated identity providers like Sign in with
Google.1  
Crucially, CredentialManager primarily focuses on the act of authentication – verifying who the user
is. For actions that require accessing user data stored with Google (e.g., Google Drive files, Gmail
messages, Calendar events), a separate component,
com.google.android.gms.auth.api.identity.AuthorizationClient, is used for granular authorization
requests.2 This separation of concerns is a cornerstone of Google's current identity strategy for
Android. Documentation updated as recently as May 11, 2025, confirms that the older Google Sign-In
for Android library is deprecated and slated for removal in 2025, with a clear directive to migrate
to CredentialManager for sign-in functionalities and to use AuthorizationClient for authorization
tasks.2 Understanding these newer components and their interplay is therefore essential for
contemporary Android development.

### **Addressing Core Confusion for Purely Mobile Apps**

For developers building purely mobile Android applications—those without a developer-managed backend
server—several aspects of Google's authentication and authorization flows can be perplexing. A
common point of confusion is the apparent requirement for different types of OAuth 2.0 client IDs,
particularly the "Web application" client ID (often referred to as a server client ID), even when no
traditional backend server is part of the application architecture. Furthermore, the mechanisms for
securely obtaining and managing API access tokens, especially long-lived refresh tokens for offline
access, on the client-side present unique challenges.  
This report aims to demystify these complexities. It will provide a coherent explanation of the
end-to-end process for purely mobile Android applications that need to authenticate users with their
Google accounts and subsequently authorize access to Google APIs, including scenarios requiring
offline data access.  
A key clarification from the outset is that while an application may be "backend-less" from the
developer's perspective (i.e., no custom server-side code is deployed by the developer), it is not
operating in isolation when interacting with Google services. When an Android application seeks to
access Google APIs such as Gmail or Google Drive, it is, in fact, acting as a client to Google's
extensive backend infrastructure. Google's OAuth 2.0 framework needs to identify and authorize the
*application entity* making these API requests, not just the end-user. This conceptual framing helps
to understand why certain configurations, which might seem server-oriented (like the use of a Web
client ID for specific grant types), are indeed necessary even for mobile-only applications. The "
backend" in these interactions is Google's own authorization and resource servers.

## **2\. Demystifying Client IDs for Mobile-Only Google API Access**

### **The Fundamental Role of OAuth 2.0 Client IDs**

At the heart of Google's identity system is the OAuth 2.0 protocol, which enables third-party
applications to access user resources without exposing user credentials. A fundamental component of
this protocol is the OAuth 2.0 Client ID. This identifier is provisioned via the Google Cloud
Console and serves as the primary means by which Google's authorization server recognizes and
authenticates an application attempting to access Google APIs.4 Google supports various client ID
types, each tailored to the security characteristics and requirements of different application
architectures, such as web server applications, client-side JavaScript applications, and native
mobile applications.4 The choice of client ID type dictates aspects of the OAuth flow, including how
client secrets are handled and what redirect URI mechanisms are appropriate.

### **Android Client ID**

For native Android applications, Google mandates the use of an "Android" OAuth 2.0 client ID.4 This
client ID type is specifically designed for applications that run directly on Android devices.  
Purpose and Configuration:  
The Android Client ID uniquely identifies your mobile application to Google's authentication
services. Its configuration is tightly bound to the application's specific build attributes. To
create an Android Client ID in the Google Cloud Console, developers must provide:

1. **Package Name:** The unique application ID as defined in the build.gradle file (e.g.,
   com.example.myapp).
2. **SHA-1 Signing Certificate Fingerprint:** A hash of the certificate used to sign the Android
   application package (APK or AAB). This ensures that only authentic versions of your application,
   signed with your private key, can use the associated client ID.6 Different SHA-1 fingerprints are
   typically required for debug and release builds.

Security Characteristics:  
Android applications are classified as "public clients" in OAuth 2.0 terminology. This is because
they are distributed to user devices where the application code can be inspected, making it
impossible to securely store a client\_secret within the app itself.4 Attempting to embed a client
secret in a mobile app would pose a significant security risk, as it could be easily extracted and
misused.  
To mitigate the risks associated with public clients not using a client secret, the Proof Key for
Code Exchange (PKCE, RFC 7636\) extension to the Authorization Code Flow is essential.7 PKCE adds a
dynamic, per-request secret (the code\_verifier) that the client generates and transforms (into a
code\_challenge) for the authorization request. The authorization server stores the code\_challenge,
and when the client later exchanges the authorization code for tokens, it must provide the original
code\_verifier. This ensures that even if an authorization code is intercepted, it cannot be
exchanged for tokens by a malicious party without the code\_verifier. Google's identity platform
fully supports PKCE for native application flows.

### **Why a "Web Client ID" (often termed Server Client ID) is Necessary for Offline Access/Refresh
Tokens**

While an Android Client ID is fundamental for identifying the mobile app, scenarios involving "
offline access"—the ability for an app to access Google APIs when the user is not actively
interacting with it—often necessitate the use of a "Web application" client ID (frequently referred
to as a server client ID). Offline access is facilitated by refresh tokens, which are long-lived
credentials that can be used to obtain new, short-lived access tokens.4  
The requirement for a Web Client ID in such mobile-only contexts stems from how Google's OAuth 2.0
infrastructure manages and attributes these powerful, long-term grants.

1. **Google's Perspective on Long-Term Grants:** When an application requests offline access and
   thus a refresh token, Google needs to associate this persistent grant with a registered
   application entity that is configured for such capabilities. Historically, refresh tokens were
   primarily intended for backend servers (confidential clients) that could securely store client
   secrets and manage these tokens. Even if a modern mobile app performs the token exchange itself
   using its Android Client ID and PKCE, the *request* for offline capabilities often involves a Web
   Client ID to represent the overall application entity registered for this type of access.
2. **Official Documentation Mandates Both:** Android developer documentation for integrating Sign in
   with Google via CredentialManager 9 and for using AuthorizationClient 8 consistently instructs
   developers to create *both* an Android Client ID *and* a "Web application" client ID in the
   Google Cloud Console. The documentation explicitly states that this Web Client ID "will be used
   to identify your backend server when it communicates with Google's authentication services".8
   Even if "your backend server" is not a developer-managed one, Google's services act as that
   backend in the OAuth exchange. Legacy Google Sign-In documentation for offline access also
   emphasized creating a "Web application client ID for your backend server".15
3. **Usage in GetSignInWithGoogleOption.Builder().setServerClientId():** When using
   CredentialManager for Google Sign-In, the setServerClientId() method of
   GetSignInWithGoogleOption.Builder expects this Web Client ID.9 This ID is then typically included
   as the aud (audience) claim in the ID Token returned by Google. This allows a backend system (
   which could be Google's own services, or a third-party identity platform like Auth0 or Firebase
   acting as an intermediary) to verify that the ID Token was intended for it.20 For instance, Auth0
   documentation explains that in the ID Token returned from Google, the azp (authorized party) is
   the Android Client ID, while the aud (audience) is the Web Client ID. This allows Auth0 to
   correctly validate the token during its exchange process.20 Similarly, Firebase uses this server
   client ID to identify the Firebase project to Google and facilitate secure token exchange for
   Firebase authentication.21
4. **Usage in AuthorizationRequest.Builder().requestOfflineAccess(serverClientId):** When using
   AuthorizationClient to obtain a serverAuthCode for the purpose of acquiring a refresh token, the
   requestOfflineAccess(String serverClientId) method also requires this Web Client ID.8 The
   serverAuthCode obtained is a one-time code. If a backend server were to exchange this code, it
   would use the Web Client ID and its associated client secret. If the mobile app exchanges this
   code itself using PKCE, the Web Client ID still serves to identify the registered application
   that was granted permission for offline access.

The Web Client ID, in the context of a mobile-only app requesting offline access, acts as the
primary identifier for the "application" in Google's system that is authorized for such server-like,
long-term grants. The Android Client ID then serves to verify the specific mobile app instance that
is initiating the request on behalf of that registered application entity. This dual-identifier
system allows Google to maintain a distinction between the on-device public client and the
conceptual application entity that has been configured and vetted for potentially more sensitive
offline access scopes. Therefore, developers must create and correctly utilize both client ID types
as per Google's guidelines.  
The following table summarizes the roles of these client IDs:

| Feature                                   | Android Client ID                                                                                 | Web Client ID (Server Client ID)                                                                                                                       |
|:------------------------------------------|:--------------------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Primary Purpose**                       | Identifies the specific native Android app instance to Google.                                    | Identifies the overall application entity registered with Google, particularly for flows involving server-side validation or offline access grants.    |
| **Configuration**                         | Package Name, SHA-1 Signing Certificate Fingerprint. 8                                            | Typically configured with authorized JavaScript origins and redirect URIs for web apps, but for mobile offline access, it's primarily an identifier. 8 |
| **Client Secret**                         | No client secret (public client). 4                                                               | Has an associated client secret (confidential client). However, the secret is NOT used by the mobile app directly.                                     |
| **Used for Mobile-Only Refresh Token?**   | Yes, used in the PKCE token exchange request to identify the mobile app. 7                        | Yes, required when calling setServerClientId() in GetSignInWithGoogleOption or requestOfflineAccess() in AuthorizationRequest. 9                       |
| **Why Required for Mobile Refresh Token** | Authenticates the specific mobile app during the PKCE token exchange for access/refresh tokens. 7 | Acts as the registered application identifier for the offline access grant. Included in ID token audience or used to scope the serverAuthCode. 20      |

## **3\. Modern Authentication & Authorization: The Recommended Two-Step Process**

### **Guiding Principle: Separation of Authentication and Authorization**

A fundamental tenet of Google's modern identity services for Android is the clear separation between
authentication and authorization.2

* **Authentication** is the process of verifying a user's identity – establishing *who the user is*.
* **Authorization** is the process of granting or denying that authenticated user (or the
  application acting on their behalf) permission to access specific data or resources.

This separation allows for more granular control, improved user experience (by requesting
permissions only when needed), and a cleaner architectural design. Official Android developer
documentation explicitly recommends using the CredentialManager API for authentication (user sign-up
or sign-in) and the AuthorizationClient API for authorization (granting access to user data stored
by Google, such as Gmail or Drive scopes).2

### **Step 1: Authentication with CredentialManager and GetSignInWithGoogleOption**

The initial step in engaging with a user's Google identity is authentication. For Android apps,
CredentialManager provides the contemporary API for this purpose, supporting various methods
including Sign in with Google.  
Primary Goal:  
The main objective of this step is to securely sign in the user with their Google Account and obtain
an ID Token. An ID Token is a JSON Web Token (JWT) that contains user profile information and proves
the user's identity to your application or, if applicable, to a backend server for verification.9  
Key Components & Flow:  
The authentication process using CredentialManager for Google Sign-In typically involves the
following:

1. **GetSignInWithGoogleOption.Builder() or GetGoogleIdOption.Builder()**: These builders are used
   to configure the specifics of the Google Sign-In request. GetSignInWithGoogleOption is generally
   used when the sign-in is triggered by a dedicated "Sign in with Google" button, while
   GetGoogleIdOption is often used for "One Tap" or automatic sign-in scenarios.9
    * setServerClientId(WEB\_CLIENT\_ID): This is a critical and mandatory setting. The
      WEB\_CLIENT\_ID refers to the "Web application" client ID obtained from the Google Cloud
      Console. Its primary role here is to specify the intended audience (aud claim) of the ID Token
      that will be generated. This allows a backend server (or services like Firebase/Auth0) to
      verify that the ID Token was issued for it.9
    * setNonce(): Including a nonce (a unique, cryptographically random string generated per
      request) is highly recommended. It helps prevent replay attacks and enhances the security of
      the sign-in process.9
    * setFilterByAuthorizedAccounts(boolean filter): This option can be used to control whether the
      user is shown all Google accounts on the device or only those previously used with the app.
      Setting it to true is common for sign-in, while false might be used for initial sign-up.9
    * setAutoSelectEnabled(boolean enabled): This can enable automatic sign-in if criteria are met (
      e.g., only one matching credential).9
2. **GetCredentialRequest**: The configured GetSignInWithGoogleOption (or GetGoogleIdOption) is
   added to a GetCredentialRequest. This request can also include options for other credential types
   like passwords or passkeys if the app supports them.9
3. **CredentialManager.getCredential(request, activity,...)**: This asynchronous call initiates the
   sign-in flow. CredentialManager will typically display a system UI (like a bottom sheet) allowing
   the user to select their Google Account.9
4. **GetCredentialResponse**: Upon successful user selection and authentication, the getCredential()
   method returns a GetCredentialResponse.
5. **GoogleIdTokenCredential**: The actual credential is obtained from result.getCredential(). For
   Google Sign-In, this will be an instance of CustomCredential. The type of this CustomCredential
   must be checked against GoogleIdTokenCredential.TYPE\_GOOGLE\_ID\_TOKEN\_CREDENTIAL. If it
   matches, it can be converted to a GoogleIdTokenCredential object using
   GoogleIdTokenCredential.createFrom(customCredential.getData()).9

What GoogleIdTokenCredential Provides:  
The GoogleIdTokenCredential object is the primary outcome of this authentication step. Its main
components are:

* **getIdToken()**: This method returns the raw ID Token string (a JWT). This token is the core
  proof of authentication. If the application has a backend, this ID Token should be sent securely
  to the backend for validation (e.g., checking the signature, issuer, audience, and expiration).9
  For a purely mobile application without its own backend, the ID token itself is not directly used
  to call Google APIs that require OAuth 2.0 access tokens.
* **User Profile Information**: The GoogleIdTokenCredential object may provide direct access to some
  basic user profile information (like display name, email, photo URI). Alternatively, this
  information is embedded within the ID Token JWT and can be extracted after parsing and validating
  the token. Documentation suggests that members of googleIdTokenCredential can be used for UX
  purposes, but for controlling access to user data, the token must first be validated.9 The exact
  accessor methods on GoogleIdTokenCredential versus what's in the parsed GoogleIdToken (after
  server-side verification or careful client-side parsing if no backend) can vary, and API
  references should be consulted for specifics.9

A critical point is that the GoogleIdTokenCredential obtained through this CredentialManager flow *
*does not directly provide a serverAuthCode** (an OAuth 2.0 authorization code) that can be
exchanged for API access tokens and refresh tokens.9 The GetSignInWithGoogleOption.Builder does not
have methods to request specific OAuth scopes (like https://mail.google.com/) or to explicitly
request offline\_access to get an authorization code.9 Its purpose is solely authentication and ID
token retrieval. If an application requires access to Google APIs beyond basic profile information,
or needs offline access (refresh tokens), a subsequent authorization step is mandatory.

### **Step 2: Authorization with AuthorizationClient for API Scopes and Offline Access**

Once the user's identity has been established via CredentialManager, the application may need to
request permission to access specific Google APIs on the user's behalf. This is where
AuthorizationClient comes into play.  
Primary Goal:  
The main objective of this step is to request the user's consent for specific Google API scopes (
e.g., read-only access to Gmail, access to Google Drive app data folder). If the application
requires the ability to access these APIs when the user is not actively using the app (offline
access), this step is also used to obtain a serverAuthCode. This code is a one-time authorization
code that the application can then exchange for an access token and, crucially, a refresh token.8  
Key Components & Flow:  
The authorization process using AuthorizationClient involves these components:

1. **Identity.getAuthorizationClient(activity)**: This static method is used to obtain an instance
   of AuthorizationClient.8
2. **AuthorizationRequest.Builder()**: This builder is used to construct the AuthorizationRequest
   object, specifying the details of the permissions being sought.
    * setRequestedScopes(List\<Scope\> scopes): This method is used to define the list of OAuth 2.0
      scopes your application requires. For example, to request permission to read Gmail messages,
      you would include a scope like new Scope("https://mail.google.com/").8 It's a best practice to
      request scopes incrementally and only when needed for a specific feature, rather than asking
      for all possible permissions upfront.8
    * requestOfflineAccess(String serverClientId): This is the key method to call if your
      application needs a refresh token for offline API access. It signals to Google that you are
      requesting an authorization code that can be exchanged for a refresh token. The serverClientId
      parameter here is, once again, the **Web Client ID** from your Google Cloud Console project.8
    * requestOfflineAccess(String serverClientId, boolean forceCodeForRefreshToken): This is an
      overloaded version of the previous method. The forceCodeForRefreshToken boolean parameter, if
      set to true, ensures that a new refresh token is granted upon code exchange, even if one was
      previously issued. This is typically used in recovery scenarios where a server might have lost
      a user's refresh token. By default, or if false, a refresh token is usually granted only the
      first time offline access is requested for a given set of scopes by that client.22 For most
      initial requests, the simpler requestOfflineAccess(String serverClientId) is sufficient.
    * Other methods like setAccount(Account account) or filterByHostedDomain(String hostedDomain)
      can also be used to refine the request.22
3. **AuthorizationClient.authorize(request)**: This asynchronous method initiates the authorization
   flow. It returns a Task\<AuthorizationResult\>. If the requested scopes have not been previously
   granted by the user for this application, or if explicit consent is required (e.g., for sensitive
   scopes or when forceCodeForRefreshToken is true), Google will display a consent screen to the
   user.8
4. **AuthorizationResult**: This object is returned upon the completion of the authorize task.
    * hasResolution(): If this method returns true, it means user interaction is required (i.e., the
      consent screen needs to be shown). The AuthorizationResult will contain a PendingIntent (
      accessible via getPendingIntent()) that the application must launch using
      startIntentSenderForResult() to display the consent UI.8 The outcome of this UI interaction
      will be delivered back to the activity's onActivityResult() method.
    * getServerAuthCode(): If offline access was requested (via requestOfflineAccess()) and the user
      granted the necessary permissions, this method returns the one-time serverAuthCode string.
      This code is essential for obtaining a refresh token.8 If offline access was not requested, or
      if it was denied, this method will return null.
    * getAccessToken(): This method may return a short-lived access token directly if the requested
      scopes have already been granted and a valid access token is available, or if offline access
      was not requested. This access token can be used immediately to call Google APIs for the
      granted scopes.23 However, if long-term/offline access is the goal, the serverAuthCode and
      subsequent refresh token are more important.
    * The AuthorizationResult from onActivityResult is retrieved using
      Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data).8

The serverAuthCode:  
It is crucial to understand that the serverAuthCode is a temporary, one-time-use authorization code.
It is not an access token or a refresh token itself. Its sole purpose is to be securely exchanged
with Google's token endpoint for an access token and, if offline access was requested, a refresh
token.  
The AuthorizationClient is thus the indispensable gateway for an application to request specific API
permissions and to obtain the serverAuthCode necessary for long-term offline access via refresh
tokens. Any Android application that needs to interact with Google APIs beyond the basic user
profile information (which might be derivable from the ID Token after validation) *must* use the
AuthorizationClient to request the appropriate scopes. If offline access is a requirement, then
calling requestOfflineAccess() within the AuthorizationRequest is mandatory to obtain the
serverAuthCode.  
The following table contrasts the roles of CredentialManager (for Google Sign-In) and
AuthorizationClient:

| Feature                                        | CredentialManager (GetSignInWithGoogleOption)                       | AuthorizationClient                                                                       |
|:-----------------------------------------------|:--------------------------------------------------------------------|:------------------------------------------------------------------------------------------|
| **Primary Goal**                               | User Authentication (Sign-in/Sign-up). 2                            | User Authorization (Granting API access). 2                                               |
| **Input Configuration (Key Methods)**          | setServerClientId(), setNonce(), setFilterByAuthorizedAccounts(). 9 | setRequestedScopes(), requestOfflineAccess(), setAccount(). 8                             |
| **Key Output**                                 | GoogleIdTokenCredential containing an ID Token. 9                   | AuthorizationResult containing serverAuthCode (for offline access) and/or Access Token. 8 |
| **Handles API Scopes?**                        | No, primarily for authentication scopes (openid, email, profile). 9 | Yes, explicitly requests API-specific scopes. 8                                           |
| **Provides serverAuthCode for Refresh Token?** | No. 9                                                               | Yes, if requestOfflineAccess() is used. 8                                                 |

## **4\. Handling Tokens in a Purely Mobile App (No Developer Backend)**

### **The Challenge for Mobile-Only Apps**

A traditional OAuth 2.0 Authorization Code Flow often involves a backend server exchanging the
serverAuthCode for access and refresh tokens. This exchange typically requires the server to
authenticate itself to the token endpoint using its client\_id and client\_secret. However, mobile
applications are classified as "public clients" and cannot securely store a client\_secret.4
Attempting to embed a client secret within a mobile app would make it vulnerable to extraction and
misuse. This presents a challenge for purely mobile apps that need to obtain refresh tokens for
offline API access without a dedicated developer backend.

### **Solution: OAuth 2.0 Authorization Code Flow with PKCE (Proof Key for Code Exchange)**

The solution for public clients like mobile apps is the OAuth 2.0 Authorization Code Flow augmented
with the Proof Key for Code Exchange (PKCE) extension, as defined in RFC 7636\. PKCE allows a mobile
app to securely exchange an authorization code for tokens without needing a client secret.4  
PKCE Overview:  
PKCE introduces two new parameters into the OAuth flow:

1. code\_verifier: A high-entropy cryptographically random string generated by the client
   application for each authorization request.
2. code\_challenge: A transformation of the code\_verifier (typically a Base64URL-encoded SHA256
   hash of the verifier, known as the S256 method).

How PKCE Works with Google APIs for Android Apps:  
The flow for a mobile-only Android app using PKCE to obtain tokens from Google is as follows:

1. **Generate PKCE Parameters:** The Android app (often facilitated by a library like AppAuth)
   generates a unique code\_verifier for the authorization request. It then computes the
   corresponding code\_challenge using the S256 method.7
2. **Initiate Authorization Request:** The app initiates an authorization request to Google's
   authorization endpoint (e.g., https://accounts.google.com/o/oauth2/v2/auth). This request
   includes the client\_id (the app's Android Client ID), response\_type=code, the desired scope (
   including API-specific scopes and potentially offline\_access implicitly if refresh tokens are
   expected for installed apps), the redirect\_uri configured for the app, the code\_challenge, and
   the code\_challenge\_method=S256.7 A state parameter is also recommended for CSRF protection.
3. **User Authentication and Consent:** Google authenticates the user and prompts for consent to the
   requested scopes.
4. **Receive Authorization Code:** If the user grants consent, Google's authorization server
   redirects back to the app's redirect\_uri with the serverAuthCode (authorization code) and the
   state parameter.
5. **Exchange Authorization Code for Tokens:** The app then makes a direct HTTPS POST request to
   Google's token endpoint (e.g., https://oauth2.googleapis.com/token).7

Token Endpoint Request Parameters for Mobile App (PKCE):  
The request to the token endpoint must include the following parameters 7:

* grant\_type: Must be set to authorization\_code.
* code: The serverAuthCode received in the previous step.
* redirect\_uri: The exact same redirect\_uri used in the initial authorization request.
* client\_id: The app's **Android Client ID**.
* code\_verifier: The original, unmodified code\_verifier string that the app generated in step 1\.
  Importantly, **no client\_secret is sent** in this request, as Android clients are public
  clients.4

Token Endpoint Response:  
Google's token server verifies the code\_verifier against the code\_challenge associated with the
authorization code. If the verification is successful, the server responds with a JSON object
containing:

* access\_token: A short-lived token used to make API requests.
* refresh\_token: A long-lived token (if offline access was requested/granted) used to obtain new
  access tokens when the current one expires.
* expires\_in: The lifetime of the access token in seconds.
* scope: The scopes for which access was actually granted.
* id\_token: If OpenID Connect scopes like openid, email, or profile were requested.

### **Role of Libraries like AppAuth for Android**

Implementing the OAuth 2.0 Authorization Code Flow with PKCE correctly, including managing redirect
URIs, handling Custom Tabs, making secure token requests, and managing token refresh logic, can be
complex and error-prone. The OpenID Foundation provides the AppAuth for Android library, which is
specifically designed to handle these complexities and adhere to current best practices for native
apps.4  
AppAuth handles many aspects of the OAuth 2.0 flow:

* **Service Discovery:** It can automatically discover authorization and token endpoints from a
  provider's discovery document (e.g., Google's .well-known/openid-configuration).
* **Authorization Request Construction:** It helps build AuthorizationRequest objects, automatically
  managing PKCE parameter generation.
* **Custom Tabs Integration:** It uses Custom Tabs for presenting the authorization UI, which is
  more secure than WebViews as it leverages the user's trusted browser environment and prevents the
  app from accessing user credentials directly.
* **Token Exchange:** It encapsulates the logic for making the token request to exchange the
  authorization code for tokens.
* **Token Refresh:** It provides mechanisms (AuthState.performActionWithFreshTokens) to
  automatically refresh access tokens using the refresh token when they expire.
* **State Management:** The AuthState object in AppAuth serializes all relevant request and token
  response data, making it easier to persist and restore the user's authorization state.7

While AuthorizationClient is used to obtain the serverAuthCode from Google when specific Google API
scopes are needed 8, the subsequent step of exchanging this code for tokens in a purely mobile app
scenario is where AppAuth excels. AppAuth provides a robust, secure, and standardized way for the
app to perform this client-side exchange using PKCE and the app's Android Client ID. It abstracts
the low-level HTTP interactions and PKCE mechanics, reducing the likelihood of implementation
errors. Google's own documentation on native app OAuth also recommends using libraries like
AppAuth.7

### **Securely Storing Tokens on the Device**

Once access and refresh tokens are obtained, they must be stored securely on the device, as they
grant access to sensitive user data. Refresh tokens are particularly sensitive due to their
long-lived nature.  
Recommended Storage Strategy:  
The current best practice for storing sensitive data like OAuth tokens on Android involves a
combination of the Android Keystore system and SharedPreferences (or a local database for more
complex data structures).

1. Android Keystore for Encryption Keys:  
   The Android Keystore system allows applications to generate and store cryptographic keys in a
   hardware-backed or TEE (Trusted Execution Environment) secured container. The key material itself
   is designed to be non-exportable and protected from extraction, even if the OS is compromised or
   the app's process is hijacked.42
    * Apps should use the AndroidKeyStore provider to generate a symmetric encryption key (e.g.,
      AES-GCM). This key will be used to encrypt the OAuth tokens.
    * The generated key is stored and managed by the Android Keystore, and cryptographic
      operations (encryption/decryption) using this key are performed by system processes, so the
      raw key material never enters the application's process space.
2. Encrypt Tokens Before Storage:  
   Before writing tokens to persistent storage, they should be encrypted using the key obtained from
   the Android Keystore.
    * If using AppAuth, the AuthState object, which contains all tokens and related metadata, can be
      serialized to a JSON string.7 This JSON string is then encrypted.
    * Standard Java Cryptography Architecture (JCA) classes like javax.crypto.Cipher are used with
      the Keystore-backed key for encryption (e.g., AES/GCM/NoPadding). The resulting ciphertext and
      the Initialization Vector (IV) used for encryption must be stored.
3. SharedPreferences for Encrypted Data:  
   The encrypted token data (ciphertext and IV) can then be stored in standard SharedPreferences.
    * The androidx.security:security-crypto library, which provided EncryptedSharedPreferences, was
      a wrapper that simplified this process. However, this library is now **deprecated** as of May
      2025\.51 The official guidance is to move towards using platform APIs and direct Keystore
      interaction for encryption.
    * Therefore, developers now need to implement the encryption/decryption logic themselves using
      Cipher and a Keystore-backed key, and then store the resulting byte array (typically Base64
      encoded for SharedPreferences).45
    * Google's OAuth best practices explicitly recommend using "Keystore on Android" for storing
      user tokens.47

Retrieval and Decryption:  
When the app needs to use the tokens, it retrieves the encrypted data (ciphertext and IV) from
SharedPreferences. It then uses the same Keystore-backed key and Cipher (initialized for decryption
with the stored IV) to decrypt the data back into its original form (e.g., the AuthState JSON
string, which can then be deserialized).  
This approach ensures that even if an attacker gains access to the app's SharedPreferences file (
e.g., on a rooted device), the tokens remain encrypted and unusable without the Keystore-protected
encryption key. The security of the tokens thus hinges on the security of the Android Keystore.
While libraries like Google's Tink can offer robust cryptographic implementations, the fundamental
interaction with Android Keystore for key management is key.46

## **5\. The Role of android.accounts.AccountManager in the Modern Era**

### **Traditional Purpose and Functionality**

Android's android.accounts.AccountManager has traditionally served as the system's centralized
registry for managing users' online accounts and credentials on a device.53 Its core functionalities
include:

* **Centralized Account Storage:** It provides a common place for users to add and manage their
  accounts (e.g., Google, Exchange, app-specific accounts) via the device Settings.
* **Authenticator Framework:** AccountManager utilizes a pluggable authenticator system.
  Applications that define a new account type provide a custom AbstractAccountAuthenticator
  implementation. This authenticator is responsible for handling the specifics of credential
  validation, token acquisition, and storage for that account type.53
* **Token Management:** For accounts managed through it, AccountManager can generate, cache, and
  provide authentication tokens to applications. This allows apps to access online services without
  directly handling user passwords. It relies on the authenticator to refresh tokens when they
  expire.53
* **Single Sign-On (SSO) and System Integration:** It facilitates SSO-like experiences, where adding
  an account (like a Google account) makes it available to multiple apps with user consent. It also
  supports background data synchronization by providing tokens to sync adapters.

### **CredentialManager vs. AccountManager: Division of Responsibilities (as of May 2025\)**

With the introduction and promotion of androidx.credentials.CredentialManager, the roles and
responsibilities in Android's identity landscape have become more nuanced.

* **androidx.credentials.CredentialManager**:
    * **Primary Focus:** CredentialManager is a Jetpack API designed to **streamline and unify the
      user interface and interaction gestures for sign-in and sign-up**.1 It aims to provide a
      consistent "one-tap" experience across various authentication methods.
    * **Supported Credentials:** It supports passwords, passkeys, and federated identity providers
      like Sign in with Google through a single, consolidated API.
    * **Successor to Legacy APIs:** It is positioned as the successor to older authentication APIs
      such as Google Sign-In for Android, Smart Lock for Passwords, and One Tap sign-in, all of
      which are deprecated or removed.1
    * **Integration with Password Managers:** A key feature is its ability to integrate with
      password managers, including third-party password managers on Android 14 and later, to surface
      saved credentials to the user.1
* **android.accounts.AccountManager**:
    * **Primary Focus:** AccountManager remains the Android operating system's **centralized,
      lower-level store for various account types** and their associated authenticators. It's a
      system service responsible for the persistence and management of accounts beyond the immediate
      sign-in or sign-up moment.53
    * **System-Level Integration:** It enables accounts to be visible and manageable through the
      device's system Settings and allows for system-level features like background synchronization.

The distinction can be thought of as CredentialManager being the modern, user-facing API for the
*act* of authentication (the sign-in/sign-up gesture), while AccountManager is the underlying system
component for *storing and managing* accounts that are registered with the OS. CredentialManager
simplifies how users interact with authentication prompts by abstracting the underlying credential
sources, which can include accounts managed by AccountManager (like Google accounts) or credentials
from password managers. The deprecation of APIs like Google Sign-In points to CredentialManager as
the new standard for *initiating* these authentication flows, not necessarily a complete replacement
for all of AccountManager's account storage and system integration capabilities.

### **Interoperability**

* **Sign in with Google via CredentialManager**: When a user signs in with Google through
  CredentialManager, the flow leverages the Google accounts already present on the device. These
  Google accounts are managed by AccountManager through Google's own AbstractAccountAuthenticator
  implementation.9 Thus, CredentialManager directly interoperates with AccountManager for
  system-level accounts like Google.
* **Password Managers and CredentialManager**: CredentialManager is designed to surface credentials
  stored by installed password managers.1 These password managers may or may not use AccountManager
  internally; many have their own secure storage mechanisms.
* **Custom AbstractAccountAuthenticator and CredentialManager**: For an app's custom account type (
  managed by its own AbstractAccountAuthenticator via AccountManager) to be discoverable and usable
  through the unified CredentialManager sign-in prompts, the app would likely need to implement a
  CredentialProviderService.2 This service acts as a bridge, allowing CredentialManager to query it
  for credentials. This suggests a shift: while AccountManager still stores the custom account, the
  CredentialProviderService is the modern way to expose those credentials to the CredentialManager's
  unified UI. Direct querying of AccountManager by other apps for custom account types might become
  less common if CredentialManager becomes the universal broker for sign-in credentials.

### **Relevance for Storing App-Specific OAuth Tokens in 2025**

Historically, AccountManager offered methods like setAuthToken(), getAuthToken(), and setUserData()
/getUserData(), which could be used by an authenticator (or an app with appropriate permissions) to
store arbitrary strings, including OAuth tokens, associated with an account.53  
**Security Considerations for AccountManager Token Storage:**

* **Potential for Plaintext Storage:** By default, AccountManager itself doesn't enforce encryption
  for arbitrary data stored via setAuthToken or setUserData. The security of such tokens often
  relies on the specific AbstractAccountAuthenticator implementation to encrypt them before passing
  them to AccountManager, or it relies on the sandboxed nature of the AccountManager database.48
  While access is restricted, data might be stored in plaintext within that database, making it
  vulnerable if root access is gained.
* **Focus on Access Control:** AccountManager's security model for tokens has traditionally focused
  more on controlling which applications can *request* a token for a given account, rather than
  strong cryptographic protection of the token string itself at rest, unless the authenticator
  implements such encryption.

**Comparison with Keystore \+ SharedPreferences for App-Obtained Tokens:**

* **Android Keystore Approach:** This method emphasizes using the Android Keystore to generate and
  protect an *encryption key*. This key is then used by the application to encrypt the OAuth
  tokens (e.g., access and refresh tokens obtained via a PKCE flow). The encrypted tokens are then
  stored in SharedPreferences or a local database.43
    * **Pros:** Provides strong cryptographic protection for the tokens at rest, with the encryption
      key itself being secured by hardware-backed features where available. The application has
      direct control over the encryption process.
    * **Cons:** Requires more boilerplate code from the developer to implement the
      encryption/decryption logic.

Current Recommendation (Inferred for App-Obtained Tokens):  
For OAuth tokens that an application obtains itself (e.g., after performing a client-side PKCE
exchange using AppAuth, subsequent to AuthorizationClient providing a serverAuthCode), the most
secure and recommended practice is to encrypt these tokens using keys managed by the Android
Keystore and then store the resulting ciphertext. CredentialManager itself does not appear to offer
a generic API for securely storing arbitrary post-authentication tokens.27  
The rationale is that these tokens are sensitive credentials specific to the app's session with an
OAuth-protected service. The strongest on-device protection for such arbitrary sensitive data
involves application-controlled encryption using Keystore-backed keys. While AccountManager *can*
store string data, its primary design is for accounts and tokens vended *by authenticators* for
those centrally managed accounts. For tokens an app fetches and manages itself, direct encryption
offers clearer and more robust security guarantees under the app's control. Therefore, developers
should favor using Android Keystore to encrypt OAuth tokens and store them in SharedPreferences or a
local database, rather than attempting to store them as generic data within AccountManager,
especially if not building a full custom AbstractAccountAuthenticator that implements its own robust
encryption for these tokens.  
The following table summarizes the modern roles of AccountManager and CredentialManager:

| Aspect                                   | android.accounts.AccountManager                                                                                                                    | androidx.credentials.CredentialManager                                                                            |
|:-----------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------------|
| **Primary Purpose**                      | System-level centralized storage and management of user accounts and authenticators. 53                                                            | Unified API and UI for user sign-in and sign-up gestures across multiple credential types. 1                      |
| **Credential Types Handled**             | Various account types via pluggable AbstractAccountAuthenticators (e.g., Google, Exchange, custom). 53                                             | Passwords, Passkeys, Federated IDs (e.g., Sign in with Google). 1                                                 |
| **UI/Gesture Focus**                     | Primarily system settings UI for account management; authenticators provide login UI. 53                                                           | Provides a unified, app-invoked UI (e.g., bottom sheet) for selecting credentials at sign-in/sign-up. 1           |
| **System-Level Integration**             | Core OS component; accounts visible in device Settings; used for background sync. 53                                                               | Jetpack library; integrates with system password managers and AccountManager for existing accounts like Google. 1 |
| **Storage of App-Obtained OAuth Tokens** | Can store arbitrary data via setUserData, but security relies on authenticator/default storage. Not primary design for app-specific raw tokens. 48 | Does not offer a direct API for storing arbitrary app-obtained OAuth tokens post-authentication. 27               |
| **Primary Interaction Mode**             | API for apps/authenticators to interact with a system service. 53                                                                                  | API for apps to initiate authentication flows. 2                                                                  |
| **Extensibility**                        | Via custom AbstractAccountAuthenticator implementations. 56                                                                                        | Via CredentialProviderService for third-party credential providers to surface credentials. 2                      |

## **6\. Conclusion: Best Practices for Mobile-Only Google Authentication & Authorization**

Navigating Google's identity services for purely mobile Android applications requires a clear
understanding of modern APIs and OAuth 2.0 principles. The landscape has shifted towards a
separation of authentication and authorization, with CredentialManager and AuthorizationClient being
the primary tools.  
**Recap of the Recommended Flow:**

1. **Project Setup in Google Cloud Console**: Critically, applications must configure *both* an *
   *Android Client ID** (with package name and SHA-1 fingerprint) and a **Web Client ID** (also
   known as a server client ID). The Android Client ID secures on-device interactions, while the Web
   Client ID is used as an identifier in API calls that request serverAuthCode or configure ID
   tokens for server-side validation, effectively representing the overall application entity for
   these grants.8
2. **Step 1: Authentication**: Utilize CredentialManager with GetSignInWithGoogleOption (or
   GetGoogleIdOption). Configure it using setServerClientId() with the **Web Client ID** and
   preferably setNonce(). This step authenticates the user and yields a GoogleIdTokenCredential
   containing an ID Token.9 This ID Token primarily serves to prove the user's identity. It does
   *not* directly provide an authorization code for API access.
3. **Step 2: Authorization (If API Access is Needed)**: If the application needs to access Google
   APIs (e.g., Gmail, Drive), use AuthorizationClient. Construct an AuthorizationRequest specifying
   the required API scopes using setRequestedScopes(). If offline access (and thus a refresh token)
   is necessary, call requestOfflineAccess() on the builder, providing the **Web Client ID** again.
   This flow, upon successful user consent, yields an AuthorizationResult which may contain a
   serverAuthCode (if offline access was requested and granted) and/or a short-lived access token
   for the requested scopes.8
4. **Token Exchange (Client-Side for Mobile-Only Apps)**: The serverAuthCode is a one-time code. For
   a purely mobile application without a developer-managed backend, this code must be exchanged for
   access and refresh tokens using the OAuth 2.0 Authorization Code Flow with PKCE. The recommended
   approach is to use a library like **AppAuth for Android**. AppAuth will handle the PKCE
   mechanics, making a POST request to Google's token endpoint. This request will use the
   application's **Android Client ID** and the code\_verifier (part of PKCE), and importantly, will
   *not* involve a client secret.7
5. **Secure Token Storage**: Once access and refresh tokens are obtained (often encapsulated within
   AppAuth's AuthState object), they must be stored securely. The refresh token is particularly
   sensitive. The recommended method is to:
    * Generate a symmetric encryption key (e.g., AES-GCM) using the **Android Keystore system** (
      AndroidKeyStore provider).
    * Encrypt the tokens (or the serialized AuthState JSON string) using this Keystore-backed key.
    * Store the resulting ciphertext (and IV) in standard SharedPreferences.45 The
      androidx.security:security-crypto library (which provided EncryptedSharedPreferences) is
      deprecated; direct Keystore interaction is now favored.51
6. **Token Usage and Refresh**: Utilize the stored refresh token (managed securely, potentially via
   AppAuth's AuthState mechanisms) to obtain new access tokens when existing ones expire. AppAuth's
   performActionWithFreshTokens() can automate this.40

**Key Security Considerations:**

* **PKCE is Non-Negotiable:** Always use PKCE for client-side authorization code exchange in mobile
  apps to protect against code interception attacks.
* **Robust Token Storage:** Employ the Android Keystore for protecting encryption keys used to
  encrypt tokens at rest.
* **Principle of Least Privilege:** Request only the OAuth scopes that are minimally necessary and
  request them contextually when the feature requiring them is invoked.
* **Token Lifecycle Management:** Handle token expiration gracefully and provide mechanisms for
  token revocation if necessary.
* **Library Updates:** Keep all identity and security-related libraries (CredentialManager,
  AuthorizationClient, AppAuth, etc.) up-to-date to benefit from the latest security patches and
  features.
* **Redirect URI Security:** Ensure redirect URIs are correctly configured and handled to prevent
  hijacking. Using App Links or verified custom schemes is preferable.

The Android identity ecosystem is dynamic. While AccountManager has historically played a central
role in account storage and token management, its direct use by applications for storing
self-acquired OAuth tokens is less emphasized now compared to direct Keystore-based encryption.
CredentialManager has become the standard for user-facing authentication gestures, interoperating
with AccountManager for system accounts like Google, but pushing towards CredentialProviderService
implementations for broader third-party credential integration. Developers should prioritize the
current best practices outlined by Google and the OpenID Foundation and stay informed of ongoing
developments in Android identity and security.

#### **Works cited**

1. Streamlining Android authentication ... \- Android Developers Blog, accessed May 11,
   2025, [https://android-developers.googleblog.com/2024/09/streamlining-android-authentication-credential-manager-replaces-legacy-apis.html](https://android-developers.googleblog.com/2024/09/streamlining-android-authentication-credential-manager-replaces-legacy-apis.html)
2. Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity](https://developer.android.com/identity)
3. Migrate from legacy Google Sign-In to Credential Manager and AuthorizationClient | Identity,
   accessed May 11,
   2025, [https://developer.android.com/identity/sign-in/legacy-gsi-migration](https://developer.android.com/identity/sign-in/legacy-gsi-migration)
4. Using OAuth 2.0 to Access Google APIs | Authorization, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2](https://developers.google.com/identity/protocols/oauth2)
5. Authentication methods at Google, accessed May 11,
   2025, [https://cloud.google.com/docs/authentication](https://cloud.google.com/docs/authentication)
6. Set up OAuth for your Android app | Home APIs, accessed May 11,
   2025, [https://developers.home.google.com/apis/android/oauth](https://developers.home.google.com/apis/android/oauth)
7. OAuth 2.0 for Mobile & Desktop Apps | Authorization \- Google for Developers, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/native-app](https://developers.google.com/identity/protocols/oauth2/native-app)
8. Authorize access to Google user data | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
9. Authenticate users with Sign in with Google | Identity | Android ..., accessed May 11,
   2025, [https://developer.android.com/identity/sign-in/credential-manager-siwg](https://developer.android.com/identity/sign-in/credential-manager-siwg)
10. Manage OAuth Clients \- Google Cloud Platform Console Help, accessed May 11,
    2025, [https://support.google.com/cloud/answer/15549257?hl=en](https://support.google.com/cloud/answer/15549257?hl=en)
11. Authorization Code Flow with Proof Key for Code Exchange (PKCE) \- Auth0, accessed May 11,
    2025, [https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce](https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce)
12. What is the preferred way for getting an access token for a Google API by using Google Sign-in
    for Android? \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/62356773/what-is-the-preferred-way-for-getting-an-access-token-for-a-google-api-by-using](https://stackoverflow.com/questions/62356773/what-is-the-preferred-way-for-getting-an-access-token-for-a-google-api-by-using)
13. PKCE OAuth and the Meeting SDK for Android \- Zoom Developer Platform, accessed May 11,
    2025, [https://developers.zoom.us/docs/meeting-sdk/android/build-an-app/pkce/](https://developers.zoom.us/docs/meeting-sdk/android/build-an-app/pkce/)
14. Build an Android App Using OAuth 2.0 and PKCE \- Cloudentity, accessed May 11,
    2025, [https://cloudentity.com/developers/app-dev-tutorials/android/android\_pkce\_tutorial/](https://cloudentity.com/developers/app-dev-tutorials/android/android_pkce_tutorial/)
15. Enabling Server-Side Access | Identity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/identity/legacy/gsi/offline-access](https://developer.android.com/identity/legacy/gsi/offline-access)
16. What Are Refresh Tokens and How to Use Them Securely | Auth0, accessed May 11,
    2025, [https://auth0.com/blog/refresh-tokens-what-are-they-and-when-to-use-them/](https://auth0.com/blog/refresh-tokens-what-are-they-and-when-to-use-them/)
17. Refresh access tokens and rotate refresh tokens \- Okta Developer, accessed May 11,
    2025, [https://developer.okta.com/docs/guides/refresh-tokens/main/](https://developer.okta.com/docs/guides/refresh-tokens/main/)
18. \[Partner Bug\]login with google always got ... \- Issue Tracker, accessed May 11,
    2025, [https://issuetracker.google.com/issues/385925137](https://issuetracker.google.com/issues/385925137)
19. \[Partner Bug\] \[397720952\] \- Issue Tracker \- Google, accessed May 11,
    2025, [https://issuetracker.google.com/issues/397720952](https://issuetracker.google.com/issues/397720952)
20. Add Sign In with Google to Native Android Apps \- Auth0, accessed May 11,
    2025, [https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native](https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native)
21. Authenticate with Google on Android | Firebase Authentication, accessed May 11,
    2025, [https://firebase.google.com/docs/auth/android/google-signin](https://firebase.google.com/docs/auth/android/google-signin)
22. AuthorizationRequest.Builder | Google Play services | Google for ..., accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
23. Google Identity Authorization (Android) : how to get refresh token? \- Stack Overflow, accessed
    May 11,
    2025, [https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token](https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token)
24. Enabling Server-Side Access | Identity | Android Developers, accessed May 11,
    2025, [https://developers.google.com/identity/sign-in/android/offline-access](https://developers.google.com/identity/sign-in/android/offline-access)
25. androidx.credentials | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/androidx/credentials/package-summary](https://developer.android.com/reference/androidx/credentials/package-summary)
26. accessed December 31,
    1969, [https://android.googlesource.com/platform/frameworks/support/+/androidx-main/credentials/credentials-play-services-auth/src/main/java/androidx/credentials/playservicesauth/GoogleIdTokenCredential.kt](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/credentials/credentials-play-services-auth/src/main/java/androidx/credentials/playservicesauth/GoogleIdTokenCredential.kt)
27. Sign in your user with Credential Manager | Identity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/credential-manager](https://developer.android.com/identity/sign-in/credential-manager)
28. accessed December 31,
    1969, [https://developer.android.com/reference/androidx/credentials/playservicesauth/GoogleIdTokenCredential](https://developer.android.com/reference/androidx/credentials/playservicesauth/GoogleIdTokenCredential)
29. accessed December 31,
    1969, [https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/credentials/credentials-play-services-auth/src/main/java/androidx/credentials/playservicesauth/GoogleIdTokenCredential.java](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/credentials/credentials-play-services-auth/src/main/java/androidx/credentials/playservicesauth/GoogleIdTokenCredential.java)
30. accessed December 31,
    1969, [https://developer.android.com/reference/androidx/credentials/playservicesauth/package-summary](https://developer.android.com/reference/androidx/credentials/playservicesauth/package-summary)
31. GetCredentialResponse | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/androidx/credentials/GetCredentialResponse](https://developer.android.com/reference/androidx/credentials/GetCredentialResponse)
32. accessed December 31,
    1969, [https://developers.google.com/identity/sign-in/android/reference](https://developers.google.com/identity/sign-in/android/reference)
33. Using OAuth 2.0 for Web Server Applications | Authorization \- Google for Developers, accessed
    May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2/web-server](https://developers.google.com/identity/protocols/oauth2/web-server)
34. GoogleSignInOptions.Builder | Google Play services | Google for ..., accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInOptions.Builder\#requestServerAuthCode(java.lang.String,boolean)](https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInOptions.Builder#requestServerAuthCode\(java.lang.String,boolean\))
35. Loopback IP Address flow Migration Guide | Authorization \- Google for Developers, accessed May
    11,
    2025, [https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)
36. Call Your API Using the Authorization Code Flow with PKCE \- Auth0, accessed May 11,
    2025, [https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce/call-your-api-using-the-authorization-code-flow-with-pkce](https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce/call-your-api-using-the-authorization-code-flow-with-pkce)
37. Google OAuth 2.0 auth code flow with PKCE: refresh token example \- Delphi-PRAXiS \[en\],
    accessed May 11,
    2025, [https://en.delphipraxis.net/topic/12536-google-oauth-20-auth-code-flow-with-pkce-refresh-token-example/](https://en.delphipraxis.net/topic/12536-google-oauth-20-auth-code-flow-with-pkce-refresh-token-example/)
38. Insecure API usage | Security \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/privacy-and-security/risks/insecure-api-usage](https://developer.android.com/privacy-and-security/risks/insecure-api-usage)
39. Security checklist | Android Developers, accessed May 11,
    2025, [https://developer.android.com/training/articles/security-tips](https://developer.android.com/training/articles/security-tips)
40. AppAuth for Android by openid \- OpenID on GitHub \- GitHub Pages, accessed May 11,
    2025, [https://openid.github.io/AppAuth-Android/](https://openid.github.io/AppAuth-Android/)
41. Migrate from legacy Google Sign-In to Credential Manager and ..., accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/legacy-gsi-migration\#authorization](https://developer.android.com/identity/sign-in/legacy-gsi-migration#authorization)
42. SECURE CREDENTIAL STORAGE IN MOBILE APPLICATIONS | Grootan Technologies, accessed May 11,
    2025, [https://www.grootan.com/blogs/secure-credential-storage-in-mobile-applications/](https://www.grootan.com/blogs/secure-credential-storage-in-mobile-applications/)
43. Android Keystore system | Security \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/privacy-and-security/keystore](https://developer.android.com/privacy-and-security/keystore)
44. Verify hardware-backed key pairs with key attestation | Security \- Android Developers, accessed
    May 11,
    2025, [https://developer.android.com/privacy-and-security/security-key-attestation](https://developer.android.com/privacy-and-security/security-key-attestation)
45. Android Keystore system | Security | Android Developers, accessed May 11,
    2025, [https://developer.android.com/training/articles/keystore](https://developer.android.com/training/articles/keystore)
46. Security checklist | Android Developers, accessed May 11,
    2025, [https://developer.android.com/privacy-and-security/security-tips](https://developer.android.com/privacy-and-security/security-tips)
47. Best Practices | Authorization \- Google for Developers, accessed May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2/resources/best-practices](https://developers.google.com/identity/protocols/oauth2/resources/best-practices)
48. How to securely store access token and secret in Android? \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/10161266/how-to-securely-store-access-token-and-secret-in-android](https://stackoverflow.com/questions/10161266/how-to-securely-store-access-token-and-secret-in-android)
49. Which is more secure: EncryptedSharedPreferences or storing directly in KeyStore?, accessed May
    11,
    2025, [https://stackoverflow.com/questions/78021794/which-is-more-secure-encryptedsharedpreferences-or-storing-directly-in-keystore](https://stackoverflow.com/questions/78021794/which-is-more-secure-encryptedsharedpreferences-or-storing-directly-in-keystore)
50. Building Secure Mobile Apps: iOS and Android Security Best Practices in 2025, accessed May 11,
    2025, [https://200oksolutions.com/blog/building-secure-mobile-apps-ios-and-android-security-best-practices-in-2025/](https://200oksolutions.com/blog/building-secure-mobile-apps-ios-and-android-security-best-practices-in-2025/)
51. Security | Jetpack | Android Developers, accessed May 11,
    2025, [https://developer.android.com/jetpack/androidx/releases/security](https://developer.android.com/jetpack/androidx/releases/security)
52. EncryptedSharedPreferences | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
53. AccountManager Class (Android.Accounts) | Microsoft Learn, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/dotnet/api/android.accounts.accountmanager?view=net-android-35.0](https://learn.microsoft.com/en-us/dotnet/api/android.accounts.accountmanager?view=net-android-35.0)
54. What should I use Android AccountManager for? \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/2720315/what-should-i-use-android-accountmanager-for](https://stackoverflow.com/questions/2720315/what-should-i-use-android-accountmanager-for)
55. AccountManager | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/android/accounts/AccountManager](https://developer.android.com/reference/android/accounts/AccountManager)
56. AbstractAccountAuthenticator Class (Android.Accounts) | Microsoft Learn, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/dotnet/api/android.accounts.abstractaccountauthenticator?view=net-android-35.0](https://learn.microsoft.com/en-us/dotnet/api/android.accounts.abstractaccountauthenticator?view=net-android-35.0)
57. AbstractAccountAuthenticator Class (Android.Accounts) \- Microsoft Learn, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/dotnet/api/android.accounts.abstractaccountauthenticator?view=net-android-34.0](https://learn.microsoft.com/en-us/dotnet/api/android.accounts.abstractaccountauthenticator?view=net-android-34.0)
58. shiftconnects/android-auth-manager \- GitHub, accessed May 11,
    2025, [https://github.com/shiftconnects/android-auth-manager](https://github.com/shiftconnects/android-auth-manager)
59. How to Sign Up and Log In with Passkeys in Android Using Auth0's Native Login, accessed May 11,
    2025, [https://auth0.com/blog/how-to-signup-and-login-with-passkeys-android/](https://auth0.com/blog/how-to-signup-and-login-with-passkeys-android/)
60. Integrate Credential Manager with your credential provider solution \- Android Developers,
    accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/credential-provider](https://developer.android.com/identity/sign-in/credential-provider)