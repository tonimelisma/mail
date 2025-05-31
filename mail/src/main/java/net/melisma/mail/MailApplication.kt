package net.melisma.mail

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import net.melisma.mail.BuildConfig
import timber.log.Timber

/**
 * Custom Application class required by Hilt and for app-wide initializations like Timber.
 * The @HiltAndroidApp annotation triggers Hilt's code generation, including a base class
 * for the application that supports dependency injection. It also allows Hilt to inject
 * dependencies into other Android framework classes annotated with @AndroidEntryPoint.
 */
@HiltAndroidApp
class MailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Optionally plant a release tree for crash reporting, etc.
            // Timber.plant(CrashReportingTree()) // Example: You would define CrashReportingTree
        }
    }
    // Hilt initialization is handled automatically by the annotation.
}