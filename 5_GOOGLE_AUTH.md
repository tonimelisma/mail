# **Melisma Mail: Google OAuth 2.0 Integration Strategy for Client-Side Gmail API Access**

## **1\. Executive Summary of Findings**

This report details an investigation into the optimal strategy for integrating Google OAuth 2.0 into
the Melisma Mail Android application, focusing on the critical requirement of obtaining and managing
refresh tokens entirely on the client-side for long-lived Gmail API access. The analysis centers on
the com.google.android.gms.auth.api.identity.AuthorizationClient and related Android Identity
APIs.  
**Core Conclusion on Refresh Token Acquisition:** The most viable path for client-side refresh token
acquisition involves utilizing the AuthorizationClient with requestOfflineAccess(WEB\_CLIENT\_ID).
This call is expected to provide a serverAuthCode via AuthorizationResult.getServerAuthCode(). The
Android application must then perform a client-side token exchange of this serverAuthCode using its
ANDROID\_CLIENT\_ID and Proof Key for Code Exchange (PKCE) to obtain an access token and the crucial
refresh token. While AuthorizationResult.getAccessToken() may also provide an initial short-lived
access token, the serverAuthCode is paramount for securing the refresh token.  
**PKCE Handling:** The AuthorizationRequest.Builder does not offer methods for the application to
supply PKCE parameters (e.g., code\_challenge). This strongly suggests that if AuthorizationClient
facilitates any part of the PKCE flow (particularly for the serverAuthCode acquisition), it does so
transparently. However, the subsequent client-side exchange of the serverAuthCode by the application
will necessitate manual PKCE implementation for that specific token request, where the application
generates a code\_verifier and includes it in the token exchange POST request to Google's token
endpoint. The initial part of the PKCE flow (sending a code\_challenge) related to the
serverAuthCode obtained via WEB\_CLIENT\_ID presents a complexity, as the app doesn't explicitly
send a challenge for *that specific code*. Testing will be crucial to determine if Google's endpoint
allows exchanging this serverAuthCode with an ANDROID\_CLIENT\_ID and a corresponding code\_verifier
without an explicitly app-sent code\_challenge for that initial code, or if the AuthorizationClient
handles this implicitly.  
**Client ID Usage:** Both Web and Android Client IDs play distinct roles. The WEB\_CLIENT\_ID is
used with androidx.credentials.CredentialManager (GetSignInWithGoogleOption.setServerClientId()) for
obtaining the ID Token, where it serves as the audience. It is also specified in
AuthorizationRequest.requestOfflineAccess(WEB\_CLIENT\_ID) when requesting the serverAuthCode. The
ANDROID\_CLIENT\_ID, tied to the app's package name and signature, is the appropriate client
identifier for the Android application when it performs the client-side token exchange (for the
serverAuthCode) and subsequent refresh token grants.  
**Key Recommendation:** Melisma Mail should proceed with an implementation strategy that uses
CredentialManager for initial authentication (ID Token via WEB\_CLIENT\_ID). Subsequently,
AuthorizationClient should be used to request Gmail scopes and offline access, specifying the
WEB\_CLIENT\_ID in requestOfflineAccess(). The application must then retrieve the serverAuthCode
from AuthorizationResult and perform a client-side HTTP POST request to Google's token endpoint.
This request will exchange the serverAuthCode for an access token and a refresh token, using the
app's ANDROID\_CLIENT\_ID, a redirect\_uri associated with the ANDROID\_CLIENT\_ID, and appropriate
PKCE parameters (specifically, the code\_verifier). All tokens must be stored securely using Android
AccountManager with app-level encryption backed by the Android Keystore.

## **2\. Detailed Analysis of AuthorizationClient for Client-Side Token Acquisition**

The primary objective for Melisma Mail is to achieve long-term, offline access to Gmail APIs,
necessitating a robust client-side mechanism for acquiring and managing OAuth 2.0 refresh tokens.
The com.google.android.gms.auth.api.identity.AuthorizationClient is the contemporary Google-provided
tool for this on Android.1 However, ambiguities exist regarding its precise behavior in a purely
client-side context, especially concerning PKCE and the retrieval of refresh tokens. This section
dissects these ambiguities by addressing the open questions Q1 through Q4.

### **Q1: Behavior of AuthorizationResult.getAccessToken() with requestOfflineAccess()**

The interaction between requesting offline access and the directly obtainable access token is
crucial for understanding the AuthorizationClient's internal workings.

#### **Q1a: Direct Usability of the Access Token from getAccessToken()**

The AuthorizationResult class provides a method getAccessToken(), documented simply as "Returns the
access token".2 This implies that a string, purported to be an access token, is directly available.
The central question is whether this token is non-null and usable for API calls when
requestOfflineAccess(WEB\_CLIENT\_ID) was included in the AuthorizationRequest.  
If getAccessToken() indeed returns a functional access token under these conditions, it suggests
that the AuthorizationClient, or the underlying Google Play Services, has performed more than just
fetching an authorization code. The method requestOfflineAccess() is documented to return an "
authorization code" specifically for a server to exchange for tokens.3 If an access token is *also*
immediately available on the client, it indicates that Google Play Services might have already used
an authorization code (perhaps an internally managed one, or the one also exposed as serverAuthCode)
to procure this initial access token.  
Supporting this, commentary from a Google team member, Ali Naddaf, indicates that after user grants,
calling the authorize() method (which ultimately yields the AuthorizationResult) returns an access
token immediately. This token is cached on the device, and subsequent calls to authorize() might
return a new access token if the cached one is expunged.4 This behavior strongly suggests that
AuthorizationResult.getAccessToken() will provide a usable, albeit short-lived, access token. The
empirical results from Test Scenario 1 are essential to confirm this behavior directly.

#### **Q1b: Assessment of Transparent PKCE Handling by AuthorizationClient**

