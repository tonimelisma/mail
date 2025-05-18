# KSP Debugging Chronicle: Resolving BackendException in IR Fake Override Builder

This document details the investigation and resolution of a persistent KSP (Kotlin Symbol
Processing) error encountered during the build process. The error manifested as a
`org.jetbrains.kotlin.backend.common.BackendException: Backend Internal error: Exception during IR fake override builder`
specifically related to `IrErrorTypeImpl` and `IrSimpleType` `ClassCastException` within the
`FirIrFakeOverrideBuilder`.

## 1. Initial Problem Encounter and Symptoms

The build would fail during KSP processing tasks (e.g., `kspDebugKotlin` for the `:data` module)
with the following characteristic error:

```
e: org.jetbrains.kotlin.backend.common.BackendException: Backend Internal error: Exception during IR fake override builder
Cause: java.lang.ClassCastException: class org.jetbrains.kotlin.ir.types.impl.IrErrorTypeImpl cannot be cast to class org.jetbrains.kotlin.ir.types.IrSimpleType (org.jetbrains.kotlin.ir.types.impl.IrErrorTypeImpl and org.jetbrains.kotlin.ir.types.IrSimpleType are in unnamed module of loader org.jetbrains.kotlin.konan.file.FileK značiLoader$ParentFirstClassLoader @xxxxxxx)
File being compiled: /Users/tonimelisma/Development/Mail/data/src/main/java/net/melisma/data/repository/DefaultAccountRepository.kt
The root cause was: java.lang.ClassCastException: class org.jetbrains.kotlin.ir.types.impl.IrErrorTypeImpl cannot be cast to class org.jetbrains.kotlin.ir.types.IrSimpleType
...
	at org.jetbrains.kotlin.ir.overrides.FirIrFakeOverrideBuilder.buildFakeOverride(NewFakeOverrideBuilder.kt:167)
	at org.jetbrains.kotlin.ir.overrides.FirIrFakeOverrideBuilder.buildFakeOverridesForClass(NewFakeOverrideBuilder.kt:113)
...
```

This error pointed to an issue within the Kotlin compiler's IR (Intermediate Representation)
generation phase, specifically when KSP (used by Hilt for dependency injection) was processing the
`DefaultAccountRepository.kt` file.

## 2. Initial Debugging Efforts

Standard Gradle debugging flags were employed:

* `./gradlew build --debug --full-stacktrace`
* Targeted module builds: `./gradlew :data:assembleDebug --debug --full-stacktrace`

These provided extensive logs but the core error remained the `BackendException`. Error logs were
also found in the `.kotlin/errors/` directory, confirming the same stack trace. The Kotlin compiler
version noted in these logs was consistently `2.1.20-dev-xxxx` or similar, which was an early clue.

## 3. Version Mismatch Hypothesis and Investigation

The primary hypothesis shifted towards a version conflict involving Kotlin, KSP, Hilt, AGP (Android
Gradle Plugin), or their transitive dependencies.

**Project Versions (from `gradle/libs.versions.toml` initially):**

* `kotlin = "2.0.0"`
* `ksp = "2.0.0-1.0.22"` (intended for Kotlin 2.0.0)
* `hilt = "2.56.2"`
* `agp` was initially higher (e.g., `8.9.2`), later downgraded to `8.4.0`.

**Key Findings:**

* **KSP2:** Enabling KSP2 via `ksp.useKSP2=true` in `gradle.properties` did not resolve the issue.
  The same error persisted.
* **AGP Downgrade:** Downgrading AGP from `8.9.2` to `8.4.0` (and adjusting Compose BOM accordingly)
  also had no effect on the error. The Kotlin compiler version in the error logs remained
  `2.1.20-dev-xxxx`.
* **`kotlinVersion` Task:** Running `./gradlew :data:kotlinVersion` confirmed that the project was
  correctly configured to use Kotlin `2.0.0`. This deepened the mystery of the `2.1.20-dev` version
  appearing in KSP error logs.
* **KSP Compatibility:** Research confirmed that KSP versions are tightly coupled with Kotlin
  compiler versions. The KSP release `2.0.0-1.0.22` is indeed intended for Kotlin `2.0.0`.
