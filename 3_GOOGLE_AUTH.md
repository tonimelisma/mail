# **Evaluating OAuth 2.0 Client Strategies on Android: AppAuth, Google Credential Manager, and
Authorization Client**

## **1\. Executive Summary**

The implementation of secure and robust user authentication and authorization in native Android
applications, particularly using the OAuth 2.0 Authorization Code Flow with Proof Key for Code
Exchange (PKCE), presents significant complexities. This report addresses the critical question of
whether the AppAuth for Android library remains essential for managing these complexities, or if
newer Google offerings—specifically Google Credential Manager and the Google Authorization
Client—provide comparable, comprehensive functionality, especially when interacting with generic (
non-Google) OAuth 2.0 providers.  
The analysis concludes that **AppAuth for Android continues to be indispensable for applications
requiring full, client-side management of the OAuth 2.0 Authorization Code Flow with PKCE for
generic identity providers.** AppAuth is meticulously designed to handle the intricacies of this
flow, including service discovery, secure authorization request construction with PKCE, Custom Tabs
integration for user authentication, token exchange, automated token refresh, and persistent state
management, all while adhering to industry best practices like RFC 8252\.  
In contrast, Google Credential Manager is primarily an authentication facilitation API, aimed at
streamlining user sign-in experiences by unifying password, passkey, and "Sign in with Google"
functionalities. It is not designed to manage the complete client-side OAuth 2.0 authorization grant
flow for generic providers. The Google Authorization Client, part of Google Play services, is
predominantly focused on enabling access to Google APIs, typically by providing a serverAuthCode for
backend token exchange, rather than serving as a generic, client-side OAuth 2.0 library.  
While Google's libraries enhance the Android authentication landscape for Google-centric scenarios
and simplified credential management, they do not replicate the comprehensive, provider-agnostic
capabilities of AppAuth. This report will further detail these distinctions and explore Android's
built-in token refresh mechanisms, such as AccountManager, and their practical integration with
modern HTTP clients like Ktor, highlighting the considerations for thread-safe and robust
implementations.

## **2\. Understanding the OAuth 2.0 Authorization Code Flow with PKCE in Native Android Apps**

The OAuth 2.0 Authorization Code Flow with Proof Key for Code Exchange (PKCE) is the
industry-recommended standard for authorizing users in native applications. This flow allows an
application to obtain access tokens to call protected APIs on behalf of a user, without handling the
user's credentials directly.  
The core mechanics involve several steps:

1. **Authorization Request:** The application constructs an authorization request, including its
   client ID, requested scopes, a redirect URI, and PKCE challenge parameters. This request is sent
   to the authorization server's authorization endpoint.
2. **User Agent Interaction:** The request is opened in an external user-agent, typically a Custom
   Tab in Android. The user authenticates with the identity provider and grants or denies the
   application's request.
3. **Redirection with Authorization Code:** If the user grants authorization, the authorization
   server redirects the user-agent back to the application using the pre-registered redirect URI.
   This redirect includes an authorization code and the state parameter for CSRF protection.
4. **Token Request:** The application exchanges the authorization code (and the PKCE code verifier)
   for an access token and, typically, a refresh token by making a POST request to the authorization
   server's token endpoint.

Implementing this flow correctly in native Android applications presents several specific
challenges:

* **Managing Redirect URIs:** Native applications must declare redirect URIs that can uniquely
  identify the app. This involves using custom URI schemes (e.g., com.example.app:/callback) or, for
  Android API 23+, App Links (HTTPS URLs).1 Configuring these correctly and handling the incoming
  intent with the authorization response is crucial.1
* **Securely Launching the Authorization UI:** Using an embedded WebView for OAuth 2.0 is strongly
  discouraged and often blocked by identity providers (including Google) due to significant security
  risks.4 These risks include the app's ability to inspect or modify the web content, potentially
  capturing user credentials, and the lack of a shared browser session, forcing users to
  re-authenticate.5 The recommended approach is to use the device's default browser or a browser-tab
  mechanism like Chrome Custom Tabs.2 Custom Tabs provide a more secure context by leveraging the
  user's trusted browser environment and prevent the app from directly accessing user credentials.4
  They also offer a better user experience by maintaining the app's visual branding and sharing the
  browser's cookie jar.6
* **Implementing PKCE Correctly:** PKCE (RFC 7636\) is vital for public clients like native apps,
  which cannot securely store a client secret. It mitigates the authorization code interception
  attack. This involves generating a cryptographically random code\_verifier, deriving a
  code\_challenge (typically using S256 an SHA-256 hash), sending the code\_challenge and
  code\_challenge\_method in the authorization request, and then sending the code\_verifier in the
  token request.8 Errors in generation, transformation, or transmission can lead to failed
  authentications or security vulnerabilities.5
* **Making Secure Token Requests:** The token request must be a POST request to the token endpoint,
  including the grant type (authorization\_code), the received code, the redirect URI, the client
  ID, and the PKCE code\_verifier. Securely handling the HTTP request and parsing the JSON response
  containing the access token, refresh token, and token metadata is essential.
* **Managing Token Refresh Logic and State Persistence:** Access tokens are short-lived.
  Applications need to use the refresh token to obtain new access tokens without requiring the user
  to re-authenticate. This involves detecting token expiration, making a refresh token request (with
  grant\_type=refresh\_token), and securely storing and updating the tokens.9 The entire
  authorization state (tokens, expiration times, original request parameters) needs to be persisted
  securely.

