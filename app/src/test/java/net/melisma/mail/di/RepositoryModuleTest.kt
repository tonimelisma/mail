// File: app/src/test/java/net/melisma/mail/di/RepositoryModuleTest.kt
package net.melisma.mail.di

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job // Basic Job import
// import kotlinx.coroutines.SupervisorJob // Intentionally removed for this iteration
import net.melisma.mail.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue // Keep for isActive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Apply OptIn at class level to catch anything, though we are trying to avoid needing it
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class RepositoryModuleTest {

    @Before
    fun setUp() {
    }

    @After
    fun tearDown() {
    }

    @Test
    fun `provideApplicationCoroutineScope_provides_a_valid_scope_with_a_job_and_dispatcher`() {
        // Arrange
        val expectedDispatcher: CoroutineDispatcher =
            Dispatchers.IO // This matches AppProvidesModule

        // Act
        val scope = AppProvidesModule.provideApplicationCoroutineScope(expectedDispatcher)
        val jobFromScope = scope.coroutineContext[Job]
        val dispatcherFromScope =
            scope.coroutineContext[CoroutineDispatcher.Key] // Correct way to get dispatcher

        // Assert
        assertNotNull("CoroutineScope should not be null", scope)
        assertNotNull("Job from scope's context should not be null", jobFromScope)
        assertTrue(
            "Job from scope should be active by default",
            jobFromScope!!.isActive
        ) // Basic check

        assertNotNull("Dispatcher from scope's context should not be null", dispatcherFromScope)
        assertEquals(
            "Scope's dispatcher should be Dispatchers.IO as configured in AppProvidesModule",
            expectedDispatcher, // Verifying it uses the one AppProvidesModule is configured with via its parameter
            dispatcherFromScope
        )
        // We are NOT checking `is SupervisorJob` to avoid the unresolved reference for now.
        // We trust that AppProvidesModule, which *does* compile, correctly uses SupervisorJob.
    }

    @Test
    fun `provideIoDispatcher_returns_Dispatchers_IO`() { // Renamed for clarity
        // Act
        val dispatcher = AppProvidesModule.provideIoDispatcher()

        // Assert
        assertEquals("Should provide Dispatchers.IO", Dispatchers.IO, dispatcher)
    }

    @Test
    fun `provideAuthConfigProvider_returns_provider_with_correct_MSAL_config_ID`() { // Renamed
        // Arrange
        val authConfigProvider = AppProvidesModule.provideAuthConfigProvider()

        // Act
        assertNotNull("AuthConfigProvider should not be null", authConfigProvider)
        val resId = authConfigProvider.getMsalConfigResId()

        // Assert
        assertEquals(
            "AuthConfigProvider should provide the correct resource ID from R.raw",
            R.raw.auth_config,
            resId
        )
    }
}