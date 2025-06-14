package net.melisma.mail

import android.app.Application
import android.util.Log
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
class MailApplication : Application() {

    @Inject
    lateinit var syncLifecycleObserver: net.melisma.data.sync.SyncLifecycleObserver

    override fun onCreate() {
        Log.d("MailApplication", "MailApplication: onCreate CALLED - Direct Log")
        super.onCreate()
        Timber.d("MailApplication: onCreate called - Timber Log")
        Timber.i("MailApplication fully initialized.")

        // Register SyncController lifecycle observer to toggle foreground/background polling
        ProcessLifecycleOwner.get().lifecycle.addObserver(syncLifecycleObserver)
    }
    // Hilt initialization is handled automatically by the annotation.
}