The Internet Engineering Task Force (IETF) Best Current Practice document, RFC 8252 ("OAuth 2.0 for
Native Apps"), provides critical guidance for implementing OAuth 2.0 securely and usably in native
applications.1 Key recommendations include using external user-agents (like Custom Tabs instead of
WebViews) for the authorization request and mandating the use of PKCE.5 Adherence to RFC 8252 is
paramount to avoid common pitfalls such as redirect URI hijacking, authorization code theft, and
client impersonation. Common implementation mistakes often involve improper handling of redirect
URIs, incorrect PKCE validation, misuse or omission of the state parameter (leading to CSRF
vulnerabilities), and insecure token storage.5 These complexities underscore the need for robust
library support or extremely careful manual implementation.

## **3\. AppAuth for Android: A Comprehensive OAuth 2.0 Client Solution**

AppAuth for Android is an open-source client SDK provided by the OpenID Foundation, specifically
designed to facilitate communication with OAuth 2.0 and OpenID Connect providers.1 Its core
philosophy is to directly map the requests and responses of these specifications while adhering to
modern security and usability best practices for native applications, as outlined in RFC 8252\.7 It
is built to work with any compliant OAuth 2.0 / OIDC provider, not just a specific one.1  
AppAuth addresses the inherent complexities of native OAuth 2.0 implementations through several key
features:  
Service Discovery:  
AppAuth can automatically discover a provider's authorization and token endpoints by fetching and
parsing the OpenID Connect Discovery document, typically found at a .well-known/openid-configuration
path relative to the issuer URI.2 This populates an AuthorizationServiceConfiguration object,
simplifying the initial setup. Alternatively, these endpoints can be configured manually if
discovery is not supported by the provider or not desired.2 It is important to note that "Service
Discovery" in the context of AppAuth refers to this OIDC mechanism, not Android's Network Service
Discovery (NSD) which is used for discovering services on a local network.14  
Authorization Request Construction:  
The library assists in building AuthorizationRequest objects, which encapsulate all necessary
parameters for the authorization request.1 Crucially, AppAuth automatically handles the generation
of PKCE parameters: the code\_verifier (a high-entropy cryptographic random string), the
code\_challenge (derived from the verifier, typically using S256), and the code\_challenge\_method.1
This automation reduces the risk of errors in implementing this critical security feature.  
Custom Tabs Integration:  
AppAuth strictly adheres to the RFC 8252 recommendation of using an external user-agent for the
authorization part of the flow. On Android, it defaults to using Chrome Custom Tabs if a browser
supporting them (like Google Chrome) is installed. Otherwise, it falls back to the system browser.2
WebViews are explicitly not supported due to their associated security vulnerabilities and poorer
user experience, such as the inability to share browser sessions and the risk of credential
interception by the app.4 Custom Tabs provide a secure environment by running in the context of the
user's chosen browser, preventing the app from accessing credentials directly, and offering a more
seamless UX.6  
Token Exchange:  
AppAuth encapsulates the logic for making the token request. After the authorization flow
successfully returns an authorization code via the redirect URI, the app uses AppAuth to construct a
TokenRequest.1 This request includes the authorization code, the original redirect URI, the client
ID, and the PKCE code\_verifier. AppAuth then dispatches this request to the token endpoint (e.g.,
via AuthorizationService.performTokenRequest()) and processes the response to extract the access
token, refresh token, ID token (if OIDC), and their expiration times.2 For native apps (public
clients), client secrets are generally not used in this exchange, aligning with security best
practices.  
Token Refresh:  
Access tokens are typically short-lived for security reasons. AppAuth provides a convenient
mechanism for refreshing them using the AuthState.performActionWithFreshTokens() method.1 When an
action requiring a valid access token needs to be performed, this method is called. It internally
checks if the current access token is expired or close to expiring (considering a configurable
tolerance, EXPIRY\_TIME\_TOLERANCE\_MS 9). If a refresh is needed, it automatically constructs and
dispatches a TokenRequest with grant\_type=refresh\_token using the stored refresh token. Upon
successful refresh, it updates the AuthState with the new tokens and then executes the provided
action callback with the fresh access token.2 This abstracts away the manual and error-prone logic
of token expiry checking and refresh.  
State Management:  
The AuthState class is central to AppAuth's state management.1 It serializes all relevant
information from the authorization request and token responses, including the access token, refresh
token, ID token, token expiration times, and the original authorization request parameters.2 This
object is designed to be easily persistable as a JSON string, allowing developers to store the
user's authorization state using their preferred mechanism, such as SharedPreferences (often
encrypted for security), SQLite, or a file.1 This persistence ensures that the user remains logged
in across app sessions.  
Compatibility:  
AppAuth is designed to work with any OAuth 2.0 or OpenID Connect Authorization Server that supports
native applications as documented in RFC 8252, either through custom URI scheme redirects or App
Links.1 This provider-agnostic nature is a key strength, allowing developers to integrate with a
wide variety of identity solutions.  
The comprehensive nature of AppAuth in handling these diverse and complex aspects of the OAuth 2.0
flow for native apps is its primary value. It significantly reduces the development burden and, more
importantly, the risk of introducing security vulnerabilities that can arise from incorrect manual
implementations of these protocols.

## **4\. Evaluating Google's Identity Libraries for OAuth 2.0 Flows**

Google provides several libraries and APIs for Android that touch upon authentication and
authorization, notably Google Credential Manager and the Google Authorization Client (part of Google
Play services auth library). It's crucial to evaluate their intended purpose and capabilities
concerning the full client-side OAuth 2.0 Authorization Code Flow with PKCE, especially for generic,
non-Google providers.

### **Google Credential Manager**

Intended Purpose:  
Google Credential Manager is a Jetpack API designed to simplify the sign-in experience for users and
developers on Android.19 Its primary goal is to unify various authentication methods—traditional
username/password, modern passkeys, and federated sign-in solutions like "Sign in with Google"—under
a single, streamlined API.19 This aims to improve the user experience by providing a consistent
interface for credential selection and to reduce developer complexity in integrating these diverse
methods.19  
Capabilities for OAuth 2.0:  
Credential Manager's direct OAuth 2.0 capabilities are primarily centered around "Sign in with
Google." It facilitates obtaining Google ID tokens through the GetGoogleIdOption when creating a
GetCredentialRequest.20 This ID token is typically intended for the application's backend to verify
the user's identity. While "Sign in with Google" itself uses OAuth 2.0 and PKCE behind the scenes,
this is an abstraction specific to Google as the identity provider.  
Generic OAuth 2.0 Providers:  
The crucial point for this report is Credential Manager's applicability to generic OAuth 2.0
providers. An explicit statement from a Google engineer clarifies this: "Using any type of OAuth
Authorization (as compared to Authentication) is not supported by CredentialManager. For
authorization, you would need to use the Authorization APIS directly".22 This indicates that
Credential Manager is focused on authentication (retrieving credentials to prove who the user is)
rather than managing the full client-side authorization grant flow (obtaining tokens to access
resources from any OAuth provider).  
The documentation for Credential Manager 19 emphasizes credential retrieval and management (
passwords, passkeys, Google ID tokens). It does not provide mechanisms or examples for:

* Constructing an authorization request for an arbitrary third-party OAuth provider.
* Launching a Custom Tab to a generic provider's authorization endpoint.
* Handling the redirect URI containing an authorization code from a generic provider.
* Performing the client-side token exchange (code for tokens) with a generic provider's token
  endpoint.
* Managing client-side refresh tokens obtained from a generic provider.

While the API includes a CustomCredential type, its description suggests it's for parsing results
from *other* sign-in libraries that might handle such flows, rather than Credential Manager
performing these operations itself.19 Therefore, for the full, client-side Authorization Code Flow
with PKCE against a generic OAuth provider, Google Credential Manager does **not** offer the
required functionality.

### **Google Authorization Client (e.g.,
com.google.android.gms.auth.api.identity.AuthorizationClient)**

