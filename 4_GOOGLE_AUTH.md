**Modern OAuth 2.0 for Google API Access in Purely Mobile Android Applications (as of May 2025\)**  
**1\. Executive Summary**  
Google's landscape for Android identity and authorization is undergoing a significant
transformation, emphasizing modern, secure, and streamlined approaches. For Android applications
that operate purely on mobile devices without a backend component and aim solely to access user data
within Google APIs (such as Gmail), the recommended strategy involves leveraging Credential Manager
for user authentication and the com.google.android.gms.auth.api.identity.AuthorizationClient for API
authorization. This combination facilitates a client-side OAuth 2.0 flow, secured by the Proof Key
for Code Exchange (PKCE) protocol. This marks a departure from legacy Google Sign-In mechanisms and
presents a more integrated alternative to general-purpose libraries like AppAuth for this specific
Google-centric use case.  
The core recommendation for developers is to implement a two-phase process. First, user
authentication should be handled via Credential Manager's "Sign in with Google" functionality to
establish the user's identity. Second, API authorization should be managed using the
AuthorizationClient to request necessary permissions (scopes) and obtain an authorization code. This
code is then exchanged client-side, employing PKCE, for access and refresh tokens. If the
AuthorizationClient abstracts the PKCE exchange and directly provides tokens, the process is further
simplified.  
Adopting this architecture ensures alignment with Google's current best practices, significantly
enhances application security through the mandatory use of PKCE for native applications, and
provides a more robust foundation for adapting to future updates in Google's identity services. This
report details the deprecations, the recommended architecture, implementation specifics including
token management and Ktor client integration, and contextualizes this approach against
alternatives.  
**2\. Navigating API Deprecations: com.google.android.gms.auth.api.identity by May 2025**  
A clear understanding of the evolving API landscape, particularly the deprecation status of
components within the com.google.android.gms.auth.api.identity package, is paramount for developing
stable and future-proof Android applications. Google is actively streamlining its identity services,
phasing out older mechanisms in favor of more secure and user-friendly alternatives.  
Deprecated Components:  
By May 2025, a significant portion of the com.google.android.gms.auth.api.identity API, primarily
those classes associated with the older sign-in flows, will be deprecated. Google's official
documentation explicitly directs developers to use Credential Manager as the replacement for these
sign-in functionalities.1 Deprecated classes include, but are not limited to:

* BeginSignInRequest and its builders/options (e.g., GoogleIdTokenRequestOptions,
  PasskeyJsonRequestOptions, PasswordRequestOptions)
* BeginSignInResult
* GetSignInIntentRequest and its builder
* SavePasswordRequest, SavePasswordResult
* SignInCredential, SignInPassword

These components were primarily involved in initiating sign-in flows, handling Google ID tokens for
sign-in purposes, managing passkeys through this older API, and saving passwords directly via this
interface.1 The overarching message is that Credential Manager is intended to subsume these
responsibilities.  
Furthermore, the widely used legacy Google Sign-In library (com.google.android.gms.auth.api.signin),
which provided GoogleSignInAccount objects often used for obtaining ID tokens or server
authentication codes, is also deprecated and slated for removal in 2025\.2 This deprecation is a
primary driver for the adoption of the newer Credential Manager and AuthorizationClient APIs.  
Current and Usable Components for API Authorization:  
Despite these extensive deprecations, key components within com.google.android.gms.auth.api.identity
remain current and are central to Google's modern strategy for authorizing access to Google APIs.
These include:

* **AuthorizationClient**: An interface serving as the client for the authorization API.
* **AuthorizationRequest**: A class used to configure the parameters for an authorization request,
  such as the scopes needed.
* **AuthorizationResult**: Represents the result returned from an authorization request.

These elements are fundamental to the new model for authorizing application access to Google user
data.1 Other classes like GetPhoneNumberHintIntentRequest, Identity (as an entry point), and
SaveAccountLinkingTokenRequest also remain non-deprecated but serve specific, distinct purposes
generally outside the core OAuth token acquisition flow for Google APIs discussed here.1  
Impact of Deprecations:  
Continued reliance on deprecated classes and libraries will inevitably lead to application
malfunction once these components are removed. The shift necessitates a fundamental change in how
developers approach both user sign-in and API authorization. Sign-in is now principally a concern
for Credential Manager, while API authorization is handled by the dedicated AuthorizationClient.  
This wave of deprecations signals more than just API replacement; it reflects a deliberate
architectural shift by Google. The phasing out of broad functionalities within GoogleSignInAccount
for API access, coupled with the introduction of AuthorizationClient alongside Credential Manager,
points to an intentional decoupling of authentication (verifying who the user is) from
authorization (determining what the application is permitted to do on the user's behalf with Google
services). Previously, legacy Google Sign-In often conflated these two steps, allowing applications
to request API scopes during the sign-in process.2 The new paradigm, however, promotes a separation:
Credential Manager handles the sign-in, including "Sign in with Google" 2, while AuthorizationClient
is specifically tasked with requesting permissions (scopes) for Google APIs when those permissions
are actually needed.5 This separation aligns with security best practices such as the principle of
least privilege and incremental authorization, where applications request permissions contextually
rather than upfront. This approach can enhance user trust and transparency, as users are asked for
permissions only when a relevant feature is invoked. It also simplifies the role of Credential
Manager, allowing it to focus purely on diverse authentication methods.  
**Table 1: Deprecation Status of Key com.google.android.gms.auth.api.identity Components (by May
2025\)**

| Class/Interface                                | Status     | Recommended Alternative/Primary Use Case                |
|:-----------------------------------------------|:-----------|:--------------------------------------------------------|
| BeginSignInRequest                             | Deprecated | Credential Manager                                      |
| BeginSignInRequest.GoogleIdTokenRequestOptions | Deprecated | Credential Manager (for Google ID token based sign-in)  |
| BeginSignInResult                              | Deprecated | Credential Manager                                      |
| GetSignInIntentRequest                         | Deprecated | Credential Manager                                      |
| SignInCredential                               | Deprecated | Credential Manager (for handling sign-in credentials)   |
| SavePasswordRequest                            | Deprecated | Credential Manager (for password saving, if applicable) |
| AuthorizationClient                            | Current    | Authorizing access to Google APIs                       |
| AuthorizationRequest                           | Current    | Configuring API authorization requests (scopes, etc.)   |
| AuthorizationResult                            | Current    | Handling results of API authorization requests          |
| Identity                                       | Current    | Entry point to Sign-In and Authorization APIs           |
| GetPhoneNumberHintIntentRequest                | Current    | Requesting an Intent for the Phone Number Hint flow     |

