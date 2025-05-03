package net.melisma.mail

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Custom Application class required by Hilt.
 * The @HiltAndroidApp annotation triggers Hilt's code generation, including a base class
 * for the application that supports dependency injection. It also allows Hilt to inject
 * dependencies into other Android framework classes annotated with @AndroidEntryPoint.
 */
@HiltAndroidApp
class MailApplication : Application() {
    // Application-level setup can go here if needed in the future.
    // Hilt initialization is handled automatically by the annotation.
}