Primary Role:  
The Google Authorization Client, part of the Google Play services auth library, is often mentioned
in the context of obtaining a serverAuthCode when using Google Sign-In for Google APIs.23 This
serverAuthCode is a one-time code that the application's backend server can exchange for access and
refresh tokens to call Google APIs on behalf of the user, even when the user is offline.24 The
AuthorizationResult obtained from these APIs can also provide a client-side accessToken 24,
presumably for direct calls to Google APIs from the client.  
Scope for Client-Side Generic OAuth 2.0 Flows:  
Despite the name "Authorization Client," there is no substantial evidence or clear documentation
suggesting that this client is designed to function as a generic OAuth 2.0 client library comparable
to AppAuth for non-Google providers. Its functionalities and examples are heavily oriented towards
facilitating access to Google's own APIs and services.  
The complexities noted in 22 regarding requestOfflineAccess (requiring a Web client ID instead of an
Android client ID for the AuthorizationClient) highlight potential intricacies even when dealing
with Google's ecosystem, let alone extending this to generic providers. The client does not appear
to offer configurable mechanisms for:

* OpenID Connect Discovery for generic providers.
* Launching Custom Tabs to arbitrary authorization URLs.
* Handling arbitrary redirect URIs.
* Performing token exchange and refresh with generic token endpoints using client-side logic.

Its design appears to be tightly coupled with Google's infrastructure and identity system.

### **Summary of Google's Libraries' Focus**

Google's strategy with Credential Manager and the Authorization Client seems to be:

1. **Simplify Google Sign-In and basic credential management on Android:** This is the role of
   Credential Manager, focusing on authentication and user experience for common login methods.19
2. **Provide robust SDKs for accessing Google APIs:** This is where the Authorization Client and the
   broader Google Identity Services SDK fit, facilitating secure access to Google resources.23
3. **Endorse and recommend standard-based community libraries like AppAuth for generic OAuth
   2.0/OIDC interactions:** Google's own documentation for native app OAuth continues to list
   AppAuth as a recommended library 4, implicitly acknowledging that their native SDKs are not
   intended as universal, generic OAuth 2.0 client solutions.

Attempting to use Google Credential Manager or the Authorization Client for a full, client-side
generic OAuth 2.0 flow would likely result in significant functional gaps. Developers would need to
implement substantial custom logic to bridge these gaps, effectively re-introducing the very
complexities that a dedicated library like AppAuth is designed to solve.

### **Feature-by-Feature Comparison**

To provide a clear overview, the following table compares AppAuth, Google Credential Manager, and
Google Authorization Client against key requirements for a client-side OAuth 2.0 Authorization Code
Flow with PKCE:

| Feature                                                                  | AppAuth for Android                              | Google Credential Manager                                                           | Google Authorization Client (Play Services Auth)                         |
|:-------------------------------------------------------------------------|:-------------------------------------------------|:------------------------------------------------------------------------------------|:-------------------------------------------------------------------------|
| **Generic OAuth Provider Support** (Any RFC-compliant provider)          | Fully Supported 1                                | Not Supported for full flow (primarily for "Sign in with Google" authentication) 19 | Not Supported for full flow (primarily for Google API authorization) 24  |
| **Service Discovery (OIDC .well-known)**                                 | Fully Supported 2                                | Not Applicable / Not Supported                                                      | Not Applicable / Not Supported for generic providers                     |
| **Authorization Request Construction (Manual Endpoint Config)**          | Fully Supported 2                                | Not Applicable / Not Supported                                                      | Not Applicable / Not Supported for generic providers                     |
| **PKCE Generation & Handling (Client-Side)**                             | Fully Supported (Automatic) 1                    | Handled internally for "Sign in with Google"; Not for generic providers             | Handled internally for Google flows; Not for generic providers           |
| **Custom Tabs for Authorization UI (User-Agent)**                        | Fully Supported (Recommended) 2                  | Not Applicable (Doesn't manage this part of generic flow)                           | Not Applicable (Doesn't manage this part of generic flow)                |
| **Redirect URI Handling (from Custom Tab for Generic Provider)**         | Fully Supported 1                                | Not Applicable                                                                      | Not Applicable                                                           |
| **Client-Side Authorization Code for Token Exchange (Generic Provider)** | Fully Supported 1                                | Not Supported 22                                                                    | Not Supported for generic providers                                      |
| **Client-Side Token Refresh Management (Generic Provider)**              | Fully Supported (performActionWithFreshTokens) 9 | Not Supported                                                                       | Not Supported for generic providers (manages Google tokens, potentially) |
| **Comprehensive State Management (Tokens, Request Params)**              | Fully Supported (AuthState) 1                    | Limited to retrieved credentials (e.g., Google ID token, passkey) 19                | Limited to Google-specific tokens/auth codes 24                          |
| **Adherence to RFC 8252 for Native Apps**                                | Core Design Principle 7                          | Partially (for "Sign in with Google" aspects); Not as a generic OAuth client        | Partially (for Google API access aspects); Not as a generic OAuth client |
| **Primary Use Case**                                                     | Generic OAuth 2.0/OIDC client for native apps 1  | Unified credential retrieval (passwords, passkeys, "Sign in with Google") 19        | Facilitating access to Google APIs (often via serverAuthCode) 23         |

This comparison underscores that while Google's libraries offer valuable functionalities within
their specific domains, they are not replacements for AppAuth when a comprehensive, client-side
solution for generic OAuth 2.0 providers is required.

## **5\. Is AppAuth Necessary? The Verdict**

Based on the detailed evaluation of AppAuth for Android against Google Credential Manager and the
Google Authorization Client, the verdict is clear: **for implementing the full, client-side OAuth
2.0 Authorization Code Flow with PKCE, particularly when interacting with generic (non-Google)
identity providers, AppAuth for Android remains not only highly beneficial but often necessary.**  
The necessity of AppAuth stems from its specialized design to address the comprehensive lifecycle
and inherent complexities of native OAuth 2.0 interactions in a provider-agnostic manner. As
demonstrated, AppAuth capably handles:

* **Service Discovery:** Fetching provider metadata from .well-known/openid-configuration
  endpoints.2
* **Authorization Request Construction:** Building requests with automatic PKCE parameter
  generation.1
* **Secure User-Agent Interaction:** Utilizing Custom Tabs for the authorization UI, adhering to RFC
  8252 best practices.2
* **Redirect Handling:** Managing the capture of the authorization code from the redirect URI.1
* **Token Exchange:** Securely exchanging the authorization code for tokens with the provider's
  token endpoint.15
* **Automated Token Refresh:** Providing mechanisms like AuthState.performActionWithFreshTokens for
  transparently refreshing expired access tokens.9