This table provides a quick reference for developers to identify usable and deprecated components
within the com.google.android.gms.auth.api.identity package for OAuth-related tasks, consolidating
information from sources such as 1 and.1  
**3\. Google's Recommended OAuth Architecture for Purely Mobile Apps**  
Google's contemporary documentation and API design strongly advocate for a clear separation between
authentication and authorization processes, especially for applications accessing user data.5 This
two-step philosophy forms the bedrock of the recommended OAuth architecture for purely mobile
Android apps.

* **Authentication:** This phase establishes the user's identity. It is best handled by Android's
  Credential Manager, particularly its "Sign in with Google" feature. Credential Manager provides a
  unified API for various sign-in methods, offering a consistent user experience.2
* **Authorization:** This phase involves granting the application permission to access specific
  Google API data, defined by OAuth scopes. This is the domain of the
  com.google.android.gms.auth.api.identity.AuthorizationClient.5

This separation is favored for several reasons:

* **Clarity and User Experience:** Requesting extensive permissions at the initial sign-in can
  overwhelm users and obscure the reasons for these requests. The decoupled approach encourages
  incremental authorization, where an app requests specific scopes only when a user interacts with a
  feature that requires them.3 This improves transparency and user comfort.
* **Security:** For native applications like Android apps, which cannot securely store a client
  secret, PKCE (Proof Key for Code Exchange) is the industry-standard protocol for securely
  obtaining OAuth tokens.7 The flow involving AuthorizationClient is designed to yield an
  authorization code, which can then be used in a PKCE exchange to obtain access and refresh tokens
  without exposing any long-lived secrets within the app.
* **Modernity and Support:** This architecture aligns with Google's latest Jetpack libraries (
  Credential Manager) and Google Play Services APIs. Adherence to these current recommendations
  ensures ongoing support, access to the latest features, and security updates.

Distinction from Older Methods:  
This modern approach contrasts significantly with older methods:

* **Legacy Google Sign-In:** Relying directly on GoogleSignInAccount objects obtained from the
  now-deprecated com.google.android.gms.auth.api.signin library to acquire ID tokens or server
  authentication codes for API access is outdated.3 While GoogleSignInAccount offered methods like
  getServerAuthCode() 4, the entire library is being phased out.
* **AppAuth:** AppAuth is a robust and flexible open-source library for OAuth 2.0 and OpenID Connect
  client implementations. It is highly spec-compliant and provider-agnostic. However, for the
  specific scenario of a purely mobile Android app accessing *only Google's own APIs* without
  complex OpenID Connect federation needs, Google's native AuthorizationClient and Credential
  Manager offer a more direct and potentially simpler integration. These tools are tailored for the
  Google ecosystem. AppAuth remains an excellent choice if the application needs to connect to
  diverse OAuth providers or requires intricate OIDC functionalities not directly exposed by
  Google's more specialized APIs.

The provision of an opinionated, integrated pathway (Credential Manager \+ AuthorizationClient) by
Google for its own services aims to reduce the boilerplate and complexity often associated with
implementing OAuth 2.0 from scratch or with general-purpose libraries like AppAuth, especially for
common Google API access patterns. AppAuth's flexibility necessitates more manual configuration of
endpoints, discovery documents, and request parameters. In contrast, Google's AuthorizationClient 5
is designed specifically for Google APIs and likely abstracts many Google-specific endpoint
configurations and flow details. When combined with Credential Manager's streamlined "Sign in with
Google" functionality 2, this suite can feel more like a "plug-and-play" solution within the Android
and Google ecosystem. For developers whose sole aim is to access Google APIs, this integrated
approach might present a more direct route, assuming it adequately covers the necessary OAuth grant
types, particularly Authorization Code with PKCE. The user's stated desire for the "simplest
alternatives to AppAuth" aligns with exploring this focused Google solution.  
**4\. Implementing the Modern OAuth Flow (Mobile-Only, Client-Side PKCE)**  
The recommended modern OAuth flow for a purely mobile Android application accessing Google APIs
involves distinct phases for authentication, API authorization, and token exchange.  
**Phase 1: User Authentication via Credential Manager**  
The primary goal of this phase is to authenticate the user with their Google account, confirming
their identity. This step, by itself, does not grant the application permissions to access Google
APIs.

* **Implementation Steps:**
    1. **Add Dependencies:** Include the necessary Credential Manager libraries in the app's
       build.gradle file: androidx.credentials and, for devices running Android 13 and below,
       androidx.credentials.play.services.auth.6
    2. **Configure "Sign in with Google" Request:** Use GetGoogleIdOption.Builder to set up the
       request for a Google ID token.
        * setFilterByAuthorizedAccounts(true/false): This boolean determines whether the account
          picker should only show accounts previously used to sign in to the app or allow the user
          to choose any Google account on the device, or add a new one.
        * setServerClientId(getString(R.string.default\_web\_client\_id)): This is a critical and
          often misunderstood parameter. Even for a mobile-only application without a traditional
          backend server, it is essential to provide the **OAuth 2.0 Client ID of type "Web
          application"** obtained from the Google Cloud Console project.9 This Web client ID is used
          by Google's authentication system to identify the application's OAuth configuration (such
          as redirect URIs, even if handled by the SDK) and is embedded as the audience (aud) claim
          in the resulting ID token. The Android client ID (identified by package name and SHA-1
          hash) serves other purposes, like API key restrictions or verifying the app's signature
          with Google Play Services, but for the OAuth token flow initiated via Credential
          Manager's "Sign in with Google," the Web client ID is specified here. 9 explicitly states
          that the aud (audience) of the ID token will be this Web client ID, while the azp (
          authorized party) might be the Android client ID. This indicates that Google's backend
          systems use the Web client ID's configuration as the primary reference for this particular
          OAuth flow, regardless of the client platform initiating it.
        * setAutoSelectEnabled(true/false): Enables or disables the one-tap sign-in experience,
          where an account might be automatically selected if only one suitable option exists.
        * setNonce(String nonce): (Optional) A nonce can be included for replay protection if the
          obtained ID token is intended to be sent to a backend server for verification. For a
          purely client-side scenario where the ID token is only used to assert identity before
          proceeding to API authorization, this is less critical.
    3. **Create Credential Request:** Construct a GetCredentialRequest object, adding the configured
       GetGoogleIdOption to it.
    4. **Launch Request:** Use CredentialManager.getCredential(context, request) to initiate the
       sign-in flow. For a new user sign-up, createCredential() would be used.
