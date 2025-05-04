# Melisma Mail - Test Strategy (Phase 1: Unit & Integration)

**Version:** 1.0
**Date:** 2025-05-04

## 1. Overview

This document outlines the initial testing strategy for the Melisma Mail Android application. The
focus of this phase is on establishing robust **Unit Tests** and **Integration Tests** that run on
the local JVM (`src/test`).

The application follows a modular architecture (`:app`, `:core-data`, `:feature-auth`,
`:backend-microsoft`) utilizing Hilt for dependency injection and Jetpack Compose with ViewModels (
MVVM pattern) for the UI layer. This strategy aims to leverage this architecture to create fast,
reliable, and maintainable tests.

UI/End-to-End tests (`src/androidTest`) are considered out of scope for this initial phase but are
planned for the future.

## 2. Goals

The primary goals of this testing phase are to:

* **Verify Correctness:** Ensure individual components (ViewModels, Repositories, Mappers, etc.)
  function correctly according to their specifications.
* **Prevent Regressions:** Catch unintended side effects or bugs introduced during code changes or
  refactoring.
* **Facilitate Refactoring:** Provide a safety net that allows developers to refactor code with
  confidence, knowing that existing functionality remains intact.
* **Validate Interactions:** Confirm that collaborating components interact correctly according to
  their defined contracts.
* **Improve Design:** Encourage writing loosely coupled, testable code.

## 3. Scope

* **In Scope:**
    * **Unit Tests:** Testing individual classes in isolation within each module (`:app`,
      `:core-data`, `:feature-auth`, `:backend-microsoft`).
    * **Integration Tests (JVM):** Testing the interaction between collaborating classes (e.g.,
      ViewModel <-> Repository, Repository <-> DataSource/Helper) running on the local JVM.
    * Tests located in the `src/test` source set of each module.
* **Out of Scope (For Now):**
    * UI Tests / End-to-End (E2E) Tests using Compose test APIs or Espresso.
    * Instrumented tests running on an Android device or emulator (`src/androidTest`).
    * Performance testing, security testing, usability testing.
    * Testing third-party libraries beyond ensuring our code integrates correctly with them (via
      mocks/fakes).

## 4. Test Levels & Techniques

### 4.1. Unit Tests

* **Purpose:** Verify the logic of a single class (the "unit") in complete isolation from its
  dependencies. These tests are fast and pinpoint specific failures.
* **Location:** `src/test/java/...` or `src/test/kotlin/...` within each module.
* **Key Techniques:**
    * **Mocking:** Use a mocking framework (MockK) to replace dependencies of the class under test
      with mock objects. Define behavior for mock methods/properties.
    * **Coroutine Testing:** Utilize `kotlinx-coroutines-test` (`runTest`, `TestDispatcher`,
      `TestScope`) to manage and test `suspend` functions and code within `CoroutineScope`s.
    * **Flow Testing:** Employ libraries like `Turbine` to effectively test emissions, completions,
      and errors from Kotlin Flows (`StateFlow`, `SharedFlow`).
    * **Assertions:** Use standard JUnit assertions (`assertEquals`, `assertTrue`, etc.) and MockK's
      `verify` blocks to check expected outcomes and interactions.
* **Focus Areas:** Business logic, state transformations, calculations, parsing, mapping,
  validation, boundary conditions, error handling within the unit.
* **Target Examples:** `MainViewModel`, `MicrosoftFolderRepository`, `MicrosoftAccountRepository`,
  `MicrosoftMessageRepository`, `GraphApiHelper`, `ErrorMapper`.

### 4.2. Integration Tests (JVM)

* **Purpose:** Verify the interaction and data flow between two or more collaborating components,
  ensuring they work together as expected according to their contracts. These tests run on the JVM
  and bridge the gap between unit tests and full E2E tests.
* **Location:** `src/test/java/...` or `src/test/kotlin/...` within the module where the primary
  component being tested resides (often the higher-level component like a ViewModel or Use Case).