* **State Persistence:** Offering the AuthState object for easy serialization and storage of the
  complete authorization state.1

These capabilities, offered as a cohesive and standards-compliant package, allow developers to avoid
the significant effort and high risk of error associated with manually implementing these
security-critical features.  
Conversely, Google's libraries serve different, more focused purposes:

* **Google Credential Manager** is fundamentally an *authentication* facilitation API. It excels at
  simplifying "Sign in with Google" to obtain an ID token (primarily for backend authentication),
  and at managing local credentials like passkeys and saved passwords.19 It is explicitly stated not
  to support generic OAuth *authorization* flows.22 It does not provide the infrastructure to
  conduct an entire OAuth dance with an arbitrary provider.
* **Google Authorization Client** (and the broader Google Identity Services SDK) is primarily
  tailored for authorizing access to *Google APIs*. Its main function in many native app scenarios
  is to obtain a serverAuthCode that the app's backend can use to get tokens for Google services.23
  While it might handle client-side Google access tokens, it is not designed as a generic OAuth
  client library that can be configured to work with any OAuth 2.0 provider's endpoints and flows.

Google's own recommendations further support this distinction. Historically, when deprecating
WebViews for OAuth interactions with its services, Google pointed developers towards libraries like
AppAuth for implementing browser-based flows.4 Current Google documentation on "OAuth 2.0 for
Mobile & Desktop Apps" continues to list AppAuth for Android as a recommended library for
implementing the standard OAuth 2.0 flow.8 While Google also promotes its Identity Services SDK for
a more streamlined experience *with Google services*, this does not diminish the role of AppAuth for
interactions with other, generic OAuth 2.0 providers. The existence and continued recommendation of
AppAuth by Google suggest an acknowledgment that their proprietary SDKs are not intended to be
universal OAuth clients, and that for standard, interoperable OAuth/OIDC, community-driven libraries
like AppAuth fill a crucial need.  
In scenarios where an application only needs to authenticate users via "Sign in with Google" and
pass an ID token to a backend, or manage passkeys, Google Credential Manager is an excellent choice.
If the app needs to obtain tokens specifically for Google APIs (often via a serverAuthCode for
backend exchange), the Google Identity Services SDK (encompassing the Authorization Client) is
appropriate. However, if the requirement is to implement a full client-side OAuth 2.0 Authorization
Code Flow with PKCE against any compliant third-party identity provider, choosing not to use AppAuth
would mean taking on the substantial burden of correctly and securely re-implementing its core
functionalities. This is generally ill-advised given the complexity and security sensitivity of the
OAuth 2.0 protocol.5  
Therefore, AppAuth is necessary when its comprehensive, provider-agnostic OAuth 2.0 client
capabilities are required, a domain not currently covered by Google Credential Manager or the Google
Authorization Client for generic providers.

## **6\. Token Refresh Mechanisms in Android**

Managing the lifecycle of OAuth tokens, particularly refreshing access tokens without requiring
recurrent user interaction, is a cornerstone of a seamless user experience in authenticated
applications. Android offers different approaches to this, ranging from high-level abstractions
provided by libraries like AppAuth to lower-level system components like AccountManager.

### **AppAuth's Approach to Token Refresh**

AppAuth provides a robust and convenient mechanism for token refresh through its AuthState object
and the performActionWithFreshTokens(AuthorizationService service, AuthState.AuthStateAction action)
method.1 This method encapsulates the logic for ensuring that an action (e.g., an API call) is
performed only after a valid, non-expired access token is available.  
The process within performActionWithFreshTokens typically involves:

1. **Checking Token Expiration:** It checks if the current access token stored in AuthState has
   expired or is within a predefined tolerance period of expiring (controlled by
   EXPIRY\_TIME\_TOLERANCE\_MS).9
2. **Initiating Refresh if Needed:** If the access token is deemed to require refreshing, and a
   valid refresh token is available in AuthState, the method automatically constructs a TokenRequest
   with grant\_type=refresh\_token.
3. **Performing the Refresh Request:** It uses the provided AuthorizationService to dispatch this
   token request to the OAuth provider's token endpoint.
4. **Updating AuthState:** Upon successful receipt of new tokens (a new access token and potentially
   a new refresh token), AuthState is updated with these fresh credentials.
5. **Executing the Action:** The AuthStateAction callback, provided by the developer, is then
   executed, passing the fresh access token (and ID token, if available) to it.2

This approach transparently handles the complexities of token refresh, making it straightforward for
developers to integrate into their application logic. Regarding thread safety, while
performActionWithFreshTokens manages its own network operations (which are typically asynchronous),
the AuthState object itself, if shared and mutated across different threads or asynchronous
operations outside of this specific method call, requires careful synchronization by the application
developer to prevent race conditions.

### **Android Built-in Options: AccountManager and AbstractAccountAuthenticator**

Android provides the AccountManager framework as a centralized system for managing user accounts and
their credentials.26 Developers can integrate custom account types by implementing an
AbstractAccountAuthenticator.29  
Capabilities and Implementing Token Refresh for Generic OAuth 2.0:  
When using AccountManager for a generic OAuth 2.0 provider, the responsibility for implementing the
entire OAuth flow, including token refresh, falls largely on the custom
AbstractAccountAuthenticator.32 The getAuthToken(AccountAuthenticatorResponse response, Account
account, String authTokenType, Bundle options) method is the core of this process.29  
To handle token refresh, the authenticator would need to:

1. **Securely Store Tokens:** Store the access token, refresh token, and expiration time associated
   with an account, typically using AccountManager.setAuthToken() and AccountManager.setUserData().
   As AccountManager itself may not encrypt this data by default 34, it is critical to encrypt these
   tokens before storage (see security considerations below).
2. **Check Token Validity:** When getAuthToken() is called, the authenticator must check if a stored
   access token exists and if it's still valid (not expired).
3. **Perform Refresh:** If the access token is missing, expired, or deemed stale, the authenticator
   must use the stored refresh token to make a network request directly to the generic provider's
   token endpoint (e.g., using an HTTP client like Ktor or OkHttp) with grant\_type=refresh\_token.
4. **Update Stored Tokens:** Upon successful refresh, the new access token (and potentially a new
   refresh token) must be securely stored back into AccountManager, overwriting the old ones.
5. **Return New Token:** The new access token is then returned in the Bundle via
   AccountManager.KEY\_AUTHTOKEN.
6. **Handle Refresh Failure:** If the refresh token is invalid or the refresh attempt fails for any
   other reason (e.g., network error, provider error), the authenticator must handle this
   gracefully. This typically involves invalidating the stored tokens and returning a Bundle
   containing an Intent (under AccountManager.KEY\_INTENT) that directs the user to a login activity
   to re-authenticate and obtain a new set of tokens.29

