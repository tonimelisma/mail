package net.melisma.mail.di

import net.melisma.mail.R // Import R from the app module
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RepositoryModuleTest {

    private lateinit var repositoryModule: RepositoryModule

    @Before
    fun setUp() {
        repositoryModule = RepositoryModule()
    }

    // Test the simple AuthConfigProvider implementation
    @Test
    fun `provideAuthConfigProvider returns correct resource ID`() {
        val authConfigProvider = RepositoryModule.provideAuthConfigProvider()
        // Assert that the provider returns the R value defined in the app module
        assertEquals(R.raw.auth_config, authConfigProvider.getMsalConfigResId())
    }

    // Note: Testing provideApplicationCoroutineScope requires testing CoroutineScope/Dispatcher setup,
    // which is generally more involved and often implicitly tested by components using the scope.
    // Testing Hilt modules for complex providers often relies on integration or instrumented tests.
}