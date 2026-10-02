package net.eulerai.filmarks.tv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/** What the app needs to reach Filmarks, kept on the device. */
data class Settings(val server: String, val password: String, val lang: String) {
    val en get() = lang == "en"
    val ready get() = server.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_SERVER = "https://filmarks.eulerai.net"
    }
}

class SettingsStore(private val context: Context) {
    private val server = stringPreferencesKey("server")
    private val password = stringPreferencesKey("password")
    private val lang = stringPreferencesKey("lang")

    val settings: Flow<Settings> = context.store.data.map {
        Settings(it[server] ?: Settings.DEFAULT_SERVER, it[password] ?: "", it[lang] ?: "ja")
    }

    suspend fun save(s: Settings) {
        context.store.edit {
            it[server] = s.server.trim().trimEnd('/')
            it[password] = s.password.trim()
            it[lang] = s.lang
        }
    }
}
