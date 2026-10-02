package net.eulerai.filmarks.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import kotlinx.serialization.json.jsonArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setContent {
            FilmarksTheme {
                val settings by store.settings.collectAsState(initial = null)
                Box(Modifier.fillMaxSize().background(Palette.bg)) {
                    settings?.let { s ->
                        var editing by remember { mutableStateOf(!s.ready) }
                        val scope = rememberCoroutineScope()
                        if (editing) {
                            SetupScreen(s) { scope.launch { store.save(it); editing = false } }
                        } else {
                            StatusScreen(s, onSettings = { editing = true },
                                onLang = { scope.launch { store.save(s.copy(lang = if (s.en) "ja" else "en")) } })
                        }
                    }
                }
            }
        }
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

/** Step 1's home: proves the connection (ping + the seedbox list). */
@Composable
fun StatusScreen(s: Settings, onSettings: () -> Unit, onLang: () -> Unit) {
    var status by remember(s) { mutableStateOf(tr(s.en, "接続中…", "Connecting…")) }
    LaunchedEffect(s) {
        status = try {
            val api = Api(s)
            api.get("/ping")
            val n = api.get("/seedbox")["downloads"]?.jsonArray?.size ?: 0
            tr(s.en, "接続しました · ダウンロード $n 件", "Connected · $n downloads")
        } catch (e: ApiException) {
            if (e.code == 401) tr(s.en, "パスワードが違います", "Wrong password") else "${e.code}: ${e.message}"
        } catch (e: Exception) {
            tr(s.en, "接続できません: ", "Can't connect: ") + (e.message ?: e.javaClass.simpleName)
        }
    }
    Column(Modifier.padding(64.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row {
            Text("FILMARKS ", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.text)
            Text("ARCHIVE", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.gold)
        }
        Text(status, style = MaterialTheme.typography.titleLarge, color = Palette.text)
        Text(s.server, color = Palette.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onLang) { Text(if (s.en) "日本語" else "English") }
            OutlinedButton(onClick = onSettings) { Text(tr(s.en, "設定", "Settings")) }
        }
        Text("v" + BuildConfig.VERSION_NAME, color = Palette.muted, fontSize = 14.sp)
    }
}