* **Handling the Output:**
    * Upon successful authentication, the getCredential() method returns a Credential object. If
      this object is an instance of CustomCredential and its type matches
      GoogleIdTokenCredential.TYPE\_GOOGLE\_ID\_TOKEN\_CREDENTIAL, it can be cast or processed to
      extract the GoogleIdTokenCredential.10
    * From the GoogleIdTokenCredential, the ID token can be retrieved using getIdToken(). This ID
      token is a JSON Web Token (JWT) that proves the user's identity and contains basic profile
      information (like email, name, if requested and available). **It is crucial to understand that
      this ID Token is NOT an access token for calling Google APIs like Gmail**.8 Its primary
      purpose in this flow is to confirm successful authentication.
    * The GetGoogleIdOption API, as part of Credential Manager, does not offer a direct equivalent
      to the requestServerAuthCode() method found in the older, deprecated GoogleSignInOptions. The
      primary credential obtained from "Sign in with Google" via Credential Manager is an ID Token.6
      If a server authentication code were needed for a backend system (which is outside the scope
      of this purely mobile application), that would typically involve sending the ID token to the
      backend, which would then verify it and could potentially initiate its own server-to-server
      OAuth exchanges. This is not the direct path for client-side API access token acquisition.

The requirement to use a "Web application" client ID via setServerClientId in GetGoogleIdOption for
a mobile-only application can indeed be a source of confusion. OAuth 2.0 relies on client IDs to
identify the application making requests to the authorization server. The Google Cloud Console
allows for the creation of various OAuth client ID types (Android, Web application, iOS, etc.).12
The "Sign in with Google" flow, even when initiated from an Android client through Credential
Manager, ultimately interacts with Google's OAuth infrastructure. This infrastructure uses the
provided client ID to manage configurations, including allowed redirect URIs (even if these are
abstracted away by the client-side SDKs) and, importantly, to determine the intended audience of the
issued ID token. Multiple sources 9 confirm the necessity of using the Web client ID (often named
default\_web\_client\_id in Firebase-linked projects, or explicitly created as type "Web
application" in GCP) for the setServerClientId method. This ensures that Google's authentication
servers correctly process the request and issue an ID token with the appropriate audience claim.
This does not imply that the Android application requires an actual web server; rather, it pertains
to how Google's OAuth server identifies and configures the client for this specific authentication
flow. Developers must ensure they have correctly configured both an Android client ID (for app
signing verification) and a Web application client ID in their Google Cloud project and understand
which to use in different API contexts.  
**Phase 2: API Authorization with com.google.android.gms.auth.api.identity.AuthorizationClient**  
Once the user is authenticated, the next phase is to request their consent to access specific Google
APIs (e.g., Gmail scopes) and, ideally, obtain an authorization code that can be used in a
client-side PKCE token exchange.

* **Prerequisites:**
    1. **User Authentication:** The user should be authenticated, for instance, via the Credential
       Manager flow described in Phase 1\. While AuthorizationClient might not strictly require a
       prior authenticated session to initiate a request, the user context is essential for a
       meaningful authorization flow.
    2. **Google Cloud Console Project Setup:**
        * An **Android OAuth 2.0 Client ID** must be configured in the Google Cloud Console,
          specifying the application's package name and SHA-1 signing certificate fingerprint.5 This
          client ID is distinct from the Web application client ID used in GetGoogleIdOption.
        * The specific Google APIs the application intends to access (e.g., Gmail API, Google Drive
          API) must be enabled for the project in the Google Cloud Console API Library.7
        * The OAuth consent screen must be configured with accurate application information (name,
          logo, privacy policy, terms of service).5
* **Implementation Steps:**
    1. **Obtain AuthorizationClient Instance:** Get an instance of AuthorizationClient using
       Identity.getAuthorizationClient(activityOrContext).5
    2. **Construct AuthorizationRequest:** Create an AuthorizationRequest object using
       AuthorizationRequest.Builder():
        * setRequestedScopes(List\<Scope\> scopes): This is where the application specifies the
          OAuth scopes required for its functionality. For example, to request read-only access to
          Gmail, one might use Arrays.asList(new Scope(GmailScopes.GMAIL\_READONLY)).5 The available
          scopes are defined by each Google API.12
        * **PKCE Parameters:** A critical observation is that the AuthorizationRequest.Builder
          class, as per its documentation 1, does not expose methods for explicitly setting PKCE
          parameters like code\_challenge or code\_challenge\_method. Standard Google OAuth
          documentation for native applications 7 clearly states that these parameters must be sent
          to the authorization server. This absence in AuthorizationRequest.Builder implies one of
          two possibilities:
            * The AuthorizationClient and the underlying Google Play Services layer automatically
              handle the generation and inclusion of PKCE parameters when an authorization request
              is made using an Android client ID. This would mean PKCE is implicitly supported.
            * The AuthorizationClient flow is not designed for a manual PKCE auth code grant where
              the app provides these parameters; instead, it might directly return tokens or a
              serverAuthCode.
    3. **Initiate Authorization:** Call authorizationClient.authorize(authorizationRequest). This
       method returns a Task\<AuthorizationResult\>.5
* **Handling AuthorizationResult:**
    1. **Check for Resolution:** Upon successful completion of the task, examine the
       AuthorizationResult. The method authorizationResult.hasResolution() indicates if user
       interaction (consent) is required.5
        * If true, the application has not yet been granted the requested permissions, or the user
          needs to re-consent. A PendingIntent must be retrieved using
          authorizationResult.getPendingIntent() and then launched using
          startIntentSenderForResult().5 This PendingIntent will typically display Google's standard
          consent screen to the user.
        * If false, it might imply that the user has previously granted all requested scopes and no
          further interaction is needed. In this scenario, the AuthorizationResult might already
          contain an access token or other relevant credentials.
    2. **Process onActivityResult:** If a PendingIntent was launched, the result of the user's
       interaction with the consent screen will be delivered to the calling activity's or fragment's
       onActivityResult method.
        * Inside onActivityResult, parse the result intent using Identity.getAuthorizationClient(
          this).getAuthorizationResultFromIntent(data) to obtain a new AuthorizationResult object
          reflecting the outcome of the consent flow.5
* **Obtaining the Authorization Code for Client-Side PKCE:** This is where significant ambiguity
  exists based on current documentation.
    * The AuthorizationResult object has a method getServerAuthCode().5 This code is explicitly
      documented as being for exchange by a backend server, typically requiring the server's client
      ID and client secret.4 This is unsuitable for a purely mobile application with no backend and
      using an Android client ID (which does not have a client secret).
    * AuthorizationResult also has a getAccessToken() method.1 If this method returns a valid access
      token after the PendingIntent flow (or if hasResolution() was false), it suggests that the
      AuthorizationClient might be handling the complete PKCE exchange transparently, abstracting
      the authorization code step from the developer. This would be the simplest scenario.
    * Crucially, AuthorizationResult **does not offer a clearly documented method like
      getClientSideAuthorizationCode()** that is explicitly designated for a client-side PKCE
      exchange using an Android client ID.
    * The standard PKCE flow for native apps 7 involves the application receiving the authorization
      code via a redirect URI. When AuthorizationClient manages the PendingIntent flow, it handles
      this redirect. The authorization code might be embedded within the Intent data passed to
      onActivityResult. However, it's unclear if getAuthorizationResultFromIntent(data) extracts
      this specific type of client-side code and makes it accessible through a dedicated getter, or
      if the developer would need to manually parse it from the Intent (which seems unlikely given
      the abstraction level).
    * A user experience reported on Stack Overflow 17 involved obtaining serverAuthCode from
      AuthorizationResult and exchanging it using a client secret, reinforcing that serverAuthCode
      is for backend-style exchanges.

This ambiguity regarding the retrieval of a *client-side* authorization code via AuthorizationClient
is a pivotal point. If AuthorizationClient transparently handles PKCE and provides tokens directly
via getAccessToken() (and ideally, a refresh token, though AuthorizationResult documentation doesn't
explicitly list a getRefreshToken() method 16), then the developer's task is simplified. However, if
AuthorizationResult only yields getServerAuthCode(), it is not directly usable for the user's purely
client-side PKCE requirement without a client secret. If no distinct client-side authorization code
is made available, and only getServerAuthCode() is returned, then to perform a manual client-side
PKCE token exchange 7, developers might need to bypass AuthorizationClient for the authorization
request part. This would involve manually constructing the authorization URL with PKCE parameters
and launching it in a Chrome Custom Tab, then handling the redirect to capture the code—a process
similar to what AppAuth manages. This would run counter to the notion that AuthorizationClient is
the comprehensive recommended solution for this authorization step. The most favorable
interpretation, aligning with Google's aim to simplify, would be that AuthorizationClient either
handles PKCE transparently (Scenario A below) or provides a way to get a client-side auth code (
Scenario C below).  
**Phase 3: Client-Side PKCE for Token Exchange (Assuming an Auth Code is Obtained and Manual
Exchange is Needed)**  
This phase is only applicable if the AuthorizationClient flow (Phase 2\) results in an authorization
code intended for client-side exchange, rather than directly providing an access token. The purpose
is to securely exchange this authorization code for an access token and a refresh token, without
using a client secret, leveraging the PKCE protocol.

* **Implementation Steps (following general PKCE guidelines 7):**
    1. **Generate Code Verifier and Challenge (Prior to Phase 2):**
        * **code\_verifier**: A high-entropy cryptographic random string (minimum 43, maximum 128
          characters, using unreserved characters: A-Z, a-z, 0-9, '-', '.', '\_', '\~'). This should
          be generated for each authorization request.
        * **code\_challenge**: The Base64URL-encoded (without padding) SHA256 hash of the ASCII
          code\_verifier. This is the S256 method and is recommended: code\_challenge \=
          BASE64URL-ENCODE(SHA256(ASCII(code\_verifier))).
        * If AuthorizationClient is used, and it is expected to yield a client-side authorization
          code for manual exchange, it's unclear where the application would supply the
          code\_challenge to the AuthorizationRequest. This lack of an explicit input for PKCE
          parameters in AuthorizationRequest.Builder 1 strongly suggests that if AuthorizationClient
          supports client-side PKCE, it likely handles the challenge generation and transmission
          transparently. If it does not, and a manual PKCE flow is required from scratch, then
          AuthorizationClient would not be used for initiating the authorization request; instead, a
          manual construction of the authorization URL with these PKCE parameters would be
          necessary.
    2. **Send Authorization Request (Potentially handled by Phase 2):** The code\_challenge and
       code\_challenge\_method=S256 parameters would be included in the authorization request to
       Google's authorization endpoint (https://accounts.google.com/o/oauth2/v2/auth). If
       AuthorizationClient is used, this step is abstracted.
    3. **Handle Authorization Response (Receive Authorization Code):** This is the
       authorization\_code obtained after the user grants consent (e.g., captured from the redirect
       URI that the PendingIntent flow in Phase 2 resolves to, and somehow made available to the
       app).
    4. **Exchange Authorization Code for Tokens:**
        * Make an HTTP POST request to Google's token
          endpoint: https://oauth2.googleapis.com/token.7
        * The request body must be application/x-www-form-urlencoded and include:
            * client\_id: The **Android application's client ID** (obtained from the Google Cloud
              Console, type "Android").
            * code: The authorization code received.
            * code\_verifier: The original code\_verifier string generated in step 1\. This is
              crucial for PKCE, as the server verifies it against the code\_challenge sent earlier.
            * grant\_type: Must be set to authorization\_code.
            * redirect\_uri: The same redirect URI that was used in the initial authorization
              request. For Android client IDs, this is often a pre-configured value like urn:ietf:
              wg:oauth:2.0:oob or a value derived from the application's package name. Google's
              client libraries typically manage this transparently. If AuthorizationClient was used,
              it handled the redirect URI.
        * **No client\_secret is sent in this request**, as PKCE is designed for public clients like
          mobile apps that cannot securely store a secret.
