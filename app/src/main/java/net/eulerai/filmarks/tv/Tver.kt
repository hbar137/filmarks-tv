package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
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
import java.net.URLEncoder

/*
 * TVer (Japan's free catch-up TV), as the website's /tver: rankings and
 * search as series (番組) or episodes (エピソード), series pages with 本編
 * oldest first and the extras (予告, 解説…) folded, save to the seedbox
 * (保存, 本編を全部保存), follow (登録), and the saved episodes (searching is
 * テレビ's search, Nhk.kt, as on the website). Everything
 * comes through the server, which reaches TVer only from Japan (egress).
 */

const val PAGE_TVER = "page:tver"
const val TVER_SERIES = "tver:series:" // + series id, on the page stack

/** TVer's list state, kept by the app: Back from a series returns to the same list. */
class TverState {
    var tab by mutableStateOf("all")
    var view by mutableStateOf("series") // series | episodes | saved
    var q by mutableStateOf("")
    var data by mutableStateOf<JsonObject?>(null)
    var loadedFor by mutableStateOf("")
    var saves by mutableStateOf<List<JsonObject>?>(null)
    var error by mutableStateOf("")
    var grid by mutableStateOf(LazyGridState())
    val descriptions = mutableStateMapOf<String, String>() // series id -> description
}

/** A TVer image under the device API (the password as ?p=: the image loader sends no header). */
fun tverImg(s: Settings, path: String) = s.server + "/api/kodi" + path + "?p=" + URLEncoder.encode(s.password, "UTF-8")

/** The player's request for a TVer episode: TVer's stream, or the saved copy (from = "seedbox"). */
suspend fun tverPlay(api: Api, id: String, from: String? = null): PlayRequest {
    val p = api.get("/app/tver/ep/$id/play", "from" to from)
    return PlayRequest(p.str("stream_url"), p.str("title"), p.long("position_seconds"), "/app/tver/progress",
        buildJsonObject { put("id", id) })
}

@Composable
fun TverScreen(s: Settings, st: TverState, onPlay: (PlayRequest) -> Unit, onOpen: (String) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    val key = "${st.tab}|${st.q}"
    suspend fun load() {
        try {
            st.data = api.get("/app/tver", "tab" to st.tab, "q" to st.q.ifBlank { null })
            st.loadedFor = key; st.error = ""
        } catch (e: Exception) {
            st.error = e.message ?: e.javaClass.simpleName
        }
    }
    suspend fun loadSaves() {
        runCatching { st.saves = api.get("/app/tver/saves").arr("saves") }.onFailure { st.error = it.message ?: "error" }
    }
    LaunchedEffect(key) {
        if (st.loadedFor != key) { st.data = null; st.grid = LazyGridState(); load() }
    }
    // saves in progress: refresh their status
    LaunchedEffect(st.view) {
        if (st.view == "saved") loadSaves()
        while (true) {
            delay(10_000)
            val busy = (if (st.view == "saved") st.saves.orEmpty() else st.data?.arr("episodes").orEmpty())
                .any { it.str("save_status") == "queued" || it.str("save_status") == "saving" }
            if (busy) { if (st.view == "saved") loadSaves() else load() }
        }
    }
    fun save(id: String) = scope.launch {
        status = runCatching { api.post("/app/tver/ep/$id/save", buildJsonObject { }); load(); "" }.getOrElse { it.message ?: "error" }
    }
    fun play(id: String, from: String? = null) = scope.launch {
        status = tr(s.en, "準備中…", "Starting…")
        status = runCatching { onPlay(tverPlay(api, id, from)); "" }.getOrElse { it.message ?: "error" }
    }
    val d = st.data
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item { Text("TVer", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(end = 16.dp)) }
            items(listOf("series" to tr(s.en, "番組", "Series"), "episodes" to tr(s.en, "エピソード", "Episodes"), "saved" to tr(s.en, "保存済み", "Saved"))) { (v, label) ->
                if (st.view == v) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { st.view = v }) { Text(label) }
            }
        }
        if (st.view != "saved" && st.q == "" && d != null) {
            LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.arr("tabs")) { t ->
                    if (t.str("id") == st.tab) Button(onClick = {}) { Text(t.str("title")) } else OutlinedButton(onClick = { st.tab = t.str("id") }) { Text(t.str("title")) }
                }
            }
        }
        val following = d?.arr("following").orEmpty()
        if (st.view != "saved" && following.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                item { Text(tr(s.en, "登録中", "Following"), color = Palette.muted, fontSize = 13.sp) }
                items(following) { f -> OutlinedButton(onClick = { onOpen(TVER_SERIES + f.str("id")) }) { Text(f.str("title"), fontSize = 13.sp) } }
            }
        }
        if (status != "") Text(status, color = Palette.muted, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        when {
            st.error != "" -> Text(st.error, color = Palette.red, modifier = Modifier.padding(48.dp))
            st.view == "saved" -> {
                val saves = st.saves
                if (saves == null) Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
                else if (saves.isEmpty()) Text(tr(s.en, "まだ保存した番組はありません", "Nothing saved yet"), color = Palette.muted, modifier = Modifier.padding(48.dp))
                else LazyVerticalGrid(GridCells.Adaptive(270.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(saves) { e ->
                        TverEpisodeCard(s, e, showSeries = true, onPlay = { play(e.str("id"), "seedbox") }, onSave = { save(e.str("id")) },
                            onSeries = { onOpen(TVER_SERIES + e.str("series_id")) })
                    }
                }
            }
            d == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            st.view == "series" -> {
                val series = d.arr("series")
                if (series.isEmpty()) Text(tr(s.en, "見つかりませんでした", "Nothing found"), color = Palette.muted, modifier = Modifier.padding(48.dp))
                else LazyVerticalGrid(GridCells.Adaptive(300.dp), state = st.grid, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(series) { sr -> TverSeriesCard(s, api, st, sr) { onOpen(TVER_SERIES + sr.str("id")) } }
                }
            }
            else -> {
                val eps = d.arr("episodes")
                if (eps.isEmpty()) Text(tr(s.en, "見つかりませんでした", "Nothing found"), color = Palette.muted, modifier = Modifier.padding(48.dp))
                else LazyVerticalGrid(GridCells.Adaptive(270.dp), state = st.grid, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    items(eps) { e ->
                        TverEpisodeCard(s, e, showSeries = true, onPlay = { play(e.str("id")) }, onSave = { save(e.str("id")) },
                            onSeries = { onOpen(TVER_SERIES + e.str("series_id")) })
                    }
                }
            }
        }
    }
}