Security of Token Storage with AccountManager:  
AccountManager stores account data in a system-level database, which offers some protection by
restricting access based on application signatures and UIDs. However, by default, data passed to
AccountManager.setAuthToken() or AccountManager.setUserData() might be stored in plain text or in a
way that could be accessible on a rooted device.34  
The **critical best practice** is for the application to encrypt sensitive token data *before*
passing it to AccountManager methods for storage.10 The encryption key used for this process should
be managed by the Android Keystore system, which can provide hardware-backed protection for the key
itself.36 This ensures that even if the AccountManager database is compromised, the tokens remain
encrypted and unusable without the Keystore-protected key.

### **Comparing AppAuth's AuthState with AccountManager for Token Management**

| Aspect                       | AppAuth AuthState                                                                                                 | AccountManager with Custom Authenticator                                                                                                          |
|:-----------------------------|:------------------------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------|
| **Ease of OAuth Logic**      | High; performActionWithFreshTokens abstracts refresh. AuthState is purpose-built for OAuth state.1                | Low; Requires full manual implementation of token validation, refresh network calls, and error handling within AbstractAccountAuthenticator.32    |
| **State Management**         | Comprehensive and standardized for OAuth tokens and request parameters.1                                          | Flexible but requires manual definition of what to store in setUserData or setAuthToken.                                                          |
| **Persistence**              | Developer choice (e.g., EncryptedSharedPreferences, SQLite). AuthState provides JSON serialization.1              | System-managed database; tokens should be encrypted by the app before storage.34                                                                  |
| **System Integration**       | Lower; state is app-private unless explicitly shared.                                                             | Higher; accounts can be visible in device settings and potentially shared by apps with the same signature (requires careful permission handling). |
| **Security of Stored Data**  | Relies on developer choosing secure storage (e.g., EncryptedSharedPreferences with Keystore-backed master key).38 | Relies heavily on developer implementing strong encryption (using Keystore) before calling setAuthToken/setUserData.36                            |
| **Effort for Generic OAuth** | Moderate; primarily integration of AppAuth library.                                                               | High; involves writing a full custom authenticator and all OAuth logic.40                                                                         |

While AccountManager offers deep system integration and a framework for managing various account
types, AppAuth's AuthState and its associated mechanisms provide a significantly more streamlined
and less error-prone solution specifically for OAuth 2.0 token management and refresh. The developer
effort to achieve robust and secure generic OAuth 2.0 token refresh using AccountManager is
substantially higher, requiring careful implementation of cryptographic practices and the entire
refresh flow logic. For security, both approaches should ultimately rely on the Android Keystore for
protecting the encryption keys used for token data.

## **7\. Integrating Token Refresh with Ktor**

Ktor is a modern, asynchronous HTTP client for Kotlin, increasingly popular in Android development.
Integrating OAuth 2.0 token refresh mechanisms with Ktor requires careful handling, especially when
dealing with concurrent requests and ensuring thread safety. Ktor's Auth plugin, specifically its
bearer provider, offers a foundational layer for this integration.41

### **Ktor's Auth Plugin for Bearer Tokens**

The Auth plugin in Ktor allows for automatic attachment of authentication headers and handling of
token refresh logic. For OAuth 2.0, the bearer provider is typically used:

* **install(Auth):** The plugin is installed in the HttpClient configuration.
* **bearer {... }:** Configures the bearer token authentication.
    * **loadTokens {... }:** This lambda is called by Ktor to get the current access and refresh
      tokens (as a BearerTokens object) when a request is about to be made or when tokens are needed
      after a refresh. It should retrieve tokens from the application's secure storage (e.g.,
      managed by AppAuth's AuthState or AccountManager).41
    * **refreshTokens {... }:** This suspendable lambda is invoked automatically by Ktor when a
      request receives a 401 Unauthorized response. Its responsibility is to perform the token
      refresh operation and return a new BearerTokens object. If refresh fails, it should return
      null or throw an exception. Ktor will then typically retry the original failed request with
      the new token if the refresh was successful.41

### **Strategies for Token Management with Ktor**

Using AppAuth's AuthState:  
When AppAuth manages the OAuth state:

1. **loadTokens Lambda:**
    * Access the singleton or otherwise managed AuthState instance.
    * Retrieve the current access token and refresh token from AuthState.
    * Return them as BearerTokens(accessToken, refreshToken). Ensure thread-safe access to AuthState
      if it can be modified concurrently.
2. **refreshTokens Lambda:**
    * This is the critical part. The goal is to use AuthState.performActionWithFreshTokens().
    * **Synchronization:** Since multiple Ktor requests might concurrently receive a 401 and trigger
      this refreshTokens lambda, it's essential to prevent multiple simultaneous refresh attempts. A
      kotlinx.coroutines.sync.Mutex should be used to ensure that only one coroutine executes the
      actual refresh logic at a time.42 The Ktor issue KTOR-3325 also highlights this necessity.43
    * Inside the synchronized block:
        * First, re-check if the token has already been refreshed by another coroutine that acquired
          the mutex earlier (e.g., by comparing the current token in AuthState with the one that
          caused the 401, or by checking a volatile flag).
        * If refresh is still needed, call authState.performActionWithFreshTokens(
          authorizationService, object : AuthState.AuthStateAction {... }).
        * The AuthStateAction callback will receive the new tokens. These are already updated within
          the AuthState object by performActionWithFreshTokens.
        * The refreshTokens lambda should then return the new BearerTokens(newAccessToken,
          newRefreshToken) from the updated AuthState.
        * If performActionWithFreshTokens fails (e.g., refresh token is invalid), it will typically
          throw an exception or its callback will indicate failure. This should be caught, and the
          refreshTokens lambda should return null or re-throw an appropriate exception to signal
          failure to Ktor.
    * The AuthorizationService instance required by performActionWithFreshTokens must also be
      available, typically as a singleton.

Using AccountManager with a Custom AbstractAccountAuthenticator:  
When AccountManager manages tokens via a custom authenticator:

1. **loadTokens Lambda:**
    * Call AccountManager.getAuthToken(account, authTokenType, null, activity, callback, handler) or
      AccountManager.blockingGetAuthToken(...) (with caution, not on main thread).
    * The access token is retrieved. The refresh token might be stored separately in UserData and
      would need to be fetched if Ktor's BearerTokens expects it.