* **Token Response (JSON format):**
    * access\_token: A short-lived token used to authorize requests to Google APIs.
    * refresh\_token: A long-lived token used to obtain new access tokens when the current access
      token expires, without requiring the user to re-authenticate. **For installed applications
      like Android apps, Google's OAuth server should return a refresh token upon the first
      successful exchange of an authorization code**.7 It might not be returned on subsequent
      authorization code exchanges if the user has already fully authorized the application and a
      valid refresh token already exists for that client and user. To ensure refresh tokens are
      long-lived and do not expire prematurely (e.g., after 7 days), the application's OAuth consent
      screen in the Google Cloud Console should have its "Publishing status" set to "In Production"
      .18
    * expires\_in: The lifetime of the access token in seconds (e.g., 3600 for one hour).
    * scope: A space-delimited list of scopes that the access token grants access to. This may not
      always be identical to the requested scopes.

The interaction between the detailed, manual PKCE steps outlined in Google's general OAuth
documentation 7 and the level of abstraction provided by AuthorizationClient 5 presents a few
possible scenarios for how a purely mobile app achieves client-side token acquisition:  
\* Scenario A (Transparent PKCE): AuthorizationClient handles the entire PKCE dance (generating
verifier/challenge, sending challenge, exchanging code with verifier) transparently. After the
authorize() call and any necessary PendingIntent consent flow, AuthorizationResult.getAccessToken()
would directly return a valid access token (and ideally, a way to get the refresh token). In this
case, the app does not perform Phase 3 (manual token exchange). This would be the most
straightforward path for the developer.  
\* Scenario B (Server Auth Code Only): AuthorizationClient only provides a serverAuthCode via
AuthorizationResult.getServerAuthCode(). As this code is intended for backend exchange with a client
secret 5, this scenario makes AuthorizationClient unsuitable for the specified "no backend"
constraint if a full client-side PKCE flow is desired.  
\* Scenario C (Client-Side Auth Code for App Exchange): The AuthorizationResult obtained after the
PendingIntent flow contains a client-side authorization code (distinct from serverAuthCode), and the
application then performs only the token exchange part of Phase 3 (i.e., sending this code plus the
code\_verifier to the token endpoint). This would require AuthorizationResult to provide a clear way
to access this client-side code, and the app would still need to generate the code\_verifier and
code\_challenge to be implicitly used by AuthorizationClient. This scenario seems less likely given
the lack of explicit PKCE parameter inputs to AuthorizationRequest.  
Given Google's emphasis on simplifying identity flows with new libraries, Scenario A appears to be
the most aligned with their goals. Developers should empirically test the AuthorizationClient flow,
particularly what AuthorizationResult.getAccessToken() yields after a successful consent, to
determine if tokens are provided directly.  
**5\. Comprehensive Token Management on Android**  
Once OAuth tokens (access and refresh) are acquired, their proper management is crucial for
application functionality and security.

