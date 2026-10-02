package net.eulerai.filmarks.tv

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Crash and playback-error reports to the server's log (POST /app/log): the
 * TV can't be watched from there. A crash is written to a file and sent on
 * the next start; other errors are sent at once.
 */
object Report {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file(app).writeText(e.stackTraceToString().take(8000)) }
            previous?.uncaughtException(t, e)
        }
        // last run's crash, if any
        val f = file(app)
        if (f.exists()) {
            val text = runCatching { f.readText() }.getOrDefault("")
            f.delete()
            if (text != "") send(app, "crash", text)
        }
    }

    fun send(ctx: Context, kind: String, message: String) {
        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val s = SettingsStore(app).settings.first()
                if (!s.ready) return@launch
                Api(s).post("/app/log", buildJsonObject {
                    put("kind", kind); put("message", message)
                    put("version", BuildConfig.VERSION_NAME); put("device", "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
                })
            }
        }
    }
}