2. **refreshTokens Lambda:**
    * **Synchronization:** Similar to the AppAuth approach, a Mutex is advisable if the custom
      AbstractAccountAuthenticator itself doesn't handle concurrent refresh requests robustly.
    * Inside the synchronized block:
        * Call AccountManager.invalidateAuthToken(account.type, currentAccessToken) to clear the
          cached stale token.28
        * Call AccountManager.getAuthToken() again. The custom AbstractAccountAuthenticator is
          responsible for detecting the invalidated/missing token and performing the actual network
          call to the provider's token endpoint using the stored refresh token.
        * If the refresh is successful, getAuthToken() will return a Bundle with the new access
          token.
        * Return the new BearerTokens.
        * If the authenticator's refresh logic fails (e.g., refresh token rejected), getAuthToken()
          should return a Bundle with AccountManager.KEY\_INTENT to trigger re-login, or an error.
          The refreshTokens lambda must interpret this as a failure and return null or throw an
          exception.
    * AccountManager operations are IPC and can be blocking or asynchronous; ensure they are handled
      appropriately within Ktor's coroutine context.

### **Handling Refresh Failures and Signaling Re-authentication**

If the refreshTokens lambda in Ktor returns null or an exception is thrown (signifying that the
refresh token itself is invalid or the refresh process failed irrecoverably), Ktor's Auth plugin
will not be able to retry the original request with a new token. The request will ultimately fail.  
The application must have a higher-level mechanism to detect such persistent authentication
failures:

* This could involve a custom Ktor plugin or interceptor that inspects responses or exceptions after
  the Auth plugin has attempted a refresh.
* If refreshTokens consistently fails for a request (e.g., returns null after a 401, and the retried
  request also gets a 401, which Ktor might not automatically re-trigger refresh for indefinitely
  without careful configuration), this indicates a need for full user re-authentication.44
* Upon detecting such a scenario, the application should:
    1. Clear any stored tokens (e.g., clear AuthState, or remove account from AccountManager or
       clear its tokens).
    2. Navigate the user to the login screen to initiate the OAuth authorization flow from the
       beginning.
    3. Potentially inform the user that their session has expired.

### **Advanced: Queuing Requests During Refresh**

Ktor's Auth plugin, when configured for bearer tokens, automatically retries the request that
initially received the 401 Unauthorized status after the refreshTokens lambda successfully provides
new tokens.41  
For multiple concurrent API calls that might all receive a 401 around the same time:

* The first failed request will trigger the refreshTokens lambda.
* Subsequent requests might also fail with a 401 before the first refresh operation completes.
* As discussed, the Mutex within the refreshTokens lambda is crucial to ensure only one actual
  network call to the token endpoint for refreshing tokens occurs.42
* While one coroutine is performing the refresh (holding the mutex), other coroutines that also hit
  a 401 and enter the refreshTokens lambda will suspend until the mutex is released.
* Once the first coroutine successfully refreshes the token and updates the shared token store (
  AuthState or AccountManager), and releases the mutex, the subsequent coroutines, upon acquiring
  the mutex, should ideally re-check the token store. If the token is already fresh, they can use it
  directly without initiating another refresh call.
* Ktor's Auth plugin should then use the newly refreshed token for retrying all requests that had
  failed with a 401\.

The implementation of the loadTokens and refreshTokens lambdas, particularly the synchronization and
interaction with the chosen token management system (AppAuth or AccountManager), is paramount for a
robust and thread-safe integration with Ktor. Developers should not assume that the Auth plugin
handles all aspects of concurrency and state management related to token refresh without careful
implementation of these callbacks.

## **8\. Recommendations and Best Practices**

Choosing and implementing an OAuth 2.0 client strategy on Android requires careful consideration of
the specific use case, the identity providers involved, and a strong commitment to security best
practices.

### **Choosing the Right Library/Approach**

1. **For Generic OAuth 2.0 (Any RFC-Compliant Provider):**
    * **Strongly Recommended: AppAuth for Android.** Its comprehensive features, adherence to RFC
      8252, and provider-agnostic design make it the most robust and secure choice for implementing
      the full client-side Authorization Code Flow with PKCE.1 It handles complexities like service
      discovery, PKCE, Custom Tabs, token exchange, and automated refresh.
2. **For Google-Only Sign-In/Authentication:**
    * **Google Credential Manager:** Ideal for simplifying "Sign in with Google" to obtain an ID
      token (often for backend verification), and for managing passkeys and saved passwords. It
      provides a unified API for these common authentication scenarios.19
    * **Google Identity Services SDK / Authorization Client:** More suitable when the primary goal
      is to obtain tokens (like serverAuthCode or client-side access tokens) specifically for
      accessing Google APIs.23
3. **Avoid Manual Implementation of the Full OAuth 2.0 Flow:**
    * Manually implementing all aspects of the OAuth 2.0 Authorization Code Flow with PKCE,
      including Custom Tab management, redirect handling, PKCE generation and verification, secure
      token requests, and refresh logic, is highly complex and prone to security vulnerabilities.5
      The risk of errors significantly outweighs potential benefits unless there's an exceptionally
      unique requirement not covered by standard libraries.

### **Security Best Practices (Summary)**

* **Always Use PKCE:** For native applications, PKCE is mandatory to protect against authorization
  code interception attacks.5 AppAuth handles this automatically.
* **Use External User-Agents (Custom Tabs):** Never use WebViews for OAuth 2.0 authorization
  requests due to severe security risks.4 AppAuth correctly uses Custom Tabs.
* **Validate the state Parameter:** Use and validate the state parameter in authorization requests
  and responses to prevent Cross-Site Request Forgery (CSRF) attacks.5 AppAuth includes support for
  this.
* **Securely Store Tokens:**
    * **AppAuth AuthState:** Serialize the AuthState object (which contains access tokens, refresh
      tokens, etc.) and store it using Android's EncryptedSharedPreferences. The master key for
      EncryptedSharedPreferences should itself be stored in the Android Keystore for hardware-backed
      protection where available.1
    * **AccountManager:** If using AccountManager with a custom authenticator, tokens must be
      encrypted by the application *before* being stored via AccountManager.setAuthToken() or
      AccountManager.setUserData(). The encryption key for this should be generated and managed by
      the Android Keystore.10
* **Handle Token Revocation and Expiration:** Implement robust logic to handle scenarios where
  tokens are revoked by the user or the provider, or when refresh tokens expire. This typically
  involves clearing stored tokens and prompting the user for re-authentication.10
* **Use HTTPS Exclusively:** All communications with the authorization server (authorization
  endpoint, token endpoint, discovery endpoint) must use HTTPS to protect data in transit.
* **Principle of Least Privilege for Scopes:** Request only the minimum necessary OAuth scopes
  required for the application's functionality. Use incremental authorization to request scopes only
  when they are needed, providing context to the user.10
* **Secure Redirect URI Handling:**
    * Use non-guessable redirect URIs. For custom schemes, use reverse domain name notation (e.g.,
      com.example.app:/oauth2redirect).
    * Ensure the authorization server is configured to only accept exact matches for registered
      redirect URIs.
    * When using App Links (HTTPS redirect URIs), ensure proper configuration and verification of
      the assetlinks.json file.

