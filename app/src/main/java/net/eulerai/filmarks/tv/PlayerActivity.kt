package net.eulerai.filmarks.tv

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plays one stream with ExoPlayer (hardware decoding; Dolby/DTS go to the
 * receiver untouched where it takes them), from the saved position, and
 * saves the position every 10 s and on exit — the web player and Kodi
 * share these resume points.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var api: Api? = null
    private val tick = Handler(Looper.getMainLooper())
    private var timeBar: DefaultTimeBar? = null
    private lateinit var progressPath: String
    private lateinit var progressBody: JsonObject

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: return finish()
        val title = intent.getStringExtra("title") ?: ""
        val start = intent.getLongExtra("start", 0)
        progressPath = intent.getStringExtra("progressPath") ?: "/progress"
        progressBody = Api.json.parseToJsonElement(intent.getStringExtra("progressBody") ?: "{}") as JsonObject
        lifecycleScope.launch { api = Api(SettingsStore(applicationContext).settings.first()) }

        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(SEEK_MS).setSeekForwardIncrementMs(SEEK_MS).build()
        val view = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            keepScreenOn = true
            setBackgroundColor(Color.BLACK) // letterbox bars
            setShutterBackgroundColor(Color.BLACK)
            setShowSubtitleButton(true) // hidden by default: the file's own subtitles and the online ones
            // the bar's default step is 1/20 of the film (minutes): 10 s per press, held = continuous
            timeBar = findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.also { it.setKeyTimeIncrement(SEEK_MS) }
        }
        setContentView(view)
        // Back first hides the controls; only with them hidden does it leave the film
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isControllerFullyVisible) view.hideController() else finish()
            }
        })
        val subs = (Api.json.parseToJsonElement(intent.getStringExtra("subs") ?: "[]") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .map {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.str("url")))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage(it.str("lang")).setLabel(it.str("label")).build()
            }
        player.setMediaItem(MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        if (start > 60) {
            player.seekTo(start * 1000)
            Toast.makeText(this, "▶ %d:%02d:%02d".format(start / 3600, start / 60 % 60, start % 60), Toast.LENGTH_SHORT).show()
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { save(); finish() }
            }
        })
        player.prepare()
        player.playWhenReady = true
        tick.post(object : Runnable {
            override fun run() { save(); tick.postDelayed(this, 10_000) }
        })
    }

    // Holding left/right on the bar speeds up: 10 s steps, then 30 s, 1 min, 2 min
    // (the remote repeats a held key ~20 times a second).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val r = event.repeatCount
            timeBar?.setKeyTimeIncrement(when {
                r < 10 -> SEEK_MS
                r < 30 -> 30_000L
                r < 60 -> 60_000L
                else -> 120_000L
            })
        }
        return super.dispatchKeyEvent(event)
    }

    private fun save() {
        val a = api ?: return
        val dur = player.duration
        if (dur <= 0) return
        val body = JsonObject(progressBody + mapOf(
            "position" to JsonPrimitive(player.currentPosition / 1000.0),
            "duration" to JsonPrimitive(dur / 1000.0)))
        // not the activity's scope: the save on exit must outlive it
        saves.launch { runCatching { a.post(progressPath, body) } }
    }

    override fun onStop() {
        super.onStop()
        save()
        player.pause()
    }

    companion object {
        private const val SEEK_MS = 10_000L
        private val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
