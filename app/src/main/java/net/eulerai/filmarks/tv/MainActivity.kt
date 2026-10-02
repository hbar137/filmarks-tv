package net.eulerai.filmarks.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setContent {
            FilmarksTheme {
                val settings by store.settings.collectAsState(initial = null)
                Box(Modifier.fillMaxSize().background(Palette.bg)) {
                    settings?.let { s -> App(s, store) }
                }
            }
        }
    }

    fun play(r: PlayRequest) {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", r.url).putExtra("title", r.title).putExtra("start", r.startSec)
            .putExtra("progressPath", r.progressPath).putExtra("progressBody", r.progressBody.toString()))
    }
}

/** Home → title pages, as a stack the remote's Back pops. */
@Composable
private fun App(s: Settings, store: SettingsStore) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as MainActivity
    var editing by remember { mutableStateOf(!s.ready) }
    var kind by remember { mutableStateOf("movie") }
    var stack by remember { mutableStateOf(listOf<String>()) }
    BackHandler(enabled = editing && s.ready || stack.isNotEmpty()) {
        if (editing) editing = false else stack = stack.dropLast(1)
    }
    when {
        editing -> SetupScreen(s) { scope.launch { store.save(it); editing = false } }
        stack.isNotEmpty() -> TitleScreen(s, stack.last()) { activity.play(it) }
        else -> HomeScreen(s, kind, onKind = { kind = it },
            onLang = { scope.launch { store.save(s.copy(lang = if (s.en) "ja" else "en")) } },
            onSettings = { editing = true }, onOpen = { stack = stack + it })
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
}

/**
 * On a TV a text field keeps Up/Down for its own cursor, so the remote
 * could never leave it: Up/Down move focus between the form's rows instead.
 */
@Composable
private fun Modifier.dpadRows(): Modifier {
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (it.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/** Server address and password (the Kodi password). */
@Composable
fun SetupScreen(s: Settings, onSave: (Settings) -> Unit) {
    var server by remember { mutableStateOf(s.server) }
    var password by remember { mutableStateOf(s.password) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
        focusedBorderColor = Palette.gold, unfocusedBorderColor = Palette.line,
        focusedLabelColor = Palette.gold, unfocusedLabelColor = Palette.muted, cursorColor = Palette.gold,
    )
    Column(Modifier.padding(64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Title(tr(s.en, "サーバー設定", "Server"))
        OutlinedTextField(server, { server = it }, label = { androidx.compose.material3.Text(tr(s.en, "サーバー", "Server")) },
            singleLine = true, colors = colors, modifier = Modifier.width(640.dp).dpadRows(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(password, { password = it }, label = { androidx.compose.material3.Text(tr(s.en, "パスワード", "Password")) },
            singleLine = true, colors = colors, modifier = Modifier.width(640.dp).dpadRows(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(onClick = { onSave(s.copy(server = server, password = password)) }, modifier = Modifier.dpadRows()) {
            Text(tr(s.en, "保存して接続", "Save and connect"))
        }
    }
}
