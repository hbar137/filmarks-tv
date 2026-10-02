package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.serialization.json.JsonObject

/** The website's browse page: sort, genre and country filters over a poster grid. */
@Composable
fun BrowseScreen(s: Settings, kind: String, onOpen: (String) -> Unit) {
    val sorts = if (s.en) listOf("popular" to "Most popular", "rating" to "Top rated", "newest" to "Newest")
                else listOf("popular" to "人気", "score" to "評価", "reviews" to "レビュー数", "new" to "新しい", "old" to "古い")
    var sort by remember(s.lang) { mutableStateOf(sorts.first().first) }
    var genre by remember(s.lang, kind) { mutableStateOf<JsonObject?>(null) }
    var country by remember(s.lang, kind) { mutableStateOf<JsonObject?>(null) }
    var picking by remember { mutableStateOf("") } // "genre" | "country" while choosing
    var cards by remember(s.lang, kind, sort, genre, country) { mutableStateOf<List<JsonObject>?>(null) }
    var genres by remember(s.lang, kind) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var countries by remember(s.lang, kind) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var page by remember(s.lang, kind, sort, genre, country) { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val api = remember(s) { Api(s) }
    suspend fun load(p: Int) {
        try {
            val d = api.get("/app/browse", "lang" to s.lang, "kind" to kind, "sort" to sort, "page" to p,
                "genre" to genre?.str("value"), "country" to country?.str("value"))
            cards = (if (p == 1) emptyList() else cards.orEmpty()) + d.arr("cards")
            if (genres.isEmpty()) genres = d.arr("genres")
            if (countries.isEmpty()) countries = d.arr("countries")
            more = d.bool("more"); page = p; error = ""
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(s.lang, kind, sort, genre, country) { load(1) }
    val grid = rememberLazyGridState()
    InfiniteLoad(grid, cards?.size ?: 0, more) { load(page + 1) }

    if (picking != "") {
        val opts = if (picking == "genre") genres else countries
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Text(tr(s.en, if (picking == "genre") "ジャンル" else "国・地域", if (picking == "genre") "Genre" else "Country"),
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
            LazyVerticalGrid(GridCells.Adaptive(220.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedButton(onClick = { if (picking == "genre") genre = null else country = null; picking = "" }) { Text(tr(s.en, "すべて", "All")) } }
                items(opts) { o ->
                    OutlinedButton(onClick = { if (picking == "genre") genre = o else country = o; picking = "" }) {
                        Text("${o.str("name")}  ${o.long("count")}", maxLines = 1)
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(tr(s.en, "作品", "Browse"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(end = 24.dp)) }
            items(sorts) { (k, label) -> if (k == sort) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { sort = k }) { Text(label) } }
            item { OutlinedButton(onClick = { picking = "genre" }) { Text(genre?.str("name") ?: tr(s.en, "ジャンル ▾", "Genre ▾")) } }
            item { OutlinedButton(onClick = { picking = "country" }) { Text(country?.str("name") ?: tr(s.en, "国・地域 ▾", "Country ▾")) } }
        }
        when {
            error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
            cards == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(cards!!) { c -> PosterCard(s, c) { onOpen(c.str("path")) } }
            }
        }
    }
}
