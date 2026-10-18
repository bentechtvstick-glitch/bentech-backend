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
         * 0 = /live/…/id.ts (pi rapid pou chanje chanèl, jan TiviMate fè) · 1 = /live/…/id.m3u8 (HLS) · 2 = /…/id (ansyen fòm) · 3 = /…/id li kòm HLS
         */
        private const val MODES = 4

        /**
         * Chanje chanèl pi vit: player a kòmanse jwe depi li gen 1 s videyo (olye 2.5 s pa defo),
         * epi 3.5 s apre yon koupi (pou l pa koupe ankò touswit). Rès tanpon an (20–60 s) pa chanje, pou imaj la pa kole.
         * Gid la (ti fenèt videyo a) sèvi ak menm reglaj la.
         */
        fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 500, 3_500)
            .build()
        /** Fason ki mache ak sèvè playlist la (app la sonje l pou pwochen chanèl yo). */
        private var preferredMode = 0
        private var tunedFor: String? = null

        /** Lyen stream yon chanèl, ak fason ki mache pou sèvè a (gid la sèvi ak li tou pou ti apèsi a). */
        fun buildItem(api: XtreamApi, ch: Channel, mode: Int = preferredMode, alt: Boolean = XtreamApi.preferAlt): MediaItem {
            val url = when {
                ch.directUrl != null -> ch.directUrl
                mode == 0 -> api.streamUrl(ch, "ts", alt)
                mode == 1 -> api.streamUrl(ch, "m3u8", alt)
                else -> api.streamUrlBare(ch, alt)
            }
            val builder = MediaItem.Builder()
                .setMediaId("channel")
                .setUri(url)
                // Pa fòse player a rete kole sou "dirèk" la (5 s te twò pre: imaj la te kanpe souvan). Li swiv sa stream nan mande.
            val isHls = if (ch.directUrl != null) ch.directUrl.contains(".m3u8", ignoreCase = true) else mode == 1 || mode == 3
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
            preferredMode = prefs.tune(account.server, "mode2")?.toIntOrNull()?.coerceIn(0, MODES - 1) ?: 0
            prefs.tune(account.server, "base")?.let { api.restoreAltBase(it) }
            XtreamApi.preferAlt = prefs.tune(account.server, "alt") == "1" && api.altBase != null
            XtreamApi.USER_AGENT = prefs.tune(account.server, "ua") ?: XtreamApi.DEFAULT_USER_AGENT
        }

        val wantedId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        channels = if (wantedId >= 0) ChannelStore.all else ChannelStore.current.ifEmpty { ChannelStore.all }
        if (channels.isEmpty()) { finish(); return }
        index = if (wantedId >= 0) channels.indexOfFirst { it.streamId == wantedId }.coerceAtLeast(0)
                else intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.size - 1)

        b.ovCategories.layoutManager = TvLinearLayoutManager(this)
        b.ovCategories.adapter = ovCatAdapter
        b.ovChannels.layoutManager = TvLinearLayoutManager(this)
        b.ovChannels.adapter = ovChAdapter
        // Lis chanèl sou videyo a: san animasyon, liy yo rete pare pou remòt la desann vit
        b.ovCategories.itemAnimator = null; b.ovChannels.itemAnimator = null
        b.ovChannels.setHasFixedSize(true); b.ovChannels.setItemViewCacheSize(20)
        b.btnGuide.setOnClickListener { openGuide() }
        b.btnMovies.setOnClickListener { openVod("movie") }
        b.btnSeries.setOnClickListener { openVod("series") }
        b.btnPlaylists.setOnClickListener { showPlaylists() }
        b.btnSettings.setOnClickListener { settingsMenu.show() }
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
        val stalkerCh = com.galaxytvstick.app.data.StreamResolver.needsResolve(channels.getOrNull(index)?.directUrl)
        if (ad != null && ad.type == "video" && !stalkerCh) {
            playPreroll(ad)
        } else {
            if (ad != null && ad.type == "image") showImageAd(ad)
            playChannel(index, adopt = adopted)
        }
        adopted = false
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
        healthFlush()
        overlay.stop()
        handler.removeCallbacksAndMessages(null)
        cancelAdBreak()
        player?.release()
        player = null
    }

    // ------------------------------------------------------------ Player 8K

    /** true = player a soti nan gid la e li deja ap jwe chanèl la (pa rekonekte). */
    private var adopted = false

    private fun initPlayer() {
        if (player != null) return

        // Gid la te deja ap jwe chanèl sa a nan ti fenèt la: pran menm player a → gwo ekran an parèt touswit
        channels.getOrNull(index)?.streamId?.let { id -> PlayerHandoff.take(id) }?.let { (p, f) ->
            dataSourceFactory = f
            player = p
            b.playerView.player = p
            p.addListener(listener)
            p.addAnalyticsListener(analytics)
            p.volume = 1f
            adopted = true
            return
        }

        // Menm konstriksyon egzak ak ti fenèt videyo gid la (EpgActivity), ki jwe san pwoblèm sou menm aparèy la:
        // player estanda Google la, san okenn reglaj espesyal sou kalite, memwa oswa dekodè.
        val dataSource = OkHttpDataSource.Factory(XtreamApi.http)
            .setUserAgent(XtreamApi.USER_AGENT)
        dataSourceFactory = dataSource

        player = ExoPlayer.Builder(this, Renderers.factory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl())
            .build().also { p ->
                b.playerView.player = p
                p.addListener(listener)
                p.addAnalyticsListener(analytics)
            }
    }

    // Pou dyagnostik: ki dekodè k ap travay, ak konbyen imaj li sote
    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
        ) { videoDecoder = decoderName; droppedFrames = 0 }

        override fun onDroppedVideoFrames(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long
        ) { this@PlayerActivity.droppedFrames += droppedFrames }
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
            b.loading.visibility = View.GONE // pa gen sèk k ap vire: dènye imaj la rete sou ekran an jiskaske nouvo chanèl la kòmanse
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
                Player.STATE_READY -> { playedOk = true; readyAt = System.currentTimeMillis(); healthStarted() }
                // Sèvè a fèmen koneksyon an (yon chanèl an dirèk pa janm "fini" vre)
                Player.STATE_ENDED -> reconnect()
                // Bloke sou "ap chaje" twò lontan: relanse koneksyon an
                Player.STATE_BUFFERING -> if (playedOk) { handler.postDelayed(stallWatch, 12_000); hStalls++ }
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
                    showPlayError(getString(R.string.stream_trouble), code)
                    lastError = code
                    reportStatus()
                    if (ch != null) {
                        // Gade sa sèvè a reponn vre (egz: yon paj web olye yon videyo) pou bay bon mesaj la
                        val pr = api.probe(if (direct) ch.directUrl!! else api.streamUrl(ch, "ts", false))
                        val diag = "v${BuildConfig.VERSION_NAME} · $code · ${pr.summary}" +
                            (if (direct) "" else " · alt server: " + (if (api.altBase != null) "yes, tried" else "none") + " · alt User-Agent: none worked")
                        val msg = when {
                            pr.blockedByNetwork -> R.string.stream_blocked_network
                            pr.kind == "error" -> R.string.stream_no_network
                            // Pwoblèm bò kote founisè a (chanèl la, kont lan, fòma a): yon mesaj ki rasire kliyan an
                            else -> R.string.stream_trouble
                        }
                        reportAlert(ch, diag)
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
    /**
     * Mesaj senp pou kliyan an (san okenn detay teknik), ak logo Galaxy la k ap fade dousman dèyè l.
     * Detay teknik la (`detail`) ale nan panel la sèlman (lastError + notifikasyon).
     */
    private fun showPlayError(message: String, @Suppress("UNUSED_PARAMETER") detail: String) {
        healthFailed()
        b.errorText.text = message
        b.errorText.visibility = View.VISIBLE
        val logo = b.errorLogo
        if (logo.visibility != View.VISIBLE) {
            logo.animate().cancel()
            logo.alpha = 0f
            logo.visibility = View.VISIBLE
            pulseLogo(true)
        }
    }

    /** Logo a monte/desann dousman (0.25 ↔ 0.55) toutotan mesaj erè a sou ekran an. */
    private fun pulseLogo(up: Boolean) {
        val logo = b.errorLogo
        if (logo.visibility != View.VISIBLE) return
        logo.animate().alpha(if (up) 0.55f else 0.25f).setDuration(2200)
            .withEndAction { pulseLogo(!up) }.start()
    }

    private fun hidePlayError() {
        b.errorText.visibility = View.GONE
        b.errorLogo.animate().cancel()
        b.errorLogo.visibility = View.GONE
    }

    /** Voye pwoblèm chanèl la bay panel la kòm notifikasyon (ak detay teknik la). */
    private fun reportAlert(ch: Channel?, detail: String) {
        if (ch == null) return
        val body = org.json.JSONObject().put("id", ch.streamId).put("type", "live").put("name", ch.name).put("detail", detail.take(400))
        val api = com.galaxytvstick.app.data.PanelApi(prefs)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { api.alert(body) } }
    }

    /** Chanèl la jwe: sonje fason ki mache a (fòm lyen, sèvè videyo, User-Agent) pou pwochen chanèl ak pwochen ouvèti yo. */
    private fun rememberWorking() {
        val server = prefs.account?.server ?: return
        if (preferredMode == curMode && XtreamApi.preferAlt == curAlt && prefs.tune(server, "ua") == XtreamApi.USER_AGENT.takeIf { it != XtreamApi.DEFAULT_USER_AGENT } && prefs.tune(server, "mode2") != null) return
        preferredMode = curMode
        XtreamApi.preferAlt = curAlt
        prefs.setTune(server, "mode2", curMode.toString())
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
                        val diag = "v${BuildConfig.VERSION_NAME} · frozen picture · decoder: $videoDecoder${if (soft) " (software)" else ""} · " +
                            "${f?.width}x${f?.height} ${f?.sampleMimeType?.substringAfter('/')} ${f?.frameRate?.takeIf { it > 0 }?.toInt() ?: "?"}fps " +
                            "${(f?.bitrate ?: -1).takeIf { it > 0 }?.let { "${it / 1000}kbps" } ?: ""} · dropped frames: $droppedFrames · mode: $curMode"
                        showPlayError(getString(R.string.stream_trouble), diag)
                        reportAlert(channels.getOrNull(index), diag)
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
        handler.removeCallbacks(stallWatch)
        handler.postDelayed({
            if (index == at && !adPlaying && player === p) {
                setChannel(p, ch, mode, alt)
            }
        }, minOf(500L * reconnects, 4_000L))
        return true
    }

    private fun playChannel(i: Int, mode: Int = preferredMode, attempt: Int = 0, alt: Boolean = XtreamApi.preferAlt, stage: Int = 0, adopt: Boolean = false) {
        val p = player ?: return
        // Nouvo chanèl (pa yon re-eseye): kontè rekoneksyon an rekòmanse
        if (attempt == 0 && stage == 0) { playedOk = false; reconnects = 0; readyAt = 0L; healthBegin(channels[i]) }
        handler.removeCallbacks(stallWatch)
        attempts = attempt
        curMode = mode
        curAlt = alt
        repairStage = stage
        index = i
        val ch = channels[index]
        if (ch.streamId > 0) { prefs.lastChannelId = ch.streamId; prefs.pushRecent(ch.streamId) } // pa relanse yon evènman ki ka fini
        hideQuickBar()
        overlay.setWatching(ch.name, ChannelStore.categories.firstOrNull { it.id == ch.categoryId }?.name.orEmpty())
        hidePlayError()
        b.resBadge.visibility = View.GONE
        lastQuality = ""; lastResolution = ""; lastCodec = ""; lastError = ""
        reportStatus()
        if (adopt && p.currentMediaItem?.mediaId == "channel") {
            // Videyo a deja ap jwe (soti nan gid la): pa rekonekte, pa mezire vitès li (sa pa ta vre)
            hCh = null
            if (p.playbackState == Player.STATE_READY) { playedOk = true; readyAt = System.currentTimeMillis() }
            p.playWhenReady = true
        } else setChannel(p, ch, mode, alt)
        showInfo()
    }

    // ---- Sante chanèl la (pou panel la): konbyen tan li pran pou l kòmanse, konbyen fwa li koupe, si l pa mache ----
    // Voye touswit: "start" lè l kòmanse jwe, "fail" lè l pa mache, "watch" (koupi yo) lè kliyan an kite chanèl la.
    private var hCh: Channel? = null
    private var hStartAt = 0L
    private var hStartMs = -1L
    private var hStalls = 0
    private var hFail = false
    private var hFailSent = false
    /** Depi kilè n ap konte koupi yo (kòmansman chanèl la, oswa dènye rapò 15 minit lan). */
    private var hWatchFrom = 0L
    private val healthPeriodic = object : Runnable {
        override fun run() { healthPartial(); handler.postDelayed(this, 15 * 60_000L) }
    }

    /** Vitès entènèt TV a (estimasyon lektè a, kbps), pou panel la wè si koneksyon kliyan an fèb. */
    private fun bwKbps(): Long = runCatching {
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate / 1000
    }.getOrDefault(0L)

    /** Kliyan an gade menm chanèl la lontan: voye yon rapò chak 15 minit (pa sèlman lè l chanje chanèl). */
    private fun healthPartial() {
        val ch = hCh ?: return
        if (hStartMs < 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        val watchSec = (now - hWatchFrom) / 1000
        if (watchSec < 60) return
        val st = hStalls
        healthSend(ch, "watch") { put("stalls", st); put("watchSec", watchSec); put("bwKbps", bwKbps()) }
        hStalls = 0; hWatchFrom = now
    }

    private fun healthBegin(ch: Channel) {
        healthFlush()
        hCh = ch; hStartAt = android.os.SystemClock.elapsedRealtime(); hStartMs = -1L; hStalls = 0; hFail = false; hFailSent = false
    }

    private fun healthSend(ch: Channel, kind: String, fill: org.json.JSONObject.() -> Unit) {
        if (ch.streamId <= 0) return // evènman ak lyen pa l: pa yon chanèl founisè a
        val body = org.json.JSONObject().put("id", ch.streamId).put("name", ch.name).put("kind", kind).apply(fill)
        val api = com.galaxytvstick.app.data.PanelApi(prefs)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { api.health(body) } }
    }

    /** Chanèl la fèk kòmanse jwe: voye vitès la touswit. */
    private fun healthStarted() {
        val ch = hCh ?: return
        if (hStartMs >= 0) return
        hStartMs = android.os.SystemClock.elapsedRealtime() - hStartAt
        hWatchFrom = android.os.SystemClock.elapsedRealtime()
        healthSend(ch, "start") { put("startMs", hStartMs); put("bwKbps", bwKbps()) }
        handler.removeCallbacks(healthPeriodic); handler.postDelayed(healthPeriodic, 15 * 60_000L)
    }

    /** Chanèl la pa mache (mesaj erè a parèt). */
    private fun healthFailed() {
        val ch = hCh ?: return
        hFail = true
        if (hStartMs >= 0 || hFailSent) return
        hFailSent = true
        healthSend(ch, "fail") { put("fail", true) }
    }

    /** Kliyan an kite chanèl la: voye konbyen fwa li te koupe (si l gade omwen 1 minit). */
    private fun healthFlush() {
        val ch = hCh ?: return
        hCh = null
        val elapsed = android.os.SystemClock.elapsedRealtime() - hStartAt
        if (hStartMs < 0) {
            // Li pa janm kòmanse: konte l kòm "pa mache" si kliyan an te tann omwen 10 s
            if (!hFailSent && elapsed >= 10_000) healthSend(ch, "fail") { put("fail", true) }
            return
        }
        handler.removeCallbacks(healthPeriodic)
        val watchSec = ((android.os.SystemClock.elapsedRealtime() - hWatchFrom) / 1000).coerceAtLeast(0)
        val st = hStalls
        if (watchSec >= 60) healthSend(ch, "watch") { put("stalls", st); put("watchSec", watchSec); put("bwKbps", bwKbps()) }
    }

    /** Mete chanèl la nan player a. Pòtal Stalker: mande lyen videyo a anvan (li chanje chak fwa). */
    private var resolveSeq = 0
    private fun setChannel(p: ExoPlayer, ch: Channel, mode: Int, alt: Boolean) {
        // Pòtal Stalker oswa paj YouTube live: chèche vre lyen videyo a anvan
        val cmd = ch.directUrl?.takeIf { com.galaxytvstick.app.data.StreamResolver.needsResolve(it) }
        if (cmd == null) { p.setMediaItem(channelItem(ch, mode, alt)); p.prepare(); p.playWhenReady = true; return }
        val seq = ++resolveSeq
        val acc = prefs.account ?: return
        lifecycleScope.launch {
            val url = runCatching { com.galaxytvstick.app.data.StreamResolver.resolve(acc, cmd) }
            if (seq != resolveSeq || player !== p) return@launch
            url.onSuccess { u -> p.setMediaItem(channelItem(ch.copy(directUrl = u), mode, alt)); p.prepare(); p.playWhenReady = true }
                .onFailure { val d = "v${BuildConfig.VERSION_NAME} · ${it.message}"; showPlayError(getString(R.string.stream_trouble), d); reportAlert(channels.getOrNull(index), d); lastError = it.message.orEmpty(); reportStatus() }
        }
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
        hideQuickBar()
        if (ads.isEmpty() || adPlaying || isFinishing || player == null) return
        if (b.overlay.blocker.visibility == View.VISIBLE) return
        hideChannelOverlay()
        b.infoPanel.visibility = View.GONE
        hidePlayError()
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

    // ------------------------------------------------------------ Ba rapid (OK sou plen ekran)

    private val quickVisible get() = b.quickBar.visibility == View.VISIBLE
    private val hideQuick = Runnable { hideQuickBar() }
    private val quickClockFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

    private fun hideQuickBar() {
        handler.removeCallbacks(hideQuick)
        b.quickBar.visibility = View.GONE
    }

    private fun keepQuickBar() {
        handler.removeCallbacks(hideQuick)
        handler.postDelayed(hideQuick, 10_000)
    }

    /** OK sou plen ekran: enfo pwogram nan anba, ak Gid TV, lis chanèl yo ak dènye chanèl kliyan an te gade. */
    private fun showQuickBar() {
        val ch = channels.getOrNull(index) ?: return
        handler.removeCallbacks(hideInfo)
        b.infoPanel.visibility = View.GONE
        b.qLogo.load(ch.icon) { error(R.drawable.ic_tv) }
        val now = EpgRepository.nowFor(ch)
        val next = EpgRepository.nextFor(ch)
        // Chanèl la pa nan gwo fichye gid la: mande sèvè a pou chanèl sa a sèlman, epi mete ba a ajou
        if (now == null && EpgRepository.needsSingle(ch)) lifecycleScope.launch {
            if (EpgRepository.loadSingle(api, ch) && quickVisible && channels.getOrNull(index)?.streamId == ch.streamId) showQuickBar()
        }
        b.qTitle.text = now?.title ?: ch.name
        if (now != null) {
            b.qTime.text = "${timeFmt.format(Date(now.start))} – ${timeFmt.format(Date(now.end))}"
            b.qProgress.progress = now.progress()
            b.qLeft.text = "${((now.end - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)} min"
        }
        val epgVis = if (now != null) View.VISIBLE else View.GONE
        b.qTime.visibility = epgVis; b.qProgress.visibility = epgVis; b.qLeft.visibility = epgVis
        b.qChannel.text = if (ch.num > 0) "${ch.num}   ${ch.name}" else ch.name
        val tech = ArrayList<String>()
        if (lastQuality.isNotBlank()) tech.add(lastQuality)
        player?.videoFormat?.frameRate?.takeIf { it > 1f }?.let { tech.add("${Math.round(it)} FPS") }
        player?.audioFormat?.channelCount?.takeIf { it > 0 }?.let { tech.add(when (it) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "$it CH" }) }
        b.qTech.text = tech.joinToString("  ·  ")
        b.qNext.text = next?.let { "${timeFmt.format(Date(it.start))} – ${timeFmt.format(Date(it.end))}   ${it.title}" } ?: ""
        b.qClock.text = quickClockFmt.format(Date())

        b.qTiles.removeAllViews()
        val guide = quickTile(R.drawable.ic_m_guide, null, getString(R.string.quick_guide)) { hideQuickBar(); openGuide() }
        quickTile(R.drawable.ic_m_list, null, getString(R.string.quick_channels)) { hideQuickBar(); showChannelOverlay() }
        val byId = ChannelStore.all.associateBy { it.streamId }
        val recents = prefs.recentChannels.filter { it != ch.streamId }.mapNotNull { byId[it] }.take(8)
        recents.forEach { r ->
            quickTile(0, r, EpgRepository.nowFor(r)?.title ?: r.name) {
                hideQuickBar()
                var i = channels.indexOfFirst { it.streamId == r.streamId }
                if (i < 0) { channels = ChannelStore.all; ChannelStore.current = channels; i = channels.indexOfFirst { it.streamId == r.streamId } }
                if (i >= 0) playChannel(i)
            }
        }
        // Efase istwa a (dènye chanèl yo)
        if (recents.isNotEmpty()) quickTile(R.drawable.ic_m_clear, null, getString(R.string.quick_clear)) {
            prefs.recentChannels = listOf(ch.streamId)
            showQuickBar()
        }
        b.quickBar.visibility = View.VISIBLE
        guide.requestFocus()
        keepQuickBar()
    }

    private fun quickTile(icon: Int, ch: Channel?, label: String, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        val tile = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams((128 * d).toInt(), (78 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            setBackgroundResource(R.drawable.bg_focus)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has -> if (has) keepQuickBar() }
        }
        if (ch != null) {
            tile.addView(android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((84 * d).toInt(), (36 * d).toInt())
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                load(ch.icon) { error(R.drawable.ic_tv) }
            })
        } else {
            tile.addView(android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply { bottomMargin = (4 * d).toInt() }
                setImageResource(icon)
            })
        }
        tile.addView(android.widget.TextView(this).apply {
            text = label
            textSize = if (ch != null) 11f else 14f
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
        })
        b.qTiles.addView(tile)
        return tile
    }

    private fun showChannelOverlay() {
        hideQuickBar()
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
        handOffToGuide()
        startActivity(Intent(this, EpgActivity::class.java))
        @Suppress("DEPRECATION") overridePendingTransition(0, 0) // san animasyon: gid la parèt touswit
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
        handler.postDelayed(sendStatus, 350)
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
            if (!first) overlay.reloadSilently() // an silans: videyo a pa kanpe
        }

        // Playlist la retire (oswa chanje) nan panel la
        if (acc?.playlistId != null) {
            val pl = cfg.playlists.firstOrNull { it.id == acc.playlistId }
            if (pl == null) {
                android.widget.Toast.makeText(this, R.string.playlist_removed, android.widget.Toast.LENGTH_LONG).show()
                logout()
                return
            }
            val want = pl.toAccount(BuildConfig.DEFAULT_SERVER)
            if (want != null && (want.server != acc.server || want.username != acc.username || want.password != acc.password ||
                    want.type != acc.type || want.epgUrl != acc.epgUrl)) {
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
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_USE_CACHE, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Chanje playlist: sove l epi rechaje chanèl yo. */
    private fun switchTo(pl: PanelPlaylist) {
        val account = pl.toAccount(BuildConfig.DEFAULT_SERVER)
        if (account == null) {
            android.widget.Toast.makeText(this, getString(R.string.error_no_server, pl.name), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        prefs.account = account
        prefs.lastChannelId = -1
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /** Menm meni Settings ak gid la, men li louvri dirèkteman sou videyo a. */
    private val settingsMenu by lazy { SettingsMenu(this, prefs) { selectOverlayCategory(ovCategory, focusList = false) } }

    private fun showSettingsOld() {
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
    /**
     * Retounen sou gid la: bay ti fenèt gid la menm player a k ap jwe chanèl la (pa rekonekte).
     * San sa, ti fenèt la te kòmanse yon nouvo koneksyon e sèvè a te rejwe kèk segonn ki te deja pase.
     */
    private fun handOffToGuide() {
        val p = player ?: return
        val ch = channels.getOrNull(index) ?: return
        if (adPlaying || inBreak || ch.streamId <= 0 || p.currentMediaItem?.mediaId != "channel" || p.playerError != null) return
        if (p.playbackState != Player.STATE_READY && p.playbackState != Player.STATE_BUFFERING) return
        healthFlush()
        handler.removeCallbacks(stallWatch)
        p.removeListener(listener)
        p.removeAnalyticsListener(analytics)
        b.playerView.player = null
        PlayerHandoff.put(p, dataSourceFactory, ch.streamId)
        player = null
    }

    private fun goHome() {
        handOffToGuide()
        if (isTaskRoot) startActivity(Intent(this, EpgActivity::class.java))
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

    /** ▲▼ plizyè fwa vit: montre chak chanèl touswit, men chaje sèlman sa kliyan an kanpe sou li a. */
    private var zapPending = false
    private val doZap = Runnable {
        zapPending = false
        playChannel(index)
        b.overlay.gfx.onChannelChanged()
    }

    private fun zap(delta: Int) {
        if (channels.isEmpty() || adPlaying) return
        index = (index + delta + channels.size) % channels.size
        zapPending = true
        showInfo()
        handler.removeCallbacks(doZap)
        handler.postDelayed(doZap, 200)
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

        // Ba rapid la ouvè: ◀ ▶ deplase sou kare yo, OK chwazi, BACK/▲/▼ fèmen l
        if (quickVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { hideQuickBar(); return true }
                KeyEvent.KEYCODE_GUIDE -> { hideQuickBar(); openGuide(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    hideQuickBar()
                    settingsMenu.show() // touswit sou videyo a (pa chaje gid la)
                    return true
                }
            }
            keepQuickBar()
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (b.infoPanel.visibility == View.VISIBLE) b.infoPanel.visibility = View.GONE else goHome()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showChannelOverlay(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { // bouton ≡ remòt la: Reglaj
                hideChannelOverlay()
                settingsMenu.show() // touswit sou videyo a, videyo a kontinye jwe dèyè l
                return true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> { openGuide(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { restartProgram(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            // OK = ba rapid la (enfo pwogram, Gid TV, lis chanèl, dènye chanèl yo)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showQuickBar(); return true }
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
