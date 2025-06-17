package net.melisma.mail

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import net.melisma.mail.logging.ReleaseTree

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
        super.onCreate()

        // Initialise Timber so that all existing Timber.d/i/... calls actually emit logs.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.i("Timber DebugTree planted (debug build)")
        } else {
            Timber.plant(ReleaseTree())
            Timber.i("Timber ReleaseTree planted (release build)")
        }

        Timber.d("MailApplication: onCreate completed – logging initialised")

        // Register SyncController lifecycle observer to toggle foreground/background polling
        ProcessLifecycleOwner.get().lifecycle.addObserver(syncLifecycleObserver)
    }
    // Hilt initialization is handled automatically by the annotation.
}