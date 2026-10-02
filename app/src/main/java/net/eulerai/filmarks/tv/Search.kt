package net.eulerai.filmarks.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Search (the website's: titles in both catalogs, people). The TV keyboard
 * has a microphone key for voice.
 */
@Composable
fun SearchScreen(s: Settings, kind: String, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<HomeRow>?>(null) }
    var people by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val api = remember(s) { Api(s) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go() = scope.launch {
        if (q.isBlank()) return@launch
        status = tr(s.en, "検索中…", "Searching…")
        try {
            val d = api.get("/app/search", "q" to q.trim(), "lang" to s.lang, "kind" to kind)
            rows = d.arr("rows").map { HomeRow(it.str("title"), it.arr("cards")) }
            people = d.arr("people")
            status = if (rows!!.isEmpty() && people.isEmpty()) tr(s.en, "見つかりません", "Nothing found") else ""
        } catch (e: Exception) {
            status = e.message ?: "error"
        }
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
        focusedBorderColor = Palette.gold, unfocusedBorderColor = Palette.line, cursorColor = Palette.gold,
        focusedLabelColor = Palette.gold, unfocusedLabelColor = Palette.muted,
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, colors = colors,
                    label = { androidx.compose.material3.Text(tr(s.en, "作品・人物を検索", "Search titles and people")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.width(720.dp).focusRequester(focus))
                if (status != "") Text(status, color = Palette.muted)
            }
        }
        if (people.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(s.en, "人物", "People"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.text, modifier = Modifier.padding(start = 48.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(people) { p -> OutlinedButton(onClick = { onOpen(p.str("path")) }) { Text(p.str("name")) } }
                }
            }
        }
        rows?.let { rs -> items(rs) { row -> PosterRowOf(s, row, onOpen) } }
    }
}
