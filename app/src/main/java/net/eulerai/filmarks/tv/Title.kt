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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A title page: details, your marks, what can be played, and a series' episodes. */
@Composable
fun TitleScreen(s: Settings, path: String, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit = {}) {
    var t by remember(path) { mutableStateOf<JsonObject?>(null) }
    var error by remember(path) { mutableStateOf("") }
    var status by remember(path) { mutableStateOf("") }
    var watched by remember(path) { mutableStateOf(false) }
    var listed by remember(path) { mutableStateOf(false) }
    var rdLinks by remember(path) { mutableStateOf<List<JsonObject>?>(null) }
    // a series' episode files on the seedbox: episode id -> (hash, number)
    var sbEpisodes by remember(path) { mutableStateOf<Map<Long, Pair<String, Int>>>(emptyMap()) }
    var sbUnmatched by remember(path) { mutableStateOf<List<JsonObject>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val api = remember(s) { Api(s) }
    val plays = remember(s) { Plays(s, api) }
    LaunchedEffect(path) {
        try {
            val d = api.get("/app/title", "path" to path)
            watched = d.bool("watched")
            listed = d.bool("watchlisted")
            t = d
            val map = mutableMapOf<Long, Pair<String, Int>>()
            val unmatched = mutableListOf<JsonObject>()
            for (sb in d.arr("seedbox").filter { it.bool("series") }) {
                val files = runCatching { api.get("/seedbox/${sb.str("hash")}").arr("episodes") }.getOrDefault(emptyList())
                if (files.any { it.long("episode_id") > 0 }) {
                    for (f in files) if (f.long("episode_id") > 0) map[f.long("episode_id")] = sb.str("hash") to f.long("number").toInt()
                } else {
                    unmatched += sb
                }
            }
            sbEpisodes = map
            sbUnmatched = unmatched
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    val d = t
    if (d == null) {
        Text(if (error != "") error else tr(s.en, "読み込み中…", "Loading…"), color = if (error != "") Palette.red else Palette.muted, modifier = Modifier.padding(48.dp))
        return
    }
    val tmdb = d.long("tmdb_id")
    val isMovie = d.str("media") == "movie"
    fun run(block: suspend () -> PlayRequest?) = scope.launch {
        try {
            block()?.let { onPlay(it.copy(audioLang = d.str("lang"))); status = "" }
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }
    fun loadRD() = scope.launch {
        try {
            rdLinks = plays.movieLinks(tmdb) { status = it }
            status = if (rdLinks!!.isEmpty()) tr(s.en, "Real-Debrid に見つかりません", "Not found on Real-Debrid") else ""
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (d.str("backdrop") != "") {
            AsyncImage(d.str("backdrop"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.25f))
        }
        LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Header(s, d) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isMovie) {
                        for (sb in d.arr("seedbox").filter { !it.bool("series") }) {
                            Button(onClick = { status = tr(s.en, "準備中…", "Starting…"); run { plays.seedbox(sb.str("hash"), 0, d.str("title")) } }) {
                                Text("▶ " + tr(s.en, "再生", "Play") + " · ${sb.str("quality")}")
                            }
                        }
                        if (tmdb > 0) OutlinedButton(onClick = { loadRD() }) { Text("Real-Debrid") }
                        if (tmdb > 0) OutlinedButton(onClick = {
                            watched = !watched
                            scope.launch { runCatching { api.post("/app/watched", buildJsonObject { put("media", "movie"); put("tmdb", tmdb); put("on", watched) }) } }
                        }) { Text(if (watched) tr(s.en, "✓ 観た", "✓ Watched") else tr(s.en, "観たにする", "Mark watched")) }
                    }
                    if (tmdb > 0) OutlinedButton(onClick = {
                        listed = !listed
                        scope.launch { runCatching { api.post("/app/watchlist", buildJsonObject { put("media", if (isMovie) "movie" else "show"); put("tmdb", tmdb); put("on", listed) }) } }
                    }) { Text(if (listed) tr(s.en, "✓ ウォッチリスト", "✓ Watchlist") else tr(s.en, "＋ ウォッチリスト", "+ Watchlist")) }
                    TrailerButton(s, d.str("trailer"))
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
                            OutlinedButton(onClick = { status = tr(s.en, "準備中…", "Starting…"); run { plays.rdMovie(tmdb, l.long("id"), d.str("title")) } }) {
                                Column {
                                    Text("▶ $label")
                                    Text(l.str("filename").ifEmpty { l.str("torrent_title") }, fontSize = 12.sp, color = Palette.muted, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            if (!isMovie && tmdb > 0) {
                item {
                    Seasons(s, api, plays, d, sbEpisodes, onStatus = { status = it }, onPlay = { onPlay(it.copy(audioLang = d.str("lang"))) })
                }
            }
            // downloads TMDB can't place: their files by number
            for (sb in sbUnmatched) {
                item { SeedboxFiles(s, api, sb) { n -> status = tr(s.en, "準備中…", "Starting…"); run { plays.seedbox(sb.str("hash"), n, d.str("title")) } } }
            }
            item { CastRow(s, d.arr("people"), onOpen) }
            val similar = d.arr("similar")
            if (similar.isNotEmpty()) item {
                PosterRowOf(s, HomeRow(tr(s.en, "似ている作品", "More like this"), similar), onOpen)
            }
            item { ReviewsSection(s, api, path) }
            item { AvistazSection(s, api, path) }
        }
    }
}

@Composable
private fun Header(s: Settings, d: JsonObject) {
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

/**
 * A series' seasons (TMDB) and the chosen season's episodes, with watched
 * marks; an episode plays from the seedbox when a downloaded file has it,
 * else through Real-Debrid.
 */
@Composable
private fun Seasons(s: Settings, api: Api, plays: Plays, d: JsonObject, sb: Map<Long, Pair<String, Int>>,
                    onStatus: (String) -> Unit, onPlay: (PlayRequest) -> Unit) {
    val tmdb = d.long("tmdb_id")
    // a Filmarks season page is one TMDB season; an English show page has all of them
    val seasons = if (d.long("season_number") > 0) listOf(d.long("season_number").toInt())
                  else (1..maxOf(1, d.long("seasons").toInt())).toList()
    var season by remember(tmdb) { mutableStateOf(seasons.first()) }
    var data by remember(tmdb, season) { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tmdb, season) {
        data = runCatching { api.get("/shows/$tmdb/seasons/$season") }.getOrNull()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { n ->
                    val label = tr(s.en, "シーズン$n", "Season $n")
                    if (n == season) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { season = n }) { Text(label) }
                }
            }
        }
        val sd = data
        if (sd == null) {
            Text(tr(s.en, "エピソードを読み込み中…", "Loading episodes…"), color = Palette.muted)
            return@Column
        }
        val eps = sd.arr("episodes")
        val watched = sd["watched"].obj()
        val show = sd["show"].obj() ?: JsonObject(emptyMap())
        fun playAt(i: Int) {
            val ep = eps[i]
            onStatus(tr(s.en, "準備中…", "Starting…"))
            scope.launch {
                try {
                    val r = plays.episode(show, ep, sb[ep.long("id")], onStatus)
                    val next = eps.getOrNull(i + 1)
                    onPlay(if (next == null) r else r.copy(
                        nextLabel = "E${next.long("episode_number")} " + next.str("name"),
                        next = { playAt(i + 1) }))
                    onStatus("")
                } catch (e: Exception) {
                    onStatus(e.message ?: "error")
                }
            }
        }
        eps.forEachIndexed { i, ep ->
            val n = ep.long("episode_number")
            val seen = watched?.bool(ep.long("id").toString()) == true
            val src = if (sb.containsKey(ep.long("id"))) tr(s.en, "ダウンロード済み", "downloaded") else ""
            OutlinedButton(onClick = { playAt(i) }, modifier = Modifier.width(1100.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (seen) "✓" else "▶", color = if (seen) Palette.gold else Palette.text, modifier = Modifier.width(24.dp))
                    Text("E$n", fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                    Text(ep.str("name"), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(640.dp))
                    Text(listOf(ep.str("air_date"), src).filter { it != "" }.joinToString(" · "), color = Palette.muted, fontSize = 13.sp)
                }
            }
        }
    }
}

/** A series download whose episodes TMDB can't place: buttons by file number. */
@Composable
private fun SeedboxFiles(s: Settings, api: Api, sb: JsonObject, onPlay: (Int) -> Unit) {
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
