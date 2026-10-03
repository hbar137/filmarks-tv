package net.eulerai.filmarks.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Report.install(this)
        deepLink.value = intent?.data?.getQueryParameter("path")
        val store = SettingsStore(applicationContext)
        setContent {
            FilmarksTheme {
                val settings by store.settings.collectAsState(initial = null)
                Box(Modifier.fillMaxSize().background(Palette.bg)) {
                    settings?.let { s -> App(s, store, deepLink) }
                }
            }
        }
    }

    // a title to open, from the Google TV home's Continue watching (filmarkstv://open?path=)
    val deepLink = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink.value = intent.data?.getQueryParameter("path")
    }

    // counts returns from the player: title pages reload their ✓ marks
    val playerReturns = mutableIntStateOf(0)
    private val playerResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        playerReturns.intValue++
    }

    fun play(r: PlayRequest, en: Boolean) {
        playerResult.launch(Intent(this, PlayerActivity::class.java)
            .putExtra("url", r.url).putExtra("title", r.title).putExtra("start", r.startSec).putExtra("en", en)
            .putExtra("progressPath", r.progressPath).putExtra("progressBody", r.progressBody.toString())
            .putExtra("subs", JsonArray(r.subs.items.map { it.json() }).toString())
            .putExtra("subChoice", r.subs.choice?.toString()).putExtra("subsBase", r.subsBase)
            .putExtra("scrobblePath", r.scrobblePath).putExtra("scrobbleBody", r.scrobbleBody?.toString())
            .putExtra("introStart", r.introStart).putExtra("introEnd", r.introEnd).putExtra("outroStart", r.outroStart)
            .putExtra("chain", r.chain?.toString())
            .putExtra("audioLang", r.audioLang).putExtra("path", r.path).putExtra("poster", r.poster))
    }
}

/** Home → pages and title pages, as a stack the remote's Back pops. */
@Composable
private fun App(s: Settings, store: SettingsStore, deepLink: MutableState<String?>) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as MainActivity
    var editing by remember { mutableStateOf(!s.ready) }
    var kind by remember { mutableStateOf("movie") }
    var stack by remember { mutableStateOf(listOf<String>()) }
    var update by remember { mutableStateOf("") }
    LaunchedEffect(deepLink.value) {
        deepLink.value?.takeIf { it.startsWith("/") }?.let { stack = listOf(it) }
        deepLink.value = null
    }
    // TV apps stay running for days: check when the app comes back to the
    // front, and every 30 min while it's open (once at start missed releases)
    LaunchedEffect(Unit) {
        while (true) { update = Update.check(); delay(30 * 60 * 1000L) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { update = Update.check() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = editing && s.ready || stack.isNotEmpty()) {
        if (editing) editing = false else stack = stack.dropLast(1)
    }
    val open: (String) -> Unit = { if (it != "") stack = stack + it }
    when (val top = stack.lastOrNull()) {
        null -> if (editing) SetupScreen(s) { scope.launch { store.save(it); editing = false } }
            else HomeScreen(s, kind, onKind = { kind = it },
                onLang = { scope.launch { store.save(s.copy(lang = if (s.en) "ja" else "en")) } },
                onSettings = { editing = true }, onPage = open, onOpen = open,
                update = update, onUpdate = {
                    scope.launch {
                        Toast.makeText(activity, tr(s.en, "ダウンロード中…", "Downloading…"), Toast.LENGTH_SHORT).show()
                        runCatching { Update.install(activity) }.onFailure {
                            Toast.makeText(activity, it.message ?: "update failed", Toast.LENGTH_LONG).show()
                        }
                    }
                })
        PAGE_DOWNLOADS -> DownloadsScreen(s, open)
        PAGE_SEARCH -> SearchScreen(s, kind, open)
        PAGE_WATCHLIST -> ListScreen(s, "watchlist", open)
        PAGE_HISTORY -> ListScreen(s, "history", open)
        PAGE_BROWSE -> BrowseScreen(s, kind, open)
        else -> if (top.startsWith("/people/") || top.startsWith("/en/person/")) PersonScreen(s, top, open)
                else TitleScreen(s, top, { activity.play(it, s.en) }, open, activity.playerReturns.intValue)
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
        Text("Filmarks TV " + BuildConfig.VERSION_NAME, color = Palette.muted, fontSize = 14.sp)
    }
}