A significant observation is that the AuthorizationRequest.Builder API does not expose methods such
as setCodeChallenge() or setCodeChallengeMethod. This absence is a strong indicator regarding PKCE
handling. For a modern OAuth client library from Google, PKCE is an indispensable security measure
for public clients like mobile applications. Therefore, if the application cannot supply a
code\_challenge, one of two scenarios must be true: either PKCE is not employed (which is highly
improbable and insecure), or it is managed transparently by the AuthorizationClient and Google Play
Services.  
The standard PKCE flow for native applications mandates that the application generates a
code\_verifier, derives a code\_challenge from it, includes the code\_challenge in the authorization
request to the authorization server, and subsequently sends the code\_verifier in the token exchange
request to the token endpoint.5  
If getAccessToken() returns a usable token (as explored in Q1a), and the application did not
manually configure any PKCE parameters, then the logical conclusion is that AuthorizationClient
*must* be handling the entire PKCE exchange transparently. To obtain an access token from an
authorization code grant, a token exchange is necessary. For public clients, PKCE is vital to secure
this exchange against authorization code interception.5 Thus, if the application receives an access
token without setting a code\_challenge, the library (AuthorizationClient/Play Services) must have
internally performed the following steps:

1. Generated a code\_verifier and its corresponding code\_challenge.
2. Sent the code\_challenge along with the internal authorization request.
3. Received an authorization code from the authorization server.
4. Exchanged this authorization code, along with the internally generated code\_verifier, for
   tokens (at least an access token).

This transparent handling would significantly simplify the application's responsibilities, as it
would not need to manage the complexities of PKCE parameter generation and transmission for this
initial access token.

#### **Q1c: Feasible Mechanisms for Client-Side Refresh Token Retrieval**

The AuthorizationResult API does not offer an explicit getRefreshToken() method 2, which is the
central challenge for Melisma Mail. If getAccessToken() provides an access token and PKCE is handled
transparently by AuthorizationClient, the mechanism for obtaining the *refresh token* remains to be
determined. Several possibilities exist:

1. **Undocumented or Bundled Method on AuthorizationResult:** This is improbable for a public Google
   API, as it would deviate from standard documentation practices. Nevertheless, thorough inspection
   of the AuthorizationResult object in Test Scenario 1 is warranted.
2. **Implicit Management by Google Play Services:** Google Play Services might internally store the
   refresh token and use it to automatically refresh the access token that is vended through
   getAccessToken() upon subsequent calls or when the current one expires. The observations by
   Renaud Cerrato and Ali Naddaf 4 regarding authorize() refreshing the access token without UI
   prompts lend credence to this for access tokens. However, this implicit management does not
   directly provide the *refresh token string* to the application. An email client like Melisma
   Mail, which needs to make background API calls via Ktor and manage the token lifecycle
   explicitly (e.g., handling 401 errors by initiating a token refresh), requires the actual refresh
   token string. Relying solely on getAccessToken() for a potentially auto-refreshed access token
   might be insufficient if the application cannot trigger the refresh on demand or obtain the
   refresh token itself.
3. **Access Token Allows Subsequent Client-Side Call for Refresh Token:** This is not a standard
   OAuth 2.0 flow. Access tokens are designed for accessing protected resources, not for obtaining
   other types of tokens.
4. **Using AuthorizationResult.getServerAuthCode() for Client-Side Exchange (Most Promising):** Even
   if getAccessToken() returns a usable access token, the serverAuthCode obtained via
   AuthorizationResult.getServerAuthCode() (when requestOfflineAccess() is used) is likely the key
   to the refresh token. A Stack Overflow answer explicitly suggests this path: "If you need to
   obtain a refresh token... set requestOfflineAccess(YOUR-SERVER-CLIENT-ID). This results in
   getting back not only an access token, but also an authorization code (AuthCode). This AuthCode
   can be exchanged for a new access token and a refresh token. This can be done on your mobile
   device...".4 This indicates that the serverAuthCode is intended to be exchangeable client-side,
   leading directly to the considerations in Q2.

### **Q2: Nature and Utility of AuthorizationResult.getServerAuthCode() in a Client-Only Flow**

Understanding the serverAuthCode is pivotal if it's the means to the refresh token in a client-only
architecture.

#### **Q2a: Confirmation of serverAuthCode's Primary Design for Backend Exchange**

