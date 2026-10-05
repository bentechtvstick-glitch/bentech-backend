package com.galaxytvstick.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Ad
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.EpgRepository
import com.galaxytvstick.app.data.Program
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityPlayerBinding
import com.galaxytvstick.app.util.DeviceCaps
import coil.load
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.data.Account
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.PanelConfig
import com.galaxytvstick.app.data.PanelPlaylist
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_OPEN_LIST = "open_list"
        /** Tip "Ad" pou bumper yo (ekran anvan/apre koupi piblisite a). */
        private const val BUMPER = "bumper"
        /**
         * Fason pou mande stream nan, youn apre lòt jiskaske youn mache:
         * 0 = /live/…/id.m3u8 (HLS) · 1 = /live/…/id.ts · 2 = /…/id (ansyen fòm) · 3 = /…/id li kòm HLS
         */
        private const val MODES = 4
        /** Fason ki mache ak sèvè playlist la (app la sonje l pou pwochen chanèl yo). */
        private var preferredMode = 0
        private var tunedFor: String? = null

        /** Lyen stream yon chanèl, ak fason ki mache pou sèvè a (gid la sèvi ak li tou pou ti apèsi a). */
        fun buildItem(api: XtreamApi, ch: Channel, mode: Int = preferredMode, alt: Boolean = XtreamApi.preferAlt): MediaItem {
            val url = when {
                ch.directUrl != null -> ch.directUrl
                mode == 0 -> api.streamUrl(ch, "m3u8", alt)
                mode == 1 -> api.streamUrl(ch, "ts", alt)
                else -> api.streamUrlBare(ch, alt)
            }
            val builder = MediaItem.Builder()
                .setMediaId("channel")
                .setUri(url)
                // Pa fòse player a rete kole sou "dirèk" la (5 s te twò pre: imaj la te kanpe souvan). Li swiv sa stream nan mande.
            val isHls = if (ch.directUrl != null) ch.directUrl.contains(".m3u8", ignoreCase = true) else mode == 0 || mode == 3
            if (isHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            return builder.build()
        }
    }

    // Lis chanèl sou videyo a (style TiviMate)
    private var ovCategory = ChannelLists.CAT_ALL
    private val ovCatAdapter = CategoryAdapter { selectOverlayCategory(it.id, focusList = true) }
    private val ovChAdapter = ChannelAdapter(
        onClick = { pos -> pickFromOverlay(pos) },
        onLongClick = { ch -> toggleFavoriteInOverlay(ch) },
        onFocus = { ch -> showProgramDetail(ch) }
    )
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var overlay: OverlayController
    private var player: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var openListOnStart = false
    private var index = 0
    private var lastCrash = ""
    private var attempts = 0
    private var curMode = 0
    private var curAlt = false
    /** 0 = nòmal · 1 = sèvè server_info · 2 = sèvè lis M3U la · 3 = lòt User-Agent */
    private var repairStage = 0
    private var dataSourceFactory: OkHttpDataSource.Factory? = null

    // Piblisite preroll
    private var prerollAd: Ad? = null
    private var adPlaying = false
    private var adSecondsLeftToSkip = 0

    // Koupi piblisite (tankou chèn TV): plizyè spot youn apre lòt, epi retounen sou chanèl la
    private var breakQueue: List<Ad> = emptyList()
    private var breakPos = -1               // -1 = pa nan yon koupi
    private var breakSkipAfter = 0          // 0 = kliyan an pa ka sote
    private var breakElapsed = 0            // segonn depi koupi a kòmanse
    private var spotElapsed = 0             // segonn depi spot la kòmanse
    private var breakCursor = 0             // pwochen spot nan wotasyon otomatik la
    private var lastBreakAt = android.os.SystemClock.elapsedRealtime()
    private var lastBreakMinute = ""
    private val inBreak get() = breakPos >= 0

    // Chanje chanèl ak nimewo sou remòt la
    private var numberBuffer = ""

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { b.infoPanel.visibility = View.GONE }
    private val commitNumber = Runnable { jumpToNumber() }
    private val adTick = object : Runnable {
        override fun run() {
            if (!adPlaying) return
            if (adSecondsLeftToSkip > 0) {
                b.adSkip.text = getString(R.string.ad_skip_in, adSecondsLeftToSkip)
                adSecondsLeftToSkip--
                handler.postDelayed(this, 1000)
            } else {
                b.adSkip.text = getString(R.string.ad_skip_now)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = Prefs(this)
        val account = prefs.account ?: run { finish(); return }
        api = XtreamApi(account)
        // App la te fèmen sibitman dènye fwa? Montre rezon an yon sèl fwa
        getSharedPreferences("galaxy_crash", MODE_PRIVATE).let { sp ->
            val crash = sp.getString("last", null)
            if (crash != null) {
                sp.edit().remove("last").apply()
                lastCrash = crash
                AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
                    .setTitle(R.string.crash_title)
                    .setMessage(crash)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        // Reglaj ki te mache dènye fwa pou sèvè sa a (pou pa rechèche chak fwa app la ouvri)
        if (tunedFor != account.server) {
            tunedFor = account.server
            preferredMode = prefs.tune(account.server, "mode")?.toIntOrNull()?.coerceIn(0, MODES - 1) ?: 0
            prefs.tune(account.server, "base")?.let { api.restoreAltBase(it) }
            XtreamApi.preferAlt = prefs.tune(account.server, "alt") == "1" && api.altBase != null
            XtreamApi.USER_AGENT = prefs.tune(account.server, "ua") ?: XtreamApi.DEFAULT_USER_AGENT
        }

        val wantedId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        channels = if (wantedId >= 0) ChannelStore.all else ChannelStore.current.ifEmpty { ChannelStore.all }
        if (channels.isEmpty()) { finish(); return }
        index = if (wantedId >= 0) channels.indexOfFirst { it.streamId == wantedId }.coerceAtLeast(0)
                else intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.size - 1)

        b.ovCategories.layoutManager = LinearLayoutManager(this)
        b.ovCategories.adapter = ovCatAdapter
        b.ovChannels.layoutManager = LinearLayoutManager(this)
        b.ovChannels.adapter = ovChAdapter
        b.btnGuide.setOnClickListener { openGuide() }
        b.btnMovies.setOnClickListener { openVod("movie") }
        b.btnSeries.setOnClickListener { openVod("series") }
        b.btnPlaylists.setOnClickListener { showPlaylists() }
        b.btnSettings.setOnClickListener { showSettings() }
        openListOnStart = intent.getBooleanExtra(EXTRA_OPEN_LIST, false)

        // Chwazi yon piblisite preroll (videyo oswa imaj plen ekran) si panel la voye youn
        prerollAd = PanelState.config.ads.firstOrNull {
            it.placement == "preroll" || it.placement == "fullscreen"
        }

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = true,
            onConfig = { cfg -> onPanelConfig(cfg) },
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        overlay.start()
        // Chaje EPG a si ekran prensipal la poko fè l
        lifecycleScope.launch {
            val fresh = runCatching { EpgRepository.ensureLoaded(api, ChannelStore.all) }.getOrDefault(false)
            if (fresh) {
                ovChAdapter.notifyDataSetChanged()
                if (b.infoPanel.visibility == View.VISIBLE) showInfo()
            }
        }
        val ad = prerollAd
        if (ad != null && ad.type == "video") {
            playPreroll(ad)
        } else {
            if (ad != null && ad.type == "image") showImageAd(ad)
            playChannel(index)
        }
        prerollAd = null // yon sèl fwa pa ouvèti
        handler.postDelayed(breakScheduler, 20_000)
        lastFrames = -1; frozenTicks = 0
        handler.postDelayed(videoWatch, 4_000)
        if (openListOnStart) {
            openListOnStart = false
            b.root.post { if (!adPlaying) showChannelOverlay() }
        }
    }

    override fun onStop() {
        super.onStop()
        overlay.stop()
        handler.removeCallbacksAndMessages(null)
        cancelAdBreak()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ Player 8K

    private fun initPlayer() {
        if (player != null) return

        // Menm konstriksyon egzak ak ti fenèt videyo gid la (EpgActivity), ki jwe san pwoblèm sou menm aparèy la:
        // player estanda Google la, san okenn reglaj espesyal sou kalite, memwa oswa dekodè.
        val dataSource = OkHttpDataSource.Factory(XtreamApi.http)
            .setUserAgent(XtreamApi.USER_AGENT)
        dataSourceFactory = dataSource

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build().also { p ->
                b.playerView.player = p
                p.addListener(listener)
                // Pou dyagnostik: ki dekodè k ap travay, ak konbyen imaj li sote
                p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
                    ) { videoDecoder = decoderName; droppedFrames = 0 }

                    override fun onDroppedVideoFrames(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
                    ) { this@PlayerActivity.droppedFrames += droppedFrames }
                })
            }
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (adPlaying) return
            val label = DeviceCaps.labelForHeight(videoSize.height)
            b.resBadge.text = label
            // Ba enfo a montre sèlman logo + non chanèl la + EPG; kalite a ale nan panel la sèlman.
            b.resBadge.visibility = View.GONE
            val codec = player?.videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase().orEmpty()
            b.techInfo.text = "${videoSize.width}×${videoSize.height}  $codec"
            lastQuality = label
            lastResolution = if (videoSize.width > 0) "${videoSize.width}x${videoSize.height}" else ""
            lastCodec = codec
            reportStatus()
        }

        override fun onPlaybackStateChanged(state: Int) {
            b.loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            val nowBuffering = state == Player.STATE_BUFFERING
            if (nowBuffering != buffering) { buffering = nowBuffering; reportStatus() }
            // Sonje fòma ki mache ak sèvè sa a (HLS oswa TS) pou pwochen chanèl yo pa pèdi tan sou move a
            if (state == Player.STATE_READY && !adPlaying && player?.currentMediaItem?.mediaId == "channel" &&
                channels.getOrNull(index)?.directUrl == null) rememberWorking()
            if (state == Player.STATE_ENDED && adPlaying) { if (inBreak) nextBreakAd() else endPreroll() }
            // ---- Chanèl la pa dwe rete kanpe: rekonekte poukont li ----
            val onChannel = !adPlaying && player?.currentMediaItem?.mediaId == "channel"
            handler.removeCallbacks(stallWatch)
            if (onChannel) when (state) {
                Player.STATE_READY -> { playedOk = true; readyAt = System.currentTimeMillis() }
                // Sèvè a fèmen koneksyon an (yon chanèl an dirèk pa janm "fini" vre)
                Player.STATE_ENDED -> reconnect()
                // Bloke sou "ap chaje" twò lontan: relanse koneksyon an
                Player.STATE_BUFFERING -> if (playedOk) handler.postDelayed(stallWatch, 12_000)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (adPlaying && mediaItem?.mediaId == "channel") endPreroll()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (inBreak) { nextBreakAd(); return } // spot la pa ka jwe: pase sou pwochen an
            if (adPlaying) { endPreroll(); playChannel(index); return }
            // Chanèl la t ap jwe byen epi li koupe (rezo, sèvè founisè a): rekonekte sou menm fason an, pa montre erè touswit
            if (playedOk && reconnect()) return
            // Si yon fason pa mache, eseye pwochen an (HLS → TS → ansyen fòm lyen) — sèlman pou chanèl Xtream
            val ch = channels.getOrNull(index)
            val direct = ch?.directUrl != null
            if (attempts < MODES - 1 && !direct) {
                playChannel(index, mode = (curMode + 1) % MODES, attempt = attempts + 1, alt = curAlt, stage = repairStage)
            } else {
                val code = error.errorCodeName
                val at = index
                lifecycleScope.launch {
                    // Chèche poukont li yon fason ki mache: lòt sèvè videyo, epi lòt User-Agent
                    if (!direct && ch != null) {
                        var stage = repairStage
                        while (stage < 3) {
                            stage++
                            var alt = curAlt
                            val found = when (stage) {
                                1 -> if (!curAlt && api.resolveAltBase() != null) { alt = true; true } else false
                                2 -> if (api.discoverFromM3u() != null) { alt = true; true } else false
                                else -> {
                                    var ua = api.findUserAgent(api.streamUrl(ch, "ts", false))
                                    alt = false
                                    if (ua == null && api.altBase != null) { ua = api.findUserAgent(api.streamUrl(ch, "ts", true)); alt = true }
                                    if (ua != null) { XtreamApi.USER_AGENT = ua; dataSourceFactory?.setUserAgent(ua); true } else false
                                }
                            }
                            if (index != at || player == null) return@launch
                            if (found) { playChannel(at, mode = 0, attempt = 0, alt = alt, stage = stage); return@launch }
                        }
                    }
                    // Mesaj senp pou kliyan an; detay teknik la an piti anba l (epi nan panel la)
                    showPlayError(getString(R.string.stream_unavailable), code)
                    lastError = code
                    reportStatus()
                    if (ch != null) {
                        // Gade sa sèvè a reponn vre (egz: yon paj web olye yon videyo) pou bay bon mesaj la
                        val pr = api.probe(if (direct) ch.directUrl!! else api.streamUrl(ch, "ts", false))
                        val diag = "v${BuildConfig.VERSION_NAME} · $code · ${pr.summary}" +
                            (if (direct) "" else " · lòt sèvè: " + (if (api.altBase != null) "wi, eseye" else "okenn") + " · lòt User-Agent: okenn pa mache")
                        val msg = when {
                            pr.blockedByNetwork -> R.string.stream_blocked_network
                            pr.kind == "error" -> R.string.stream_no_network
                            pr.code == 401 || pr.code == 403 || pr.code == 429 -> R.string.stream_refused
                            pr.isVideo -> R.string.stream_unsupported
                            else -> R.string.stream_unavailable
                        }
                        if (index == at && b.errorText.visibility == View.VISIBLE) {
                            showPlayError(getString(msg), diag)
                            lastError = diag
                            reportStatus()
                        }
                    }
                }
            }
        }
    }

    /** Mesaj erè: yon fraz klè an gwo, detay teknik la an piti epi pal anba l. */
    private fun showPlayError(message: String, detail: String) {
        val text = android.text.SpannableString(message + "\n" + detail)
        val from = message.length + 1
        text.setSpan(android.text.style.RelativeSizeSpan(0.5f), from, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(0x99FFFFFF.toInt()), from, text.length, 0)
        b.errorText.text = text
        b.errorText.visibility = View.VISIBLE
    }

    /** Chanèl la jwe: sonje fason ki mache a (fòm lyen, sèvè videyo, User-Agent) pou pwochen chanèl ak pwochen ouvèti yo. */
    private fun rememberWorking() {
        val server = prefs.account?.server ?: return
        if (preferredMode == curMode && XtreamApi.preferAlt == curAlt && prefs.tune(server, "ua") == XtreamApi.USER_AGENT.takeIf { it != XtreamApi.DEFAULT_USER_AGENT } && prefs.tune(server, "mode") != null) return
        preferredMode = curMode
        XtreamApi.preferAlt = curAlt
        prefs.setTune(server, "mode", curMode.toString())
        prefs.setTune(server, "alt", if (curAlt) "1" else "0")
        prefs.setTune(server, "base", if (curAlt) api.altBase else null)
        prefs.setTune(server, "ua", XtreamApi.USER_AGENT.takeIf { it != XtreamApi.DEFAULT_USER_AGENT })
    }

    private fun channelItem(ch: Channel, mode: Int, alt: Boolean = XtreamApi.preferAlt): MediaItem = buildItem(api, ch, mode, alt)

    // ---- Rekoneksyon otomatik ----
    /** true = chanèl aktyèl la te deja rive jwe (donk yon erè se yon koupi, pa yon move lyen). */
    private var playedOk = false
    private var readyAt = 0L
    private var reconnects = 0
    private val stallWatch = Runnable { if (!adPlaying && player?.playbackState == Player.STATE_BUFFERING) reconnect() }

    // ---- Imaj la kole pandan son an kontinye: relanse chanèl la ----
    private var lastFrames = -1
    private var frozenTicks = 0
    private var videoDecoder = ""
    private var droppedFrames = 0
    private var freezeRestarts = 0
    private var firstFreezeAt = 0L
    private val videoWatch: Runnable = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 4_000)
            val p = player ?: return
            // Sèlman lè yon chanèl ki gen videyo ap jwe (pa pandan piblisite, pa pou radyo)
            if (adPlaying || !p.isPlaying || p.currentMediaItem?.mediaId != "channel" || p.videoFormat == null) { lastFrames = -1; frozenTicks = 0; return }
            val frames = runCatching { p.videoDecoderCounters?.also { it.ensureUpdated() }?.renderedOutputBufferCount }.getOrNull() ?: return
            if (frames == lastFrames) {
                // 2 kontwòl youn dèyè lòt (8 s) san okenn nouvo imaj: videyo a kole
                if (++frozenTicks >= 2) {
                    frozenTicks = 0; lastFrames = -1
                    val now = System.currentTimeMillis()
                    if (now - firstFreezeAt > 180_000) { firstFreezeAt = now; freezeRestarts = 0 }
                    if (++freezeRestarts <= 3) reconnect()
                    else {
                        // Relanse 3 fwa nan 3 minit pa ranje l: di sa klèman, ak detay pou jwenn kòz la
                        val f = p.videoFormat
                        val soft = videoDecoder.startsWith("OMX.google", true) || videoDecoder.startsWith("c2.android", true)
                        val diag = "v${BuildConfig.VERSION_NAME} · imaj kole · dekodè: $videoDecoder${if (soft) " (lojisyèl)" else ""} · " +
                            "${f?.width}x${f?.height} ${f?.sampleMimeType?.substringAfter('/')} ${f?.frameRate?.takeIf { it > 0 }?.toInt() ?: "?"}fps " +
                            "${(f?.bitrate ?: -1).takeIf { it > 0 }?.let { "${it / 1000}kbps" } ?: ""} · imaj sote: $droppedFrames · fason: $curMode"
                        showPlayError(getString(R.string.stream_unsupported), diag)
                        lastError = diag
                        reportStatus()
                    }
                }
            } else { frozenTicks = 0; lastFrames = frames }
        }
    }

    /** Relanse chanèl la sou menm fason ki t ap mache a. Retounen false lè li eseye twòp fwa (lè sa a nou montre erè a). */
    private fun reconnect(): Boolean {
        val p = player ?: return false
        val ch = channels.getOrNull(index) ?: return false
        // Si chanèl la te jwe byen pandan 1 minit, kontè a rekòmanse a zewo
        if (readyAt > 0 && System.currentTimeMillis() - readyAt > 60_000) reconnects = 0
        if (reconnects >= 8) { playedOk = false; return false }
        reconnects++
        val at = index; val mode = curMode; val alt = curAlt
        b.loading.visibility = View.VISIBLE
        handler.removeCallbacks(stallWatch)
        handler.postDelayed({
            if (index == at && !adPlaying && player === p) {
                p.setMediaItem(channelItem(ch, mode, alt))
                p.prepare()
                p.playWhenReady = true
            }
        }, minOf(500L * reconnects, 4_000L))
        return true
    }

    private fun playChannel(i: Int, mode: Int = preferredMode, attempt: Int = 0, alt: Boolean = XtreamApi.preferAlt, stage: Int = 0) {
        val p = player ?: return
        // Nouvo chanèl (pa yon re-eseye): kontè rekoneksyon an rekòmanse
        if (attempt == 0 && stage == 0) { playedOk = false; reconnects = 0; readyAt = 0L }
        handler.removeCallbacks(stallWatch)
        attempts = attempt
        curMode = mode
        curAlt = alt
        repairStage = stage
        index = i
        val ch = channels[index]
        if (ch.directUrl == null) prefs.lastChannelId = ch.streamId // pa relanse yon evènman ki ka fini
        b.errorText.visibility = View.GONE
        b.resBadge.visibility = View.GONE
        lastQuality = ""; lastResolution = ""; lastCodec = ""; lastError = ""
        reportStatus()
        p.setMediaItem(channelItem(ch, mode, alt))
        p.prepare()
        p.playWhenReady = true
        showInfo()
    }

    // ------------------------------------------------------------ Piblisite

    private fun playPreroll(ad: Ad) {
        val p = player ?: return
        adPlaying = true
        adSecondsLeftToSkip = ad.skipAfterSec.coerceAtLeast(0)
        b.adBox.visibility = View.VISIBLE
        handler.post(adTick)
        val adItem = MediaItem.Builder().setMediaId("ad").setUri(ad.url).build()
        curMode = preferredMode; attempts = 0; curAlt = XtreamApi.preferAlt; repairStage = 0
        p.setMediaItems(listOf(adItem, channelItem(channels[index], preferredMode)))
        p.prepare()
        p.playWhenReady = true
    }

    private fun endPreroll() {
        adPlaying = false
        handler.removeCallbacks(adTick)
        b.adBox.visibility = View.GONE
        val p = player ?: return
        if (p.currentMediaItemIndex == 0 && p.mediaItemCount > 1) p.seekToDefaultPosition(1)
        showInfo()
    }

    private fun showImageAd(ad: Ad) {
        b.fullAd.visibility = View.VISIBLE
        b.fullAd.load(ad.url)
        handler.postDelayed({ b.fullAd.visibility = View.GONE }, ad.durationSec.coerceAtLeast(3) * 1000L)
    }

    // ------------------------------------------------------------ Koupi piblisite (tankou chèn TV)

    private val breakTick = object : Runnable {
        override fun run() {
            if (!inBreak) return
            val ad = breakQueue.getOrNull(breakPos) ?: return
            if (ad.type == BUMPER) { // bumper: pa gen etikèt "PIBLISITE", jis tann dire a
                if (spotElapsed >= ad.durationSec.coerceAtLeast(2)) { nextBreakAd(); return }
                spotElapsed++
                handler.postDelayed(this, 1000)
                return
            }
            val p = player
            val left = if (ad.type == "video" && p != null && p.duration > 0) ((p.duration - p.currentPosition) / 1000).toInt()
                       else ad.durationSec - spotElapsed
            val total = breakQueue.count { it.type != BUMPER }
            val n = breakQueue.take(breakPos + 1).count { it.type != BUMPER }
            var text = "$n/$total   ·   %d:%02d".format(left.coerceAtLeast(0) / 60, left.coerceAtLeast(0) % 60)
            if (breakSkipAfter > 0) {
                val wait = breakSkipAfter - breakElapsed
                text += "\n" + if (wait > 0) getString(R.string.ad_skip_in, wait) else getString(R.string.ad_skip_now)
            }
            b.adSkip.text = text
            // Imaj: pase sou pwochen spot la lè dire a fini
            if (ad.type != "video" && spotElapsed >= ad.durationSec.coerceAtLeast(3)) { nextBreakAd(); return }
            breakElapsed++; spotElapsed++
            handler.postDelayed(this, 1000)
        }
    }

    /** Lanse yon koupi piblisite: spot yo pase youn apre lòt plen ekran, epi TV a tounen sou chanèl la. */
    private fun startAdBreak(ads: List<Ad>, skipAfter: Int) {
        if (ads.isEmpty() || adPlaying || isFinishing || player == null) return
        if (b.overlay.blocker.visibility == View.VISIBLE) return
        hideChannelOverlay()
        b.infoPanel.visibility = View.GONE
        b.errorText.visibility = View.GONE
        // Yon nimewo chanèl kliyan an t ap tape pa dwe ranplase piblisite a
        handler.removeCallbacks(commitNumber); numberBuffer = ""; b.numberEntry.visibility = View.GONE
        // Bumper panel la (Grafik TV): youn anvan premye spot la, youn apre dènye a
        val bumper = PanelState.config.graphics?.bumper
        breakQueue = buildList {
            if (bumper != null && bumper.has(false)) add(Ad("bumper-in", BUMPER, "", "break", bumper.durationSec, 0, ""))
            addAll(ads)
            if (bumper != null && bumper.has(true)) add(Ad("bumper-out", BUMPER, "", "break", bumper.durationSec, 0, ""))
        }
        b.overlay.gfx.suppressed = true // kache logo bug, scoreboard… pandan piblisite a
        breakSkipAfter = skipAfter
        breakElapsed = 0
        adPlaying = true
        lastBreakAt = android.os.SystemClock.elapsedRealtime()
        playBreakAd(0)
        reportStatus()
    }

    private fun playBreakAd(i: Int) {
        val p = player ?: return
        breakPos = i
        spotElapsed = 0
        val ad = breakQueue[i]
        handler.removeCallbacks(breakTick)
        if (ad.type == BUMPER) {
            p.stop()
            b.adBox.visibility = View.GONE
            b.fullAd.visibility = View.GONE
            // Si bumper a pa ka parèt (panel la fèk retire l), sote l
            if (!b.overlay.gfx.showBumper(out = ad.id == "bumper-out")) { nextBreakAd(); return }
            handler.post(breakTick)
            return
        }
        b.overlay.gfx.hideBumper()
        b.adBox.visibility = View.VISIBLE
        lifecycleScope.launch { runCatching { PanelApi(prefs).adEvent(ad.id, "start") } }
        if (ad.type == "video") {
            b.fullAd.visibility = View.GONE
            p.setMediaItem(MediaItem.Builder().setMediaId("ad").setUri(ad.url).build())
            p.prepare()
            p.playWhenReady = true
        } else {
            p.stop()
            b.fullAd.visibility = View.VISIBLE
            b.fullAd.load(ad.url)
        }
        handler.post(breakTick)
    }

    private fun nextBreakAd() {
        if (!inBreak) return
        breakQueue.getOrNull(breakPos)?.takeIf { it.type != BUMPER }?.let { ad -> lifecycleScope.launch { runCatching { PanelApi(prefs).adEvent(ad.id, "complete") } } }
        if (breakPos + 1 < breakQueue.size) playBreakAd(breakPos + 1) else endAdBreak()
    }

    /** Fen koupi a: retounen sou chanèl la. */
    private fun endAdBreak() {
        if (!inBreak) return
        cancelAdBreak()
        playChannel(index)
    }

    /** Kanpe koupi a san relanse chanèl la (egz: app la ale an aryè plan). */
    private fun cancelAdBreak() {
        if (!inBreak) return
        handler.removeCallbacks(breakTick)
        breakPos = -1
        breakQueue = emptyList()
        adPlaying = false
        lastBreakAt = android.os.SystemClock.elapsedRealtime()
        b.adBox.visibility = View.GONE
        b.fullAd.visibility = View.GONE
        b.overlay.gfx.hideBumper()
        b.overlay.gfx.suppressed = false
    }

    /** Koupi otomatik: chak X minit ak nan lè fiks panel la mete yo (verifye chak 20 s). */
    private val breakScheduler = object : Runnable {
        override fun run() {
            val ab = PanelState.config.adBreak
            if (ab != null && ab.auto && !adPlaying && player?.isPlaying == true) {
                val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                val due = ab.everyMin > 0 && android.os.SystemClock.elapsedRealtime() - lastBreakAt >= ab.everyMin * 60_000L
                val timed = hm in ab.times && hm != lastBreakMinute
                if (due || timed) {
                    lastBreakMinute = hm
                    // Wotasyon: chak koupi pran pwochen spot yo nan lis la
                    val n = ab.spotsPerBreak.coerceAtMost(ab.spots.size)
                    val pick = (0 until n).map { ab.spots[(breakCursor + it) % ab.spots.size] }
                    breakCursor = (breakCursor + n) % ab.spots.size
                    startAdBreak(pick, ab.skipAfterSec)
                }
            }
            handler.postDelayed(this, 20_000)
        }
    }

    // ------------------------------------------------------------ Lis sou videyo

    private val overlayVisible get() = b.channelOverlay.visibility == View.VISIBLE

    private fun showChannelOverlay() {
        b.infoPanel.visibility = View.GONE
        b.channelOverlay.visibility = View.VISIBLE
        overlay.setListOpen(true)
        ovCatAdapter.items = ChannelLists.categories(this)
        ovChAdapter.favorites = prefs.favorites
        ovChAdapter.playingId = channels.getOrNull(index)?.streamId ?: -1
        selectOverlayCategory(ovCategory, focusList = true)
    }

    private fun hideChannelOverlay() {
        b.channelOverlay.visibility = View.GONE
        overlay.setListOpen(false)
    }

    private fun selectOverlayCategory(id: String, focusList: Boolean) {
        ovCategory = id
        ovCatAdapter.selectedId = id
        val list = ChannelLists.channelsFor(id, prefs)
        ovChAdapter.items = list
        if (!focusList) return
        // Mete fokis sou chanèl k ap jwe a si l nan lis la
        val playing = channels.getOrNull(index)?.streamId
        val pos = list.indexOfFirst { it.streamId == playing }.coerceAtLeast(0)
        if (list.isEmpty()) {
            b.ovCategories.post { b.ovCategories.findViewHolderForAdapterPosition(ovCatAdapter.items.indexOfFirst { it.id == id })?.itemView?.requestFocus() }
            return
        }
        (b.ovChannels.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
        b.ovChannels.post {
            b.ovChannels.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
        showProgramDetail(list[pos])
    }

    private fun toggleFavoriteInOverlay(ch: Channel) {
        prefs.toggleFavorite(ch.streamId)
        ovChAdapter.favorites = prefs.favorites
    }

    private fun pickFromOverlay(pos: Int) {
        val list = ovChAdapter.items
        if (pos !in list.indices) return
        val playingId = channels.getOrNull(index)?.streamId
        channels = list
        ChannelStore.current = list
        if (list[pos].streamId == playingId && player?.isPlaying == true) {
            index = pos
            hideChannelOverlay()
            return
        }
        playChannel(pos)
        b.overlay.gfx.onChannelChanged()
        ovChAdapter.playingId = list[pos].streamId
        hideChannelOverlay()
    }

    private fun showProgramDetail(ch: Channel) {
        b.ovChName.text = "${ch.num}  ${ch.name}"
        val now: Program? = EpgRepository.nowFor(ch)
        val next: Program? = EpgRepository.nextFor(ch)
        if (now != null) {
            b.ovTitle.text = now.title
            b.ovTime.text = "${timeFmt.format(Date(now.start))} - ${timeFmt.format(Date(now.end))}"
            b.ovDesc.text = now.desc
        } else {
            b.ovTitle.text = getString(if (EpgRepository.isLoaded) R.string.epg_no_info else R.string.epg_loading)
            b.ovTime.text = ""
            b.ovDesc.text = ""
        }
        b.ovNext.text = next?.let { getString(R.string.epg_next_fmt, timeFmt.format(Date(it.start)), it.title) } ?: ""
    }

    /** 🎬 Films / 🎞 Séries */
    private fun openVod(kind: String) {
        hideChannelOverlay()
        startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, kind))
    }

    /** ⏪ sou remòt la: rekòmanse pwogram k ap pase a depi kòmansman (catch-up), si chanèl la gen achiv. */
    private fun restartProgram() {
        val ch = channels.getOrNull(index) ?: return
        val now = EpgRepository.nowFor(ch)
        if (!ch.tvArchive || ch.directUrl != null || now == null) {
            android.widget.Toast.makeText(this, R.string.catchup_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = ((now.end - now.start) / 60_000).toInt() + 1
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "catchup")
                .putExtra(VodPlayerActivity.EXTRA_URL, api.catchupUrl(ch, now.start, minutes, prefs.serverTimezone))
                .putExtra(VodPlayerActivity.EXTRA_TITLE, now.title.ifBlank { ch.name })
                .putExtra(VodPlayerActivity.EXTRA_SUBTITLE, "${ch.name}  ·  ${timeFmt.format(Date(now.start))} - ${timeFmt.format(Date(now.end))}")
                .putExtra(VodPlayerActivity.EXTRA_KEY, "c:${ch.streamId}:${now.start}")
                .putExtra(VodPlayerActivity.EXTRA_POSTER, ch.icon)
                .putExtra(VodPlayerActivity.EXTRA_STREAM_ID, ch.streamId)
        )
    }

    private fun openGuide() {
        hideChannelOverlay()
        startActivity(Intent(this, EpgActivity::class.java))
    }

    // ------------------------------------------------------------ Panel

    private var reRegistering = false

    // ------------------------------------------------------------ Estati an dirèk pou panel la

    private var lastQuality = ""
    private var lastResolution = ""
    private var lastCodec = ""
    private var lastError = ""
    private var buffering = false
    private val sendStatus = Runnable {
        val ch = channels.getOrNull(index) ?: return@Runnable
        val now = EpgRepository.nowFor(ch)
        val body = org.json.JSONObject()
            .put("screen", "player")
            .put("channel", org.json.JSONObject().put("id", ch.streamId).put("num", ch.num).put("name", ch.name)
                .put("category", ChannelStore.categories.firstOrNull { it.id == ch.categoryId }?.name ?: ""))
            .put("quality", lastQuality)
            .put("resolution", lastResolution)
            .put("codec", lastCodec)
            .put("buffering", buffering)
            .put("adBreak", inBreak)
            .put("error", if (lastError.isEmpty() && lastCrash.isNotEmpty()) "CRASH · $lastCrash" else lastError)
            .put("playlistName", prefs.account?.name ?: "")
            .put("appVersion", BuildConfig.VERSION_NAME)
        if (now != null) body.put("program", org.json.JSONObject().put("title", now.title).put("start", now.start).put("end", now.end))
        lifecycleScope.launch { runCatching { PanelApi(prefs).status(body) } }
    }

    /** Di panel la sa TV a ap montre (regroupe chanjman ki rive youn dèyè lòt). */
    private fun reportStatus() {
        handler.removeCallbacks(sendStatus)
        handler.postDelayed(sendStatus, 800)
    }

    /** Kòmand sèvis kliyan an voye soti nan panel la. */
    private fun onRemoteCommand(cmd: com.galaxytvstick.app.data.RemoteCommand) {
        when (cmd.type) {
            "adbreak" -> startAdBreak(cmd.ads, cmd.skipAfterSec)
            "play" -> {
                val wasInBreak = inBreak
                cancelAdBreak()
                val i = ChannelStore.all.indexOfFirst { it.streamId == cmd.channelId }
                if (i < 0) {
                    if (wasInBreak) playChannel(index) // piblisite a te kanpe videyo a: tounen sou chanèl la
                    android.widget.Toast.makeText(this, R.string.channel_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                channels = ChannelStore.all
                ChannelStore.current = channels
                hideChannelOverlay()
                playChannel(i)
            }
            "reload" -> reloadPlaylist()
            "restart" -> {
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            "logout" -> logout()
        }
    }

    /** Panel la voye nouvo règ: aplike yo san kanpe TV a. */
    private fun onPanelConfig(cfg: PanelConfig) {
        val acc = prefs.account

        // Sèvè a pa konnen aparèy sa a (li pèdi done li yo): pa dekonekte kliyan an,
        // re-anrejistre aparèy la ak playlist li ap itilize a pou panel la retabli l.
        if (!cfg.known) {
            if (!reRegistering) {
                reRegistering = true
                lifecycleScope.launch {
                    runCatching { PanelApi(prefs).register(acc?.username, restore = acc) }
                    reRegistering = false
                }
            }
            return
        }

        // Admin nan mande tout aparèy yo rechaje (Settings → Force Refresh)
        if (cfg.forceRefreshAt.isNotBlank() && cfg.forceRefreshAt != prefs.lastForceRefresh) {
            val first = prefs.lastForceRefresh.isEmpty()
            prefs.lastForceRefresh = cfg.forceRefreshAt
            if (!first) {
                reloadPlaylist()
                return
            }
        }

        // Playlist la retire (oswa chanje) nan panel la
        if (acc?.playlistId != null) {
            val pl = cfg.playlists.firstOrNull { it.id == acc.playlistId }
            if (pl == null) {
                android.widget.Toast.makeText(this, R.string.playlist_removed, android.widget.Toast.LENGTH_LONG).show()
                logout()
                return
            }
            if (pl.username != acc.username || pl.password != acc.password ||
                (pl.server.isNotBlank() && XtreamApi.normalizeServer(pl.server) != acc.server)) {
                switchTo(pl)
                return
            }
        }

        ChannelStore.applyPanel(cfg)
        val allowed = ChannelStore.all.map { it.streamId }.toHashSet()
        val playingId = channels.getOrNull(index)?.streamId
        // Chanèl evènman ki gen lyen dirèk yo pa soti nan Xtream: kenbe yo
        // Pran vèsyon ki ajou a (non admin nan ka fèk chanje nan panel la)
        val fresh = ChannelStore.all.associateBy { it.streamId }
        channels = channels.mapNotNull { if (it.directUrl != null) it else fresh[it.streamId] }.ifEmpty { ChannelStore.all }
        if (channels.isEmpty()) {
            player?.stop()
            return
        }
        val newIndex = channels.indexOfFirst { it.streamId == playingId }
        if (newIndex >= 0) {
            index = newIndex
        } else if (!adPlaying) {
            // Chanèl la kache pa panel la: pase sou premye chanèl ki otorize
            playChannel(0)
        }
        if (overlayVisible) {
            ovCatAdapter.items = ChannelLists.categories(this)
            ovChAdapter.items = ChannelLists.channelsFor(ovCategory, prefs)
        }
    }

    // ------------------------------------------------------------ Playlist & Paramèt

    private fun showPlaylists() {
        val list = PanelState.config.playlists
        val acc = prefs.account
        if (list.isEmpty()) {
            AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
                .setTitle(R.string.playlists)
                .setMessage(getString(R.string.current_mark, acc?.name ?: acc?.username ?: ""))
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }
        val labels = list.map {
            val n = it.name.ifBlank { it.username }
            if (it.id == acc?.playlistId) getString(R.string.current_mark, n) else "    $n"
        }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.playlists)
            .setItems(labels) { _, which ->
                val pl = list[which]
                if (pl.id != acc?.playlistId) switchTo(pl)
            }
            .show()
    }

    /** Rechaje chanèl yo san chanje playlist (Force Refresh panel la). */
    private fun reloadPlaylist() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Chanje playlist: sove l epi rechaje chanèl yo. */
    private fun switchTo(pl: PanelPlaylist) {
        val server = pl.server.ifBlank { BuildConfig.DEFAULT_SERVER }
        if (server.isBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.error_no_server, pl.name), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        prefs.account = Account(XtreamApi.normalizeServer(server), pl.username.trim(), pl.password.trim(), pl.name.ifBlank { pl.username }, pl.id)
        prefs.lastChannelId = -1
        android.widget.Toast.makeText(this, R.string.switching_playlist, android.widget.Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun showSettings() {
        val acc = prefs.account
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.settings)
            .setMessage(
                getString(
                    R.string.settings_info,
                    acc?.name ?: acc?.username ?: "", prefs.mac, prefs.deviceKey,
                    DeviceCaps.maxSupported, BuildConfig.VERSION_NAME
                )
            )
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.language) { _, _ -> chooseLanguage() }
            .setNegativeButton(R.string.logout) { _, _ -> confirmLogout() }
            .show()
    }

    /** Kliyan an chwazi lang app la (Kreyòl, English, Français, Español). */
    private fun chooseLanguage() {
        val codes = arrayOf("ht", "en", "fr", "es")
        val names = arrayOf("Kreyòl", "English", "Français", "Español")
        val current = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags().take(2)
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.language)
            .setSingleChoiceItems(names, codes.indexOf(current).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(codes[which])
                )
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> logout() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun logout() {
        prefs.account = null
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** BACK pandan w ap gade TV: tounen sou ekran akèy la. */
    private fun goHome() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finishAffinity() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    // ------------------------------------------------------------ Remòt

    private fun showInfo() {
        val ch = channels.getOrNull(index) ?: return
        b.infoNumber.text = ch.num.toString()
        b.infoName.text = ch.name
        b.infoLogo.load(ch.icon) { placeholder(R.drawable.ic_tv); error(R.drawable.ic_tv) }
        val now = EpgRepository.nowFor(ch)
        val next = EpgRepository.nextFor(ch)
        if (now != null) {
            b.infoNow.text = "${timeFmt.format(Date(now.start))} - ${timeFmt.format(Date(now.end))}   ${now.title}"
            b.infoProgress.progress = now.progress()
            b.infoNow.visibility = View.VISIBLE
            b.infoProgress.visibility = View.VISIBLE
        } else {
            b.infoNow.visibility = View.GONE
            b.infoProgress.visibility = View.GONE
        }
        if (next != null) {
            b.infoNext.text = getString(R.string.epg_next_fmt, timeFmt.format(Date(next.start)), next.title)
            b.infoNext.visibility = View.VISIBLE
        } else b.infoNext.visibility = View.GONE
        b.infoPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty() || adPlaying) return
        val next = (index + delta + channels.size) % channels.size
        playChannel(next)
        b.overlay.gfx.onChannelChanged()
    }

    private fun jumpToNumber() {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        b.numberEntry.visibility = View.GONE
        if (n == null || adPlaying) return
        val i = channels.indexOfFirst { it.num == n }
        if (i >= 0) { playChannel(i); b.overlay.gfx.onChannelChanged() }
        else android.widget.Toast.makeText(this, R.string.channel_unavailable, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (b.overlay.blocker.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)

        if (inBreak) {
            // Tankou sou yon chèn TV: pa ka chanje chanèl pandan piblisite a. OK = sote si panel la pèmèt li.
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
                breakSkipAfter > 0 && breakElapsed >= breakSkipAfter) endAdBreak()
            else if (keyCode == KeyEvent.KEYCODE_BACK) confirmExit()
            return true
        }
        if (adPlaying) {
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && adSecondsLeftToSkip <= 0) {
                endPreroll()
                return true
            }
            return if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyDown(keyCode, event) else true
        }

        // Lè lis la ouvè, remòt la navige nan lis la
        if (overlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { hideChannelOverlay(); return true }
                KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (b.infoPanel.visibility == View.VISIBLE) b.infoPanel.visibility = View.GONE else goHome()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showChannelOverlay(); return true }
            KeyEvent.KEYCODE_MENU -> { openGuide(); return true } // bouton ≡ remòt la: gid la (chanèl, fim, seri, reglaj)
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> { openGuide(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { restartProgram(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            // OK = lis chanèl sou videyo a (tankou TiviMate)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showChannelOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (b.infoPanel.visibility == View.VISIBLE) b.infoPanel.visibility = View.GONE else showInfo()
                return true
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (numberBuffer.length < 5) numberBuffer += (keyCode - KeyEvent.KEYCODE_0).toString()
                b.numberEntry.text = numberBuffer
                b.numberEntry.visibility = View.VISIBLE
                handler.removeCallbacks(commitNumber)
                handler.postDelayed(commitNumber, 1500)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
