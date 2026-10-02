package net.eulerai.filmarks.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Filmarks' device API (/api/kodi on the server; the Kodi add-on uses the
 * same one). Authenticated by the X-MovieRec-Password header — the header
 * name is the API's, kept from movieRec.
 */
class Api(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun get(path: String, vararg query: Pair<String, Any?>): JsonObject = call(path, null, *query)

    /** POST a JSON body (progress saves). */
    suspend fun post(path: String, body: JsonObject): JsonObject = call(path, body)

    private suspend fun call(path: String, body: JsonObject?, vararg query: Pair<String, Any?>): JsonObject = withContext(Dispatchers.IO) {
        val url = (settings.server + "/api/kodi" + path).toHttpUrl().newBuilder().apply {
            for ((k, v) in query) if (v != null) addQueryParameter(k, v.toString())
        }.build()
        val req = Request.Builder().url(url)
            .header("X-MovieRec-Password", settings.password)
            .header("Accept", "application/json")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.toString() }.getOrNull()
                throw ApiException(resp.code, msg?.trim('"') ?: "HTTP ${resp.code}")
            }
            val el: JsonElement = json.parseToJsonElement(body)
            el as? JsonObject ?: JsonObject(mapOf("items" to el))
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
