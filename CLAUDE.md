# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Project Overview

Melisma Mail is an Android email client aiming to provide a clean, native Material 3 user experience
similar to stock Pixel apps. The app currently supports Microsoft Outlook accounts with plans to add
Google (Gmail) support.

**Current Status:**

- Authentication with Microsoft accounts (MSAL)
- Folder listing after signing in
- In progress: Message view and core functionality

## Architecture

- **Modular Architecture:**
    - `:app` - Main application module with UI/ViewModels
    - `:core-data` - Core data interfaces and models
    - `:core-common` - Shared utilities like error mapping
    - `:backend-microsoft` - Microsoft/Outlook specific implementation
    - (Planned) `:backend-google` - Google/Gmail specific implementation

- **Key Patterns:**
    - MVVM with Jetpack Compose UI
    - Repository Pattern with interfaces in `:core-data`
    - Dependency Injection with Hilt
    - Coroutines and Flow for asynchronous operations
    - Ktor with OkHttp for networking

## Build and Run Commands

### Basic Commands

```bash
# Build the app
./gradlew build

# Install and run on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run specific test class
./gradlew :module-name:testDebugUnitTest --tests "net.melisma.package.TestClass"

# Run a specific test method
./gradlew :module-name:testDebugUnitTest --tests "net.melisma.package.TestClass.testMethod"
```

### Development Flow

```bash
# Clean build when changing dependencies
./gradlew clean build

# Run Kotlin linting
./gradlew ktlintCheck

# Run Android linting
./gradlew lint

# Generate test coverage report (if configured)
./gradlew testDebugUnitTestCoverage
```

## Testing Approach

The project uses:

- **JUnit 4** for test runner
- **MockK** for mocking
- **kotlinx-coroutines-test** for testing coroutines
- **Turbine** for testing Flow
- **Unit tests** focus on individual components in isolation
- **Integration tests** verify interactions between components
- Tests follow the AAA pattern (Arrange-Act-Assert)
- Tests reside in `src/test` directory of respective modules

## Important Conventions

- **Error Handling:** Use the `ErrorMapperService` from `:core-common` to map exceptions to
  user-friendly messages.
- **Network Calls:** Use Ktor for all API calls, avoid direct HttpURLConnection.
- **Authentication:**
    - Microsoft: Uses `MicrosoftAuthManager` and `MicrosoftTokenProvider` in `:backend-microsoft`
    - (Future) Google: Will implement similar structure in `:backend-google`
- **UI:** Follow Material 3 design guidelines to match Pixel aesthetics

## Current Development Focus

The project is following a prioritized backlog:

1. Core Mail Viewing (message list, message view)
2. Basic Mail Actions (mark read/unread, delete, archive)
3. Composing & Sending emails
4. Implementing offline caching & background sync
5. Google account integration

When contributing or making changes, refer to the sequence in DESIGN_DOC.md for context on the
current priorities and architecture decisions.