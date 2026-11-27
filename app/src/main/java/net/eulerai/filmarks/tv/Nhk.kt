package net.eulerai.filmarks.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * テレビ: NHK ONE's catch-up (見逃し) and the search of TVer and NHK, as the
 * website's /nhk and /tv/search. NHK's video is Widevine-protected: the
 * server passes the encrypted stream and the licence request along, and the
 * device (its Widevine) decrypts. Nothing can be saved.
 */

const val KIND_TV = "tv"                 // the home's fourth world, next to 映画 | ドラマ | アニメ
const val PAGE_TVSEARCH = "page:tvsearch"
const val NHK_LIST = "nhk:pl:"           // + playlist id, on the page stack
const val NHK_GENRE = "nhk:genre:"       // + genre (deck) id

/** The テレビ home's NHK rows, kept by the app while pages open on top. */
class NhkState {
    var home by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf("")
    val rows = mutableStateMapOf<String, List<JsonObject>>() // playlist id -> its watchable episodes
}

/** The テレビ search's query and results, kept the same way. */
class TvSearchState {
    var q by mutableStateOf("")
    var data by mutableStateOf<JsonObject?>(null)
    var status by mutableStateOf("")
}

/** An NHK image or stream path from the server (signed: no password). */
fun nhkImg(s: Settings, path: String) = if (path == "") "" else s.server + path

/** The player's request for an NHK episode: its stream and licence links. */
suspend fun nhkPlay(api: Api, id: String): PlayRequest {
    val p = api.get("/app/nhk/ep/$id/play")
    return PlayRequest(p.str("stream_url"), p.str("title"), 0, "", buildJsonObject { put("id", id) }, licenseUrl = p.str("license_url"))
}

/** Plays an NHK episode, or says why not. */
@Composable
fun rememberNhkPlayer(s: Settings, onPlay: (PlayRequest) -> Unit): (JsonObject) -> Unit {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    return { e ->
        if (!e.bool("ready")) Toast.makeText(ctx, tr(s.en, "いまは見逃し配信がありません", "Not on catch-up now"), Toast.LENGTH_SHORT).show()
        else scope.launch {
            Toast.makeText(ctx, tr(s.en, "準備中…", "Starting…"), Toast.LENGTH_SHORT).show()
            runCatching { onPlay(nhkPlay(api, e.str("id"))) }.onFailure {
                Toast.makeText(ctx, it.message ?: "error", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** The テレビ home's body: NHK's genres, then its 見逃し rows (each loaded when it comes into view). */
@Composable
fun NhkHome(s: Settings, st: NhkState, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    val play = rememberNhkPlayer(s, onPlay)
    LaunchedEffect(s) {
        if (st.home == null) runCatching { st.home = api.get("/app/nhk"); st.error = "" }.onFailure { st.error = it.message ?: "error" }
    }
    val h = st.home
    when {
        st.error != "" -> Text(st.error, color = Palette.red, modifier = Modifier.padding(48.dp))
        h == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    item { Text("NHK", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp)) }
                    items(h.arr("genres")) { g -> OutlinedButton(onClick = { onOpen(NHK_GENRE + g.str("id")) }) { Text(g.str("title"), fontSize = 13.sp) } }
                }
            }
            items(h.arr("sections")) { sec ->
                val id = sec.str("id")
                LaunchedEffect(id) {
                    if (st.rows[id] == null) st.rows[id] = runCatching {
                        api.get("/app/nhk/pl/$id", "row" to 1, "lang" to s.lang).arr("episodes")
                    }.getOrDefault(emptyList())
                }
                val eps = st.rows[id]
                if (eps == null || eps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sec.str("title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp))
                    if (eps == null) Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 48.dp).height(40.dp))
                    else LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(eps) { e -> Box(Modifier.width(260.dp)) { NhkEpisodeCard(s, e, onPlay = { play(e) }, onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) }) } }
                        item { OutlinedButton(onClick = { onOpen(NHK_LIST + id) }) { Text(tr(s.en, "すべて →", "All →")) } }
                    }
                }
            }
        }
    }
}