* **Secure Storage:**
    * Access tokens and, more critically, refresh tokens are sensitive credentials. They grant
      access to user data and must be protected.
    * On Android, the recommended practice is to use the **Android Keystore system** to encrypt
      these tokens before storing them. Common storage locations like SharedPreferences or a local
      SQLite database should only hold the encrypted versions of the tokens. The Keystore provides
      hardware-backed protection for cryptographic keys, making it difficult for attackers to
      extract them.
* **Access Token Refresh:**
    * Access tokens are intentionally short-lived (typically one hour, as indicated by the
      expires\_in value 17). This limits the window of exposure if an access token is compromised.
    * When an API call to a Google service fails with an HTTP 401 Unauthorized error, or proactively
      before an access token is known to expire, the application must use the stored refresh token
      to obtain a new access token.
    * **Refresh Token Request (Client-Side):**
        * A POST request is made to Google's token endpoint: https://oauth2.googleapis.com/token.
        * The request body (application/x-www-form-urlencoded) must include:
            * client\_id: The application's Android client ID.
            * refresh\_token: The stored refresh token.
            * grant\_type: refresh\_token.
        * **No client\_secret is sent** when using an Android client ID and a refresh token obtained
          via a PKCE flow.
        * The response from the token endpoint will be a JSON object containing a new access\_token,
          a new expires\_in value, and sometimes token\_type (Bearer). Importantly, refresh tokens
          themselves *may* be rotated (i.e., a new refresh token is issued along with the new access
          token), although Google's documentation for installed apps 7 primarily describes the
          refresh token as long-lived. If a new refresh token is returned, the application must
          securely store it, replacing the old one.
    * The longevity of refresh tokens is a key consideration. Refresh tokens may expire after 7 days
      if the application's OAuth consent screen in the Google Cloud Console is in the "Testing"
      state. To obtain long-lived refresh tokens that do not expire (unless revoked by the user or
      due to security events), the application's publishing status must be set to "In Production".18
      This is an operational step for the developer, involving verifying the application with
      Google. The type of OAuth client (e.g., "Android" type for this scenario) also plays a role in
      token behavior.18 Careful configuration of the Google Cloud project is therefore integral to
      ensuring a good user experience where users do not have to frequently re-authorize the
      application.
