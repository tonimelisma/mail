## **AppAuth-Only Flow for Google Sign-In & Authorization on Android**

This guide details how to implement a complete Google Sign-In and API authorization flow using only
the AppAuth-Android library. This approach is suitable for client-only Android applications that
need to obtain OAuth 2.0 access tokens and refresh tokens to call Google APIs (like Gmail) on behalf
of the user. It allows users to choose from existing Google accounts signed into their browser or
add a new Google account directly within the flow.  
**Core Principles of this Flow:**

* **Single Library for OAuth:** AppAuth-Android handles all aspects of the OAuth 2.0 Authorization
  Code Grant flow with PKCE (Proof Key for Code Exchange).
* **Browser-Based Authentication:** Authentication and consent occur in a Custom Tab (or the system
  browser), not in a WebView. This is a security best practice and allows leveraging existing
  browser sessions.
* **User Choice:** Within the Custom Tab, Google's sign-in page allows users to select from accounts
  already signed into the browser or to sign in with/add a new Google account.
* **Refresh Tokens:** The flow is configured to request offline\_access, enabling your app to obtain
  refresh tokens for long-term API access.
* **Secure Token Management:** AppAuth's AuthState helps manage tokens, and this guide will
  emphasize secure persistence.

**Relationship with Android's AccountManager:**

* The AppAuth-Only flow does **not** directly interact with Android's AccountManager to fetch a list
  of device accounts or to add new Google accounts to the system settings.
* Instead, AppAuth launches a Custom Tab. If the user is already signed into Google accounts in
  their default browser (which powers the Custom Tab), those accounts will typically be available
  for selection on Google's sign-in page.
* If a user adds a new Google account or signs into an existing one through the Custom Tab, that
  session is primarily within the browser's context. It doesn't automatically add that Google
  account to the Android system's AccountManager (Settings \> Accounts). Adding accounts to the
  system AccountManager is a user-initiated action via Android's system settings.
* This flow is compatible with devices that have accounts in AccountManager; it simply uses a
  different mechanism (browser sessions) for the authentication interaction.

### **Step 1: Prerequisites and Configuration**

#### **1.1. Google Cloud Console Setup**

Before writing any code, configure your project in the Google Cloud Console:

