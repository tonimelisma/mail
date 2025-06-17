package net.melisma.mail.logging

import android.util.Log
import timber.log.Timber

/**
 * Very lightweight log tree for production builds – only WARN and ERROR are forwarded to logcat
 * to avoid leaking potentially sensitive info while still surfacing important crashes.
 */
class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.WARN || priority == Log.ERROR) {
            Log.println(priority, tag, message)
            t?.let { Log.e(tag, "", it) }
        }
    }
} 