* **Token Expiry and Revocation:**
    * Applications should proactively manage access token expiry, either by tracking the expires\_in
      time or by robustly handling 401 errors from API calls.
    * A "sign out" or "disconnect account" feature should be provided to the user. This action
      should not only clear the tokens locally from secure storage but also attempt to revoke the
      tokens on Google's servers. Token revocation can be done by making a request
      to https://oauth2.googleapis.com/revoke?token=TOKEN\_TO\_REVOKE (where TOKEN\_TO\_REVOKE can
      be either the access token or the refresh token). Revoking the refresh token effectively
      invalidates the application's ability to access the user's data offline.

**Table 2: OAuth Credentials in a Purely Mobile Google API Flow**

| Credential Type                       | How Obtained (Modern Flow)                                                                                                                                  | Primary Purpose in Mobile-Only App                                                                                          |
|:--------------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------|
| ID Token                              | Credential Manager (GetGoogleIdOption) after user authenticates with "Sign in with Google".                                                                 | Authenticate the user; provides client-side assertion of the user's identity. **Not used for Google API access.**           |
| Authorization Code (Client-Side PKCE) | Result of AuthorizationClient flow (PendingIntent \+ onActivityResult), if it provides a code for client-side exchange. OR Manual Custom Tab flow.          | Short-lived, one-time code exchanged for Access and Refresh tokens using the PKCE protocol.                                 |
| Access Token                          | Client-side PKCE token exchange (using Authorization Code \+ code\_verifier). OR directly from AuthorizationResult.getAccessToken() if PKCE is transparent. | Authorize requests to Google APIs (e.g., Gmail API). Short-lived. Sent in the Authorization header of API requests.         |
| Refresh Token                         | Client-side PKCE token exchange (typically returned with the first access token).                                                                           | Obtain new Access Tokens when the current one expires, without requiring user re-authentication. Intended to be long-lived. |

This table clarifies the distinct roles and origins of the various credentials involved in the
recommended OAuth flow. Understanding these distinctions, especially between an ID token (for
authentication) and an access token (for API authorization), is fundamental for correct
implementation.  
**6\. Integrating with Ktor Client for API Calls**  
Ktor is a modern, Kotlin-first asynchronous HTTP client that is well-suited for Android development.
Its Auth plugin can significantly simplify the handling of bearer tokens, including access token
attachment and automated refresh logic, when making calls to Google APIs.

* **Setup:**
    * Add the necessary Ktor dependencies to the application's build.gradle file. This typically
      includes:
        * ktor-client-core
        * An engine, such as ktor-client-cio or ktor-client-android
        * ktor-client-auth for the authentication features
        * ktor-client-content-negotiation and a serialization library like
          ktor-serialization-kotlinx-json for handling JSON request/response bodies.
* Configuring Ktor Auth Plugin:  
  The Auth plugin is configured within the HttpClient block. For bearer token authentication, the
  bearer {... } configuration scope is used.19
    1. **loadTokens {... } Lambda:**
        * This lambda is responsible for loading the currently stored access and refresh tokens. The
          application should retrieve these tokens from its secure storage (e.g., Android
          Keystore-backed SharedPreferences).
        * It must return a BearerTokens object, which is a simple data class holding the accessToken
          and refreshToken: BearerTokens("your\_access\_token", "your\_refresh\_token").19
        * Ktor calls this lambda when the client is initialized and whenever it needs to ensure it
          has the latest tokens, such as after a successful refresh.
    2. **refreshTokens {... } Lambda:**
        * This crucial lambda is invoked automatically by Ktor when an API request made by the
          client receives a 401 Unauthorized response (and the WWW-Authenticate header indicates a
          Bearer scheme, or if sendWithoutRequest was configured to always send the token).
        * Inside this suspendable lambda, the application must: a. Perform the refresh token
          exchange with Google's token endpoint (https://oauth2.googleapis.com/token) as detailed in
          Section 5\. This will involve making a new HTTP POST request. This request can be made
          using a separate, basic Ktor HttpClient instance (to avoid circular dependencies with the
          main client's auth plugin) or a direct Ktor request call if structured carefully. b. Upon
          successful exchange, receive the new access token (and potentially a new refresh token if
          Google rotates them). c. Securely store these new tokens, overwriting the old ones. d.
          Return the new BearerTokens object containing the fresh tokens.19
        * After this lambda successfully completes and returns new tokens, Ktor's Auth plugin will
          automatically retry the original API request that failed (the one that triggered the 401
          error) using the new access token.
    3. **sendWithoutRequest { request \-\>... } Lambda (Optional but Recommended):**
        * This lambda determines whether Ktor should proactively send the Authorization: Bearer
          \<access\_token\> header with requests without first waiting for a 401 challenge from the
          server.
        * For calls to known protected resources like Google APIs, it's efficient to set this to
          always send the token. This can be done by returning true or by specifying a condition,
          for example, request.url.host \== "www.googleapis.com".19
* **Making API Calls:**
    * Once the HttpClient is configured with the Auth plugin, making authenticated API calls becomes
      straightforward. Ktor will automatically manage attaching the access token to outgoing
      requests (that match the sendWithoutRequest condition) and handle the refresh cycle if a token
      expires.
    * Example: val gmailMessages \=
      httpClient.get("https://gmail.googleapis.com/gmail/v1/users/me/messages")
      .body\<YourMessagesResponseType\>()

The Ktor Auth plugin, particularly its bearer provider, offers a valuable abstraction layer that
encapsulates much of the complexity associated with the token lifecycle. Without such a plugin,
developers would need to implement manual logic before each API call to retrieve the token, attach
it, and then add extensive error handling to catch 401 responses, trigger the refresh flow, store
new tokens, and retry the original request. Ktor's loadTokens and refreshTokens lambdas provide
clear extension points for developers to integrate their specific token storage and refresh
mechanisms, while Ktor manages the interception, challenge detection, token refresh invocation, and
request retry logic.19 This leads to cleaner, more maintainable networking code by centralizing
authentication concerns and separating them from the core API call logic. This aligns well with the
structured approach recommended by Google for overall authentication and authorization. If tokens
need to be programmatically cleared (e.g., on sign-out), Ktor's BearerAuthProvider allows for
this.20  
**7\. Contextualizing Alternatives: Why Not AppAuth for This Specific Scenario?**  
While AppAuth is a well-regarded and widely adopted library for OAuth 2.0 and OpenID Connect on
Android, it's important to consider whether it's the most straightforward solution for the user's
specific, narrowly defined requirements.

* **AppAuth's Strengths:**
    * **Robustness and Compliance:** AppAuth is an open-source library that adheres closely to OAuth
      2.0 and OpenID Connect specifications, including best practices like using Chrome Custom Tabs
      for authorization requests and supporting PKCE.
    * **Provider-Agnostic:** It is designed to work with any standard OAuth 2.0 or OpenID Connect
      identity provider, not just Google.
    * **Comprehensive Flow Management:** It helps manage the authorization request (launching Custom
      Tabs), handles the redirect URI, and provides utilities for making token requests and refresh
      token requests.
* Why Google's Integrated Approach Might Be Simpler for This Use Case:  
  The user's constraints are key: a purely mobile Android application, accessing only Google APIs (
  specifically Gmail), with no backend server component, and a desire for the simplest alternatives
  to potentially more complex setups.
    * **Google's Tailored Solution:** Credential Manager and
      com.google.android.gms.auth.api.identity.AuthorizationClient are tools specifically designed
      and provided by Google for its ecosystem on the Android platform.
        * Credential Manager 2 offers a streamlined and modern "Sign in with Google" user
          experience, integrated with system-level account management.
        * AuthorizationClient 5 is the designated and recommended pathway for authorizing
          application access to Google APIs on Android. It is likely to handle Google-specific
          nuances and endpoint configurations under the hood, potentially reducing the amount of
          manual setup required by the developer.
    * **Reduced Boilerplate (Potentially):** If AuthorizationClient effectively handles the PKCE
      mechanism transparently or provides a clear and simple way to obtain a client-side
      authorization code (addressing the ambiguity discussed in Section 4), the integration might
      involve less manual configuration of authorization endpoints, token endpoints, and discovery
      documents compared to setting up AppAuth specifically for Google.
    * **Alignment with Google Play Services and Jetpack:** These Google-provided tools are typically
      delivered as part of Google Play Services or Android Jetpack, ensuring they are maintained,
      updated in line with Android platform evolution, and aligned with Google's evolving
      authentication and security requirements.

