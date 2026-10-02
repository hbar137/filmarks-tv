package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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

/**
 * The watchlist or history (the website's pages, from Trakt): tabs for
 * movies / shows / anime, a poster grid, and more history page by page.
 */
@Composable
fun ListScreen(s: Settings, what: String, onOpen: (String) -> Unit) {
    var tab by remember(what) { mutableStateOf("movie") }
    var cards by remember(what, tab) { mutableStateOf<List<JsonObject>?>(null) }
    var page by remember(what, tab) { mutableIntStateOf(1) }
    var more by remember(what, tab) { mutableStateOf(false) }
    var error by remember(what, tab) { mutableStateOf("") }
    val api = remember(s) { Api(s) }
    suspend fun load(p: Int) {
        try {
            val d = api.get("/app/$what", "lang" to s.lang, "tab" to tab, "page" to p)
            cards = (if (p == 1) emptyList() else cards.orEmpty()) + d.arr("cards")
            more = d.bool("more")
            page = p
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(what, tab) { load(1) }
    val grid = rememberLazyGridState()
    InfiniteLoad(grid, cards?.size ?: 0, more) { load(page + 1) }
    val title = if (what == "watchlist") tr(s.en, "ウォッチリスト", "Watchlist") else tr(s.en, "視聴履歴", "History")
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Row(Modifier.padding(start = 48.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(end = 24.dp))
            for ((k, label) in listOf("movie" to tr(s.en, "映画", "Movies"), "tv" to tr(s.en, "ドラマ", "Shows"), "anime" to tr(s.en, "アニメ", "Anime"))) {
                if (k == tab) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { tab = k }) { Text(label) }
            }
        }
        when {
            error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
            cards == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            cards!!.isEmpty() -> Text(tr(s.en, "まだありません", "Nothing here yet"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), state = grid, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(cards!!) { c -> PosterCard(s, c) { onOpen(c.str("path")) } }
            }
        }
    }
}

/**
 * Infinite scroll: calls loadMore when the grid's last visible card is
 * within a couple of rows of the end (once per page: loading is guarded).
 */
@Composable
fun InfiniteLoad(state: LazyGridState, count: Int, more: Boolean, loadMore: suspend () -> Unit) {
    var loading by remember { mutableStateOf(false) }
    val nearEnd by remember(count) {
        derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= count - 12 }
    }
    LaunchedEffect(nearEnd, more, count) {
        if (nearEnd && more && !loading && count > 0) {
            loading = true
            try { loadMore() } finally { loading = false }
        }
    }
}
