package net.eulerai.filmarks.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * テレビ: NHK ONE's catch-up (見逃し) and the search of TVer and NHK, as the
 * website's /nhk and /tv/search. NHK's video is Widevine-protected: the
 * server passes the encrypted stream and the licence request along, and the
 * device (its Widevine) decrypts. 保存 queues an episode for the server's
 * capture worker, which records it to the seedbox (an MP4 + Japanese
 * subtitles, started at night); the saved copies play like any file.
 */

const val KIND_TV = "tv"                 // the home's fourth world, next to 映画 | ドラマ | アニメ
const val PAGE_TVSEARCH = "page:tvsearch"
const val NHK_LIST = "nhk:pl:"           // + playlist id, on the page stack
const val NHK_GENRE = "nhk:genre:"       // + genre (deck) id
const val PAGE_NHK_SAVES = "page:nhksaves"

/** The テレビ home's NHK rows, kept by the app while pages open on top. */
class NhkState {
    var home by mutableStateOf<JsonObject?>(null)
    var error by mutableStateOf("")
    val rows = mutableStateMapOf<String, List<JsonObject>>() // playlist id -> its watchable episodes
    var live by mutableStateOf<List<JsonObject>?>(null)       // the simulcast channels (総合, Eテレ) and what's on
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

/** The player's request for a live channel (g1, e1). */
suspend fun nhkLivePlay(api: Api, ch: String): PlayRequest {
    val p = api.get("/app/nhk/live/$ch/play")
    return PlayRequest(p.str("stream_url"), p.str("title"), 0, "", buildJsonObject { put("ch", ch) }, licenseUrl = p.str("license_url"))
}

/** The player's request for a saved copy (a plain MP4 on the seedbox) with its subtitles. */
fun nhkSavedPlay(e: JsonObject): PlayRequest {
    val subs = e.str("subs").takeIf { it != "" }?.let { Subs(listOf(Sub("nhk-ja", it, "ja", "日本語"))) } ?: Subs()
    return PlayRequest(e.str("file"), e.str("title"), 0, "", buildJsonObject { put("id", e.str("id")) }, subs = subs)
}

/** 保存: queues an episode for capture; the map holds what was queued here until the lists reload. */
@Composable
fun rememberNhkSave(s: Settings): Pair<Map<String, String>, (String) -> Unit> {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val queued = remember { mutableStateMapOf<String, String>() }
    return queued to { id ->
        scope.launch {
            runCatching { api.post("/app/nhk/ep/$id/save", buildJsonObject { }) }
                .onSuccess {
                    queued[id] = "queued"
                    Toast.makeText(ctx, tr(s.en, "保存待ちにしました（録画は夜1時から）", "Queued (recorded from 1 AM)"), Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(ctx, it.message ?: "error", Toast.LENGTH_LONG).show() }
        }
    }
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
    val (queued, save) = rememberNhkSave(s)
    LaunchedEffect(s) {
        if (st.home == null) runCatching { st.home = api.get("/app/nhk"); st.error = "" }.onFailure { st.error = it.message ?: "error" }
    }
    // what's on changes: asked again each time the テレビ home is shown
    LaunchedEffect(s) { runCatching { st.live = api.get("/app/nhk/live").arr("channels") } }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun playLive(ch: JsonObject) {
        if (!ch.bool("ready")) { Toast.makeText(ctx, tr(s.en, "いまは同時配信がありません", "Not live now"), Toast.LENGTH_SHORT).show(); return }
        scope.launch {
            Toast.makeText(ctx, tr(s.en, "準備中…", "Starting…"), Toast.LENGTH_SHORT).show()
            runCatching { onPlay(nhkLivePlay(api, ch.str("id"))) }.onFailure { Toast.makeText(ctx, it.message ?: "error", Toast.LENGTH_LONG).show() }
        }
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
                    item { OutlinedButton(onClick = { onOpen(PAGE_NHK_SAVES) }) { Text(tr(s.en, "保存済み", "Saved"), fontSize = 13.sp) } }
                    items(h.arr("genres")) { g -> OutlinedButton(onClick = { onOpen(NHK_GENRE + g.str("id")) }) { Text(g.str("title"), fontSize = 13.sp) } }
                }
            }
            st.live?.takeIf { it.isNotEmpty() }?.let { chans ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(s.en, "ライブ（同時配信）", "Live"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(chans) { ch -> Box(Modifier.width(260.dp)) { NhkLiveCard(s, ch) { playLive(ch) } } }
                        }
                    }
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
                        items(eps) { e -> Box(Modifier.width(260.dp)) { NhkEpisodeCard(s, e, queued[e.str("id")], onPlay = { play(e) }, onSave = { save(e.str("id")) },
                            onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) }) } }
                        item { OutlinedButton(onClick = { onOpen(NHK_LIST + id) }) { Text(tr(s.en, "すべて →", "All →")) } }
                    }
                }
            }
        }
    }
}

