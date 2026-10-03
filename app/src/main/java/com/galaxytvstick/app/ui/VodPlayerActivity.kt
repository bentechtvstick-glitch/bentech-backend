package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Episode
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.WatchProgress
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityVodPlayerBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Player Films, Séries ak Catch-up (tankou TiviMate Premium):
 * - ba tan ak kontwòl (OK), ◀ rekile 10 s, ▶ avanse 30 s
 * - kontinye kote w te rive a (pozisyon an sove chak 10 s)
 * - epizòd swivan an kòmanse otomatikman
 */
@OptIn(UnstableApi::class)
class VodPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind"            // "movie", "episode" oswa "catchup"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_URL = "url"
        const val EXTRA_KEY = "key"              // kle pwogrè a (egz: "m:123")
        const val EXTRA_POSTER = "poster"
        const val EXTRA_PARENT_ID = "parent_id"  // ID fim nan oswa seri a
        const val EXTRA_EPISODES = "episodes"    // JSON: tout epizòd sezon an
        const val EXTRA_EP_INDEX = "ep_index"
        const val EXTRA_FROM_START = "from_start"
        const val EXTRA_STREAM_ID = "stream_id"  // catch-up: chanèl la

        /** Lis epizòd yo pase isit la (pa nan Intent la: yon seri ki gen anpil epizòd ta depase limit 1 MB Intent yo). */
        @Volatile var pendingEpisodes: List<Episode> = emptyList()

        fun episodesJson(list: List<Episode>): String = JSONArray().apply {
            list.forEach {
                put(JSONObject().put("id", it.id).put("ext", it.ext).put("t", it.title).put("s", it.season)
                    .put("n", it.num).put("i", it.image ?: "").put("d", it.durationSecs))
            }
        }.toString()

        fun parseEpisodes(json: String?): List<Episode> {
            val arr = runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Episode(o.optString("id"), o.optInt("s"), o.optInt("n"), o.optString("t"), o.optString("ext", "mp4"), "",
                    o.optInt("d"), o.optString("i").takeIf { it.isNotBlank() })
            }
        }

        fun episodeLabel(e: Episode): String =
            "S%02d E%02d".format(e.season, e.num) + (if (e.title.isNotBlank()) " · ${e.title}" else "")
    }

    private data class Item(val key: String, val url: String, val title: String, val subtitle: String, val poster: String?)

    private lateinit var b: ActivityVodPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var history: WatchHistory
    private lateinit var overlay: OverlayController
    private var player: ExoPlayer? = null

    private var kind = "movie"
    private var items: List<Item> = emptyList()
    private var index = 0
    private var parentId = 0
    private var resumeNext = true
    private var triedHls = false

    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() { saveProgress(); handler.postDelayed(this, 10_000) }
    }
    private val uiTick = object : Runnable {
        override fun run() { updateNextBox(); handler.postDelayed(this, 1_000) }
    }
    private val hideSeekHint = Runnable { b.seekHint.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityVodPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        val account = prefs.account ?: run { finish(); return }
        api = XtreamApi(account)
        history = WatchHistory(this)

        kind = intent.getStringExtra(EXTRA_KIND) ?: "movie"
        parentId = intent.getIntExtra(EXTRA_PARENT_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val poster = intent.getStringExtra(EXTRA_POSTER)
        items = if (kind == "episode") {
            pendingEpisodes.ifEmpty { parseEpisodes(intent.getStringExtra(EXTRA_EPISODES)) }.map { e ->
                Item("e:${e.id}", api.episodeUrl(e), title, episodeLabel(e), e.image ?: poster)
            }
        } else {
            val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
            listOf(Item(intent.getStringExtra(EXTRA_KEY) ?: "x:$url", url, title, intent.getStringExtra(EXTRA_SUBTITLE).orEmpty(), poster))
        }
        if (items.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_EP_INDEX, 0).coerceIn(0, items.size - 1)
        resumeNext = !intent.getBooleanExtra(EXTRA_FROM_START, false)

        b.vodKind.text = getString(when (kind) { "episode" -> R.string.series; "catchup" -> R.string.catchup_label; else -> R.string.movies })
        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            b.titleBar.visibility = v
        })

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = false, showTicker = false,
            onConfig = { cfg -> if (cfg.isBlocking) player?.pause() },
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        overlay.start()
        play(index, resume = resumeNext)
        resumeNext = true // si kliyan an soti epi l tounen, kontinye kote l te ye a
        handler.postDelayed(saveTick, 10_000)
        handler.post(uiTick)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.let { index = it.currentMediaItemIndex.coerceIn(0, items.size - 1) }
        handler.removeCallbacksAndMessages(null)
        overlay.stop()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ Player

    private fun initPlayer() {
        if (player != null) return
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 90_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val dataSource = OkHttpDataSource.Factory(XtreamApi.http).setUserAgent(XtreamApi.USER_AGENT)
        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build().also { p ->
                p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                b.playerView.player = p
                p.addListener(listener)
            }
    }

    private fun mediaItem(it: Item): MediaItem = MediaItem.Builder().setMediaId(it.key).setUri(it.url).apply {
        if (it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()

    private fun play(i: Int, resume: Boolean) {
        val p = player ?: return
        index = i
        val prog = if (resume && kind != "catchup") history.get(items[i].key)?.takeIf { it.resumable } else null
        p.setMediaItems(items.map { mediaItem(it) }, i, prog?.positionMs ?: 0L)
        p.prepare()
        p.playWhenReady = true
        b.errorText.visibility = View.GONE
        updateTitle()
        if (prog != null) Toast.makeText(this, getString(R.string.vod_resumed_at, fmt(prog.positionMs)), Toast.LENGTH_SHORT).show()
        reportStatus()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            // Epizòd anvan an fini: make l konsa
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) markFinished(index)
            index = p.currentMediaItemIndex.coerceIn(0, items.size - 1)
            b.nextBox.visibility = View.GONE
            updateTitle()
            reportStatus()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                markFinished(index)
                finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val cur = items.getOrNull(index)
            // Catch-up: si .ts pa mache, eseye HLS (.m3u8)
            if (kind == "catchup" && !triedHls && cur != null && cur.url.endsWith(".ts")) {
                triedHls = true
                items = listOf(cur.copy(url = cur.url.removeSuffix(".ts") + ".m3u8"))
                p.setMediaItem(mediaItem(items[0]))
                p.prepare()
                p.playWhenReady = true
                return
            }
            b.errorText.text = getString(R.string.vod_error_playback, error.errorCodeName)
            b.errorText.visibility = View.VISIBLE
        }
    }

    private fun updateTitle() {
        val it = items.getOrNull(index) ?: return
        b.vodTitle.text = it.title
        b.vodSubtitle.text = it.subtitle
        b.vodSubtitle.visibility = if (it.subtitle.isBlank()) View.GONE else View.VISIBLE
    }

    /** "Epizòd swivan" parèt 20 dènye segonn yo. */
    private fun updateNextBox() {
        val p = player ?: return
        val next = items.getOrNull(index + 1)
        val left = if (p.duration > 0) p.duration - p.currentPosition else Long.MAX_VALUE
        val show = next != null && left in 1..20_000 && p.isPlaying
        b.nextTitle.text = next?.subtitle.orEmpty()
        b.nextBox.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ Pwogrè ("Kontinye gade")

    private fun progressFor(i: Int, pos: Long, dur: Long): WatchProgress? {
        if (kind == "catchup" || dur <= 0) return null
        val it = items.getOrNull(i) ?: return null
        return WatchProgress(
            key = it.key, positionMs = pos, durationMs = dur, updatedAt = System.currentTimeMillis(),
            title = if (kind == "episode") "${it.title} · ${it.subtitle}" else it.title,
            poster = it.poster, kind = if (kind == "episode") VodKind.SERIES else VodKind.MOVIE,
            parentId = parentId, url = it.url
        )
    }

    private fun saveProgress() {
        val p = player ?: return
        progressFor(p.currentMediaItemIndex, p.currentPosition, p.duration)?.let { history.put(it) }
    }

    private fun markFinished(i: Int) {
        val p = player ?: return
        val d = if (p.duration > 0) p.duration else 1L
        progressFor(i, d, d)?.let { history.put(it) }
    }

    // ------------------------------------------------------------ Remòt

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
        val target = (p.currentPosition + ms).coerceIn(0L, dur)
        p.seekTo(target)
        b.seekHint.text = "${if (ms > 0) "▶▶ +${ms / 1000}" else "◀◀ −${-ms / 1000}"} s   ${fmt(target)}"
        b.seekHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 1200)
    }

    /** Touch nou jere sou ACTION_DOWN: bloke ACTION_UP yo tou pou PlayerView pa louvri kontwòl yo. */
    private val handledKeys = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (b.overlay.blocker.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && handledKeys.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (handleKey(event)) { handledKeys.add(event.keyCode); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        run {
            val shown = b.playerView.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!shown) { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!shown) { seekBy(30_000); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!shown) { b.playerView.showController(); return true }
                KeyEvent.KEYCODE_BACK -> if (shown) { b.playerView.hideController(); return true }
            }
        }
        return false
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------ Panel

    private fun reportStatus() {
        val it = items.getOrNull(index) ?: return
        val body = JSONObject()
            .put("screen", if (kind == "catchup") "catchup" else "vod")
            .put("channel", JSONObject().put("id", intent.getIntExtra(EXTRA_STREAM_ID, 0)).put("num", 0)
                .put("name", if (kind == "episode") "${it.title} · ${it.subtitle}" else it.title).put("category", b.vodKind.text.toString()))
            .put("playlistName", prefs.account?.name ?: "")
            .put("appVersion", BuildConfig.VERSION_NAME)
        lifecycleScope.launch { runCatching { PanelApi(prefs).status(body) } }
    }

    private fun onRemoteCommand(cmd: RemoteCommand) {
        when (cmd.type) {
            "play" -> {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_STREAM_ID, cmd.channelId)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
            "reload", "restart" -> {
                startActivity(
                    Intent(this, if (cmd.type == "reload") MainActivity::class.java else LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            "logout" -> {
                prefs.account = null
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }
}