/** A series in the 番組 view: thumbnail, title, broadcaster and its description (loaded when shown, kept). */
@Composable
private fun TverSeriesCard(s: Settings, api: Api, st: TverState, sr: JsonObject, onOpen: () -> Unit) {
    val id = sr.str("id")
    LaunchedEffect(id) {
        if (st.descriptions[id] == null) runCatching { st.descriptions[id] = api.get("/app/tver/series/$id/info").str("description") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(169.dp), colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(sr.str("title"), color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
                AsyncImage(tverImg(s, sr.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(sr.str("title"), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(sr.str("broadcaster").takeIf { it != "" },
            sr.long("count").takeIf { it > 0 }?.let { tr(s.en, "このリストに${it}本", "$it in this list") }).joinToString(" · ")
        if (meta != "") Text(meta, color = Palette.muted, fontSize = 12.sp)
        Text(st.descriptions[id].orEmpty(), color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(52.dp))
    }
}

/** An episode: the thumbnail plays it; under it its save status or 保存. */
@Composable
fun TverEpisodeCard(s: Settings, e: JsonObject, showSeries: Boolean, onPlay: () -> Unit, onSave: () -> Unit, onSeries: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Card(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(152.dp), colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(e.str("title"), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                AsyncImage(tverImg(s, e.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val left = e.long("days_left")
                if (left >= 0) Text(if (left == 0L) tr(s.en, "今日まで", "last day") else tr(s.en, "あと${left}日", "$left d left"),
                    color = if (left < 2) Palette.gold else Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        if (showSeries && e.str("series_title") != "") Text(e.str("series_title"), color = Palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(e.str("title"), color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 19.sp, modifier = Modifier.height(40.dp))
        val dur = e.long("duration")
        Text(listOfNotNull(e.str("date").takeIf { it != "" }, e.str("broadcaster").takeIf { it != "" },
            dur.takeIf { it > 0 }?.let { tr(s.en, "${it / 60}分", "${it / 60} min") }).joinToString(" · "),
            color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (e.str("save_status")) {
                "done" -> Text("✓ " + tr(s.en, "保存済み", "Saved"), color = Palette.gold, fontSize = 12.sp)
                "saving" -> Text(tr(s.en, "保存中 ", "Saving ") + "${e.long("save_percent")}%", color = Palette.muted, fontSize = 12.sp)
                "queued" -> Text(tr(s.en, "保存待ち", "Queued"), color = Palette.muted, fontSize = 12.sp)
                "failed" -> OutlinedButton(onClick = onSave) { Text(tr(s.en, "保存失敗・再試行", "Failed · retry"), fontSize = 12.sp) }
                else -> OutlinedButton(onClick = onSave) { Text(tr(s.en, "保存", "Save"), fontSize = 12.sp) }
            }
            if (showSeries && e.str("series_id") != "") OutlinedButton(onClick = onSeries) { Text(tr(s.en, "番組へ", "Series"), fontSize = 12.sp) }
        }
    }
}

/** A series: description, 登録 and 本編を全部保存, 本編 oldest first, the other groups folded. */
@Composable
fun TverSeriesScreen(s: Settings, id: String, onPlay: (PlayRequest) -> Unit) {
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    var d by remember(id) { mutableStateOf<JsonObject?>(null) }
    var error by remember(id) { mutableStateOf("") }
    var status by remember(id) { mutableStateOf("") }
    val open = remember(id) { mutableStateMapOf<String, Boolean>() } // extra groups unfolded
    suspend fun load() {
        try { d = api.get("/app/tver/series/$id"); error = "" } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    LaunchedEffect(id) {
        load()
        while (true) {
            delay(10_000)
            val busy = d?.arr("groups").orEmpty().flatMap { it.arr("episodes") }
                .any { it.str("save_status") == "queued" || it.str("save_status") == "saving" }
            if (busy) load()
        }
    }
    fun save(epId: String) = scope.launch {
        status = runCatching { api.post("/app/tver/ep/$epId/save", buildJsonObject { }); load(); "" }.getOrElse { it.message ?: "error" }
    }
    fun play(epId: String, from: String? = null) = scope.launch {
        status = tr(s.en, "準備中…", "Starting…")
        status = runCatching { onPlay(tverPlay(api, epId, from)); "" }.getOrElse { it.message ?: "error" }
    }
    val dd = d
    when {
        error != "" -> { Text(error, color = Palette.red, modifier = Modifier.padding(48.dp)); return }
        dd == null -> { Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp)); return }
    }
    val following = dd!!.bool("following")
    fun LazyGridScope.header(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }
    fun LazyGridScope.episodes(eps: List<JsonObject>) = items(eps) { e ->
        TverEpisodeCard(s, e, showSeries = false, onPlay = { play(e.str("id")) }, onSave = { save(e.str("id")) }, onSeries = {})
    }
    LazyVerticalGrid(GridCells.Adaptive(270.dp), contentPadding = PaddingValues(48.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        header {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                AsyncImage(tverImg(s, dd.str("thumb")), null, contentScale = ContentScale.Crop, modifier = Modifier.size(320.dp, 180.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dd.str("title"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    if (dd.str("description") != "") Text(dd.str("description"), color = Palette.text, fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val follow = {
                            scope.launch {
                                status = runCatching { api.post("/app/tver/series/$id/follow", buildJsonObject { put("on", !following) }); load(); "" }
                                    .getOrElse { it.message ?: "error" }
                            }
                        }
                        if (following) OutlinedButton(onClick = { follow() }) { Text("✓ " + tr(s.en, "登録中 · 解除", "Following · stop")) }
                        else Button(onClick = { follow() }) { Text("＋ " + tr(s.en, "登録して新しい回を自動保存", "Follow: save new episodes")) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                status = runCatching {
                                    val n = api.post("/app/tver/series/$id/saveall", buildJsonObject { }).long("queued")
                                    load(); tr(s.en, "${n}本を保存待ちにしました", "$n queued")
                                }.getOrElse { it.message ?: "error" }
                            }
                        }) { Text(tr(s.en, "本編を全部保存", "Save all episodes")) }
                    }
                    if (status != "") Text(status, color = Palette.muted)
                }
            }
        }
        for (g in dd.arr("groups")) {
            val eps = g.arr("episodes")
            val title = g.str("title")
            if (g.bool("main")) {
                header { Text("$title  " + tr(s.en, "古い順 · ${eps.size}本", "oldest first · ${eps.size}"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text) }
                if (eps.isEmpty()) header { Text(tr(s.en, "いま配信中の回はありません", "Nothing on TVer right now"), color = Palette.muted) }
                episodes(eps)
            } else {
                header {
                    OutlinedButton(onClick = { open[title] = !(open[title] ?: false) }) {
                        Text((if (open[title] == true) "▾ " else "▸ ") + "$title (${eps.size})")
                    }
                }
                if (open[title] == true) episodes(eps)
            }
        }
        val gone = dd.arr("gone")
        if (gone.isNotEmpty()) {
            header { Text(tr(s.en, "保存済み（TVer の配信は終了）", "Saved (no longer on TVer)"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.text) }
            items(gone) { e -> TverEpisodeCard(s, e, showSeries = false, onPlay = { play(e.str("id"), "seedbox") }, onSave = {}, onSeries = {}) }
        }
    }
}