/** A live channel: what's on now (and next); the picture plays it. */
@Composable
fun NhkLiveCard(s: Settings, ch: JsonObject, onPlay: () -> Unit) {
    val ready = ch.bool("ready")
    val now = ch["now"].obj()
    val next = ch["next"].obj()
    Column(Modifier.alpha(if (ready) 1f else 0.55f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Card(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(146.dp), colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(ch.str("name"), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                AsyncImage(nhkImg(s, ch.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Text(if (ready) tr(s.en, "● ライブ", "● LIVE") else tr(s.en, "配信なし", "Not available"),
                    color = if (ready) Palette.gold else Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Text(ch.str("name"), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(now?.str("title") ?: "", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp, modifier = Modifier.height(40.dp))
        if (now != null) Text("${now.str("start")}–${now.str("end")}" + if (!now.bool("simul")) tr(s.en, " · ネット配信なし", " · not streamed") else "",
            color = Palette.muted, fontSize = 11.sp, maxLines = 1)
        if (next != null) Text(tr(s.en, "次 ", "Next ") + "${next.str("start")} ${next.str("title")}", color = Palette.muted, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** An NHK episode: the thumbnail plays it (dimmed when it can't be watched now); 保存 or its save status under it. */
@Composable
fun NhkEpisodeCard(s: Settings, e: JsonObject, queued: String? = null, showSeries: Boolean = true, onPlay: () -> Unit,
                   onSave: (() -> Unit)? = null, onSeries: () -> Unit) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onSave != null) NhkSaveStatus(s, e.str("save_status").ifEmpty { queued ?: "" }, e.long("save_percent"), ready, onSave)
            if (showSeries && e.str("series_id") != "") OutlinedButton(onClick = onSeries) { Text(tr(s.en, "番組へ", "Series"), fontSize = 12.sp) }
        }
    }
}

/** An episode's save status, or 保存 when it can be saved (on catch-up now). */
@Composable
private fun NhkSaveStatus(s: Settings, status: String, percent: Long, ready: Boolean, onSave: () -> Unit) {
    when (status) {
        "done" -> Text("✓ " + tr(s.en, "保存済み", "Saved"), color = Palette.gold, fontSize = 12.sp)
        "saving" -> Text(tr(s.en, "録画中 ", "Recording ") + "$percent%", color = Palette.muted, fontSize = 12.sp)
        "queued" -> Text(tr(s.en, "保存待ち", "Queued"), color = Palette.muted, fontSize = 12.sp)
        "failed" -> if (ready) OutlinedButton(onClick = onSave) { Text(tr(s.en, "保存失敗・再試行", "Failed · retry"), fontSize = 12.sp) }
        else -> if (ready) OutlinedButton(onClick = onSave) { Text(tr(s.en, "保存", "Save"), fontSize = 12.sp) }
    }
}

/** The saved NHK episodes (and those queued or recording): ▶ plays the saved copy, with its subtitles. */
@Composable
fun NhkSavesScreen(s: Settings, onPlay: (PlayRequest) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    var saves by remember { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember { mutableStateOf("") }
    suspend fun load() {
        runCatching { saves = api.get("/app/nhk/saves").arr("items"); error = "" }.onFailure { error = it.message ?: "error" }
    }
    LaunchedEffect(Unit) {
        load()
        while (true) { // queued and recording ones move on
            delay(10_000)
            if (saves.orEmpty().any { it.str("status") == "queued" || it.str("status") == "saving" }) load()
        }
    }
    fun delete(id: String) = scope.launch {
        error = runCatching { api.post("/app/nhk/ep/$id/delete", buildJsonObject { }); load(); "" }.getOrElse { it.message ?: "error" }
    }
    val list = saves
    LazyColumn(contentPadding = PaddingValues(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(s.en, "NHK 保存済み", "NHK saved"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                Text(tr(s.en, "録画は毎晩1時〜4時（太平洋時間）。配信終了まで24時間を切った回はすぐに録画します。",
                    "Recorded nightly 1–4 AM Pacific; an episode leaving within 24 hours is recorded right away."), color = Palette.muted, fontSize = 13.sp)
                if (error != "") Text(error, color = Palette.red)
            }
        }
        when {
            list == null -> item { Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted) }
            list.isEmpty() -> item { Text(tr(s.en, "まだ保存した番組はありません。エピソードの「保存」からどうぞ。", "Nothing saved yet. Use Save on an episode."), color = Palette.muted) }
            else -> items(list) { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.str("status") == "done") androidx.tv.material3.Button(onClick = { onPlay(nhkSavedPlay(e)) }) { Text("▶", fontSize = 16.sp) }
                    Column(Modifier.width(620.dp)) {
                        if (e.str("series_title") != "") Text(e.str("series_title"), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.str("title"), color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val dur = e.long("duration")
                        Text(listOfNotNull(
                            when (e.str("status")) {
                                "done" -> tr(s.en, "保存済み", "Saved") + if (e.str("subs") != "") tr(s.en, " · 字幕あり", " · subtitles") else ""
                                "saving" -> tr(s.en, "録画中 ", "Recording ") + "${e.long("percent")}%"
                                "queued" -> tr(s.en, "保存待ち", "Queued")
                                else -> tr(s.en, "保存失敗", "Failed") + e.str("error").takeIf { it != "" }?.let { ": $it" }.orEmpty()
                            },
                            dur.takeIf { it > 0 }?.let { tr(s.en, "${it / 60}分", "${it / 60} min") },
                        ).joinToString(" · "), color = if (e.str("status") == "failed") Palette.red else Palette.muted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (e.str("status") != "saving") OutlinedButton(onClick = { delete(e.str("id")) }) { Text(tr(s.en, "削除", "Delete"), fontSize = 12.sp) }
                }
            }
        }
    }
}

/** A series' or NHK list's episodes, newest first as NHK has them, 10 at a time. */
@Composable
fun NhkListScreen(s: Settings, id: String, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val play = rememberNhkPlayer(s, onPlay)
    val (queued, save) = rememberNhkSave(s)
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
    val prefs = LocalContext.current.getSharedPreferences("nhk", android.content.Context.MODE_PRIVATE)
    var readyOnly by remember { mutableStateOf(prefs.getBoolean("readyOnly", false)) } // 配信中のみ, kept on the device
    val shown = if (readyOnly) eps.filter { it.bool("ready") } else eps
    // 配信中のみ with nothing watchable on the pages so far: fetch on by itself
    LaunchedEffect(readyOnly, eps.size, loading) { if (readyOnly && !loading && next > 0 && shown.isEmpty()) load(next) }
    val series = id.startsWith("series-tep-")
    LazyVerticalGrid(GridCells.Adaptive(270.dp), contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.ifBlank { "NHK" }, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                if (total > 0) Text(tr(s.en, "全${total}本（見られるのは最近の放送のみ）", "$total episodes (only recent ones can be watched)"), color = Palette.muted)
                OutlinedButton(onClick = { readyOnly = !readyOnly; prefs.edit().putBoolean("readyOnly", readyOnly).apply() }) {
                    Text((if (readyOnly) "☑ " else "☐ ") + tr(s.en, "配信中のみ", "Watchable only"), fontSize = 13.sp)
                }
                if (error != "") Text(error, color = Palette.red)
            }
        }
        items(shown) { e -> NhkEpisodeCard(s, e, queued[e.str("id")], showSeries = !series, onPlay = { play(e) }, onSave = { save(e.str("id")) }, onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) }) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                loading -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted)
                next > 0 -> OutlinedButton(onClick = { scope.launch { load(next) } }) { Text(tr(s.en, "もっと見る", "More")) }
                eps.isEmpty() && error == "" -> Text(tr(s.en, "エピソードがありません", "No episodes"), color = Palette.muted)
                shown.isEmpty() && error == "" -> Text(tr(s.en, "いま配信中のエピソードはありません", "Nothing to watch right now"), color = Palette.muted)
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
    val (nhkQueued, saveNhk) = rememberNhkSave(s)
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
                                else NhkEpisodeCard(s, e, nhkQueued[e.str("id")], onPlay = { playNhk(e) }, onSave = { saveNhk(e.str("id")) }, onSeries = { onOpen(NHK_LIST + "series-tep-" + e.str("series_id")) })
                            }
                        }
                    }
                }
            }
        }
    }
}