### **Common Pitfalls to Avoid (Recap)**

* **Using WebViews for OAuth:** Introduces significant security risks.4
* **Incorrect PKCE Implementation:** Failing to generate a high-entropy code\_verifier, using "
  plain" code\_challenge\_method when S256 is available, or errors in server-side validation.5
* **Improper Redirect URI Handling:** Using overly broad redirect URIs, scheme conflicts if multiple
  apps register the same custom scheme, or failing to validate the redirect URI on the server side
  can lead to token leakage.3
* **Insecure Token Storage:** Storing tokens in plain text in SharedPreferences or AccountManager
  without additional encryption.10 Even AccountManager data can be accessed on rooted devices if not
  encrypted by the app.
* **Ignoring or Misusing the state Parameter:** Exposes the application to CSRF attacks during the
  authorization flow.5
* **Not Handling Token Refresh Failures Gracefully:** Can lead to user lockouts, infinite loops, or
  a poor user experience. Applications must have a clear path to re-authentication when refresh
  tokens are no longer valid.
* **Client Secret Exposure in Native Apps:** Native apps are public clients and cannot securely
  store a client secret. Rely on PKCE for client protection. Authorization servers should not
  require client secrets from public clients for the token exchange if PKCE is used.

Adhering to these recommendations and leveraging well-vetted libraries like AppAuth for Android will
significantly enhance the security, robustness, and maintainability of Android applications
implementing OAuth 2.0. For scenarios strictly involving Google services, Google's own identity
libraries offer streamlined solutions, but for the broader world of generic OAuth 2.0 providers,
AppAuth remains the cornerstone for client-side implementation.

#### **Works cited**

