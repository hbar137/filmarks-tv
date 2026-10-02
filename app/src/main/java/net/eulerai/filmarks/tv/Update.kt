package net.eulerai.filmarks.tv

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Updates from the GitHub releases CI publishes (the same APK the
 * Downloader code installs): the latest release's run number against this
 * build's versionCode.
 */
object Update {
    private const val LATEST = "https://api.github.com/repos/hbar137/filmarks-tv/releases/latest"
    private const val APK = "https://github.com/hbar137/filmarks-tv/releases/latest/download/filmarks-tv.apk"
    private val http = OkHttpClient()

    /** The newer version's name ("0.1.12"), or "" when this is the latest. */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                val tag = (Api.json.parseToJsonElement(r.body!!.string()) as JsonObject).str("tag_name").removePrefix("v")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                if (code > BuildConfig.VERSION_CODE) tag else ""
            }
        }.getOrDefault("")
    }

    /** Downloads the latest APK and opens the installer (Android asks once to allow this app to install). */
    suspend fun install(context: Context) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "filmarks-tv.apk")
            http.newCall(Request.Builder().url(APK).build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("download: HTTP ${r.code}")
                f.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            f
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