* **Dependency Analysis (`ksp` configuration):** The breakthrough came from inspecting the
  dependencies of the `ksp` configuration for the `:data` module:
  ```bash
  ./gradlew :data:dependencies --configuration ksp --debug | cat
  ```
  This output revealed that `com.google.devtools.ksp:symbol-processing` (a core part of KSP) or one
  of its transitive dependencies was pulling in a different version of `kotlin-compiler-embeddable`.
  Despite the project being on Kotlin `2.0.0`, KSP's internal mechanisms were attempting to use
  components from a `2.1.20-dev` (or similar snapshot/dev version) of the Kotlin compiler. This
  mismatch was the likely source of the IR `ClassCastException`.

  The relevant part of the dependency graph showed:
  ```
  +--- com.google.dagger:hilt-compiler:2.56.2
  |    +--- com.google.dagger:dagger-compiler:2.56.2
  |    |    +--- com.google.dagger:dagger-spi:2.56.2
  |    |    |    +--- com.google.dagger:dagger:2.56.2
  |    |    |    |    \--- javax.inject:javax.inject:1
  |    |    |    \--- jakarta.inject:jakarta.inject-api:2.0.1
  |    |    +--- androidx.room:room-compiler-processing:2.6.1
  |    |    |    \--- androidx.room:room-compiler-processing-testing:2.6.1
  |    |    +--- com.google.devtools.ksp:symbol-processing-api:2.0.0-1.0.22
  |    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.23 -> 2.0.0
  |    |    +--- com.google.devtools.ksp:symbol-processing:2.0.0-1.0.22
  |    |    |    +--- com.google.devtools.ksp:symbol-processing-api:2.0.0-1.0.22 (*)
  |    |    |    +--- org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.23  <-- Problematic if not aligned, even if KSP itself is for 2.0.0
  |    |    |    +--- org.jetbrains.kotlin:kotlin-compiler-runner:1.9.23
  |    |    |    \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.23 -> 2.0.0
  |    |    +--- ... (other dependencies)
  |    \--- com.google.dagger:dagger-producers:2.56.2
  |         ...
  \--- javax.inject:javax.inject:1
  ```
  (Note: The above is a simplified representation; the key was that some part of KSP or its
  dependencies was not respecting the project's Kotlin `2.0.0` consistently for compiler artifacts).

## 4. Resolution: Enforcing Kotlin Version Consistency

The fix involved forcing a consistent Kotlin version across all configurations and dependencies in
the project. This was achieved by adding a `resolutionStrategy` to the root `build.gradle.kts` file:

```kotlin
// File: build.gradle.kts (Project Level)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.hilt.gradle) apply false
}

subprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(libs.versions.kotlin.get()) // Ensure Kotlin version from libs.versions.toml
                because("Enforce consistent Kotlin version ${libs.versions.kotlin.get()} across all modules and configurations")
            }
        }
    }
}
```

**Verification:**
After applying this change and stopping the Gradle daemon (`./gradlew --stop`), a subsequent run of
`./gradlew :data:dependencies --configuration ksp --debug | cat` was intended to show that all
`org.jetbrains.kotlin` artifacts, including `kotlin-compiler-embeddable` used by KSP, resolved to
the project's defined Kotlin version (`2.0.0` in this case).

## 5. Outcome of the Resolution Strategy

After enforcing the Kotlin version consistency with the `resolutionStrategy` in the root
`build.gradle.kts`:

* Re-running `./gradlew :data:dependencies --configuration ksp --debug | cat` showed that
  `org.jetbrains.kotlin:kotlin-compiler-embeddable` and other Kotlin artifacts within the KSP
  resolution path were now correctly aligned with the project's Kotlin version (`2.0.0`).
* A subsequent build attempt (`./gradlew :data:assembleDebug --debug --full-stacktrace`) **succeeded
  **. The `BackendException` related to `IrFakeOverrideBuilder` was resolved.

## 6. Key Learnings for KSP Debugging

* **Version Sensitivity:** KSP is highly sensitive to Kotlin compiler versions. The KSP artifact
  version (e.g., `2.0.0-1.0.22`) must align with the Kotlin version used by the project (`2.0.0`).
* **Transitive Kotlin Compiler Dependencies:** The most challenging aspect was that KSP (or its
  dependencies) could internally attempt to use a slightly different version of Kotlin compiler
  components (like `kotlin-compiler-embeddable`) than what the project explicitly defines. This
  mismatch can lead to subtle IR errors.
* **Error Log Clues:** The Kotlin compiler version reported in KSP error logs (e.g., in
  `.kotlin/errors/`) can be a strong indicator of such a mismatch, even if Gradle tasks like
  `kotlinVersion` report the correct project version.
* **`ksp` Dependency Configuration:** Inspecting the `ksp` configuration's dependency tree (
  `:module:dependencies --configuration ksp`) is crucial for uncovering these version conflicts.
* **Gradle Resolution Strategy:** Using Gradle's `resolutionStrategy` to enforce a consistent
  version for critical libraries (especially `org.jetbrains.kotlin.*`) across all configurations is
  a powerful way to resolve such conflicts.
* **Clean State:** Stopping the Gradle daemon (`./gradlew --stop`) between significant build
  configuration changes is important to ensure changes are picked up correctly.

This detailed approach, moving from general debugging to specific version analysis and finally to
enforcing dependency resolution, was key to identifying and fixing this complex KSP issue. 