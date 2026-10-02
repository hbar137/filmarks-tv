package net.eulerai.filmarks.tv

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An online subtitle the player can switch to (WebVTT from the server). */
data class Sub(val url: String, val lang: String, val label: String)

/**
 * What the player needs: the stream, where to resume, where to save
 * progress, online subtitles, Trakt scrobbling, an anime's intro, and the
 * next episode (played when this one ends).
 */
data class PlayRequest(
    val url: String,
    val title: String,
    val startSec: Long,
    val progressPath: String,
    val progressBody: JsonObject,
    val subs: List<Sub> = emptyList(),
    val scrobblePath: String = "",
    val scrobbleBody: JsonObject? = null,
    val introStart: Double = -1.0,
    val introEnd: Double = -1.0,
    val nextLabel: String = "",
    val next: (() -> Unit)? = null,
    val audioLang: String = "", // the title's original language: its audio track is chosen first
    val path: String = "",      // the title's page (Watch Next opens it)
    val poster: String = "",
)

/** Turns a choice on a title page into a PlayRequest, through the server's device API. */
class Plays(private val s: Settings, private val api: Api) {

    /**
     * Online subtitles (OpenSubtitles / SubDL through the server) in Japanese and
     * English, at most 4 each; given up on after 8 s so playback never waits long.
     */
    private suspend fun subs(listPath: String): List<Sub> = withTimeoutOrNull(8000) {
        runCatching {
            api.get(listPath, "languages" to "ja,en").arr("items").groupBy { it.str("language").lowercase() }
                .flatMap { (_, l) -> l.take(4) }
                .mapNotNull { r ->
                    val id = r.str("file_id").ifEmpty { return@mapNotNull null }
                    val source = r.str("source").ifEmpty { "opensubtitles" }
                    val lang = r.str("language").lowercase()
                    val name = r.str("file_name").ifEmpty { r.str("release") }.ifEmpty { lang }
                    val url = s.server + "/api/kodi/subtitle-file/" + source + "/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20") +
                        "/sub.vtt?p=" + URLEncoder.encode(s.password, "UTF-8")
                    Sub(url, lang, "$lang · $name")
                }
        }.getOrDefault(emptyList())
    } ?: emptyList()

    private fun movieScrobble(imdb: String) =
        if (imdb == "") null else buildJsonObject { put("imdb_id", imdb) }

    /** A seedbox file: a movie (n = 0), or episode n of a series download. */
    suspend fun seedbox(hash: String, n: Int, fallbackTitle: String): PlayRequest = coroutineScope {
        val p = if (n > 0) api.get("/play-seedbox/$hash", "n" to n) else api.get("/play-seedbox/$hash")
        if (n > 0) {
            val epid = p.long("episode_id")
            val subs = async { if (epid > 0) subs("/episode-subtitles/$epid") else emptyList() }
            PlayRequest(p.str("stream_url"), "${p.str("show_title").ifEmpty { fallbackTitle }} · " + tr(s.en, "第${n}話", "E$n"),
                p.long("position_seconds"), "/progress-episode",
                buildJsonObject { put("episode_id", epid); put("show_id", p.long("show_id")); put("link_id", 0); put("seedbox", hash); put("number", n) },
                subs.await(), "/scrobble-episode", episodeScrobble(p))
        } else {
            val subs = async { if (p.long("movie_id") > 0) subs("/subtitles/${p.long("movie_id")}") else emptyList() }
            PlayRequest(p.str("stream_url"), p.str("title").ifEmpty { fallbackTitle }, p.long("position_seconds"), "/progress",
                buildJsonObject { put("movie_id", p.long("movie_id")); put("link_id", 0); put("seedbox", hash) },
                subs.await(), "/scrobble", movieScrobble(p.str("imdb_id")))
        }
    }

    /** A movie's Real-Debrid links (one per quality), searching first when there are none. */
    suspend fun movieLinks(tmdb: Long, status: (String) -> Unit): List<JsonObject> {
        status(tr(s.en, "Real-Debrid を確認中…", "Checking Real-Debrid…"))
        var links = api.get("/movies/$tmdb").arr("debrid_links")
        if (links.isEmpty()) {
            status(tr(s.en, "Real-Debrid で検索中…（最大2分）", "Searching Real-Debrid… (up to 2 min)"))
            api.post("/realdebrid/resolve/$tmdb", buildJsonObject { })
            for (i in 0 until 60) {
                delay(2000)
                val st = api.get("/realdebrid/resolve-status/$tmdb")
                links = st.arr("links")
                if (st.str("status") != "resolving") break
            }
        }
        status("")
        return links
    }

    /** A movie's Real-Debrid link. */
    suspend fun rdMovie(tmdb: Long, linkId: Long, fallbackTitle: String): PlayRequest = coroutineScope {
        val subs = async { subs("/subtitles/$tmdb") }
        val p = api.get("/play/$linkId")
        PlayRequest(p.str("stream_url"), p.str("title").ifEmpty { fallbackTitle }, p.long("position_seconds"), "/progress",
            buildJsonObject { put("movie_id", tmdb); put("link_id", linkId) },
            subs.await(), "/scrobble", movieScrobble(p.str("imdb_id")))
    }

    private fun episodeScrobble(p: JsonObject) =
        if (p.str("show_imdb_id") == "" || p.long("season_number") == 0L) null
        else buildJsonObject {
            put("show_imdb_id", p.str("show_imdb_id")); put("season_number", p.long("season_number")); put("episode_number", p.long("episode_number"))
        }

    /**
     * A TMDB episode: from its downloaded file when there is one (sb = hash,
     * number), else Real-Debrid (searching first when needed). Anime get
     * their intro from aniskip.
     */
    suspend fun episode(show: JsonObject, ep: JsonObject, sb: Pair<String, Int>?, status: (String) -> Unit): PlayRequest = coroutineScope {
        val epid = ep.long("id")
        val n = ep.long("episode_number").toInt()
        val intro = async {
            if (!show.bool("is_anime")) null
            else withTimeoutOrNull(5000) { runCatching { api.get("/aniskip/${show.long("id")}/$n")["intro"].obj() }.getOrNull() }
        }
        val r = if (sb != null) {
            seedbox(sb.first, sb.second, show.str("title"))
        } else {
            val st = api.get("/realdebrid/resolve-episode-status/$epid")
            if (st.arr("links").isEmpty()) {
                status(tr(s.en, "Real-Debrid で検索中…（最大2分）", "Searching Real-Debrid… (up to 2 min)"))
                api.post("/realdebrid/resolve-episode/$epid", buildJsonObject { })
                var found = false
                for (i in 0 until 60) {
                    delay(2000)
                    val x = api.get("/realdebrid/resolve-episode-status/$epid")
                    found = x.arr("links").isNotEmpty()
                    if (x.str("status") != "resolving") break
                }
                if (!found) throw Exception(tr(s.en, "Real-Debrid に見つかりません", "Not found on Real-Debrid"))
            }
            val subs = async { subs("/episode-subtitles/$epid") }
            val p = api.get("/play-episode/$epid")
            PlayRequest(p.str("stream_url"),
                "${p.str("show_title")} · S${p.long("season_number")} E${p.long("episode_number")} ${p.str("episode_title")}",
                p.long("position_seconds"), "/progress-episode",
                buildJsonObject { put("episode_id", epid); put("show_id", p.long("show_id")); put("link_id", epid) },
                subs.await(), "/scrobble-episode", episodeScrobble(p))
        }
        val i = intro.await()
        if (i != null && i.dbl("end") > 0) r.copy(introStart = i.dbl("start"), introEnd = i.dbl("end")) else r
    }
}
