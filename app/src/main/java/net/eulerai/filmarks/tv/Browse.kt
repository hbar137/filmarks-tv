package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.serialization.json.JsonObject

/**
 * Browse's filters, results and scroll position. The app keeps it (not
 * the screen, which leaves the composition while a title is open on top),
 * so Back from a title returns to the same list, scrolled where it was.
 */
class BrowseState {
    var key by mutableStateOf("")       // lang|kind this state is for
    var sort by mutableStateOf("")
    var genre by mutableStateOf<JsonObject?>(null)
    var country by mutableStateOf<JsonObject?>(null)
    var language by mutableStateOf<JsonObject?>(null)
    var dubsOnly by mutableStateOf(false)
    var cards by mutableStateOf<List<JsonObject>?>(null)
    var loadedFor by mutableStateOf("") // the filters cards were loaded for
    var genres by mutableStateOf<List<JsonObject>>(emptyList())
    var countries by mutableStateOf<List<JsonObject>>(emptyList())
    var languages by mutableStateOf<List<JsonObject>>(emptyList())
    var page by mutableIntStateOf(1)
    var more by mutableStateOf(false)
    var error by mutableStateOf("")
    var opened by mutableIntStateOf(-1) // the card opened last: focus returns to it
    var grid by mutableStateOf(LazyGridState())

    fun reset(key: String, sort: String) {
        this.key = key; this.sort = sort
        genre = null; country = null; language = null; dubsOnly = false
        cards = null; loadedFor = ""; genres = emptyList(); countries = emptyList(); languages = emptyList()
        page = 1; more = false; error = ""; opened = -1; grid = LazyGridState()
    }

    val filters get() = listOf(sort, genre?.str("value"), country?.str("value"), language?.str("value"), dubsOnly).joinToString("|")
}

/** The website's browse page: sort, genre, country and (English) language filters over a poster grid. */
@Composable
fun BrowseScreen(s: Settings, kind: String, st: BrowseState, onOpen: (String) -> Unit) {
    val sorts = if (s.en) listOf("popular" to "Most popular", "rating" to "Top rated", "newest" to "Newest")
                else listOf("popular" to "人気", "score" to "評価", "reviews" to "レビュー数", "new" to "新しい", "old" to "古い")
    if (st.key != "${s.lang}|$kind") st.reset("${s.lang}|$kind", sorts.first().first)
    var picking by remember { mutableStateOf("") } // "genre" | "country" | "language" while choosing
    val api = remember(s) { Api(s) }
    suspend fun load(p: Int) {
        val f = st.filters
        try {
            val d = api.get("/app/browse", "lang" to s.lang, "kind" to kind, "sort" to st.sort, "page" to p,
                "genre" to st.genre?.str("value"), "country" to st.country?.str("value"), "language" to st.language?.str("value"),
                "dubs" to if (st.dubsOnly) 1 else null)
            if (f != st.filters) return // the filters changed while this loaded
            st.cards = (if (p == 1) emptyList() else st.cards.orEmpty()) + d.arr("cards")
            if (st.genres.isEmpty()) st.genres = d.arr("genres")
            if (st.countries.isEmpty()) st.countries = d.arr("countries")
            if (st.languages.isEmpty()) st.languages = d.arr("languages")
            st.more = d.bool("more"); st.page = p; st.error = ""; st.loadedFor = f
        } catch (e: Exception) {
            st.error = e.message ?: e.javaClass.simpleName
        }
    }
    // Load when the filters change; coming back from a title they haven't,
    // and the list is shown as it was.
    LaunchedEffect(st.key, st.filters) {
        if (st.loadedFor != st.filters) {
            st.cards = null; st.opened = -1; st.grid = LazyGridState()
            load(1)
        }
    }
    InfiniteLoad(st.grid, st.cards?.size ?: 0, st.more) { load(st.page + 1) }

    if (picking != "") {
        val opts = when (picking) { "genre" -> st.genres; "country" -> st.countries; else -> st.languages }
        fun pick(o: JsonObject?) {
            when (picking) { "genre" -> st.genre = o; "country" -> st.country = o; else -> { st.language = o; st.dubsOnly = false } }
            picking = ""
        }
        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            Text(when (picking) { "genre" -> tr(s.en, "ジャンル", "Genre"); "country" -> tr(s.en, "国・地域", "Country"); else -> tr(s.en, "言語", "Language") },
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
            if (opts.isEmpty()) Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
            LazyVerticalGrid(GridCells.Adaptive(220.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedButton(onClick = { pick(null) }) { Text(tr(s.en, "すべて", "All")) } }
                items(opts) { o ->
                    OutlinedButton(onClick = { pick(o) }) {
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
            items(sorts) { (k, label) -> if (k == st.sort) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { st.sort = k }) { Text(label) } }
            item { OutlinedButton(onClick = { picking = "genre" }) { Text(st.genre?.str("name") ?: tr(s.en, "ジャンル ▾", "Genre ▾")) } }
            item { OutlinedButton(onClick = { picking = "country" }) { Text(st.country?.str("name") ?: tr(s.en, "国・地域 ▾", "Country ▾")) } }
            // English catalog only (Filmarks has no language data); shown at
            // once, its choices fill in with the first page
            if (s.en) item { OutlinedButton(onClick = { picking = "language" }) { Text(st.language?.str("name") ?: "Language ▾") } }
            if (st.language != null) item {
                val label = tr(s.en, "吹替のみ", "Dubs only")
                if (st.dubsOnly) Button(onClick = { st.dubsOnly = false }) { Text("✓ $label") } else OutlinedButton(onClick = { st.dubsOnly = true }) { Text(label) }
            }
        }
        val cards = st.cards
        when {
            st.error != "" -> Text(st.error, color = Palette.red, modifier = Modifier.padding(48.dp))
            cards == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> {
                val back = remember { FocusRequester() }
                LazyVerticalGrid(GridCells.Adaptive(150.dp), state = st.grid, contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(cards) { i, c ->
                        PosterCard(s, c, if (i == st.opened) Modifier.focusRequester(back) else Modifier) { st.opened = i; onOpen(c.str("path")) }
                    }
                }
                // back from a title: focus the card that was opened
                LaunchedEffect(Unit) { if (st.opened >= 0) runCatching { back.requestFocus() } }
            }
        }
    }
}
