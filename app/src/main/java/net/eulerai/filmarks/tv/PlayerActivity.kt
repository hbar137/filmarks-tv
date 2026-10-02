package net.eulerai.filmarks.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

        player = ExoPlayer.Builder(this).build()
        val view = PlayerView(this).apply { this.player = this@PlayerActivity.player; keepScreenOn = true }
        setContentView(view)
        // Back first hides the controls; only with them hidden does it leave the film
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isControllerFullyVisible) view.hideController() else finish()
            }
        })
        player.setMediaItem(MediaItem.Builder().setUri(url)
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
        private val saves = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