1. openid/AppAuth-Android: Android client SDK for communicating with OAuth 2.0 and OpenID Connect
   providers. \- GitHub, accessed May 11,
   2025, [https://github.com/openid/AppAuth-Android](https://github.com/openid/AppAuth-Android)
2. AppAuth for Android \- OpenID on GitHub, accessed May 11,
   2025, [https://openid.github.io/AppAuth-Android/](https://openid.github.io/AppAuth-Android/)
3. Build an Android App Using OAuth 2.0 and PKCE \- Cloudentity, accessed May 11,
   2025, [https://cloudentity.com/developers/app-dev-tutorials/android/android\_pkce\_tutorial/](https://cloudentity.com/developers/app-dev-tutorials/android/android_pkce_tutorial/)
4. Modernizing OAuth interactions in Native Apps for Better Usability and Security, accessed May 11,
   2025, [https://developers.googleblog.com/modernizing-oauth-interactions-in-native-apps-for-better-usability-and-security/](https://developers.googleblog.com/modernizing-oauth-interactions-in-native-apps-for-better-usability-and-security/)
5. Practical OAuth security guide for mobile applications \- Cossack Labs, accessed May 11,
   2025, [https://www.cossacklabs.com/blog/practical-oauth-security-guide-for-mobile-apps/](https://www.cossacklabs.com/blog/practical-oauth-security-guide-for-mobile-apps/)
6. Overview of Android Custom Tabs | Web on Android \- Chrome for Developers, accessed May 11,
   2025, [https://developer.chrome.com/docs/android/custom-tabs](https://developer.chrome.com/docs/android/custom-tabs)
7. AppAuth, accessed May 11, 2025, [https://appauth.io/](https://appauth.io/)
8. OAuth 2.0 for Mobile & Desktop Apps | Authorization \- Google for Developers, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/native-app](https://developers.google.com/identity/protocols/oauth2/native-app)
9. AuthState (AppAuth) \- OpenID on GitHub, accessed May 11,
   2025, [https://openid.github.io/AppAuth-Android/docs/latest/net/openid/appauth/AuthState.html](https://openid.github.io/AppAuth-Android/docs/latest/net/openid/appauth/AuthState.html)
10. Best Practices | Authorization \- Google for Developers, accessed May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2/resources/best-practices](https://developers.google.com/identity/protocols/oauth2/resources/best-practices)
11. TrustBuilder ID SDK (Android) \- Docs, accessed May 11,
    2025, [https://docs.trustbuilder.com/product/tb-identity-sdk-android](https://docs.trustbuilder.com/product/tb-identity-sdk-android)
12. TrustBuilder ID SDK (iOS) \- Docs, accessed May 11,
    2025, [https://docs.trustbuilder.com/product/tb-identity-sdk-ios](https://docs.trustbuilder.com/product/tb-identity-sdk-ios)
13. AppAuth-Android/README.md at master \- GitHub, accessed May 11,
    2025, [https://github.com/openid/AppAuth-Android/blob/master/README.md](https://github.com/openid/AppAuth-Android/blob/master/README.md)
14. Use network service discovery | Connectivity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/develop/connectivity/wifi/use-nsd](https://developer.android.com/develop/connectivity/wifi/use-nsd)
15. TokenRequest (AppAuth) \- OpenID on GitHub, accessed May 11,
    2025, [https://openid.github.io/AppAuth-Android/docs/latest/net/openid/appauth/TokenRequest.html](https://openid.github.io/AppAuth-Android/docs/latest/net/openid/appauth/TokenRequest.html)
16. App Auth Android \- Gluu Server 3.0.2 Docs, accessed May 11,
    2025, [https://gluu.org/docs/gluu-server/3.0.2/integration/mobile/appauth-oauth2.0-android/](https://gluu.org/docs/gluu-server/3.0.2/integration/mobile/appauth-oauth2.0-android/)
17. AuthState.java \- openid/AppAuth-Android \- GitHub, accessed May 11,
    2025, [https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/AuthState.java](https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/AuthState.java)
18. App-Auth Android Example Error in AuthState constructor \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/43090860/app-auth-android-example-error-in-authstate-constructor](https://stackoverflow.com/questions/43090860/app-auth-android-example-error-in-authstate-constructor)
19. Sign in your user with Credential Manager | Identity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/identity/sign-in/credential-manager](https://developer.android.com/identity/sign-in/credential-manager)
20. Authenticate with Google on Android \- Firebase, accessed May 11,
    2025, [https://firebase.google.com/docs/auth/android/google-signin](https://firebase.google.com/docs/auth/android/google-signin)
21. Add Sign In with Google to Native Android Apps \- Auth0, accessed May 11,
    2025, [https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native](https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native)
22. OAuth consent screen in android app with OAuth2.0 using Credential Manager API, accessed May 11,
    2025, [https://stackoverflow.com/questions/77878155/oauth-consent-screen-in-android-app-with-oauth2-0-using-credential-manager-api](https://stackoverflow.com/questions/77878155/oauth-consent-screen-in-android-app-with-oauth2-0-using-credential-manager-api)
23. Enabling Server-Side Access | Identity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/identity/legacy/gsi/offline-access](https://developer.android.com/identity/legacy/gsi/offline-access)
24. AuthorizationResult | Google Play services, accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult)
25. GoogleSignInAccount | Google Play services, accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInAccount](https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInAccount)
26. AccountManager | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/android/accounts/AccountManager](https://developer.android.com/reference/android/accounts/AccountManager)
27. AccountManager Class (Android.Accounts) | Microsoft Learn, accessed May 11,
    2025, [https://learn.microsoft.com/en-us/dotnet/api/android.accounts.accountmanager?view=net-android-35.0](https://learn.microsoft.com/en-us/dotnet/api/android.accounts.accountmanager?view=net-android-35.0)
28. android.accounts.AccountManager \- Documentation \- HCL Open Source, accessed May 11,
    2025, [http://opensource.hcltechsw.com/volt-mx-native-function-docs/Android/android.accounts-Android-10.0/\#\!/api/android.accounts.AccountManager](http://opensource.hcltechsw.com/volt-mx-native-function-docs/Android/android.accounts-Android-10.0/#!/api/android.accounts.AccountManager)
29. AbstractAccountAuthenticator | API reference | Android Developers, accessed May 11,
    2025, [https://developer.android.com/reference/android/accounts/AbstractAccountAuthenticator](https://developer.android.com/reference/android/accounts/AbstractAccountAuthenticator)
30. Create a sync adapter | Connectivity \- Android Developers, accessed May 11,
    2025, [https://developer.android.com/training/sync-adapters/creating-sync-adapter](https://developer.android.com/training/sync-adapters/creating-sync-adapter)
31. Using OAuth 2.0 to Access Google APIs | Authorization, accessed May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2](https://developers.google.com/identity/protocols/oauth2)
32. How to handle refresh tokens with android account manager \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/42254830/how-to-handle-refresh-tokens-with-android-account-manager](https://stackoverflow.com/questions/42254830/how-to-handle-refresh-tokens-with-android-account-manager)
33. How to Write An AndroidAuthenticator \- Viblo, accessed May 11,
    2025, [https://viblo.asia/p/how-to-write-an-androidauthenticator-qzaGzNLdGyO](https://viblo.asia/p/how-to-write-an-androidauthenticator-qzaGzNLdGyO)
34. What is the best way to store an Auth Token on Android? : r/androiddev \- Reddit, accessed May
    11,
    2025, [https://www.reddit.com/r/androiddev/comments/470h8a/what\_is\_the\_best\_way\_to\_store\_an\_auth\_token\_on/](https://www.reddit.com/r/androiddev/comments/470h8a/what_is_the_best_way_to_store_an_auth_token_on/)
35. Android : All app can access my password in AccountManager \- Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/44286100/android-all-app-can-access-my-password-in-accountmanager](https://stackoverflow.com/questions/44286100/android-all-app-can-access-my-password-in-accountmanager)
36. Android Keystore system | Security | Android Developers, accessed May 11,
    2025, [https://developer.android.com/privacy-and-security/keystore](https://developer.android.com/privacy-and-security/keystore)
37. Encryption Tutorial For Android: Getting Started \- Kodeco, accessed May 11,
    2025, [https://www.kodeco.com/778533-encryption-tutorial-for-android-getting-started%20tutorial/page/3](https://www.kodeco.com/778533-encryption-tutorial-for-android-getting-started%20tutorial/page/3)
38. Security checklist | Android Developers, accessed May 11,
    2025, [https://developer.android.com/privacy-and-security/security-tips](https://developer.android.com/privacy-and-security/security-tips)
39. Which is more secure: EncryptedSharedPreferences or storing directly in KeyStore?, accessed May
    11,
    2025, [https://stackoverflow.com/questions/78021794/which-is-more-secure-encryptedsharedpreferences-or-storing-directly-in-keystore](https://stackoverflow.com/questions/78021794/which-is-more-secure-encryptedsharedpreferences-or-storing-directly-in-keystore)
40. shiftconnects/android-auth-manager \- GitHub, accessed May 11,
    2025, [https://github.com/shiftconnects/android-auth-manager](https://github.com/shiftconnects/android-auth-manager)
41. Bearer authentication in Ktor Client, accessed May 11,
    2025, [https://ktor.io/docs/client-bearer-auth.html](https://ktor.io/docs/client-bearer-auth.html)
42. Handling Token Expiration in Ktor: Automatic Token Refresh for API Calls \- Droidcon, accessed
    May 11,
    2025, [https://www.droidcon.com/2025/03/06/handling-token-expiration-in-ktor-automatic-token-refresh-for-api-calls/](https://www.droidcon.com/2025/03/06/handling-token-expiration-in-ktor-automatic-token-refresh-for-api-calls/)
43. Bearer Authentication: Queue requests until refresh of tokens is completed : KTOR-3325, accessed
    May 11,
    2025, [https://youtrack.jetbrains.com/issue/KTOR-3325/Bearer-Authentication-Queue-requests-until-refresh-of-tokens-is-completed](https://youtrack.jetbrains.com/issue/KTOR-3325/Bearer-Authentication-Queue-requests-until-refresh-of-tokens-is-completed)
44. How to handle Unauthorized after Token Refresh in android ktor \- Stack Overflow, accessed May
    11,
    2025, [https://stackoverflow.com/questions/79527984/how-to-handle-unauthorized-after-token-refresh-in-android-ktor](https://stackoverflow.com/questions/79527984/how-to-handle-unauthorized-after-token-refresh-in-android-ktor)
45. Why you probably do not need OAuth2 / OpenID Connect \- Ory, accessed May 11,
    2025, [https://www.ory.sh/blog/oauth2-openid-connect-do-you-need-use-cases-examples](https://www.ory.sh/blog/oauth2-openid-connect-do-you-need-use-cases-examples)
46. OAuth 2.0 for Client-side Web Applications | Authorization | Google for Developers, accessed May
    11,
    2025, [https://developers.google.com/identity/protocols/oauth2/javascript-implicit-flow](https://developers.google.com/identity/protocols/oauth2/javascript-implicit-flow)
47. Using OAuth 2.0 for Web Server Applications | Authorization \- Google for Developers, accessed
    May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2/web-server](https://developers.google.com/identity/protocols/oauth2/web-server)
48. How Secure is your Android Keystore Authentication? \- WithSecure™ Labs, accessed May 11,
    2025, [https://labs.withsecure.com/publications/how-secure-is-your-android-keystore-authentication](https://labs.withsecure.com/publications/how-secure-is-your-android-keystore-authentication)