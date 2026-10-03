package net.eulerai.filmarks.tv

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val outroStart: Double = -1.0, // an anime's ending credits (aniskip): the next-episode button shows from here
    // how to find the next episode (see Plays.next): the player follows it
    // itself, in place, so a run of episodes keeps going
    val chain: JsonObject? = null,
    val audioLang: String = "", // the title's original language: its audio track is chosen first
    val path: String = "",      // the title's page (Watch Next opens it)
    val poster: String = "",
)

/** Turns a choice on a title page into a PlayRequest, through the server's device API. */
class Plays(private val s: Settings, val api: Api) {

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
    suspend fun seedbox(hash: String, n: Int, fallbackTitle: String, fileSeason: Int = 0): PlayRequest = coroutineScope {
        val p = if (n > 0) api.get("/play-seedbox/$hash", "n" to n, "fs" to fileSeason.takeIf { it > 0 }) else api.get("/play-seedbox/$hash")
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
    suspend fun episode(show: JsonObject, ep: JsonObject, sb: JsonObject?, status: (String) -> Unit): PlayRequest = coroutineScope {
        val epid = ep.long("id")
        val n = ep.long("episode_number").toInt()
        val skip = async {
            if (!show.bool("is_anime")) null
            else withTimeoutOrNull(5000) { runCatching { api.get("/aniskip/${show.long("id")}/$n") }.getOrNull() }
        }
        val r = if (sb != null) {
            seedbox(sb.str("hash"), sb.long("number").toInt(), show.str("title"), sb.long("file_season").toInt())
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
        val sk = skip.await()
        val intro = sk?.get("intro").obj()
        val outro = sk?.get("outro").obj()
        var out = r
        if (intro != null && intro.dbl("end") > 0) out = out.copy(introStart = intro.dbl("start"), introEnd = intro.dbl("end"))
        if (outro != null && outro.dbl("start") > 0) out = out.copy(outroStart = outro.dbl("start"))
        out
    }
}

// ---- the next episode
//
// A chain is {type:"tmdb", tmdb, season, last_season, show, episodes[], index,
// sb:{episode id: [hash, number]}} (a TMDB season's episodes; downloaded
// files where there are some) or {type:"files", hash, title, files[], index}
// (a download TMDB can't place).

fun tmdbChain(tmdb: Long, season: Int, lastSeason: Int, show: JsonObject, episodes: List<JsonObject>, index: Int,
              sb: JsonObject): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("tmdb"), "tmdb" to JsonPrimitive(tmdb), "season" to JsonPrimitive(season),
    "last_season" to JsonPrimitive(lastSeason), "show" to show, "episodes" to JsonArray(episodes),
    "index" to JsonPrimitive(index),
    "sb" to sb, // episode id -> {hash, number, file_season} (the season endpoint's "seedbox")
))

fun filesChain(hash: String, title: String, files: List<JsonObject>, index: Int): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("files"), "hash" to JsonPrimitive(hash), "title" to JsonPrimitive(title),
    "files" to JsonArray(files), "index" to JsonPrimitive(index),
))

private fun JsonObject.with(vararg kv: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(this + kv.toMap())

private fun sbOf(chain: JsonObject, epid: Long): JsonObject? = chain["sb"].obj()?.get(epid.toString()).obj()

/** The next episode's label ("E5 Title", "S2 E1 …"), "" when none is known. */
fun nextLabel(en: Boolean, chain: JsonObject?): String {
    val c = chain ?: return ""
    val i = c.long("index").toInt()
    return when (c.str("type")) {
        "tmdb" -> c.arr("episodes").getOrNull(i + 1)?.let { "E${it.long("episode_number")} " + it.str("name") }
            ?: if (c.long("season") < c.long("last_season")) tr(en, "シーズン${c.long("season") + 1}", "Season ${c.long("season") + 1}") else ""
        "files" -> c.arr("files").getOrNull(i + 1)?.let { tr(en, "第${it.long("number")}話", "E${it.long("number")}") } ?: ""
        else -> ""
    }
}

/** Plays the chain's next episode: its request and the chain moved on to it; null at the end. */
suspend fun Plays.next(chain: JsonObject, status: (String) -> Unit = {}): PlayRequest? {
    val i = chain.long("index").toInt()
    when (chain.str("type")) {
        "tmdb" -> {
            val show = chain["show"].obj() ?: JsonObject(emptyMap())
            val eps = chain.arr("episodes")
            eps.getOrNull(i + 1)?.let { ep ->
                return episode(show, ep, sbOf(chain, ep.long("id")), status).copy(chain = chain.with("index" to JsonPrimitive(i + 1)))
            }
            val season = chain.long("season")
            if (season >= chain.long("last_season")) return null
            val ns = api.get("/shows/${chain.long("tmdb")}/seasons/${season + 1}")
            val first = ns.arr("episodes").firstOrNull() ?: return null
            val show2 = ns["show"].obj() ?: show
            // the new season's downloaded files (a multi-season download lists them per season)
            val sb2 = ns["seedbox"].obj() ?: JsonObject(emptyMap())
            return episode(show2, first, sb2[first.long("id").toString()].obj(), status).copy(chain = chain.with(
                "season" to JsonPrimitive(season + 1), "show" to show2, "episodes" to JsonArray(ns.arr("episodes")),
                "index" to JsonPrimitive(0), "sb" to sb2))
        }
        "files" -> {
            val f = chain.arr("files").getOrNull(i + 1) ?: return null
            return seedbox(chain.str("hash"), f.long("number").toInt(), chain.str("title")).copy(chain = chain.with("index" to JsonPrimitive(i + 1)))
        }
    }
    return null
}
