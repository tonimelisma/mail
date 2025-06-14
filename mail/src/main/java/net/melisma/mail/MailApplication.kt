package net.melisma.mail

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Custom Application class required by Hilt and for app-wide initializations like Timber.
 * The @HiltAndroidApp annotation triggers Hilt's code generation, including a base class
 * for the application that supports dependency injection. It also allows Hilt to inject
 * dependencies into other Android framework classes annotated with @AndroidEntryPoint.
 */
@HiltAndroidApp
class MailApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncLifecycleObserver: net.melisma.data.sync.SyncLifecycleObserver

    override fun onCreate() {
        Log.d("MailApplication", "MailApplication: onCreate CALLED - Direct Log")
        super.onCreate()
        Timber.d("MailApplication: onCreate called - Timber Log")
        Timber.d("MailApplication: Injected workerFactory instance: %s", workerFactory)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber logging planted.")
        } else {
            // TODO: Plant a crash reporting tree for release builds if needed
            Timber.d("Timber logging NOT planted (release build or custom logic).")
        }
        Timber.i("MailApplication fully initialized.")

        // Register SyncController lifecycle observer to toggle foreground/background polling
        ProcessLifecycleOwner.get().lifecycle.addObserver(syncLifecycleObserver)
    }
    // Hilt initialization is handled automatically by the annotation.

    override val workManagerConfiguration: Configuration
        get() {
            Timber.d("MailApplication: workManagerConfiguration getter invoked.")
            Timber.d(
                "MailApplication: Using workerFactory for WorkManager config: %s",
                workerFactory
            )
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(Log.DEBUG) // Keep WM logs verbose for now
                .build()
        }
}