/** An NHK episode: the thumbnail plays it (dimmed when it can't be watched now). */
@Composable
fun NhkEpisodeCard(s: Settings, e: JsonObject, showSeries: Boolean = true, onPlay: () -> Unit, onSeries: () -> Unit) {
    val ready = e.bool("ready")
    Column(Modifier.alpha(if (ready) 1f else 0.55f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Card(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(146.dp), colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(e.str("title"), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                AsyncImage(nhkImg(s, e.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val left = e.long("days_left")
                Text(when {
                    left < 0 -> tr(s.en, "配信なし", "Not available")
                    left == 0L -> tr(s.en, "今日まで", "last day")
                    else -> tr(s.en, "あと${left}日", "$left d left")
                }, color = if (left in 0L..1L) Palette.gold else Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        if (showSeries && e.str("series_title") != "") Text(e.str("series_title"), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(e.str("title"), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp, modifier = Modifier.height(40.dp))
        val dur = e.long("duration")
        Text(listOfNotNull(e.str("date").takeIf { it != "" }, e.str("channel").takeIf { it != "" },
            dur.takeIf { it > 0 }?.let { tr(s.en, "${it / 60}分", "${it / 60} min") }).joinToString(" · "),
            color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showSeries && e.str("series_id") != "") OutlinedButton(onClick = onSeries) { Text(tr(s.en, "番組へ", "Series"), fontSize = 12.sp) }
    }
}

/** A series' or NHK list's episodes, newest first as NHK has them, 10 at a time. */
@Composable
fun NhkListScreen(s: Settings, id: String, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val play = rememberNhkPlayer(s, onPlay)
    val eps = remember(id) { mutableStateListOf<JsonObject>() }
    var title by remember(id) { mutableStateOf("") }
    var total by remember(id) { mutableStateOf(0L) }
    var next by remember(id) { mutableStateOf(0L) }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf("") }
    suspend fun load(offset: Long) {
        loading = true
        try {
            val d = api.get("/app/nhk/pl/$id", "offset" to offset, "lang" to s.lang)
            if (title == "") title = d.str("title")
            total = d.long("total"); next = d.long("next"); eps.addAll(d.arr("episodes")); error = ""
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }
    LaunchedEffect(id) { if (eps.isEmpty()) load(0) }
    val series = id.startsWith("series-tep-")
    LazyVerticalGrid(GridCells.Adaptive(270.dp), contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.ifBlank { "NHK" }, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                if (total > 0) Text(tr(s.en, "全${total}本（見られるのは最近の放送のみ）", "$total episodes (only recent ones can be watched)"), color = Palette.muted)
                if (error != "") Text(error, color = Palette.red)
            }
        }
        items(eps) { e -> NhkEpisodeCard(s, e, showSeries = !series, onPlay = { play(e) }, onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) }) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                loading -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted)
                next > 0 -> OutlinedButton(onClick = { scope.launch { load(next) } }) { Text(tr(s.en, "もっと見る", "More")) }
                eps.isEmpty() && error == "" -> Text(tr(s.en, "エピソードがありません", "No episodes"), color = Palette.muted)
            }
        }
    }
}

/** A genre's series (NHK ONE's 番組のジャンル). */
@Composable
fun NhkGenreScreen(s: Settings, id: String, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    var d by remember(id) { mutableStateOf<JsonObject?>(null) }
    var error by remember(id) { mutableStateOf("") }
    LaunchedEffect(id) { runCatching { d = api.get("/app/nhk/genre/$id") }.onFailure { error = it.message ?: "error" } }
    val dd = d
    when {
        error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
        dd == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
        else -> LazyVerticalGrid(GridCells.Adaptive(300.dp), contentPadding = PaddingValues(48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text(dd.str("title"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text) }
            items(dd.arr("series")) { sr -> NhkSeriesCard(s, sr) { onOpen(NHK_LIST + sr.str("id")) } }
        }
    }
}

@Composable
private fun NhkSeriesCard(s: Settings, sr: JsonObject, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(169.dp), colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(sr.str("title"), color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                AsyncImage(nhkImg(s, sr.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(sr.str("title"), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sr.str("catch"), color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
    }
}

/** テレビ's search: TVer and NHK at once, each with its series and the episodes watchable now. */
@Composable
fun TvSearchScreen(s: Settings, st: TvSearchState, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val playNhk = rememberNhkPlayer(s, onPlay)
    val focus = remember { FocusRequester() }
    var typed by remember { mutableStateOf(st.q) }
    LaunchedEffect(Unit) { if (st.data == null) runCatching { focus.requestFocus() } }
    fun go() = scope.launch {
        val q = typed.trim()
        if (q == "") return@launch
        st.q = q; st.status = tr(s.en, "検索中…", "Searching…")
        st.status = runCatching { st.data = api.get("/app/tv/search", "q" to q, "lang" to s.lang); "" }.getOrElse { it.message ?: "error" }
    }
    fun playTver(id: String) = scope.launch {
        st.status = tr(s.en, "準備中…", "Starting…")
        st.status = runCatching { onPlay(tverPlay(api, id)); "" }.getOrElse { it.message ?: "error" }
    }
    fun saveTver(id: String) = scope.launch {
        st.status = runCatching { api.post("/app/tver/ep/$id/save", buildJsonObject { }); tr(s.en, "保存待ちにしました", "Queued") }.getOrElse { it.message ?: "error" }
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
        focusedBorderColor = Palette.gold, unfocusedBorderColor = Palette.line, cursorColor = Palette.gold,
        focusedLabelColor = Palette.gold, unfocusedLabelColor = Palette.muted,
    )
    val d = st.data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(typed, { typed = it }, singleLine = true, colors = colors,
                    label = { androidx.compose.material3.Text(tr(s.en, "番組・出演者を検索（TVer・NHK）", "Search shows and cast (TVer, NHK)")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.width(720.dp).focusRequester(focus))
                if (st.status != "") Text(st.status, color = Palette.muted)
            }
        }
        if (d != null) {
            for ((key, name) in listOf("tver" to "TVer", "nhk" to "NHK")) {
                val r = d[key].obj() ?: continue
                item { Text(name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp, top = 8.dp)) }
                if (r.str("error") != "") { item { Text(r.str("error"), color = Palette.red, modifier = Modifier.padding(start = 48.dp)) }; continue }
                val series = r.arr("series")
                if (series.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        item { Text(tr(s.en, "番組", "Series"), color = Palette.muted, fontSize = 13.sp) }
                        items(series) { sr ->
                            OutlinedButton(onClick = { onOpen(if (key == "tver") TVER_SERIES + sr.str("id") else NHK_LIST + sr.str("id")) }) { Text(sr.str("title"), fontSize = 13.sp) }
                        }
                    }
                }
                val eps = r.arr("episodes")
                item {
                    if (eps.isEmpty()) Text(tr(s.en, "配信中のエピソードはありません", "No episodes to watch now"), color = Palette.muted, modifier = Modifier.padding(start = 48.dp))
                    else LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(eps) { e ->
                            Box(Modifier.width(260.dp)) {
                                if (key == "tver") TverEpisodeCard(s, e, showSeries = true, onPlay = { playTver(e.str("id")) }, onSave = { saveTver(e.str("id")) },
                                    onSeries = { onOpen(TVER_SERIES + e.str("series_id")) })
                                else NhkEpisodeCard(s, e, onPlay = { playNhk(e) }, onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) })
                            }
                        }
                    }
                }
            }
        }
    }
}
