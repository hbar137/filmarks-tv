package net.eulerai.filmarks.tv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** The trailer, in the YouTube app (or whatever plays the link). */
@Composable
fun TrailerButton(s: Settings, url: String) {
    val ctx = LocalContext.current
    if (url == "") return
    OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
        Text(tr(s.en, "予告編", "Trailer"))
    }
}

/** Cast and crew as buttons to their person pages. */
@Composable
fun CastRow(s: Settings, people: List<JsonObject>, onOpen: (String) -> Unit) {
    if (people.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(s.en, "キャスト・スタッフ", "Cast & crew"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(people) { p ->
                OutlinedButton(onClick = { if (p.str("path") != "") onOpen(p.str("path")) }) {
                    Column {
                        Text(p.str("name"))
                        val role = listOf(p.str("role"), p.str("character")).filter { it != "" }.joinToString(" · ")
                        if (role != "") Text(role, fontSize = 11.sp, color = Palette.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Reviews (Filmarks' on Japanese pages, TMDB's on English ones), loaded on request, page by page. */
@Composable
fun ReviewsSection(s: Settings, api: Api, path: String) {
    var reviews by remember(path) { mutableStateOf<List<JsonObject>?>(null) }
    var page by remember(path) { mutableStateOf(0) }
    var more by remember(path) { mutableStateOf(false) }
    var status by remember(path) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val route = "/app/reviews" + path.substringBefore('?')
    fun load() = scope.launch {
        status = tr(s.en, "読み込み中…", "Loading…")
        try {
            val d = api.get(route, "page" to page + 1)
            reviews = reviews.orEmpty() + d.arr("reviews")
            more = d.bool("more"); page += 1
            status = if (reviews!!.isEmpty()) tr(s.en, "レビューはまだありません", "No reviews yet") else ""
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (reviews == null) OutlinedButton(onClick = { load() }) { Text(tr(s.en, "レビューを見る", "Show reviews")) }
        val first = remember(path) { FocusRequester() }
        reviews.orEmpty().forEachIndexed { i, r ->
            // a Card so the remote can land on each review and scroll through them
            Card(onClick = {}, colors = CardDefaults.colors(containerColor = Palette.card),
                modifier = Modifier.width(1100.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val rating = r.dbl("rating").takeIf { it > 0 }?.let { if (it > 5) "★ %.0f/10".format(it) else "★ %.1f".format(it) } ?: ""
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (rating != "") Text(rating, color = Palette.gold)
                        Text(r.str("author"), color = Palette.text, fontWeight = FontWeight.Bold)
                        Text(r.str("date"), color = Palette.muted, fontSize = 12.sp)
                    }
                    Text(if (r.bool("spoiler")) tr(s.en, "（ネタバレを含むレビュー）", "(contains spoilers)") else r.str("body"),
                        color = Palette.text, maxLines = 8)
                }
            }
        }
        // the button that had focus is gone: give it to the first review, not the page's top
        LaunchedEffect(page) { if (page == 1 && reviews.orEmpty().isNotEmpty()) runCatching { first.requestFocus() } }
        if (more) OutlinedButton(onClick = { load() }) { Text(tr(s.en, "もっと見る", "More")) }
        if (status != "") Text(status, color = Palette.muted)
    }
}

/**
 * AvistaZ releases (Japanese pages: movies, dramas, anime) with
 * 「シードボックスへ」, as the website's AvistaZ section.
 */
@Composable
fun AvistazSection(s: Settings, api: Api, path: String) {
    if (path == "") return
    var rs by remember(path) { mutableStateOf<List<JsonObject>?>(null) }
    var status by remember(path) { mutableStateOf("") }
    var sent by remember(path) { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rs == null) OutlinedButton(onClick = {
            status = tr(s.en, "AvistaZ を検索中…", "Searching AvistaZ…")
            scope.launch {
                try {
                    val d = api.get("/app/avistaz$path")
                    rs = d.arr("releases")
                    status = d.str("error").ifEmpty { if (rs!!.isEmpty()) tr(s.en, "見つかりません", "Nothing found") else "" }
                } catch (e: Exception) { status = e.message ?: "error" }
            }
        }) { Text(tr(s.en, "AvistaZ で検索", "Search AvistaZ")) }
        val first = remember(path) { FocusRequester() }
        LaunchedEffect(rs) { if (rs.orEmpty().isNotEmpty()) runCatching { first.requestFocus() } }
        rs.orEmpty().forEachIndexed { i, r ->
            val cost = when {
                r.dbl("download_multiply") == 0.0 -> tr(s.en, "フリーリーチ", "freeleech")
                r.dbl("download_multiply") < 1 -> "DL ×%.1f".format(r.dbl("download_multiply"))
                else -> ""
            }
            val line = listOf(r.str("quality"), "%.1f GB".format(r.long("size_bytes") / 1e9), "↑${r.long("seeders")} ↓${r.long("leechers")}",
                r.str("rip_type"), cost, r.strs("audio").joinToString("/")).filter { it != "" }.joinToString(" · ")
            val hash = r.str("hash")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(860.dp)) {
                    Text(r.str("title"), color = Palette.text, maxLines = 1)
                    Text(line, color = Palette.muted, fontSize = 12.sp)
                }
                val fm = if (i == 0) Modifier.focusRequester(first) else Modifier
                if (hash in sent) Button(onClick = {}, modifier = fm) { Text(tr(s.en, "✓ 送信済み", "✓ Sent")) }
                else OutlinedButton(modifier = fm, onClick = {
                    scope.launch {
                        try {
                            api.post("/app/avistaz$path/$hash", buildJsonObject { })
                            sent = sent + hash
                            status = tr(s.en, "シードボックスに送りました。ダウンロードの進行は「ダウンロード」で。", "Sent to the seedbox. Progress is under Downloads.")
                        } catch (e: Exception) { status = e.message ?: "error" }
                    }
                }) { Text(tr(s.en, "シードボックスへ", "To seedbox")) }
            }
        }
        if (status != "") Text(status, color = Palette.muted)
    }
}