1. **Create or Select a Project:** Go to
   the [Google Cloud Console](https://console.cloud.google.com/).
2. **Enable APIs:**
    * Navigate to "APIs & Services" \> "Library."
    * Search for and enable the "Gmail API" (or any other Google APIs your app needs).
    * The "Identity Toolkit API" and "Google People API" might also be useful depending on the user
      information you need.
3. **Configure OAuth Consent Screen:**
    * Navigate to "APIs & Services" \> "OAuth consent screen."
    * Choose "External" user type (unless your app is internal to an organization).
    * Fill in the required information: App name, User support email, Developer contact information.
    * **Scopes:** Add the scopes your application requires. For this flow, you'll need:
        * openid
        * email
        * profile
        * offline\_access (crucial for getting a refresh token)
        * Specific API scopes (e.g., https://www.googleapis.com/auth/gmail.readonly for Gmail).
    * Add test users while your app is in "Testing" mode. For production, you'll need to publish
      your app and potentially go through verification.
4. **Create OAuth 2.0 Client ID:**
    * Navigate to "APIs & Services" \> "Credentials."
    * Click "+ CREATE CREDENTIALS" \> "OAuth client ID."
    * Select "Android" as the Application type.
    * Enter a Name (e.g., "My Email App Android Client").
    * Enter your **Package name** (from your build.gradle file).
    * Enter the **SHA-1 signing certificate fingerprint** of your signing key. You can get this
      using:  
      keytool \-list \-v \-keystore mystore.keystore \-alias myalias

      (Replace mystore.keystore and myalias with your actual keystore path and alias. For debug
      builds, the path is often \~/.android/debug.keystore with alias androiddebugkey and password
      android).
    * Click "CREATE." Note down the **Client ID** (e.g.,
      YOUR\_ANDROID\_CLIENT\_ID.apps.googleusercontent.com). This is what AppAuth will use.

#### **1.2. Add AppAuth Dependency**

Add the AppAuth-Android library to your app's build.gradle file:  
// In app/build.gradle  
dependencies {  
implementation 'net.openid:appauth:0.11.1' // Or the latest version  
// ... other dependencies  
}

Sync your project with Gradle files.

#### **1.3. Define Redirect URI and Manifest Placeholder**

AppAuth requires a redirect URI to capture the authorization response. This URI must be unique to
your app.

1. **Choose a Redirect URI Scheme:** Use a custom scheme based on your application ID. For example,
   if your application ID is com.example.myemailapp, a good scheme would be
   com.example.myemailapp.auth. The full redirect URI might look like com.example.myemailapp.auth:
   /oauth2redirect.
2. Configure build.gradle for Redirect URI Scheme:  
   Add a manifest placeholder for the scheme in your app's build.gradle file:  
   // In app/build.gradle  
   android {  
   defaultConfig {  
   applicationId "com.example.myemailapp"  
   // ... other configs  
   manifestPlaceholders \= \[  
   'appAuthRedirectScheme': "${applicationId}.auth" // Or your chosen scheme  
   \]  
   }  
   // ...  
   }

   This makes the scheme available in your AndroidManifest.xml.

### **Step 2: Android Manifest Configuration**

Configure an activity to handle the redirect URI. This activity will receive the authorization
response from the Custom Tab.  
\<manifest xmlns:android="http://schemas.android.com/apk/res/android"  
package="com.example.myemailapp"\>

    \<application  
        ...\>  
        \<activity  
            android:name=".MainActivity"  
            android:exported="true"\>  
            \<intent-filter\>  
                \<action android:name="android.intent.action.MAIN" /\>  
                \<category android:name="android.intent.category.LAUNCHER" /\>  
            \</intent-filter\>  
        \</activity\>

        \<activity  
            android:name=".auth.AuthCallbackActivity"  
            android:exported="true"  
            android:launchMode="singleTask"\>  
            \<intent-filter\>  
                \<action android:name="android.intent.action.VIEW" /\>  
                \<category android:name="android.intent.category.DEFAULT" /\>  
                \<category android:name="android.intent.category.BROWSABLE" /\>  
                \<data android:scheme="${appAuthRedirectScheme}" /\>  
            \</intent-filter\>  
        \</activity\>

        \<meta-data  
            android:name="net.openid.appauth.use\_custom\_tabs"  
            android:value="true" /\>  
    \</application\>  

\</manifest\>

* Replace com.example.myemailapp with your actual package name.
* Replace .auth.AuthCallbackActivity with the actual name of your callback activity.
* android:launchMode="singleTask" is recommended for the callback activity.
* The data android:scheme="${appAuthRedirectScheme}" uses the placeholder defined in build.gradle.

### **Step 3: Core AppAuth Implementation (Kotlin)**

#### **3.1. Service Configuration and State Management**

It's good practice to manage your AppAuth configuration and state in a dedicated class or
singleton.  
// AuthStateManager.kt  
import android.content.Context  
import android.net.Uri  
import androidx.core.content.edit  
import androidx.security.crypto.EncryptedSharedPreferences  
import androidx.security.crypto.MasterKey  
import net.openid.appauth.AuthState  
import net.openid.appauth.AuthorizationServiceConfiguration  
import net.openid.appauth.ResponseTypeValues

object AuthStateManager {

    private const val AUTH\_PREFS\_NAME \= "app\_auth\_prefs"  
    private const val AUTH\_STATE\_KEY \= "authStateJson"

    // Google's OAuth 2.0 endpoints  
    val serviceConfig \= AuthorizationServiceConfiguration(  
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"), // Authorization endpoint  
        Uri.parse("https://oauth2.googleapis.com/token")          // Token endpoint  
    )

    fun readAuthState(context: Context): AuthState {  
        val masterKey \= MasterKey.Builder(context, MasterKey.DEFAULT\_MASTER\_KEY\_ALIAS)  
            .setKeyScheme(MasterKey.KeyScheme.AES256\_GCM)  
            .build()

        val sharedPreferences \= EncryptedSharedPreferences.create(  
            context,  
            AUTH\_PREFS\_NAME,  
            masterKey,  
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256\_SIV,  
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256\_GCM  
        )  
        val authStateJson \= sharedPreferences.getString(AUTH\_STATE\_KEY, null)  
        return if (authStateJson \!= null) {  
            try {  
                AuthState.jsonDeserialize(authStateJson)  
            } catch (e: org.json.JSONException) {  
                //Log.e("AuthState", "Failed to deserialize auth state", e)  
                AuthState(serviceConfig) // Return fresh state if deserialization fails  
            }  
        } else {  
            AuthState(serviceConfig) // No stored state, return fresh  
        }  
    }

    fun persistAuthState(context: Context, authState: AuthState) {  
        val masterKey \= MasterKey.Builder(context, MasterKey.DEFAULT\_MASTER\_KEY\_ALIAS)  
            .setKeyScheme(MasterKey.KeyScheme.AES256\_GCM)  
            .build()

        val sharedPreferences \= EncryptedSharedPreferences.create(  
            context,  
            AUTH\_PREFS\_NAME,  
            masterKey,  
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256\_SIV,  
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256\_GCM  
        )  
        sharedPreferences.edit {  
            putString(AUTH\_STATE\_KEY, authState.jsonSerializeString())  
        }  
        //Log.d("AuthState", "AuthSate persisted. Refresh Token: ${authState.refreshToken}")  
    }

    fun clearAuthState(context: Context) {  
         val masterKey \= MasterKey.Builder(context, MasterKey.DEFAULT\_MASTER\_KEY\_ALIAS)  
            .setKeyScheme(MasterKey.KeyScheme.AES256\_GCM)  
            .build()

        val sharedPreferences \= EncryptedSharedPreferences.create(  
            context,  
            AUTH\_PREFS\_NAME,  
            masterKey,  
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256\_SIV,  
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256\_GCM  
        )  
        sharedPreferences.edit {  
            remove(AUTH\_STATE\_KEY)  
        }  
        //Log.d("AuthState", "AuthState cleared.")  
    }  

}

* **Secure Storage:** This example uses EncryptedSharedPreferences to securely store the AuthState
  JSON, which contains sensitive tokens. You'll need the androidx.security:security-crypto
  dependency.

#### **3.2. Initiating the Authorization Flow**

In your Activity or Fragment where the user initiates sign-in:  
// In your SignInActivity.kt or similar  
import android.app.Activity  
import android.content.Intent  
import android.net.Uri  
import android.os.Bundle  
import android.util.Log  
import android.widget.Button  
import androidx.activity.result.contract.ActivityResultContracts  
import androidx.appcompat.app.AppCompatActivity  
import net.openid.appauth.AuthorizationRequest  
import net.openid.appauth.AuthorizationService  
import net.openid.appauth.ResponseTypeValues  
import com.example.myemailapp.R // Your R file

class SignInActivity : AppCompatActivity() {

    private lateinit var authService: AuthorizationService  
    private lateinit var authState: AuthState

    // Define your client ID and redirect URI  
    // It's good practice to store Client ID in strings.xml or buildConfig, not hardcoded  
    private val googleClientId: String by lazy { getString(R.string.google\_oauth\_client\_id\_android) }  
    private val redirectUri: Uri by lazy { Uri.parse(getString(R.string.google\_oauth\_redirect\_uri)) } // e.g., "com.example.myemailapp.auth:/oauth2redirect"

    private val authLauncher \= registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result \-\>  
        if (result.resultCode \== Activity.RESULT\_OK) {  
            val data: Intent? \= result.data  
            if (data \!= null) {  
                // The AuthCallbackActivity should handle the result and finish.  
                // This launcher is primarily for the initial dispatch.  
                // If AuthCallbackActivity sends a result back here, handle it.  
                Log.d("SignInActivity", "Auth flow completed, result handled by AuthCallbackActivity or returned here.")  
                // You might want to navigate to the main app screen if login was successful  
                // This often involves checking AuthState again or receiving a specific signal.  
            }  
        } else {  
            Log.w("SignInActivity", "Authorization flow cancelled or failed before redirect.")  
            // Handle cancellation (e.g., user pressed back in Custom Tab)  
        }  
    }

    override fun onCreate(savedInstanceState: Bundle?) {  
        super.onCreate(savedInstanceState)  
        setContentView(R.layout.activity\_sign\_in) // Your layout with a sign-in button

        authService \= AuthorizationService(this)  
        authState \= AuthStateManager.readAuthState(this) // Load existing state

        // Check if already authorized  
        if (authState.isAuthorized && authState.accessToken \!= null) {  
            Log.d("SignInActivity", "User already authorized. Access Token: ${authState.accessToken}")  
            // Navigate to main app screen  
            // navigateToMainApp()  
            // finish()  
            // return  
        }

        val signInButton: Button \= findViewById(R.id.signInButtonGoogle)  
        signInButton.setOnClickListener {  
            initiateAuthorization()  
        }  
    }

    private fun initiateAuthorization() {  
        val serviceConfig \= AuthStateManager.serviceConfig // Use the centrally defined config

        val authRequestBuilder \= AuthorizationRequest.Builder(  
            serviceConfig,  
            googleClientId, // Your Android Client ID  
            ResponseTypeValues.CODE, // We want an authorization code  
            redirectUri  
        ).setScopes(  
            "openid",  
            "email",  
            "profile",  
            "https.www.googleapis.com/auth/gmail.readonly", // Example Gmail scope  
            "offline\_access" // Crucial for getting a refresh token  
        )  
        // PKCE is handled automatically by AppAuth if not explicitly disabled

        val authRequest \= authRequestBuilder.build()

        val authIntent \= authService.getAuthorizationRequestIntent(authRequest)  
        try {  
            authLauncher.launch(authIntent)  
        } catch (e: Exception) {  
            Log.e("SignInActivity", "Error launching auth intent", e)  
            // Handle error (e.g., no browser available that supports Custom Tabs)  
        }  
    }

    override fun onDestroy() {  
        super.onDestroy()  
        authService.dispose()  
    }  

}

* **Client ID and Redirect URI:** Store these in strings.xml or buildConfig rather than hardcoding.
* **Scopes:** Include openid, email, profile for basic user info (ID Token), your required API
  scopes, and offline\_access to get a refresh token.
* **PKCE:** AppAuth handles PKCE (Proof Key for Code Exchange) automatically.

#### **3.3. Handling the Redirect in AuthCallbackActivity**

This activity receives the redirect from Google, extracts the authorization code (or error), and
exchanges it for tokens.  
// AuthCallbackActivity.kt  
package com.example.myemailapp.auth // Ensure this matches your manifest

import android.app.Activity  
import android.content.Intent  
import android.os.Bundle  
import android.util.Log  
import androidx.appcompat.app.AppCompatActivity  
import net.openid.appauth.AuthorizationException  
import net.openid.appauth.AuthorizationResponse  
import net.openid.appauth.AuthorizationService  
import com.example.myemailapp.AuthStateManager // Your AuthStateManager

class AuthCallbackActivity : AppCompatActivity() {

    private lateinit var authService: AuthorizationService

    override fun onCreate(savedInstanceState: Bundle?) {  
        super.onCreate(savedInstanceState)  
        authService \= AuthorizationService(this)  
        handleAuthorizationResponse(intent)  
    }

    // This can also be called if the activity is re-launched with a new intent  
    override fun onNewIntent(intent: Intent?) {  
        super.onNewIntent(intent)  
        intent?.let { handleAuthorizationResponse(it) }  
    }

    private fun handleAuthorizationResponse(intent: Intent) {  
        val response \= AuthorizationResponse.fromIntent(intent)  
        val exception \= AuthorizationException.fromIntent(intent)

        val currentAuthState \= AuthStateManager.readAuthState(this)

        if (response \!= null) {  
            // Authorization successful, exchange for tokens  
            Log.d("AuthCallback", "Authorization successful. Code: ${response.authorizationCode}")  
            authService.performTokenRequest(  
                response.createTokenExchangeRequest()  
                // AppAuth includes the PKCE code\_verifier automatically  
            ) { tokenResponse, tokenEx \-\>  
                if (tokenResponse \!= null) {  
                    currentAuthState.update(tokenResponse, tokenEx)  
                    AuthStateManager.persistAuthState(this, currentAuthState)  
                    Log.i("AuthCallback", "Token exchange successful. Refresh Token: ${currentAuthState.refreshToken}")

                    // Send a result back to the calling activity or navigate  
                    setResult(Activity.RESULT\_OK)  
                    // navigateToMainApp() or broadcast success  
                } else {  
                    Log.e("AuthCallback", "Token exchange failed", tokenEx)  
                    setResult(Activity.RESULT\_CANCELED) // Indicate failure  
                    // Show error to user  
                }  
                finish() // Close this activity  
            }  
        } else if (exception \!= null) {  
            Log.e("AuthCallback", "Authorization failed: ${exception.message}", exception)  
            setResult(Activity.RESULT\_CANCELED) // Indicate failure  
             // Show error to user  
            finish() // Close this activity  
        } else {  
            Log.w("AuthCallback", "User cancelled authorization flow (no response or exception).")  
            setResult(Activity.RESULT\_CANCELED) // Indicate cancellation  
            finish() // Close this activity  
        }  
    }

    override fun onDestroy() {  
        super.onDestroy()  
        authService.dispose()  
    }  

}

#### **3.4. Making API Calls with Automatic Token Refresh**

Use AuthState.performActionWithFreshTokens() to ensure you always have a valid access token.  
// Example of making an API call  
import net.openid.appauth.AuthorizationException  
import net.openid.appauth.AuthorizationService

fun callGmailApi(context: Context, onTokenReady: (accessToken: String?, error: String?) \-\>
Unit) {  
val authState \= AuthStateManager.readAuthState(context)  
val authService \= AuthorizationService(context) // Recreate or retrieve instance

    if (\!authState.isAuthorized || authState.accessToken \== null) {  
        Log.w("ApiCall", "User not authorized or no access token found.")  
        onTokenReady(null, "User not authorized. Please sign in.")  
        authService.dispose()  
        return  
    }

    authState.performActionWithFreshTokens(authService) { accessToken, idToken, ex \-\>  
        if (ex \!= null) {  
            Log.e("ApiCall", "Token refresh failed or other auth error: ${ex.message}", ex)  
            var errorMessage \= "Authentication error."  
            if (ex.code \== AuthorizationException.TokenRequestErrors.INVALID\_GRANT.code) {  
                // Refresh token might be invalid/revoked.  
                Log.e("ApiCall", "Refresh token invalid. Clearing AuthState.")  
                AuthStateManager.clearAuthState(context) // Clear stored state  
                errorMessage \= "Session expired. Please sign in again."  
            }  
            onTokenReady(null, errorMessage)  
            return@performActionWithFreshTokens  
        }

        if (accessToken \!= null) {  
            Log.i("ApiCall", "Successfully obtained a fresh access token: $accessToken")  
            // Use this accessToken to make your Gmail API request (e.g., via Retrofit, Volley)  
            onTokenReady(accessToken, null)  
        } else {  
            Log.e("ApiCall", "Access token is null without an exception.")  
            onTokenReady(null, "Failed to obtain access token.")  
        }  
        // It's important to dispose of the authService if it's created ad-hoc here  
        // If it's a long-lived instance managed elsewhere, dispose it accordingly.  
        // For this example, assuming it might be created per call or managed by a component:  
        // authService.dispose() // Be careful with lifecycle if it's a shared instance.  
    }  

}

// In your Activity/ViewModel where you need to call the API:  
// callGmailApi(this) { accessToken, error \-\>  
// if (accessToken \!= null) {  
// // Make your API request with the accessToken  
// Log.d("MyActivity", "Ready to call API with token: $accessToken")  
// } else {  
// // Handle error, prompt for re-login if necessary  
// Log.e("MyActivity", "Failed to get token: $error")  
// }  
// }

### **Step 4: User Experience for Account Selection/Addition**

* When initiateAuthorization() is called, AppAuth launches a Custom Tab.
* Google's sign-in page within this Custom Tab will:
    * Display any Google accounts the user is already signed into within that browser context,
      allowing them to select one.
    * Provide an option like "Use another account" or "Create account," allowing the user to sign in
      with a different existing Google account or create a new one.
* This flow naturally handles both choosing an existing (browser-session) account and adding/signing
  into a new one without needing separate logic in your app to manage an account picker before
  launching AppAuth.

### **Step 5: Logout / Clearing Authentication State**

To log a user out:

1. **Clear Local State:** Delete the persisted AuthState using AuthStateManager.clearAuthState(
   context).
2. **Optional \- Token Revocation:** For a more complete logout, you might want to revoke the
   tokens (especially the refresh token) with Google. This requires an additional API call to
   Google's token revocation
   endpoint (https://oauth2.googleapis.com/revoke?token=TOKEN\_TO\_REVOKE). AppAuth doesn't have a
   built-in method for revocation, so you'd implement this with an HTTP client.
3. **Optional \- Browser Session:** You cannot programmatically clear the Google session from the
   user's browser via AppAuth. The user would need to sign out of Google in their browser manually
   if they wish to do so.

// In your settings or profile screen  
fun handleLogout(context: Context) {  
val authState \= AuthStateManager.readAuthState(context)  
val refreshToken \= authState.refreshToken // Get the token before clearing

    // 1\. Clear local authentication state  
    AuthStateManager.clearAuthState(context)  
    Log.d("Logout", "Local AuthState cleared.")

    // 2\. Optional: Attempt to revoke the refresh token  
    if (refreshToken \!= null) {  
        // Make an HTTPS POST request to:  
        // https://oauth2.googleapis.com/revoke  
        // with parameter: token=THE\_REFRESH\_TOKEN  
        // This would typically be done with a library like Retrofit or OkHttp.  
        // Example (conceptual, needs actual HTTP client implementation):  
        // revokeGoogleToken(refreshToken) { success \-\>  
        //     if (success) Log.d("Logout", "Refresh token revoked successfully.")  
        //     else Log.w("Logout", "Failed to revoke refresh token.")  
        // }  
    }

    // 3\. Guide user to sign-in screen  
    // val intent \= Intent(context, SignInActivity::class.java)  
    // intent.flags \= Intent.FLAG\_ACTIVITY\_NEW\_TASK or Intent.FLAG\_ACTIVITY\_CLEAR\_TASK  
    // context.startActivity(intent)  

}

This comprehensive AppAuth-Only flow provides a secure, standard, and flexible way to integrate
Google Sign-In into your Android application, allowing users to easily choose existing accounts or
add new ones while ensuring your app can obtain and manage refresh tokens for long-term API access.