* **Key Techniques:**
    * **Partial Mocking/Fakes:** Instantiate real objects for the components being integrated (e.g.,
      `MainViewModel`, `MicrosoftFolderRepository`). Mock the dependencies *below* the integration
      boundary (e.g., mock `GraphApiHelper` and `TokenProvider` when testing `MainViewModel`
      integrated with `MicrosoftFolderRepository`).
    * **Controlled Dependencies:** Provide controlled inputs/outputs via the mocked lower-level
      dependencies.
    * **Coroutine/Flow Testing:** Use `kotlinx-coroutines-test` and `Turbine` as needed to manage
      asynchronous interactions and observe results.
    * **Assertions:** Assert the final state or outcome resulting from the interaction. Verify that
      components called each other as expected using MockK's `verify`.
* **Focus Areas:** Data flow between layers (ViewModel <-> Repository, Repository <-> DataSource),
  correct handling of data transformations across components, error propagation between layers.
* **Target Examples:** `MainViewModel` <-> `FolderRepository` (testing if ViewModel correctly
  processes folder states emitted by the repository), `FolderRepository` <-> `TokenProvider` /
  `GraphApiHelper` (testing if repository correctly calls API helper after getting a token).

## 5. Tooling & Libraries

* **Test Runner:** JUnit 4
* **Mocking:** MockK (`io.mockk:mockk`)
* **Coroutine/Flow Testing:** `kotlinx-coroutines-test`, `app.cash.turbine:turbine`
* **Assertions:** JUnit assertions, MockK `verify`

## 6. Requirements & Best Practices

* **Test Location:** All unit and integration tests covered by this phase reside in the `src/test`
  directory of their respective modules.
* **Naming Convention:** Test classes should be named `[ClassNameUnderTest]Test.kt`. Test methods
  should clearly describe the scenario being tested (e.g., `\`state reflects authentication
  success\`` or `whenFoldersRequested_andApiSucceeds_thenSuccessStateIsEmitted`).
* **Test Structure:** Follow the Arrange-Act-Assert (AAA) pattern for clarity.
* **Isolation:**
    * Unit tests MUST mock all external dependencies (classes not part of the standard library or
      the unit itself).
    * Integration tests should clearly define the boundary of integration and mock dependencies
      outside that boundary.
* **Readability & Maintainability:** Write clear, concise tests. Avoid complex logic within tests.
  Prefer smaller, focused tests over large, monolithic ones. Keep test setup clean (`@BeforeEach` /
  `@Before`).
* **No Android Framework Dependencies (where possible):** Avoid direct dependencies on `android.*`
  classes in `src/test`. Mock `Context` or other Android classes if absolutely necessary for JVM
  tests, or consider moving the test to `src/androidTest`. The `MainViewModel` test shows mocking
  `Context` for the `ConnectivityManager`.
* **Speed & Reliability:** Tests must run quickly on the local JVM and produce consistent results.
  Avoid flaky tests.
* **Code Coverage:** While not a primary driver, aim for good coverage of critical business logic
  and potential error paths. Use coverage reports (`./gradlew testDebugUnitTestCoverage` if
  configured) as a guide, not a strict target. Focus on *quality* of tests over raw percentage.

## 7. Future Considerations

This document focuses on Phase 1. Future phases will expand the strategy to include:

* **UI/E2E Tests (`src/androidTest`):** Using Jetpack Compose testing APIs (`createComposeRule`) and
  Hilt's testing support (`@HiltAndroidTest`) to verify user flows, UI state, and interactions on
  emulators/devices.
* **Hilt Integration in Instrumented Tests:** Setting up test runners, Hilt test modules, and fake
  implementations for replacing dependencies in instrumented tests.
* **CI Integration:** Integrating test execution (unit and instrumented) into a Continuous
  Integration pipeline.

