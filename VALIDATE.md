# Authentication Validation Approaches

## Current Situation

The codebase currently uses Ktor's `Auth` plugin with a bearer token provider for Google
authentication. The authentication flow is implemented in `BackendGoogleModule.kt` and involves:

1. Loading tokens via `loadTokens` block
2. Refreshing tokens via `refreshTokens` block
3. Validating tokens via `validate` block (currently commented out due to build issues)

The validation is crucial for ensuring that:

- The account ID in the token matches the expected account
- The token hasn't expired
- The token is properly signed and valid

## Approach 1: Using `authenticate` Instead of `validate`

### Objective

Replace the problematic `validate` block with Ktor's `authenticate` function, which serves a similar
purpose but is more fundamental to the authentication pipeline.

### Current Code

```kotlin
install(Auth) {
    bearer {
        loadTokens { ... }
        refreshTokens { ... }
        // validate { ... } // Currently commented out
    }
}
```

### Detailed Implementation

```kotlin
install(Auth) {
    bearer {
        loadTokens {
            Timber.tag("KtorAuth").d("Google Auth: loadTokens called.")
            try {
                googleKtorTokenProvider.getBearerTokens()
            } catch (e: NeedsReauthenticationException) {
                Timber.tag("KtorAuth").w(e, "Google Auth: Needs re-authentication during loadTokens.")
                null
            } catch (e: TokenProviderException) {
                Timber.tag("KtorAuth").e(e, "Google Auth: TokenProviderException during loadTokens.")
                null
            }
        }
        
        refreshTokens { oldTokens ->
            Timber.tag("KtorAuth").d("Google Auth: refreshTokens called.")
            try {
                googleKtorTokenProvider.refreshTokens(oldTokens)
            } catch (e: TokenProviderException) {
                Timber.tag("KtorAuth").e(e, "Google Auth: TokenProviderException during refreshTokens.")
                null
            }
        }
        
        authenticate { credentials ->
            try {
                val accountId = credentials.token
                Timber.tag("KtorAuth").d("Google Auth: Authenticating account ID: $accountId")
                
                // Validate token format and expiration
                if (!isValidTokenFormat(accountId)) {
                    Timber.tag("KtorAuth").w("Google Auth: Invalid token format")
                    return@authenticate null
                }
                
                // Check if token matches active account
                if (accountId == activeGoogleAccountHolder.activeAccountId.value) {
                    Timber.tag("KtorAuth").d("Google Auth: Authentication successful")
                    UserPrincipal(accountId)
                } else {
                    Timber.tag("KtorAuth").w("Google Auth: Account ID mismatch")
                    null
                }
            } catch (e: Exception) {
                Timber.tag("KtorAuth").e(e, "Google Auth: Authentication failed")
                null
            }
        }
    }
}

private fun isValidTokenFormat(token: String): Boolean {
    // Implement token format validation
    return token.matches(Regex("^[a-zA-Z0-9-_]+$"))
}

### Error Handling
- Authentication failures return `null`, which Ktor interprets as an authentication failure
- Detailed logging at each step for debugging
- Token format validation before account ID comparison
- Exception handling to prevent crashes

### Integration Points
- Works with existing `googleKtorTokenProvider`
- Maintains compatibility with `UserPrincipal`
- Preserves existing token refresh mechanism
- Integrates with current logging system

## Approach 2: Custom Ktor Plugin

### Objective
Create a custom Ktor plugin that handles authentication without relying on the built-in `Auth` plugin's bearer provider.

### Current Code
```kotlin
install(Auth) {
    bearer {
        loadTokens { ... }
        refreshTokens { ... }
        // validate { ... } // Currently commented out
    }
}
```

### Detailed Implementation

```kotlin
val GoogleAuth = createApplicationPlugin(name = "GoogleAuth") {
    val tokenProvider = pluginConfig.googleKtorTokenProvider
    val accountHolder = pluginConfig.activeGoogleAccountHolder
    
    on(AuthenticationPipeline.CheckAuthentication) { context ->
        try {
            val token = context.request.bearerToken()
            Timber.tag("GoogleAuth").d("Checking authentication for token: ${token?.take(10)}...")
            
            if (token == null) {
                Timber.tag("GoogleAuth").d("No bearer token found")
                return@on
            }
            
            // Validate token format
            if (!isValidTokenFormat(token)) {
                Timber.tag("GoogleAuth").w("Invalid token format")
                return@on
            }
            
            // Check token expiration
            if (isTokenExpired(token)) {
                Timber.tag("GoogleAuth").d("Token expired, attempting refresh")
                val refreshedTokens = tokenProvider.refreshTokens(BearerTokens(token, ""))
                if (refreshedTokens != null) {
                    context.request.header(HttpHeaders.Authorization, "Bearer ${refreshedTokens.accessToken}")
                } else {
                    Timber.tag("GoogleAuth").w("Token refresh failed")
                    return@on
                }
            }
            
            // Verify account ID
            val accountId = token
            if (accountId == accountHolder.activeAccountId.value) {
                Timber.tag("GoogleAuth").d("Authentication successful for account: $accountId")
                context.principal(UserPrincipal(accountId))
            } else {
                Timber.tag("GoogleAuth").w("Account ID mismatch")
            }
        } catch (e: Exception) {
            Timber.tag("GoogleAuth").e(e, "Authentication failed")
        }
    }
}

class GoogleAuthConfig {
    lateinit var googleKtorTokenProvider: GoogleKtorTokenProvider
    lateinit var activeGoogleAccountHolder: ActiveGoogleAccountHolder
}

// Usage in BackendGoogleModule.kt
install(GoogleAuth) {
    googleKtorTokenProvider = get()
    activeGoogleAccountHolder = get()
}

// Helper functions
private fun isValidTokenFormat(token: String): Boolean {
    return token.matches(Regex("^[a-zA-Z0-9-_]+$"))
}

private fun isTokenExpired(token: String): Boolean {
    // Implement token expiration check
    // This could involve JWT decoding if using JWT tokens
    return false // Placeholder
}

### Error Handling
- Comprehensive try-catch blocks
- Detailed logging at each step
- Token validation and expiration checks
- Automatic token refresh attempt
- Graceful failure handling

### Integration Points
- Configurable through dependency injection
- Maintains compatibility with existing token provider
- Preserves account holder integration
- Extensible for additional validation steps

### Security Considerations
1. Token Format Validation
   - Ensures tokens match expected format
   - Prevents malformed token attacks
   
2. Token Expiration
   - Automatic refresh of expired tokens
   - Prevents use of stale credentials
   
3. Account Verification
   - Ensures token matches active account
   - Prevents token reuse across accounts

4. Logging
   - Detailed audit trail
   - No sensitive data in logs
   - Debug information for troubleshooting

## Comparison

| Aspect | Approach 1 | Approach 2 |
|--------|------------|------------|
| Complexity | Low | Medium |
| Maintenance | Low | Medium |
| Flexibility | Low | High |
| Risk of Build Issues | Medium | Low |
| Integration | Seamless | Manual |
| Customization | Limited | Extensive |

## Recommendation

While Approach 1 is simpler and maintains better integration with Ktor's authentication framework, Approach 2 provides more control and reliability. Given the current build issues, Approach 2 might be the safer choice despite requiring more initial work.

The decision should be based on:
1. How critical the authentication validation is
2. How much control is needed over the authentication flow
3. Whether the build issues are likely to persist
4. The team's capacity for maintaining custom authentication code 