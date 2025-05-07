// File: app/src/test/java/net/melisma/mail/di/RepositoryModuleTest.kt
package net.melisma.mail.di

// import kotlinx.coroutines.test.StandardTestDispatcher // Not used
// import kotlinx.coroutines.test.TestScope // Not strictly needed for these tests anymore
// import kotlinx.coroutines.test.resetMain // Not needed if not setting main
// import kotlinx.coroutines.test.setMain // Not needed if not setting main
// import net.melisma.core_data.di.ApplicationScope // This was for the @ApplicationScope annotation which is now @Singleton
// import net.melisma.core_data.di.Dispatcher // Dispatcher qualifier is used on parameters, not directly in test logic here
// import net.melisma.core_data.di.MailDispatchers // Enum, not directly used in test logic here
// import net.melisma.mail.R // Cannot access R class in a plain JVM unit test without Robolectric

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// REMOVED Robolectric imports

// ADDED OptIn as suggested by the compiler error.
// SupervisorJob itself is stable, but it might be interacting with or using something
// internally that the current Kotlin/coroutines version flags under ExperimentalStdlibApi
// in certain contexts, or the compiler's check is overly broad here.
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalStdlibApi::class)
class RepositoryModuleTest {

    @Before
    fun setUp() {
        // No setup needed for these tests
    }

    @After
    fun tearDown() {
        // No teardown needed
    }

    @Test
    fun `provideApplicationCoroutineScope returns CoroutineScope with SupervisorJob and specific Dispatcher`() {
        // Arrange
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        // Act
        val scope = RepositoryModule.provideApplicationCoroutineScope(ioDispatcher)

        // Assert
        assertNotNull("CoroutineScope should not be null", scope)
        // Check if the Job in the context is a SupervisorJob
        val job = scope.coroutineContext[Job]
        assertNotNull("Job should not be null in CoroutineScope's context", job)
        assertTrue(
            "Scope's job should be a SupervisorJob. Actual: ${job?.let { it::class.simpleName }}",
            job is SupervisorJob // This is the usage of SupervisorJob
        )
        assertEquals(
            "Scope's dispatcher should be the one provided",
            ioDispatcher,
            scope.coroutineContext[CoroutineDispatcher.Key]
        )
    }

    @Test
    fun `provideIoDispatcher returns Dispatchers IO`() {
        // Act
        val dispatcher = RepositoryModule.provideIoDispatcher()

        // Assert
        assertEquals("Should provide Dispatchers.IO", Dispatchers.IO, dispatcher)
    }

    @Test
    fun `provideAuthConfigProvider returns AuthConfigProvider instance`() {
        // Act
        val authConfigProvider = RepositoryModule.provideAuthConfigProvider()

        // Assert
        assertNotNull("AuthConfigProvider should not be null", authConfigProvider)
        val resId = authConfigProvider.getMsalConfigResId()
        // Check that resId is an Int. We cannot check its specific value
        // against R.raw.auth_config without Robolectric or mocking R.
        assertTrue(
            "getMsalConfigResId should return an Int. Actual type: ${resId::class.simpleName}",
            resId is Int
        )
    }
}