The official API documentation for AuthorizationResult.getServerAuthCode() states it "Returns the
server authorization code that can be exchanged *by the server* for a refresh token" (2, emphasis
added). Similarly, the documentation for AuthorizationRequest.Builder.requestOfflineAccess(String
serverClientId) explains that "...an authorization code is returned so *the server* can use the
authorization code to exchange for a refresh token. The serverClientId parameter explicitly refers
to the 'client ID of the server that will need the authorization code.'" (3, emphasis added). Legacy
Google Sign-In documentation concerning requestServerAuthCode also consistently describes a flow
where this code is sent to the application's backend server for exchange.8  
These sources unequivocally indicate that the serverAuthCode is *intended* for use by a backend
server. Such a server would typically use its client secret (if it's a confidential web client)
during the token exchange process. The parameter name serverClientId (which expects a Web Client ID)
further reinforces this intended server-side usage. This documented intent creates a tension with
Melisma Mail's requirement for a purely client-side flow. Native mobile applications are public
clients and *must not* embed client secrets 6; PKCE was developed to address this exact scenario for
public clients. Therefore, if the serverAuthCode is to be utilized client-side, it must be
compatible with a PKCE-based exchange where a client secret is not required for the Android client.

#### **Q2b: Viability of Client-Side PKCE Exchange with serverAuthCode using an Android Client ID**

This is the linchpin for Melisma Mail's strategy. If the serverAuthCode (obtained using the
WEB\_CLIENT\_ID during the AuthorizationRequest) can be exchanged client-side using the
application's ANDROID\_CLIENT\_ID and a code\_verifier, the problem of refresh token acquisition is
solved.  
The standard OAuth 2.0 PKCE token exchange step for native applications requires parameters such as
client\_id (the native app's client ID), code (the authorization code), code\_verifier,
grant\_type='authorization\_code', and redirect\_uri. Crucially, Google's documentation on native
app PKCE specifies that a client secret is not applicable for Android clients during this
exchange.5  
This leads to a "Hybrid PKCE Flow Hypothesis":

1. Initial authentication occurs via CredentialManager using
   GetSignInWithGoogleOption.setServerClientId(WEB\_CLIENT\_ID), yielding an ID Token where the
   audience is the WEB\_CLIENT\_ID.9
2. Authorization for Gmail scopes is requested via AuthorizationClient.authorize() with
   AuthorizationRequest.Builder.requestOfflineAccess(WEB\_CLIENT\_ID).
3. It's hypothesized that AuthorizationClient transparently handles the *initial part of PKCE* for
   this request (i.e., internally generates a code\_verifier, derives a code\_challenge, and sends
   this code\_challenge with the authorization request associated with the WEB\_CLIENT\_ID). The
   AuthorizationResult then provides the serverAuthCode.
4. The Android application takes this serverAuthCode.
5. The application then *manually* performs the *second part* of the PKCE exchange (the token
   request) using its ANDROID\_CLIENT\_ID. The parameters for this POST request
   to https://oauth2.googleapis.com/token would be:
    * grant\_type=authorization\_code
    * code=serverAuthCode (from AuthorizationResult)
    * client\_id=ANDROID\_CLIENT\_ID (the app's native client ID)
    * redirect\_uri (a URI associated with the ANDROID\_CLIENT\_ID and configured in the Google
      Cloud Console)
    * code\_verifier: This is the most ambiguous element. If AuthorizationClient generated the
      verifier internally for the serverAuthCode request, the app does not possess this verifier.
      For the app to complete the exchange, either:
        * Google's token endpoint has special handling for serverAuthCodes obtained via
          AuthorizationClient with a WEB\_CLIENT\_ID, possibly relaxing the code\_verifier check if
          the exchange request comes from the ANDROID\_CLIENT\_ID (identified by package
          name/signature). This would be a Google-specific deviation from strict PKCE.
        * The AuthorizationClient would need to expose the code\_verifier it used, for which there
          is no current evidence.
        * The serverAuthCode is not PKCE-protected at all, making client-side exchange insecure
          without additional measures. This is unlikely for a modern Google API.

A critical point of ambiguity arises: requestOfflineAccess() uses the WEB\_CLIENT\_ID. Standard
native PKCE token exchanges use the ANDROID\_CLIENT\_ID. Can an auth code obtained in the context of
a WEB\_CLIENT\_ID be exchanged using an ANDROID\_CLIENT\_ID? Google's token endpoint must associate
the incoming authorization code with the original request and its PKCE challenge. If the original
request was tied to the WEB\_CLIENT\_ID, using the ANDROID\_CLIENT\_ID in the token exchange might
fail unless Google's backend has specific logic to permit this "cross-client" reference for this
particular SDK-mediated flow. Some anecdotal evidence from Capacitor/Cordova contexts 10 shows that
using a Web Client ID for client-side configuration made Google Sign-In work where an Android Client
ID did not, hinting that Web Client IDs can sometimes function in client contexts. However, this was
for sign-in, not necessarily for exchanging an authorization code obtained via requestOfflineAccess
for API scopes.  
Test Scenario 2 is designed to directly probe this. It will attempt to exchange the serverAuthCode
using the ANDROID\_CLIENT\_ID and a freshly generated code\_verifier. If AuthorizationClient did not
send a corresponding code\_challenge when obtaining the serverAuthCode, this test (as designed by
the user, assuming Google *somehow* got the challenge) would likely fail with an invalid\_grant
error (mismatched or missing verifier). If it succeeds, it implies a specific behavior by Google's
token endpoint for these SDK-originated codes.  
A refined perspective for Test Scenario 2 is that PKCE *is* required. If AuthorizationClient *did*
internally send a code\_challenge (linked to the WEB\_CLIENT\_ID), then the serverAuthCode it
returns *is* a PKCE-enabled code. The core issue is whether this code can be exchanged using the
ANDROID\_CLIENT\_ID and the *original, internally generated code\_verifier* (which the app doesn't
have access to). This suggests that if serverAuthCode is to be exchanged client-side by the app, one
of the following must be true:

1. AuthorizationClient performs the *entire* exchange transparently and makes the refresh token
   available (as discussed in Q1c).
2. AuthorizationClient provides *both* the serverAuthCode *and* the code\_verifier it used (no
   evidence of this).
3. The serverAuthCode obtained with WEB\_CLIENT\_ID is special and can be exchanged using
   ANDROID\_CLIENT\_ID *without* a code\_verifier if the call originates from a recognized Android
   app. This would be a deviation from RFC 7636\.
4. The serverAuthCode can be exchanged using the WEB\_CLIENT\_ID and a code\_verifier. Web Client
   IDs are typically for confidential clients (requiring a secret), but they can be configured for
   public client use (e.g., for Single-Page Applications).11 An Android app using its
   WEB\_CLIENT\_ID as if it were a public client for the token exchange is plausible but requires
   careful verification of client ID type configurations and capabilities.

### **Q3: How does PKCE truly operate with AuthorizationClient?**

The exact mechanics of PKCE within AuthorizationClient are central to a secure implementation.

#### **Q3a: If AuthorizationClient is handling PKCE transparently (leading to getAccessToken()
returning a token as in Q1a), does it use the Android Client ID internally for this PKCE exchange
with Google's token endpoint?**

If AuthorizationClient transparently handles PKCE to yield the access token obtainable via
getAccessToken(), it logically *should* use the app's registered ANDROID\_CLIENT\_ID for that token
exchange. This aligns with standard practice for native Android applications, where the
ANDROID\_CLIENT\_ID is bound to the app's package name and SHA-1 signature, providing a layer of
client authentication.5  
However, the situation is complicated if requestOfflineAccess(WEB\_CLIENT\_ID) was called. If the
request for the authorization code (which AuthorizationClient might be internally exchanging) was
initiated with the WEB\_CLIENT\_ID as a parameter, it raises questions:

* Does AuthorizationClient internally switch to using the ANDROID\_CLIENT\_ID for the actual token
  exchange part?
* Or, does it perform the token exchange using the WEB\_CLIENT\_ID, but without a client secret,
  effectively treating the WEB\_CLIENT\_ID as a public client for this specific SDK-mediated flow?
  The anecdotal evidence from 10 and 10 (Web Client ID working in a client context) might be
  relevant here.

The ID Token obtained via CredentialManager (using GetSignInWithGoogleOption.setServerClientId(
WEB\_CLIENT\_ID)) has the WEB\_CLIENT\_ID as its audience (aud claim).9 This ID Token is for
*authentication*. The subsequent *authorization* flow with AuthorizationClient for API scopes is a
distinct step.1 It is plausible that when AuthorizationClient is used *without* requesting offline
access, it defaults to using the ANDROID\_CLIENT\_ID for its internal PKCE flow to populate
getAccessToken(). When requestOfflineAccess(WEB\_CLIENT\_ID) is involved, the flow for
serverAuthCode is explicitly tied to the WEB\_CLIENT\_ID by the API's documentation.3 The behavior
of getAccessToken() in *this specific context* (offline access requested with Web Client ID) is
therefore a key point of investigation. The choice of client ID for the initial authorization code
request (which might be internal to AuthorizationClient) dictates which client ID is expected by
Google's token endpoint during the exchange.

#### **Q3b: If AuthorizationClient does not handle PKCE transparently and only provides a code that
we must exchange, how would our app supply the code\_challenge initially, given
AuthorizationRequest.Builder has no such method?**

This scenario implies that AuthorizationClient is unsuitable for a fully client-side PKCE flow if it
only returns either a "non-PKCE" authorization code or a PKCE-enabled authorization code without
providing the application a means to have sent the corresponding code\_challenge. As the user
correctly surmised, this seems increasingly unlikely. If AuthorizationClient is the recommended
modern tool for authorization on Android 1, it should inherently support secure flows. Failing to
support PKCE transparently *and* not allowing manual PKCE parameter input would render it deficient
for public clients.  
In such a case, the application would be forced to abandon AuthorizationClient for the authorization
step. Instead, it would need to employ a library like AppAuth for Android 5, which grants full
manual control over PKCE parameters (generation of code\_verifier and code\_challenge, and their
inclusion in requests) and would use the ANDROID\_CLIENT\_ID throughout the authorization process.
This would mean a two-part strategy: androidx.credentials.CredentialManager for authentication (ID
Token), followed by AppAuth for authorization (API scopes, refresh token).  
The existence and promotion of AuthorizationClient as a modern API suggest it should offer a more
integrated solution. The most probable scenario is transparent PKCE handling for getAccessToken().
The primary ambiguity remains around getServerAuthCode() and its interaction with PKCE and different
client ID types.

### **Q4: What is the role of AuthorizationResult.toGoogleSignInAccount() in obtaining usable
tokens?**

The utility of GoogleSignInAccount in this new authorization flow needs clarification.

#### **Q4a: If getAccessToken() provides a token, does converting to GoogleSignInAccount offer any
additional, reliable way to access the refresh token or other necessary credentials for the
client-side flow?**

The GoogleSignInAccount class is part of a deprecated Google Sign-In API.12 The
AuthorizationResult.toGoogleSignInAccount() method is documented to convert the result to an
equivalent GoogleSignInAccount object if the authorization operation successfully returned tokens;
otherwise, it returns null (e.g., if a PendingIntent was provided).2  
A Google team member, Ali Naddaf, has commented that GoogleSignInAccount is deprecated and that
toGoogleSignInAccount() might return an empty or useless object in the context of
AuthorizationResult. He also suggested it might be removed from AuthorizationResult in the
future.12  
Given its deprecated status and this direct feedback, relying on toGoogleSignInAccount() for any
critical token information, particularly a refresh token, is ill-advised for a new implementation
like Melisma Mail. The focus for token acquisition should remain firmly on
AuthorizationResult.getAccessToken() and AuthorizationResult.getServerAuthCode(). It is highly
unlikely that toGoogleSignInAccount() offers a reliable pathway to the refresh token.

### **Table 1: AuthorizationResult Method Analysis for Token Retrieval**

To consolidate the analysis of AuthorizationResult methods central to the user's core questions, the
following table summarizes their documented purpose and assessed relevance for obtaining refresh
tokens client-side:

| Method                  | Return Type         | Official Description Summary (from )                                                               | Relevance to Client-Side Refresh Token                                                                                                                                                              | Key Evidence Sources |
|:------------------------|:--------------------|:---------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|:---------------------|
| getAccessToken()        | String              | "Returns the access token."                                                                        | **Potentially YES.** If non-null after requestOfflineAccess(), implies transparent PKCE. May provide short-lived AT. Unlikely to provide RT directly. (Addresses Q1a, Q1b)                          | 2                    |
| getServerAuthCode()     | String              | "Returns the server authorization code that can be exchanged *by the server* for a refresh token." | **Potentially YES (indirectly).** Primary candidate for client-side exchange if Q1 doesn't yield RT, despite "server" nomenclature. Central to Q2. Requires client-side PKCE exchange to be viable. | 1                    |
| toGoogleSignInAccount() | GoogleSignInAccount | "Converts to GSA if tokens returned."                                                              | **NO.** Deprecated and unreliable for new token acquisition strategies (Addresses Q4).                                                                                                              | 2                    |

This table provides a quick reference, linking the methods to specific questions and supporting
evidence, aiding in strategic decision-making for Melisma Mail.

## **3\. Guidance on Operational and Implementation Questions**

Beyond the core token acquisition mechanics, operational aspects such as token longevity and refresh
mechanisms are vital for a seamless user experience.

### **S1: Confirmation and Considerations for Long-Lived Refresh Tokens**

The user correctly understands that the "In Production" status of the OAuth consent screen in the
Google Cloud Console is generally key for obtaining long-lived refresh tokens from Google. Refresh
tokens issued to applications in "Testing" mode often have a short lifespan (e.g., 7 days).  
The documentation for requestOfflineAccess 3 implies that the refresh token obtained is intended to
allow server access when the user is not actively using the app. This inherently suggests that the
refresh token itself should be long-lived, provided the OAuth consent screen is appropriately
configured. Google also imposes limits on the number of active refresh tokens per client/user
combination and per user across all clients; requesting too many can lead to older ones being
invalidated.13  
**Recommendation:**

1. Ensure Melisma Mail's OAuth Consent Screen in the Google Cloud Console is configured to "In
   Production" before extensive testing or release.
2. After successfully obtaining a refresh token via the client-side flow, conduct empirical testing
   by attempting to use it to get new access tokens over a period exceeding 7 days (e.g., for
   several weeks or months).
3. Monitor for invalid\_grant errors during refresh attempts. Such errors can indicate that the
   refresh token has been revoked (by the user or Google), has expired (if not truly long-lived), or
   has been invalidated due to token limits.13

### **S2: Client-Side Token Refresh Mechanism with Ktor: Google Token Endpoint Requirements and
Refresh Token Rotation**

Once a refresh token is obtained and securely stored, Melisma Mail's Ktor HTTP client will need to
use it to fetch new access tokens.

#### **S2a: Parameters for Refresh Token Grant**

A standard OAuth 2.0 refresh token grant involves a POST request to the token endpoint. For a native
Android application performing this client-side, the following parameters are typically required for
Google's token endpoint (https://oauth2.googleapis.com/token):

* grant\_type: Must be set to refresh\_token.
* refresh\_token: The actual stored refresh token string.
* client\_id: The application's ANDROID\_CLIENT\_ID.

Crucially, a client\_secret is **not** included for public clients like native Android apps.5 The
PKCE code\_verifier is also not part of the refresh token grant itself; its role is to protect the
initial authorization code exchange. An analogous flow for Okta confirms that for PKCE-acquired
refresh tokens with public clients, the refresh call only needs grant\_type, client\_id, and the
refresh\_token.14  
Recommendation:  
When Ktor's refreshTokens lambda is triggered, it should construct an HTTP POST request
to https://oauth2.googleapis.com/token with the body containing client\_id (the Android Client ID),
refresh\_token (the stored refresh token), and grant\_type='refresh\_token'.

#### **S2b: Refresh Token Rotation**

Refresh token rotation is a security best practice where the authorization server issues a new
refresh token each time the current one is used to obtain a new access token. The previously used
refresh token is then invalidated. Auth0 documentation, for instance, clearly describes this
behavior.6  
Google's behavior regarding refresh token rotation can sometimes vary depending on the specific API
or client type. While some Google documentation implies that refresh tokens can be long-lived and
reused until they expire or are revoked 13, it is essential for the application to be prepared for
rotation. If a new refresh token is returned in the JSON response from Google's token endpoint
during a refresh grant, the application *must* securely store this new refresh token and discard the
one that was just used. Failure to do so (i.e., continuing to use an old, rotated-out refresh token)
will result in subsequent refresh attempts failing.  
Recommendation:  
Melisma Mail's token refresh logic must inspect the JSON response from Google's token endpoint after
a successful refresh grant.

* If the response body contains a refresh\_token field with a new token value, the application must
  update its stored refresh token with this new value.
* If no new refresh\_token field is present in the response, the application should continue to use
  the existing stored refresh token for future refresh attempts. This conditional update ensures
  compatibility with Google's potential use of refresh token rotation.

## **4\. Recommended Implementation Strategy for Melisma Mail**

Based on the analysis of Google's Identity APIs and OAuth 2.0 best practices for native clients, the
following step-by-step strategy is recommended for Melisma Mail to achieve client-side Gmail API
access with refresh tokens.

### **Step 1: Initial User Authentication (Obtaining ID Token)**

The first phase is to authenticate the user and obtain a Google ID Token.

* Utilize androidx.credentials.CredentialManager along with GetSignInWithGoogleOption.
* Configure the GetSignInWithGoogleOption by calling GetSignInWithGoogleOption.Builder()
  .setServerClientId(YOUR\_WEB\_CLIENT\_ID).9 The YOUR\_WEB\_CLIENT\_ID refers to the OAuth 2.0
  Client ID of type "Web application" created in the Google Cloud Console. This ID will be the
  audience (aud claim) of the resulting Google ID Token. While traditionally for server-side
  verification, its use here is mandated by the API for identifying the app's configuration to
  Google.
* Include a nonce by calling setNonce() on the builder to mitigate replay attacks.9
* Successful execution of this flow will yield a GoogleIdTokenCredential. This object contains the
  Google ID Token, which confirms the user's identity. However, this token does not grant
  permissions to access Gmail APIs.

### **Step 2: Requesting Gmail API Scopes and Offline Access (Authorization)**

Once the user is authenticated, the next step is to request their consent for the necessary Gmail
API scopes and to enable offline access.

* Employ the com.google.android.gms.auth.api.identity.AuthorizationClient.
* Construct an AuthorizationRequest object using AuthorizationRequest.Builder():
    * Specify all required Gmail API scopes (
      e.g., https://www.googleapis.com/auth/gmail.readonly, https://www.googleapis.com/auth/gmail.send,
      etc., depending on Melisma Mail's features) using setRequestedScopes().
    * Crucially, invoke requestOfflineAccess(YOUR\_WEB\_CLIENT\_ID).3 The YOUR\_WEB\_CLIENT\_ID
      should ideally be the same Web Client ID used in Step 1\. This call signals to Google that the
      application requires an authorization code that can eventually be exchanged for a refresh
      token.
    * The requestOfflineAccess method has an overload: requestOfflineAccess(String serverClientId,
      boolean forceCodeForRefreshToken).3 The forceCodeForRefreshToken parameter should generally be
      set to false. Setting it to true forces the user to re-consent even if they've previously
      granted permissions and is typically used if the application has lost a previous refresh token
      and needs to ensure a new one is issued with explicit consent. For the initial grant, false is
      appropriate, as the documentation suggests, "The first time you retrieve a code, a refresh
      token will be granted automatically. Subsequent requests will require additional user
      consent.".3
* Initiate the authorization flow by calling AuthorizationClient.authorize(authorizationRequest).
* The result of this call will be an AuthorizationResult. Check AuthorizationResult.hasResolution().
  If true, retrieve the PendingIntent using AuthorizationResult.getPendingIntent() and launch it (
  e.g., via an Activity Result Launcher). This will typically display Google's consent screen to the
  user.
* Upon completion of the user consent flow, the result will be delivered back to the application (
  e.g., in the onActivityResult callback or the callback of the Activity Result Launcher). This
  result will contain the final AuthorizationResult.

### **Step 3: Obtaining Access and Refresh Tokens**

This step is the core of the solution and depends on the behavior confirmed by the test plan.

* Path A (Hypothetical Simplest Path \- Less Likely for Refresh Token String):  
  If AuthorizationResult.getAccessToken() returns a non-null access token AND a refresh token string
  is directly accessible from AuthorizationResult or through an implicit mechanism that Ktor can
  leverage directly. This would mean AuthorizationClient handled PKCE transparently and provided all
  necessary tokens. However, as established, there's no direct getRefreshToken() method, making this
  path unlikely for obtaining the actual refresh token string needed for explicit management by
  Ktor.
* Path B (Most Probable and Recommended Path: Exchanging serverAuthCode Client-Side):  
  This path assumes that while AuthorizationResult.getAccessToken() might provide an initial
  short-lived access token 4, the serverAuthCode is the key to obtaining the long-lived refresh
  token.
    1. From the final AuthorizationResult obtained in Step 2, call
       authorizationResult.getServerAuthCode() to retrieve the authorization code (let's call it
       authCode).2
    2. The authorizationResult.getAccessToken() might also return a usable short-lived access token
       at this point. This token can be used for immediate API calls if needed, but the authCode is
       essential for the refresh token.
    3. **Perform a client-side PKCE-protected token exchange:**
        * **HTTP Request:** The application must make an HTTPS POST request to Google's token
          endpoint: https://oauth2.googleapis.com/token.
        * **Parameters (form-urlencoded body):**
            * grant\_type: authorization\_code
            * code: The authCode obtained from getServerAuthCode().
            * client\_id: **YOUR\_ANDROID\_CLIENT\_ID**. This is a critical part of the
              recommendation. The native application's ANDROID\_CLIENT\_ID (associated with the
              app's package name and SHA-1 signing certificate fingerprint) is designed for public
              clients and does not require a client secret.5 Using it here aligns with PKCE best
              practices for native applications.
            * redirect\_uri: The redirect\_uri that is registered in the Google Cloud Console for
              your ANDROID\_CLIENT\_ID. This URI must exactly match one of the registered redirect
              URIs. For native apps, this is often a custom scheme (e.g., com.example.app:
              /oauth2redirect) or an HTTPS App Link.
            * code\_verifier: This is the most complex parameter in this specific flow.
                * The standard PKCE flow requires the client to generate a code\_verifier, derive a
                  code\_challenge, send the code\_challenge with the authorization request, and then
                  send the code\_verifier with the token request.5
                * Since AuthorizationRequest.Builder does not allow setting a code\_challenge, if
                  AuthorizationClient obtained the serverAuthCode using the WEB\_CLIENT\_ID and
                  transparently handled the code\_challenge part, the application does not know the
                  code\_verifier that AuthorizationClient might have used.
                * **Recommended Approach for Testing 4:** The application should generate its own
                  fresh, cryptographically-secure code\_verifier for *this token exchange request*.
                    * If this exchange succeeds, it implies that either:
                        * Google's token endpoint does not strictly require a matching
                          code\_verifier for serverAuthCodes obtained via AuthorizationClient when
                          the exchange is made with an ANDROID\_CLIENT\_ID (a Google-specific
                          leniency).
                        * The serverAuthCode obtained via requestOfflineAccess(WEB\_CLIENT\_ID) is
                          not PKCE-protected in the standard way, or its PKCE protection is tied
                          only to the WEB\_CLIENT\_ID context in a way that the ANDROID\_CLIENT\_ID
                          exchange bypasses or satisfies differently.
                    * If this exchange fails with an invalid\_grant (often indicating a PKCE
                      verifier issue), it points to a more complex interaction. The serverAuthCode
                      might indeed be PKCE-protected by a verifier unknown to the app, making direct
                      exchange by the app impossible without further information or a different
                      strategy (like using AppAuth for the entire authorization flow from the
                      start).
                * The Stack Overflow answer 4 suggests this client-side exchange is feasible, though
                  the linked example uses older APIs.4 The success of Test Scenario 2 will be highly
                  informative.
        * **Successful Exchange Response:** A successful POST request to the token endpoint will
          return a JSON object containing:
            * access\_token: A new short-lived access token.
            * refresh\_token: The long-lived refresh token. **This is the primary goal.**
            * expires\_in: The lifetime of the access token in seconds.
            * scope: The scopes for which the access token is valid.
        * Securely store the refresh\_token (and the initial access\_token) as described in Step 4\.

### **Step 4: Secure Token Storage**

Properly securing the obtained OAuth tokens is paramount.

* As per the user's preference, use the Android AccountManager for storing the tokens.
  AccountManager provides a centralized way to manage account credentials.
* Before storing any token (access token, refresh token) in AccountManager, encrypt it using
  cryptographickeys generated by and stored in the Android Keystore system. The application should
  define its own account type for AccountManager. This ensures that even if the AccountManager
  database is somehow compromised (e.g., on a rooted device), the tokens themselves remain
  encrypted.

### **Step 5: Using Tokens with Ktor and Refreshing Access Tokens**

With tokens stored, Melisma Mail's Ktor HTTP client can be configured to make authenticated API
calls and handle token refresh.

* For every API request to Gmail, Ktor must include the current access\_token in the Authorization
  HTTP header, typically as a Bearer token (e.g., Authorization: Bearer \<access\_token\>).
* Implement Ktor's Auth feature, specifically configuring its refreshTokens lambda (or equivalent
  mechanism). This lambda will be invoked when an API call results in a 401 Unauthorized error (
  indicating an expired or invalid access token), or it can be triggered proactively if the app
  tracks token expiry.
* Inside the refreshTokens lambda:
    1. Retrieve the encrypted refresh\_token from AccountManager and decrypt it using the Android
       Keystore-backed key.
    2. Make an HTTPS POST request to Google's token endpoint (https://oauth2.googleapis.com/token).
    3. The request body (form-urlencoded) must contain:
        * grant\_type=refresh\_token
        * refresh\_token=THE\_STORED\_AND\_DECRYPTED\_REFRESH\_TOKEN
        * client\_id=YOUR\_ANDROID\_CLIENT\_ID
        * (No client\_secret is sent)
    4. Upon a successful response (HTTP 200 OK), the JSON body will contain a new access\_token and
       its expires\_in value.
    5. **Handle Refresh Token Rotation:** The response *may* also contain a new refresh\_token (see
       S2b). If it does, the application must encrypt and store this new refresh token, replacing
       the old one. If no new refresh token is provided, the existing one remains valid and should
       be reused.
    6. Store the new access\_token (encrypted) and update any in-memory cached version.
    7. The Ktor Auth feature should then automatically retry the failed request with the new
       access\_token.

### **Table 2: Client ID Roles in Google OAuth for Android (Client-Side Flow)**

This table clarifies the distinct roles and usage contexts of the Web and Android Client IDs in the
recommended OAuth flow, addressing a key area of potential confusion.

| Client ID Type    | Configuration Context                                         | Purpose in Flow                                                                                                                                                        | Key Evidence Sources                     |
|:------------------|:--------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|:-----------------------------------------|
| Web Client ID     | GetSignInWithGoogleOption.setServerClientId()                 | Used for aud (audience) claim in the Google ID Token obtained via CredentialManager. This is primarily for authentication.                                             | 9                                        |
| Web Client ID     | AuthorizationRequest.requestOfflineAccess()                   | Parameter to signal request for an authorization code intended for "server" exchange; influences the serverAuthCode obtained from AuthorizationResult. (Authorization) | 3                                        |
| Android Client ID | App's build configuration & Google Cloud Console registration | Primary identifier for the native Android app, bound to its package name & SHA-1 signature. Expected for client-side PKCE token exchange and refresh token grants.     | 1                                        |
| Android Client ID | Token Exchange (for serverAuthCode)                           | client\_id parameter in the POST request to https://oauth2.googleapis.com/token when exchanging the serverAuthCode.                                                    | 5 (general PKCE), 4 (implies client use) |
| Android Client ID | Refresh Token Grant                                           | client\_id parameter in the POST request to https://oauth2.googleapis.com/token when using grant\_type=refresh\_token.                                                 | 14 (analogous), standard OAuth           |

## **5\. Critical Security Considerations and Best Practices**

Implementing OAuth 2.0 client-side requires meticulous attention to security to protect user data
and tokens.

* **Secure Token Storage:** As emphasized in the recommended strategy, tokens (especially refresh
  tokens) must be encrypted at the application level before being stored in AccountManager. The
  Android Keystore system provides hardware-backed key storage (on supported devices), making it the
  preferred way to protect the encryption keys used for token encryption. This adds a crucial layer
  of defense against token theft on compromised devices.
* **PKCE Usage:** PKCE (Proof Key for Code Exchange) is essential for any client-side authorization
  code flow, as it mitigates the risk of authorization code interception.
    * If Path B (client-side exchange of serverAuthCode) is implemented, and the application is
      manually constructing the token request, it must ensure proper generation of the
      code\_verifier (a high-entropy cryptographic random string of 43-128 characters using
      unreserved characters 5) and the corresponding code\_challenge (using the S256 method:
      BASE64URL-ENCODE(SHA256(ASCII(code\_verifier)))).
    * A significant consideration is that if AuthorizationClient (when requestOfflineAccess(
      WEB\_CLIENT\_ID) is used) does *not* transparently handle the initial code\_challenge part of
      PKCE for the serverAuthCode, or if the serverAuthCode it provides is not compatible with a
      code\_verifier generated by the app for the exchange with the ANDROID\_CLIENT\_ID, then the
      security of this specific exchange could be weaker than a full, app-controlled PKCE flow (like
      one managed by AppAuth). The outcome of Test Scenario 2 is critical here. If the
      serverAuthCode exchange works without a code\_verifier or with a mismatched one, it implies
      Google has specific server-side considerations for codes obtained via AuthorizationClient.
* **Redirect URI Security:** The redirect\_uri used in the OAuth flow (associated with the
  ANDROID\_CLIENT\_ID) must be securely configured.
    * Android App Links (app-claimed HTTPS URLs) are generally more secure than custom URI schemes.
      Custom schemes can potentially be intercepted by malicious applications if multiple apps
      register the same scheme. Comments in 4 and 16 highlight issues with custom schemes and
      recommend App Links.
    * The redirect\_uri must be precisely registered in the Google Cloud Console for the
      ANDROID\_CLIENT\_ID.
* **Scope Management:** Adhere to the principle of least privilege.
    * Request only the Gmail API scopes that Melisma Mail absolutely needs for its functionality (
      e.g., https://www.googleapis.com/auth/gmail.readonly, https://www.googleapis.com/auth/gmail.modify,
      etc.), in addition to the offline\_access scope (which is implicitly handled by
      requestOfflineAccess()).
    * While the user query indicates a preference for requesting all scopes upfront, consider the
      user experience. If feasible and less intrusive, incremental authorization (requesting scopes
      only when a feature needing them is accessed) is often preferred.11 If upfront, ensure the
      consent screen clearly explains why these permissions are needed.
* **Token Revocation:** Provide a clear mechanism within Melisma Mail for users to sign out and
  disconnect their Google account. This should trigger a token revocation call to Google.
    * To revoke a token (either an access token or, more effectively, a refresh token), make an
      HTTPS GET or POST request to https://oauth2.googleapis.com/revoke?token=TOKEN\_TO\_REVOKE.
      Revoking a refresh token typically invalidates all access tokens issued based on it.
    * Clear the tokens from AccountManager after successful revocation. 17 mentions a JavaScript
      equivalent, google.accounts.oauth2.revoke(), illustrating the concept.
* **Error Handling:** Implement comprehensive error handling for all stages of the OAuth flow and
  for API calls.
    * Gracefully manage OAuth errors such as invalid\_grant (which can mean an invalid refresh
      token, expired code, PKCE mismatch, etc.), invalid\_scope, unauthorized\_client, and network
      connectivity issues.
    * If a refresh token becomes permanently invalid (e.g., revoked by the user from their Google
      account settings), the application must guide the user through the re-authentication and
      re-authorization process (starting from Step 1 of the recommended strategy).
* **Anti-CSRF (State Parameter):** While AuthorizationClient is likely to handle the state parameter
  transparently during the authorization flow it initiates, if any part of the authorization
  request/response becomes manual (e.g., if switching to a library like AppAuth for full control),
  ensure a unique, unpredictable state parameter is included in the authorization request. This
  state value must then be validated when the authorization response is received via the redirect
  URI to prevent Cross-Site Request Forgery attacks.5
* **Do Not Embed Web Client ID Secret:** It is critical to reiterate that even though a
  WEB\_CLIENT\_ID is used in parts of this flow, its associated client secret (if one was generated,
  as is typical for web server applications) must *never* be embedded or stored within the Android
  application.6 The proposed client-side flows rely on the WEB\_CLIENT\_ID being used as an
  identifier in contexts where a secret is not required from the mobile client (e.g., ID token
  audience, parameter for requestOfflineAccess), or on the ANDROID\_CLIENT\_ID (which doesn't have a
  secret) being used for the actual token exchange.

By adhering to this recommended strategy and these security best practices, Melisma Mail can
implement a robust and secure client-side Google OAuth 2.0 integration for accessing Gmail APIs. The
outcomes of the planned test scenarios will be crucial for refining the specifics of Step 3 (Token
Acquisition).

#### **Works cited**

1. Authorize access to Google user data | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/authorization](https://developer.android.com/identity/authorization)
2. AuthorizationResult | Google Play services | Google for Developers, accessed May 11,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationResult)
3. AuthorizationRequest.Builder | Google Play services | Google for ..., accessed May 11,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationRequest.Builder)
4. Google Identity Authorization (Android) : how to get refresh token? \- Stack Overflow, accessed
   May 11,
   2025, [https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token](https://stackoverflow.com/questions/79342368/google-identity-authorization-android-how-to-get-refresh-token)
5. OAuth 2.0 for Mobile & Desktop Apps | Authorization \- Google for Developers, accessed May 11,
   2025, [https://developers.google.com/identity/protocols/oauth2/native-app](https://developers.google.com/identity/protocols/oauth2/native-app)
6. Authorization Code Flow with Proof Key for Code Exchange (PKCE) \- Auth0, accessed May 11,
   2025, [https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce](https://auth0.com/docs/get-started/authentication-and-authorization-flow/authorization-code-flow-with-pkce)
7. What is PKCE? Flow Examples and How It Works \- Descope, accessed May 11,
   2025, [https://www.descope.com/learn/post/pkce](https://www.descope.com/learn/post/pkce)
8. Enabling Server-Side Access | Identity \- Android Developers, accessed May 11,
   2025, [https://developer.android.com/identity/legacy/gsi/offline-access](https://developer.android.com/identity/legacy/gsi/offline-access)
9. Authenticate users with Sign in with Google | Identity | Android ..., accessed May 11,
   2025, [https://developer.android.com/identity/sign-in/credential-manager-siwg](https://developer.android.com/identity/sign-in/credential-manager-siwg)
10. Android ClientId do not working, but Web ClientId do working · Issue ..., accessed May 11,
    2025, [https://github.com/CodetrixStudio/CapacitorGoogleAuth/issues/157](https://github.com/CodetrixStudio/CapacitorGoogleAuth/issues/157)
11. Using OAuth 2.0 to Access Google APIs | Authorization, accessed May 11,
    2025, [https://developers.google.com/identity/protocols/oauth2](https://developers.google.com/identity/protocols/oauth2)
12. How to get user account name after Google Drive authorization ..., accessed May 11,
    2025, [https://stackoverflow.com/questions/79347159/how-to-get-user-account-name-after-google-drive-authorization](https://stackoverflow.com/questions/79347159/how-to-get-user-account-name-after-google-drive-authorization)
13. Requesting new Access Token with our Refresh Token Returns : Invalid\_grant \- bad request,
    accessed May 11,
    2025, [https://groups.google.com/g/adwords-api/c/LgBodqQwDKA](https://groups.google.com/g/adwords-api/c/LgBodqQwDKA)
14. Refresh access token with a refresh token acquired through PKCE flow \- Questions, accessed May
    11,
    2025, [https://devforum.okta.com/t/refresh-access-token-with-a-refresh-token-acquired-through-pkce-flow/14374](https://devforum.okta.com/t/refresh-access-token-with-a-refresh-token-acquired-through-pkce-flow/14374)
15. Authorization Request \- OAuth 2.0 Simplified, accessed May 11,
    2025, [https://www.oauth.com/oauth2-servers/pkce/authorization-request/](https://www.oauth.com/oauth2-servers/pkce/authorization-request/)
16. Build an Android App Using OAuth 2.0 and PKCE \- SecureAuth Product Docs, accessed May 11,
    2025, [https://docs.secureauth.com/ciam/en/build-an-android-app-using-oauth-2-0-and-pkce.html](https://docs.secureauth.com/ciam/en/build-an-android-app-using-oauth-2-0-and-pkce.html)
17. Using the token model | Authorization \- Google for Developers, accessed May 11,
    2025, [https://developers.google.com/identity/oauth2/web/guides/use-token-model](https://developers.google.com/identity/oauth2/web/guides/use-token-model)