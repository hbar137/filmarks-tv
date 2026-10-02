package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.serialization.json.JsonObject

/** A person's page: their titles (Japanese /people/:id, English /en/person/:id). */
@Composable
fun PersonScreen(s: Settings, path: String, onOpen: (String) -> Unit) {
    var d by remember(path) { mutableStateOf<JsonObject?>(null) }
    var error by remember(path) { mutableStateOf("") }
    LaunchedEffect(path) {
        val id = path.substringAfterLast('/')
        try {
            d = Api(s).get(if (path.startsWith("/en/")) "/app/en-person/$id" else "/app/person/$id", "lang" to s.lang)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    val p = d
    if (p == null) {
        Text(if (error != "") error else tr(s.en, "読み込み中…", "Loading…"), color = if (error != "") Palette.red else Palette.muted, modifier = Modifier.padding(48.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (p.str("photo") != "") AsyncImage(p.str("photo"), null, contentScale = ContentScale.Crop, modifier = Modifier.size(140.dp, 210.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.str("name"), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.text)
                    if (p.str("bio") != "") Text(p.str("bio"), color = Palette.muted, maxLines = 6, modifier = Modifier.width(900.dp))
                }
            }
        }
        items(p.arr("rows")) { r -> PosterRowOf(s, HomeRow(r.str("title"), r.arr("cards")), onOpen) }
    }
}
