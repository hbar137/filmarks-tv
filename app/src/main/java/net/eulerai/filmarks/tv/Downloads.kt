package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The seedbox downloads as the website's Downloads page: posters, download
 * progress (refreshed while something downloads), seeding time and ratio,
 * and Delete once AvistaZ's 72 h / ratio 1 rule allows it.
 */
@Composable
fun DownloadsScreen(s: Settings, onOpen: (String) -> Unit) {
    var items by remember(s) { mutableStateOf<List<JsonObject>?>(null) }
    var error by remember(s) { mutableStateOf("") }
    var armed by remember { mutableStateOf("") } // the hash whose Delete was pressed once
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        try {
            items = api.get("/app/downloads", "lang" to s.lang).arr("downloads")
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    LaunchedEffect(s) {
        load()
        while (items.orEmpty().any { !it.bool("done") && it.str("error") == "" }) { delay(5000); load() }
    }
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Text(tr(s.en, "ダウンロード", "Downloads"), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text,
            modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        when {
            error != "" -> Text(error, color = Palette.red, modifier = Modifier.padding(48.dp))
            items == null -> Text(tr(s.en, "読み込み中…", "Loading…"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            items!!.isEmpty() -> Text(tr(s.en, "まだダウンロードはありません", "No downloads yet"), color = Palette.muted, modifier = Modifier.padding(48.dp))
            else -> LazyVerticalGrid(GridCells.Adaptive(170.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                items(items!!) { d ->
                    val note = when {
                        d.str("error") != "" -> d.str("error")
                        !d.bool("done") -> tr(s.en, "ダウンロード中 ", "Downloading ") + "%.0f%%".format(d.dbl("progress") * 100)
                        else -> "${d.str("quality")} · %.1f GB".format(d.long("size_bytes") / 1e9)
                    }
                    Column(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PosterCard(s, JsonObject(d + mapOf("note" to JsonPrimitive(note)))) { if (d.str("path") != "") onOpen(d.str("path")) }
                        if (d.has("ratio")) {
                            val h = d.long("seeding_time") / 3600
                            Text(tr(s.en, "シード ${h / 24}日${h % 24}時間 · 比率 ", "Seeding ${h / 24} d ${h % 24} h · ratio ") + "%.2f".format(d.dbl("ratio")),
                                color = Palette.muted, fontSize = 11.sp)
                            val left = d.long("delete_left")
                            val hash = d.str("hash")
                            if (left > 0) {
                                Text(tr(s.en, "削除まで ${left / 86400}日${left % 86400 / 3600}時間", "Delete in ${left / 86400} d ${left % 86400 / 3600} h"),
                                    color = Palette.muted, fontSize = 11.sp)
                            } else {
                                OutlinedButton(onClick = {
                                    if (armed != hash) { armed = hash; return@OutlinedButton }
                                    scope.launch {
                                        runCatching { api.post("/app/downloads/$hash/delete", buildJsonObject { }) }
                                            .onFailure { error = it.message ?: "error" }
                                        armed = ""; load()
                                    }
                                }) { Text(if (armed == hash) tr(s.en, "ファイルごと削除？", "Delete files?") else tr(s.en, "削除", "Delete"), fontSize = 12.sp) }
                            }
                        } else if (d.bool("gone")) {
                            Text(tr(s.en, "シードボックスにありません", "Not on the seedbox"), color = Palette.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun JsonObject.has(k: String) = this[k] != null
