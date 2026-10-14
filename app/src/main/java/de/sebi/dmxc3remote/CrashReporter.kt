package de.sebi.dmxc3remote

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Schreibt unbehandelte Exceptions nach files/crash.txt und reicht sie danach an den
 * Standard-Handler weiter (App stürzt weiterhin ab). Beim nächsten Start wird der
 * Stacktrace angezeigt und kann kopiert werden.
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "DMXC3 Remote ${de.sebi.dmxc3remote.net.UmbraSession.APP_VERSION}\n" +
                        "Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val t = runCatching { f.readText() }.getOrNull()
        f.delete()
        return t
    }
}
