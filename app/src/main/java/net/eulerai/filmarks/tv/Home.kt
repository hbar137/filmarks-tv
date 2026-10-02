@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package net.eulerai.filmarks.tv

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.FilterChip
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.serialization.json.JsonObject

data class HomeRow(val title: String, val cards: List<JsonObject>)

// pages opened from the home's top bar (anything else on the stack is a title path)
const val PAGE_SEARCH = "page:search"
const val PAGE_WATCHLIST = "page:watchlist"
const val PAGE_HISTORY = "page:history"
const val PAGE_DOWNLOADS = "page:downloads"
const val PAGE_BROWSE = "page:browse"

/** Poster rows, as the website's home: Downloads first, then Filmarks' / TMDB rows. */
@Composable
fun HomeScreen(s: Settings, kind: String, onKind: (String) -> Unit, onLang: () -> Unit, onSettings: () -> Unit,
               onPage: (String) -> Unit, onOpen: (String) -> Unit, update: String = "", onUpdate: () -> Unit = {}) {
    var rows by remember(s, kind) { mutableStateOf<List<HomeRow>?>(null) }
    var pick by remember(s, kind) { mutableStateOf<JsonObject?>(null) }
    var error by remember(s, kind) { mutableStateOf("") }
    LaunchedEffect(s, kind) {
        try {
            val d = Api(s).get("/app/home", "lang" to s.lang, "kind" to kind)
            rows = d.arr("rows").map { HomeRow(it.str("title"), it.arr("cards")) }
            pick = d["pick"].obj()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FILMARKS ", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Text("ARCHIVE", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.gold, modifier = Modifier.padding(end = 24.dp))
            for ((k, label) in listOf("movie" to tr(s.en, "映画", "Movies"), "drama" to tr(s.en, "ドラマ", "Shows"), "anime" to tr(s.en, "アニメ", "Anime"))) {
                FilterChip(selected = kind == k, onClick = { onKind(k) }) { Text(label) }
            }
            Box(Modifier.width(24.dp))
            OutlinedButton(onClick = { onPage(PAGE_SEARCH) }) { Text(tr(s.en, "検索", "Search")) }
            OutlinedButton(onClick = { onPage(PAGE_BROWSE) }) { Text(tr(s.en, "作品", "Browse")) }
            OutlinedButton(onClick = { onPage(PAGE_WATCHLIST) }) { Text(tr(s.en, "ウォッチリスト", "Watchlist")) }
            OutlinedButton(onClick = { onPage(PAGE_HISTORY) }) { Text(tr(s.en, "履歴", "History")) }
            OutlinedButton(onClick = { onPage(PAGE_DOWNLOADS) }) { Text(tr(s.en, "ダウンロード", "Downloads")) }
            OutlinedButton(onClick = onLang) { Text(if (s.en) "日本語" else "English") }
            OutlinedButton(onClick = onSettings) { Text(tr(s.en, "設定", "Settings")) }
            if (update != "") androidx.tv.material3.Button(onClick = onUpdate) { Text(tr(s.en, "更新 $update", "Update $update")) }
        }
        when {
            error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
            rows == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                pick?.let { p -> item { Pick(s, p) { onOpen(p.str("path")) } } }
                items(rows!!) { row -> PosterRowOf(s, row, onOpen) }
            }
        }
    }
}

@Composable
fun PosterRowOf(s: Settings, row: HomeRow, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.cards) { c -> PosterCard(s, c) { onOpen(c.str("path")) } }
        }
    }
}

@Composable
fun PosterCard(s: Settings, c: JsonObject, onClick: () -> Unit) {
    Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(onClick = onClick, modifier = Modifier.size(140.dp, 210.dp),
            colors = CardDefaults.colors(containerColor = Palette.bg2)) {
            Box(Modifier.fillMaxSize()) {
                Text(c.str("title"), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                if (c.str("poster") != "") {
                    AsyncImage(model = c.str("poster"), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text(c.str("title"), color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            c.long("year").takeIf { it > 0 }?.toString(),
            when {
                c.dbl("imdb") > 0 -> "IMDb %.1f".format(c.dbl("imdb"))
                !c.str("kind").startsWith("en") && c.dbl("score") > 0 -> "★ %.1f".format(c.dbl("score"))
                else -> null
            },
            c.str("note").takeIf { it != "" },
        ).joinToString(" · ")
        Text(sub, color = Palette.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(16.dp))
    }
}

/** 今日の一本 / Today's pick: one large card at the top of the home. */
@Composable
private fun Pick(s: Settings, p: JsonObject, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth().height(230.dp),
        colors = CardDefaults.colors(containerColor = Palette.card)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (p.str("poster") != "") AsyncImage(p.str("poster"), null, contentScale = ContentScale.Crop, modifier = Modifier.size(153.dp, 230.dp))
            Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(s.en, "今日の一本", "Today's pick"), color = Palette.gold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(p.str("title"), color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val sub = listOfNotNull(p.long("year").takeIf { it > 0 }?.toString(), p.dbl("score").takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
                if (sub != "") Text(sub, color = Palette.muted)
                Text(p.str("note"), color = Palette.text, maxLines = 4, modifier = Modifier.padding(end = 32.dp))
            }
        }
    }
}
