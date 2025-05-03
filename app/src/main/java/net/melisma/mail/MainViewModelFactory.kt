package net.melisma.mail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import net.melisma.feature_auth.MicrosoftAuthManager

/**
 * Factory for creating MainViewModel instances with required dependencies.
 * This is used by the viewModels delegate in MainActivity when manual dependency
 * injection is required (i.e., when not using Hilt or another DI framework).
 */
class MainViewModelFactory(
    // The application context is needed by the ViewModel.
    private val applicationContext: Context,
    // The MicrosoftAuthManager instance is needed by the ViewModel.
    private val microsoftAuthManager: MicrosoftAuthManager
) : ViewModelProvider.Factory {

    /**
     * Creates an instance of the requested ViewModel class.
     *
     * @param modelClass The Class of the ViewModel to create.
     * @return A new instance of the ViewModel.
     * @throws IllegalArgumentException if the requested ViewModel class is unknown.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // Check if the requested ViewModel is MainViewModel.
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            // If it is, create and return a new instance with the provided dependencies.
            return MainViewModel(applicationContext, microsoftAuthManager) as T
        }
        // If the requested class is not MainViewModel, throw an exception.
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}