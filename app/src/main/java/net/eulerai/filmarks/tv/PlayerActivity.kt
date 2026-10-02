package net.eulerai.filmarks.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Plays one stream with ExoPlayer (hardware decoding; Dolby/DTS go to the
 * receiver untouched where it takes them). It offers to resume, saves the
 * position every 10 s (shared with the web player and Kodi), tells Trakt
 * what is playing, offers to skip an anime's intro, and counts down to the
 * next episode at the end.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var api: Api? = null
    private var en = false
    private val tick = Handler(Looper.getMainLooper())
    private var timeBar: DefaultTimeBar? = null
    private lateinit var progressPath: String
    private lateinit var progressBody: JsonObject
    private var scrobblePath = ""
    private var scrobbleBody: JsonObject? = null
    private var introStart = -1.0
    private var introEnd = -1.0
    private var nextLabel = ""
    private lateinit var skip: Button
    private lateinit var nextBox: LinearLayout
    private lateinit var nextText: TextView
    private var countdown = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: return finish()
        val title = intent.getStringExtra("title") ?: ""
        val start = intent.getLongExtra("start", 0)
        en = intent.getBooleanExtra("en", false)
        progressPath = intent.getStringExtra("progressPath") ?: "/progress"
        progressBody = parse(intent.getStringExtra("progressBody")) ?: JsonObject(emptyMap())
        scrobblePath = intent.getStringExtra("scrobblePath") ?: ""
        scrobbleBody = parse(intent.getStringExtra("scrobbleBody"))
        introStart = intent.getDoubleExtra("introStart", -1.0)
        introEnd = intent.getDoubleExtra("introEnd", -1.0)
        nextLabel = intent.getStringExtra("nextLabel") ?: ""
        lifecycleScope.launch { api = Api(SettingsStore(applicationContext).settings.first()) }

        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(SEEK_MS).setSeekForwardIncrementMs(SEEK_MS).build()
        view = PlayerView(this).apply {
            this.player = this@PlayerActivity.player
            keepScreenOn = true
            setBackgroundColor(Color.BLACK) // letterbox bars
            setShutterBackgroundColor(Color.BLACK)
            setShowSubtitleButton(true) // hidden by default: the file's own subtitles and the online ones
            // the bar's default step is 1/20 of the film (minutes): 10 s per press, held = faster
            timeBar = findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.also { it.setKeyTimeIncrement(SEEK_MS) }
        }
        setContentView(buildLayout())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    countdown >= 0 -> { countdown = -1; finish() } // cancel the next episode
                    view.isControllerFullyVisible -> view.hideController()
                    else -> finish()
                }
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
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) scrobble("start") else if (player.playbackState == Player.STATE_READY) scrobble("pause")
            }
        })
        player.prepare()
        if (start > 60) askResume(start) else player.playWhenReady = true
        tick.post(object : Runnable {
            var n = 0
            override fun run() {
                if (n++ % 20 == 0) save()
                updateSkip()
                tick.postDelayed(this, 500)
            }
        })
    }

    private fun parse(s: String?): JsonObject? = s?.let { runCatching { Api.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** The player, with the skip-intro button and the next-episode panel over it. */
    private fun buildLayout(): View {
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        skip = Button(this).apply {
            text = tr(en, "イントロをスキップ ▸", "Skip intro ▸")
            visibility = View.GONE
            setOnClickListener { player.seekTo((introEnd * 1000).toLong()); visibility = View.GONE }
        }
        root.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 64, 140) })
        nextText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f }
        val now = Button(this).apply {
            text = tr(en, "今すぐ再生", "Play now")
            setOnClickListener { playNext() }
        }
        nextBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC13131A.toInt())
            setPadding(40, 32, 40, 32)
            visibility = View.GONE
            addView(nextText)
            addView(now)
        }
        root.addView(nextBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 64, 64) })
        return root
    }

    /** "Resume at 1:02:03 / Start over", when there is a saved position. */
    private fun askResume(start: Long) {
        val at = "%d:%02d:%02d".format(start / 3600, start / 60 % 60, start % 60)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr(en, "続きから再生しますか？", "Resume?"))
            .setPositiveButton(tr(en, "続きから ($at)", "Resume ($at)")) { _, _ -> player.seekTo(start * 1000); player.playWhenReady = true }
            .setNegativeButton(tr(en, "最初から", "Start over")) { _, _ -> player.playWhenReady = true }
            .setOnCancelListener { player.playWhenReady = true }
            .show()
    }

    private fun updateSkip() {
        if (introEnd <= 0) return
        val pos = player.currentPosition / 1000.0
        val show = pos >= introStart && pos < introEnd - 2
        if (show && skip.visibility != View.VISIBLE) {
            skip.visibility = View.VISIBLE
            skip.requestFocus()
        } else if (!show && skip.visibility == View.VISIBLE) {
            skip.visibility = View.GONE
        }
    }

    private fun ended() {
        save()
        scrobble("stop")
        if (nextLabel == "") return finish()
        countdown = 8
        nextBox.visibility = View.VISIBLE
        nextBox.getChildAt(1).requestFocus()
        tick.post(object : Runnable {
            override fun run() {
                if (countdown < 0) return
                nextText.text = tr(en, "次: $nextLabel（${countdown}秒）", "Next: $nextLabel (${countdown}s)")
                if (countdown-- == 0) playNext() else tick.postDelayed(this, 1000)
            }
        })
    }

    private fun playNext() {
        countdown = -1
        setResult(RESULT_OK, Intent().putExtra("playNext", true))
        finish()
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
        background.launch { runCatching { a.post(progressPath, body) } }
    }

    /** Trakt scrobble (the server sends it on; Trakt records the play at stop ≥ 80 %). */
    private fun scrobble(action: String) {
        val a = api ?: return
        val base = scrobbleBody ?: return
        if (scrobblePath == "") return
        val dur = player.duration
        val pct = if (dur > 0) player.currentPosition * 100.0 / dur else 0.0
        val body = JsonObject(base + mapOf("action" to JsonPrimitive(action), "progress" to JsonPrimitive(pct)))
        background.launch { runCatching { a.post(scrobblePath, body) } }
    }

    companion object {
        private const val SEEK_MS = 10_000L
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStop() {
        super.onStop()
        save()
        if (player.playbackState != Player.STATE_ENDED) scrobble("stop")
        player.pause()
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
