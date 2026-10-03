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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    private var plays: Plays? = null
    private var chain: JsonObject? = null
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
    private var outroStart = -1.0
    private lateinit var nextBtn: Button
    private lateinit var skip: Button
    private lateinit var nextBox: LinearLayout
    private lateinit var nextText: TextView
    private var countdown = -1
    private var subsJson: List<JsonObject> = emptyList()
    private var url = ""
    private var title = ""
    // Subtitle timing, per subtitle (id -> [shift, scale]: a cue at t shows at
    // t*scale + shift), kept on the server per file with the one picked
    private var subsBase = ""
    private val timing = mutableMapOf<String, DoubleArray>()
    private var restoreSub: String? = null // selected once its track shows up
    private var lastSub: String? = null
    private val anchors = mutableMapOf<String, DoubleArray>() // id -> the first "sync to this line" [cue time, video time]
    private val cueCache = mutableMapOf<String, List<Pair<Double, String>>>()
    private var aligning: Job? = null
    private lateinit var timingRow: LinearLayout
    private lateinit var timingLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url") ?: return finish()
        title = intent.getStringExtra("title") ?: ""
        val start = intent.getLongExtra("start", 0)
        en = intent.getBooleanExtra("en", false)
        progressPath = intent.getStringExtra("progressPath") ?: "/progress"
        progressBody = parse(intent.getStringExtra("progressBody")) ?: JsonObject(emptyMap())
        scrobblePath = intent.getStringExtra("scrobblePath") ?: ""
        scrobbleBody = parse(intent.getStringExtra("scrobbleBody"))
        introStart = intent.getDoubleExtra("introStart", -1.0)
        introEnd = intent.getDoubleExtra("introEnd", -1.0)
        chain = parse(intent.getStringExtra("chain"))
        nextLabel = nextLabel(en, chain)
        outroStart = intent.getDoubleExtra("outroStart", -1.0)
        lifecycleScope.launch {
            val st = SettingsStore(applicationContext).settings.first()
            api = Api(st).also { plays = Plays(st, it) }
        }

        // the platform's decoders first (and passthrough to a receiver); FFmpeg
        // decodes what neither handles (DTS, TrueHD, …), instead of silence
        val renderers = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(this, renderers).setSeekBackIncrementMs(SEEK_MS).setSeekForwardIncrementMs(SEEK_MS).build()
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

        setSubs((Api.json.parseToJsonElement(intent.getStringExtra("subs") ?: "[]") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }, parse(intent.getStringExtra("subChoice")), intent.getStringExtra("subsBase") ?: "")
        player.setMediaItem(mediaItem())
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onPlayerError(error: PlaybackException) {
                Report.send(this@PlayerActivity, "playback",
                    "${error.errorCodeName}: ${error.message} · ${title} · ${Uri.parse(url).host} · cause ${error.cause}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) scrobble("start") else if (player.playbackState == Player.STATE_READY) scrobble("pause")
            }
        })
        // the title's own language for audio (Japanese for a Japanese film); the subtitle
        // language last chosen (kept on the device) for subtitles
        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            intent.getStringExtra("audioLang")?.takeIf { it != "" }?.let { setPreferredAudioLanguage(it) }
            prefs.getString("subLang", null)?.let { setPreferredTextLanguage(it) }
        }.build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (restoreSelection(tracks)) return
                val chosen = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.language
                if (chosen != null) prefs.edit().putString("subLang", chosen).apply()
                // a subtitle picked (or restored): remembered for this file
                val sel = selectedSub()
                if (sel?.str("id") != lastSub) {
                    lastSub = sel?.str("id")
                    if (sel != null) saveChoice(sel)
                    showTiming()
                }
            }
        })
        player.prepare()
        if (start > 60) askResume(start) else player.playWhenReady = true
        tick.post(object : Runnable {
            var n = 0
            override fun run() {
                if (n++ % 20 == 0) save()
                updateSkip()
                updateNext()
                tick.postDelayed(this, 500)
            }
        })
    }

    /** The stream with the subtitles, each retimed by the server (&shift=&scale=). */
    private fun mediaItem(): MediaItem = MediaItem.Builder().setUri(url)
        .setSubtitleConfigurations(subsJson.map {
            val t = timing[it.str("id")]
            val u = it.str("url") + if (t != null && (t[0] != 0.0 || t[1] != 1.0)) "&shift=${t[0]}&scale=${t[1]}" else ""
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(u)).setId(it.str("id"))
                .setMimeType(MimeTypes.TEXT_VTT).setLanguage(it.str("lang")).setLabel(it.str("label")).build()
        })
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    /** A file's subtitles and the pick saved for it (selected, with its timing, once its track loads). */
    private fun setSubs(items: List<JsonObject>, choice: JsonObject?, base: String) {
        subsJson = items
        subsBase = base
        timing.clear()
        anchors.clear()
        cueCache.clear()
        aligning?.cancel()
        lastSub = null
        restoreSub = null
        val id = choice?.str("id").orEmpty()
        if (items.any { it.str("id") == id }) {
            timing[id] = doubleArrayOf(choice!!.dbl("delay"), choice.dbl("scale").takeIf { it > 0 } ?: 1.0)
            restoreSub = id
        }
    }

    /** Selects restoreSub's track once it is there; true when it did. */
    private fun restoreSelection(tracks: Tracks): Boolean {
        val id = restoreSub ?: return false
        val label = subsJson.firstOrNull { it.str("id") == id }?.str("label") ?: return false
        val g = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).label == label } ?: return false
        restoreSub = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
        return true
    }

    /** The selected subtitle, when it is one of the server's (not the file's own). */
    private fun selectedSub(): JsonObject? {
        val f = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0) ?: return null
        return subsJson.firstOrNull { it.str("label") == f.label }
    }

    private fun timingOf(id: String) = timing.getOrPut(id) { doubleArrayOf(0.0, 1.0) }

    private fun saveChoice(sub: JsonObject) {
        val a = api ?: return
        if (subsBase == "") return
        val t = timingOf(sub.str("id"))
        val body = JsonObject(mapOf("id" to JsonPrimitive(sub.str("id")), "label" to JsonPrimitive(sub.str("label")),
            "lang" to JsonPrimitive(sub.str("lang")), "delay" to JsonPrimitive(t[0]), "scale" to JsonPrimitive(t[1])))
        background.launch { runCatching { a.post("$subsBase/subs/choice", body) } }
    }

    private fun showTiming(status: String = "") {
        val sel = selectedSub()
        timingLabel.text = when {
            status != "" -> status
            sel == null -> tr(en, "字幕", "Subtitles")
            else -> {
                val t = timingOf(sel.str("id"))
                tr(en, "字幕 ", "Subtitles ") + "%+.1f".format(t[0]) + tr(en, "秒", " s") +
                    if (t[1] != 1.0) " ×%.4f".format(t[1]) else ""
            }
        }
    }

    /** The selected subtitle, or a hint to pick one first. */
    private fun needSub(): JsonObject? = selectedSub() ?: run {
        Toast.makeText(this, tr(en, "先に字幕（オンライン）を選んでください", "Pick an online subtitle first"), Toast.LENGTH_LONG).show()
        null
    }

    /** Reloads the subtitles with sub's new timing, keeping the position and the selection, and saves it. */
    private fun applyTiming(sub: JsonObject) {
        restoreSub = sub.str("id")
        val pos = player.currentPosition
        val playing = player.playWhenReady
        player.setMediaItem(mediaItem(), pos)
        player.prepare()
        player.playWhenReady = playing
        saveChoice(sub)
        showTiming()
    }

    /** The −0.5 / ±0 / +0.5 buttons (±0 also undoes a scale). */
    private fun shiftSubs(by: Double) {
        val sub = needSub() ?: return
        val t = timingOf(sub.str("id"))
        if (by == 0.0) {
            t[0] = 0.0; t[1] = 1.0
            anchors.remove(sub.str("id"))
        } else {
            t[0] = Math.round((t[0] + by) * 10) / 10.0
        }
        applyTiming(sub)
    }

    /**
     * "Sync to this line": pressed as a line starts, it pauses and lists the
     * subtitle's lines around that moment; the one picked is moved there. A
     * second pick at least 5 minutes from the first also fixes the speed
     * (a frame-rate difference: the lines drift further off over time).
     */
    private fun syncLine() {
        val sub = needSub() ?: return
        val id = sub.str("id")
        val v = player.currentPosition / 1000.0 - 0.3 // the press comes a moment after the line starts
        player.pause()
        lifecycleScope.launch {
            val cues = cueCache[id] ?: runCatching { withContext(Dispatchers.IO) { parseCues(java.net.URL(sub.str("url")).readText()) } }
                .getOrNull()?.also { cueCache[id] = it }
            val t = timingOf(id)
            val near = cues.orEmpty().filter { abs(it.first * t[1] + t[0] - v) <= 90 }
            if (near.isEmpty()) {
                Toast.makeText(this@PlayerActivity, tr(en, "この前後に台詞がありません", "No lines around here"), Toast.LENGTH_LONG).show()
                player.play()
                return@launch
            }
            val labels = near.map { (c, text) -> "%+.1f".format(c * t[1] + t[0] - v) + tr(en, "秒　", " s   ") + text }.toTypedArray()
            val d = AlertDialog.Builder(this@PlayerActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tr(en, "今始まった台詞は？", "Which line just started?"))
                .setItems(labels) { _, i -> anchor(sub, near[i].first, v) }
                .setOnDismissListener { player.play() }
                .show()
            val closest = near.indices.minByOrNull { abs(near[it].first * t[1] + t[0] - v) } ?: 0
            d.listView?.setSelection(closest)
        }
    }

    private fun anchor(sub: JsonObject, cue: Double, v: Double) {
        val id = sub.str("id")
        val t = timingOf(id)
        val a = anchors[id]
        var msg = ""
        if (a != null && abs(v - a[1]) > 300 && abs(cue - a[0]) > 60 && (v - a[1]) / (cue - a[0]) in 0.9..1.1) {
            t[1] = (v - a[1]) / (cue - a[0])
            msg = tr(en, "（速度も補正）", " (speed fixed too)")
        }
        t[0] = Math.round((v - t[1] * cue) * 100) / 100.0
        if (a == null) anchors[id] = doubleArrayOf(cue, v)
        applyTiming(sub)
        Toast.makeText(this, tr(en, "字幕を合わせました", "Subtitles synced") + msg, Toast.LENGTH_SHORT).show()
    }

    /** Auto-align on the server (the file's own subtitle track, else its audio: a few minutes). */
    private fun autoAlign() {
        val sub = needSub() ?: return
        val a = api ?: return
        val id = sub.str("id")
        aligning?.cancel()
        aligning = lifecycleScope.launch {
            val body = JsonObject(mapOf("id" to JsonPrimitive(id)))
            var st = runCatching { a.post("$subsBase/subs/align", body) }.getOrNull()
            while (st != null) {
                when (st.str("state")) {
                    "done" -> {
                        val t = timingOf(id)
                        t[0] = st.dbl("shift"); t[1] = st.dbl("scale").takeIf { it > 0 } ?: 1.0
                        anchors.remove(id)
                        applyTiming(sub)
                        Toast.makeText(this@PlayerActivity, tr(en, "字幕を自動調整しました", "Subtitles aligned") + " (%+.1f".format(t[0]) + tr(en, "秒)", " s)"), Toast.LENGTH_LONG).show()
                        aligning = null
                        return@launch
                    }
                    "failed", "none" -> break
                }
                showTiming(tr(en, "自動調整中… ", "Aligning… ") + "${st.long("pct")}%")
                delay(3000)
                st = runCatching { a.get("$subsBase/subs/align", "item" to id) }.getOrNull()
            }
            showTiming()
            Toast.makeText(this@PlayerActivity, tr(en, "自動調整できませんでした", "Couldn't align automatically") +
                (st?.str("error")?.takeIf { it != "" }?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
            aligning = null
        }
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
        nextBtn = Button(this).apply {
            text = tr(en, "次のエピソード ▶", "Next episode ▶")
            visibility = View.GONE
            setOnClickListener { save(); scrobble("stop"); playNext() }
        }
        root.addView(nextBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 64, 140) })
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
        timingLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; text = tr(en, "字幕", "Subtitles"); setPadding(0, 0, 16, 0) }
        timingRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xAA13131A.toInt())
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
            addView(timingLabel)
            for ((label, by) in listOf("−0.5" to -0.5, "±0" to 0.0, "+0.5" to 0.5)) {
                addView(Button(this@PlayerActivity).apply { text = label; setOnClickListener { shiftSubs(by) } })
            }
            addView(Button(this@PlayerActivity).apply { text = tr(en, "この台詞に合わせる", "Sync to this line"); setOnClickListener { syncLine() } })
            addView(Button(this@PlayerActivity).apply { text = tr(en, "自動調整", "Auto-align"); setOnClickListener { autoAlign() } })
        }
        root.addView(timingRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48, 64, 0) })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            timingRow.visibility = if (v == View.VISIBLE && subsJson.isNotEmpty()) View.VISIBLE else View.GONE
        })
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

    private fun updateNext() {
        if (nextLabel == "" || countdown >= 0) return
        val dur = player.duration
        if (dur <= 0) return
        val pos = player.currentPosition
        val from = if (outroStart > 0) (outroStart * 1000).toLong() else dur - 120_000
        val show = pos >= from && pos < dur
        if (show && nextBtn.visibility != View.VISIBLE) {
            nextBtn.visibility = View.VISIBLE
            nextBtn.requestFocus()
        } else if (!show && nextBtn.visibility == View.VISIBLE) {
            nextBtn.visibility = View.GONE
        }
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
        nextBtn.visibility = View.GONE
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

    /**
     * The next episode, in this player: fetched from the chain the title page
     * gave (so it works even if Android has closed the app behind the player),
     * and the chain moves on with it, for a whole run of episodes.
     */
    private fun playNext() {
        countdown = -1
        nextBox.visibility = View.GONE
        nextBtn.visibility = View.GONE
        val c = chain
        val p = plays
        if (c == null || p == null) return finish()
        nextText.text = tr(en, "次のエピソードを準備中…", "Getting the next episode…")
        lifecycleScope.launch {
            val r = runCatching { p.next(c) }.onFailure { Report.send(this@PlayerActivity, "next", it.toString()) }.getOrNull()
            if (r == null) finish() else load(r)
        }
    }

    /** Switches the player to another episode's stream, its saving and scrobbling with it. */
    private fun load(r: PlayRequest) {
        url = r.url
        title = r.title
        progressPath = r.progressPath
        progressBody = r.progressBody
        scrobblePath = r.scrobblePath
        scrobbleBody = r.scrobbleBody
        introStart = r.introStart
        introEnd = r.introEnd
        outroStart = r.outroStart
        setSubs(r.subs.items.map { it.json() }, r.subs.choice, r.subsBase)
        showTiming()
        chain = r.chain
        nextLabel = nextLabel(en, chain)
        player.setMediaItem(mediaItem())
        player.prepare()
        if (r.startSec > 60) player.seekTo(r.startSec * 1000)
        player.playWhenReady = true
        Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
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
        /** A WebVTT file's lines: start (s) and text. */
        fun parseCues(vtt: String): List<Pair<Double, String>> {
            val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")
            val out = mutableListOf<Pair<Double, String>>()
            val lines = vtt.replace("\r", "").split("\n")
            var i = 0
            while (i < lines.size) {
                val l = lines[i++]
                if (!l.contains("-->")) continue
                val m = time.find(l) ?: continue
                val (h, mi, se, ms) = m.destructured
                val start = (h.toIntOrNull() ?: 0) * 3600 + mi.toInt() * 60 + se.toInt() + ms.toInt() / 1000.0
                val text = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (text.isNotEmpty()) text.append(" / ")
                    text.append(lines[i++].replace(Regex("<[^>]+>"), "").trim())
                }
                out += start to text.toString()
            }
            return out
        }

        private const val SEEK_MS = 10_000L
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStop() {
        super.onStop()
        save()
        WatchNext.update(this, intent.getStringExtra("path") ?: "", title, intent.getStringExtra("poster") ?: "",
            player.currentPosition, player.duration, progressPath == "/progress-episode")
        if (player.playbackState != Player.STATE_ENDED) scrobble("stop")
        player.pause()
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