The "simplicity" of a solution is often contextual. For an application with narrow requirements
focused solely on Google APIs on Android, Google's own libraries (Credential Manager and
AuthorizationClient) are designed to offer a more direct and integrated experience. They leverage
Google's intimate knowledge of its own authentication and authorization systems. This specialization
can translate to "simplicity" in terms of reduced setup code and configuration. However, this
simplicity might come at the cost of some transparency or fine-grained control if the underlying
mechanisms (such as how AuthorizationClient precisely implements or abstracts PKCE) are not fully
exposed or documented. AppAuth, being general-purpose, requires the developer to explicitly
configure all relevant endpoints and manage request/response details according to the specific OAuth
provider's documentation. While this offers maximum flexibility and control, it can also mean more
initial setup and boilerplate code for a single-provider scenario like Google.

* **When AppAuth is More Suitable:**
    * If the application needs to connect to multiple, varied OAuth 2.0 providers (e.g., Google,
      Facebook, a custom OAuth server).
    * If advanced OpenID Connect features beyond simple authentication (which Credential Manager's "
      Sign in with Google" provides) are required, such as relying on specific OIDC claims or flows
      not directly surfaced by Google's simpler APIs.
    * If the developer prefers the explicit, lower-level control over every step of the OAuth 2.0
      flow that AppAuth provides and finds Google's higher-level abstractions less transparent or
      suitable for their debugging and customization needs.

For the user's scenario, where the goal is the "simplest alternative" for Google API access,
Google's integrated tools are the primary recommendation, provided the AuthorizationClient path for
client-side token acquisition is clear and effective.  
**8\. Conclusion and Strategic Recommendations**  
Navigating Google's evolving identity landscape requires adherence to modern best practices to
ensure secure and robust access to Google APIs from purely mobile Android applications. The
recommended approach as of May 2025 centers on a decoupled authentication and authorization
strategy.  
**Recap of Recommended Approach:**

1. **User Authentication:** Employ Android's Credential Manager, specifically its "Sign in with
   Google" capability (using GetGoogleIdOption), to authenticate the user and obtain an ID Token for
   client-side identity assertion.
2. **API Authorization & Token Acquisition:** Utilize
   com.google.android.gms.auth.api.identity.AuthorizationClient to request the necessary OAuth
   scopes from the user.
    * If AuthorizationClient (after the consent PendingIntent flow) directly provides an access
      token (and ideally, a refresh token) via AuthorizationResult.getAccessToken(), it implies
      transparent handling of the PKCE exchange. This is the most straightforward path.
    * If AuthorizationClient yields a client-side authorization code (distinct from serverAuthCode),
      the application must then perform the PKCE token exchange manually by sending this code along
      with the code\_verifier to Google's token endpoint to receive access and refresh tokens.
3. **Token Management:** Securely store access and refresh tokens using the Android Keystore.
   Implement logic to refresh access tokens using the stored refresh token when they expire or when
   API calls return a 401 error.
4. **API Calls:** Leverage a modern HTTP client like Ktor with its Auth plugin to simplify the
   attachment of bearer tokens to API requests and to automate the access token refresh process.

**Key Takeaways and Recommendations:**

* **Stay Current with Deprecations:** Actively monitor and migrate away from deprecated APIs,
  particularly the legacy Google Sign-In library (com.google.android.gms.auth.api.signin) and
  outdated components within com.google.android.gms.auth.api.identity. Failure to do so will result
  in application breakage.
* **Embrace Decoupled Authentication and Authorization:** Clearly separate the process of
  identifying the user (authentication via Credential Manager) from the process of granting the
  application permissions to access data (authorization via AuthorizationClient). This aligns with
  modern security principles and improves user experience through incremental authorization.
* **Mandate PKCE:** For any client-side OAuth 2.0 token exchange, PKCE is essential for security in
  native applications. Ensure the chosen implementation path fully supports and correctly implements
  PKCE.
* **Verify AuthorizationClient Behavior:** Thoroughly test the AuthorizationClient flow to determine
  precisely how it facilitates client-side token acquisition. Specifically, ascertain whether it
  returns tokens directly (transparent PKCE) or provides a client-side authorization code suitable
  for manual PKCE exchange. Document this behavior clearly within the project, as it's a critical
  juncture in the OAuth flow.
* **Configure Google Cloud Console Correctly:** Pay close attention to the OAuth consent screen
  configuration in the Google Cloud Console. Setting the "Publishing status" to "In Production" is
  vital for obtaining long-lived refresh tokens.18 Ensure both Android and Web application OAuth
  client IDs are correctly configured and used in the appropriate contexts (setServerClientId in
  GetGoogleIdOption uses the Web client ID; the token exchange for API access uses the Android
  client ID).
* **Utilize Ktor's Auth Plugin:** For applications using Ktor, its Auth plugin provides a robust and
  convenient abstraction for managing bearer tokens and the refresh token lifecycle, leading to
  cleaner and more maintainable networking code.

Final Strategic Advice:  
For Android applications targeting Google's own services, prioritizing Google's recommended
libraries (Credential Manager, AuthorizationClient) generally offers the most integrated, supported,
and streamlined experience. These tools are designed to work seamlessly within the Google ecosystem.
However, a solid understanding of the underlying OAuth 2.0 principles, especially the Authorization
Code Grant with PKCE, remains indispensable for effective implementation, troubleshooting, and
adaptation to future changes.  
If the AuthorizationClient proves problematic in yielding a clear path for client-side token
acquisition (e.g., if it only provides a serverAuthCode unsuitable for client-side PKCE without a
secret, or if its mechanism for providing a client-side auth code or direct tokens is overly
obscure), developers may need to consider a more manual approach for the authorization code grant
step. This would involve manually constructing the authorization URL with PKCE parameters, launching
it in a Chrome Custom Tab, and handling the redirect to capture the authorization code, similar to
AppAuth's core mechanism. This path would still leverage Credential Manager for the initial user
authentication but would bypass AuthorizationClient for the authorization code grant, representing a
more complex alternative but one that provides explicit control over the PKCE flow. This fallback
should only be considered if the preferred AuthorizationClient route does not meet the application's
client-side OAuth requirements effectively.

#### **Works cited**

1. com.google.android.gms.auth.api.identity | Google Play services ..., accessed May 11,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/package-summary](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/package-summary)
2. Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity](https://developer.android.com/identity)
3. Request Additional Scopes | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/legacy/gsi/additional-scopes](https://developer.android.com/identity/legacy/gsi/additional-scopes)
4. Enabling Server-Side Access | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/legacy/gsi/offline-access](https://developer.android.com/identity/legacy/gsi/offline-access)
5. Authorize access to Google user data | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
6. Sign in your user with Credential Manager | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/sign-in/credential-manager](https://developer.android.com/identity/sign-in/credential-manager)
7. OAuth 2.0 for Mobile & Desktop Apps | Authorization \- Google for Developers, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/native-app](https://developers.google.com/identity/protocols/oauth2/native-app)
8. GoogleSignInAccount | Google Play services, accessed May 11,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInAccount](https://developers.google.com/android/reference/com/google/android/gms/auth/api/signin/GoogleSignInAccount)
9. Add Sign In with Google to Native Android Apps \- Auth0, accessed May 11,
   2025, [https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native](https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/google-native)
10. Authenticate with Google on Android \- Firebase, accessed May 11,
    2025, [https://firebase.google.com/docs/auth/android/google-signin](https://firebase.google.com/docs/auth/android/google-signin)
11. Getting Profile Information | Authentication \- Google for Developers, accessed May 11,
    2025, [https://developers.google.com/identity/sign-in/android/people](https://developers.google.com/identity/sign-in/android/people)
12. Using OAuth 2.0 to Access Google APIs | Authorization, accessed May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2](https://developers.google.com/identity/protocols/oauth2)
13. Set up OAuth for your Android app | Home APIs, accessed May 11,
    2025, [https://developers.home.google.com/apis/android/oauth](https://developers.home.google.com/apis/android/oauth)
14. Using OAuth 2.0 for Web Server Applications | Authorization \- Google for Developers, accessed
    May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2/web-server](https://developers.google.com/identity/protocols/oauth2/web-server)
15. OAuth 2.0 for Client-side Web Applications | Authorization | Google for Developers, accessed May
    11,
    2025, [https://developers.google.com/identity/protocols/oauth2/javascript-implicit-flow](https://developers.google.com/identity/protocols/oauth2/javascript-implicit-flow)
16. AuthorizationResult | Google Play services | Google for Developers, accessed May 11,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult)
17. OAuth2 google drive AccesToken refresh using Google Credential Manager \+ AuthorizationClient \-
    Stack Overflow, accessed May 11,
    2025, [https://stackoverflow.com/questions/79096280/oauth2-google-drive-accestoken-refresh-using-google-credential-manager-authori](https://stackoverflow.com/questions/79096280/oauth2-google-drive-accestoken-refresh-using-google-credential-manager-authori)
18. Requirements for long lived refresh token \- Google Cloud Community, accessed May 11,
    2025, [https://www.googlecloudcommunity.com/gc/Community-Hub/Requirements-for-long-lived-refresh-token/td-p/682184/jump-to/first-unread-message](https://www.googlecloudcommunity.com/gc/Community-Hub/Requirements-for-long-lived-refresh-token/td-p/682184/jump-to/first-unread-message)
19. Bearer authentication in Ktor Client, accessed May 11,
    2025, [https://ktor.io/docs/client-bearer-auth.html](https://ktor.io/docs/client-bearer-auth.html)
20. Ktor client, how correctly set access token after receiving it? \- Stack Overflow, accessed May
    11,
    2025, [https://stackoverflow.com/questions/78055897/ktor-client-how-correctly-set-access-token-after-receiving-it](https://stackoverflow.com/questions/78055897/ktor-client-how-correctly-set-access-token-after-receiving-it)