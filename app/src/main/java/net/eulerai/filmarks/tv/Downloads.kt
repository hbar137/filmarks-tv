package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The seedbox downloads as a poster grid (the website's Downloads page); a card opens its title. */
@Composable
fun DownloadsScreen(s: Settings, onOpen: (String) -> Unit) {
    var items by remember(s) { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember(s) { mutableStateOf("") }
    LaunchedEffect(s) {
        try {
            items = Api(s).get("/seedbox", "lang" to s.lang).arr("downloads").filter { it.str("path") != "" }.map {
                // the card's line under the title: quality and size
                JsonObject(it + mapOf("note" to JsonPrimitive("${it.str("quality")} · %.1f GB".format(it.long("size_bytes") / 1e9))))
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Text(tr(s.en, "ダウンロード", "Downloads"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        when {
            error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
            items == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            items!!.isEmpty() -> Text(tr(s.en, "まだダウンロードはありません", "No downloads yet"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(items!!) { c -> PosterCard(s, c) { onOpen(c.str("path")) } }
            }
        }
    }
}
