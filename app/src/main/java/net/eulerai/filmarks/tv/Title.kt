package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the player needs: the stream, where to resume, and where to save progress. */
data class PlayRequest(val url: String, val title: String, val startSec: Long, val progressPath: String, val progressBody: JsonObject)

/** A title page: details, then what can be played (seedbox files, Real-Debrid). */
@Composable
fun TitleScreen(s: Settings, path: String, onPlay: (PlayRequest) -> Unit) {
    var t by remember(path) { mutableStateOf<JsonObject?>(null) }
    var error by remember(path) { mutableStateOf("") }
    var status by remember(path) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val api = remember(s) { Api(s) }
    LaunchedEffect(path) {
        try {
            t = api.get("/app/title", "path" to path)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    val d = t
    if (d == null) {
        Text(if (error != "") error else tr(s.en, "読み込み中…", "Loading…"), color = if (error != "") Palette.red else Palette.muted, modifier = Modifier.padding(48.dp))
        return
    }
    // Plays one seedbox file (a movie, or episode n of a series).
    fun playSeedbox(hash: String, n: Int = 0) = scope.launch {
        status = tr(s.en, "準備中…", "Starting…")
        try {
            val p = if (n > 0) api.get("/play-seedbox/$hash", "n" to n) else api.get("/play-seedbox/$hash")
            val body = if (n > 0) buildJsonObject {
                put("episode_id", p.long("episode_id")); put("show_id", p.long("show_id")); put("link_id", 0)
                put("seedbox", hash); put("number", n)
            } else buildJsonObject { put("movie_id", p.long("movie_id")); put("link_id", 0); put("seedbox", hash) }
            val title = if (n > 0) "${p.str("show_title")} · " + tr(s.en, "第${n}話", "E$n") else p.str("title").ifEmpty { d.str("title") }
            onPlay(PlayRequest(p.str("stream_url"), title, p.long("position_seconds"), if (n > 0) "/progress-episode" else "/progress", body))
            status = ""
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }
    // Real-Debrid for a movie: its stored links (one per quality), after a
    // search when there are none yet; the viewer picks one.
    var rdLinks by remember(path) { mutableStateOf<List<JsonObject>?>(null) }
    fun loadRD(tmdb: Long) = scope.launch {
        try {
            status = tr(s.en, "Real-Debrid を確認中…", "Checking Real-Debrid…")
            var links = api.get("/movies/$tmdb").arr("debrid_links")
            if (links.isEmpty()) {
                status = tr(s.en, "Real-Debrid で検索中…（最大2分）", "Searching Real-Debrid… (up to 2 min)")
                api.post("/realdebrid/resolve/$tmdb", buildJsonObject { })
                for (i in 0 until 60) {
                    delay(2000)
                    val st = api.get("/realdebrid/resolve-status/$tmdb")
                    links = st.arr("links")
                    if (st.str("status") != "resolving") break
                }
            }
            rdLinks = links
            status = if (links.isEmpty()) tr(s.en, "Real-Debrid に見つかりません", "Not found on Real-Debrid") else ""
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }
    fun playRD(tmdb: Long, l: JsonObject) = scope.launch {
        try {
            val p = api.get("/play/${l.long("id")}")
            onPlay(PlayRequest(p.str("stream_url"), p.str("title").ifEmpty { d.str("title") }, p.long("position_seconds"), "/progress",
                buildJsonObject { put("movie_id", tmdb); put("link_id", l.long("id")) }))
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (d.str("backdrop") != "") {
            AsyncImage(d.str("backdrop"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.25f))
        }
        LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    AsyncImage(d.str("poster"), null, contentScale = ContentScale.Crop, modifier = Modifier.size(220.dp, 330.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.str("title"), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                        if (d.str("original_title") != "" && d.str("original_title") != d.str("title")) {
                            Text(d.str("original_title"), color = Palette.muted)
                        }
                        val meta = listOfNotNull(
                            d.long("year").takeIf { it > 0 }?.toString(),
                            d.long("runtime").takeIf { it > 0 }?.let { tr(s.en, "${it}分", "$it min") },
                            d.strs("genres").take(3).joinToString(" / ").takeIf { it != "" },
                        ).joinToString(" · ")
                        Text(meta, color = Palette.muted)
                        val ratings = listOfNotNull(
                            d.dbl("score").takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                            d.dbl("imdb").takeIf { it > 0 }?.let { "IMDb %.1f".format(it) },
                            d.str("rt").takeIf { it != "" }?.let { "RT $it" },
                        ).joinToString("   ")
                        if (ratings != "") Text(ratings, color = Palette.gold, fontSize = 18.sp)
                        Text(d.str("overview"), color = Palette.text, maxLines = 7, modifier = Modifier.width(820.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (sb in d.arr("seedbox").filter { !it.bool("series") }) {
                        Button(onClick = { playSeedbox(sb.str("hash")) }) { Text("▶ " + tr(s.en, "再生", "Play") + " · ${sb.str("quality")}") }
                    }
                    if (d.str("media") == "movie" && d.long("tmdb_id") > 0) {
                        OutlinedButton(onClick = { loadRD(d.long("tmdb_id")) }) { Text("Real-Debrid") }
                    }
                }
                if (status != "") Text(status, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
            }
            rdLinks?.takeIf { it.isNotEmpty() }?.let { links ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Real-Debrid", color = Palette.muted, fontSize = 14.sp)
                        for (l in links) {
                            val size = l.long("file_size").takeIf { it > 0 }?.let { "%.1f GB".format(it / 1e9) }
                            val label = listOfNotNull(l.str("quality").ifEmpty { null }, size,
                                l.long("seeders").takeIf { it > 0 }?.let { tr(s.en, "シード $it", "$it seeders") }).joinToString(" · ")
                            OutlinedButton(onClick = { playRD(d.long("tmdb_id"), l) }) {
                                Column {
                                    Text("▶ $label")
                                    Text(l.str("filename").ifEmpty { l.str("torrent_title") }, fontSize = 12.sp, color = Palette.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            for (sb in d.arr("seedbox").filter { it.bool("series") }) {
                item { SeedboxEpisodes(s, api, sb) { n -> playSeedbox(sb.str("hash"), n) } }
            }
            val people = d.arr("people")
            if (people.isNotEmpty()) item {
                Text(people.joinToString("、") { it.str("name") }, color = Palette.muted, maxLines = 3, modifier = Modifier.width(1100.dp))
            }
        }
    }
}

/** A series download's episode files as a row of buttons. */
@Composable
private fun SeedboxEpisodes(s: Settings, api: Api, sb: JsonObject, onPlay: (Int) -> Unit) {
    var eps by remember(sb) { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(sb) {
        eps = runCatching { api.get("/seedbox/${sb.str("hash")}").arr("episodes") }.getOrDefault(emptyList())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(s.en, "ダウンロード", "Downloaded") + " · ${sb.str("quality")} · ${sb.str("release")}", color = Palette.muted, fontSize = 14.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(eps) { e ->
                val n = e.long("number").toInt()
                val label = if (e.long("file_season") > 0) "S${e.long("file_season")} E$n" else tr(s.en, "第${n}話", "E$n")
                OutlinedButton(onClick = { onPlay(n) }) { Text("▶ $label") }
            }
        }
    }
}

@Suppress("unused")
private fun JsonObject.has(k: String) = this[k] is JsonPrimitive
