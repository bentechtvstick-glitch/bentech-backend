package com.galaxytvstick.app.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.EpgRepository
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.PanelConfig
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.VodStore
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.WatchProgress
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.OverlayPanelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran akèy Magic TV Stick (stil "TV ou a" tankou gwo sèvis satelit yo):
 * ray ikòn agoch, dènye chanèl yo, gwo kare ki jwe chanèl la an dirèk, "Plis tankou…",
 * epi lè w desann: chanèl an dirèk, bann pwomosyon panel la, Kontinye gade, fim ak seri pa kategori.
 * Tout done yo soti nan playlist kliyan an (chanèl, gid, fim, seri) ak panel la (pwomosyon, ticker…).
 */
class MagicHomeActivity : AppCompatActivity() {

    companion object {
        private const val BG_TOP = 0xFF0C1440.toInt()
        private const val BG_BOTTOM = 0xFF060B24.toInt()
        private const val CARD = 0xFF141D4D.toInt()
        private const val ACCENT = 0xFF2F6BFF.toInt()
        private const val LIVE_GREEN = 0xFF22C38E.toInt()
        private const val DIM = 0xFFC9CFEA.toInt()
        private const val MUTED = 0xFF8F98C8.toInt()
    }

    private lateinit var prefs: Prefs
    private lateinit var history: WatchHistory
    private lateinit var overlay: OverlayController
    private lateinit var api: XtreamApi
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

    // Vi yo
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var recentList: RecyclerView
    private lateinit var tile: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var tileLogo: ImageView
    private lateinit var tileLeft: TextView
    private lateinit var tileTitle: TextView
    private lateinit var tileMeta: TextView
    private lateinit var tileProgress: ProgressBar
    private lateinit var moreTitle: TextView
    private lateinit var moreList: RecyclerView
    private lateinit var promo: ImageView
    private lateinit var rowsBox: LinearLayout

    private val recentAdapter = ChannelAdapter(Style.LOGO)
    private val moreAdapter = ChannelAdapter(Style.PORTRAIT)
    private val liveAdapter = ChannelAdapter(Style.WIDE)

    // Ti player ki nan gwo kare a
    private var player: ExoPlayer? = null
    private var factory: OkHttpDataSource.Factory? = null
    private var previewCh: Channel? = null
    private var pendingCh: Channel? = null
    private val startPending = Runnable { pendingCh?.let { play(it) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val acc = prefs.account
        if (acc == null) { go(LoginActivity::class.java); return }
        if (ChannelStore.all.isEmpty()) { go(MainActivity::class.java); return }
        api = XtreamApi(acc)
        history = WatchHistory(this)
        buildUi()
        ready = true

        overlay = OverlayController(
            this, overlayBinding, lifecycleScope, inPlayer = false,
            onConfig = { cfg -> ChannelStore.applyPanel(cfg); refreshAll(cfg) },
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )

        refreshAll(PanelState.config)
        val first = ChannelStore.all.firstOrNull { it.streamId == prefs.lastChannelId } ?: ChannelStore.all.first()
        showTile(first)
        recentList.post { recentList.getChildAt(0)?.requestFocus() }

        // Gid TV a (pou non pwogram yo): disk la an premye, apre sa sèvè a
        lifecycleScope.launch {
            runCatching { EpgRepository.loadDisk(api) }
            refreshEpgViews()
            runCatching { EpgRepository.ensureLoaded(api, ChannelStore.all) }
            refreshEpgViews()
        }
        // Fim ak seri pou ranje anba yo
        lifecycleScope.launch { loadVodRows() }
        // Lè a ak ba pwogrè yo
        lifecycleScope.launch {
            while (isActive) {
                title.text = getString(R.string.mg_your_tv_fmt, timeFmt.format(Date()))
                delay(20_000)
                refreshEpgViews()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        overlay.start()
        refreshContinue()
        previewCh?.let { if (player == null) play(it) }
        val body = org.json.JSONObject().put("screen", "home").put("playlistName", prefs.account?.name ?: "")
        lifecycleScope.launch { runCatching { PanelApi(prefs).status(body) } }
    }

    override fun onStop() {
        super.onStop()
        if (!ready) return
        overlay.stop()
        handler.removeCallbacks(startPending)
        player?.release(); player = null
        playerView.player = null
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ------------------------------------------------------------ Konstriksyon ekran an

    private lateinit var overlayBinding: OverlayPanelBinding

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun badge(label: String, bg: Int, fg: Int) = text(9f, fg, true).apply {
        text = label; setPadding(dp(5), dp(1), dp(5), dp(1))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(3f) }
    }

    /** Kad blan lè remòt la sou li (tankou DirecTV). */
    private fun focusBg(fill: Int = CARD, radius: Float = 6f): StateListDrawable {
        fun shape(stroke: Boolean) = GradientDrawable().apply {
            setColor(fill); cornerRadius = dpf(radius)
            if (stroke) setStroke(dp(3), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(true))
            addState(intArrayOf(), shape(false))
        }
    }

    private fun sectionTitle(t: String) = text(14f, Color.WHITE, true).apply {
        text = t; letterSpacing = 0.04f
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16); bottomMargin = dp(8) }
    }

    private fun hList(adapter: RecyclerView.Adapter<*>, height: Int) = RecyclerView(this).apply {
        layoutManager = TvLinearLayoutManager(this@MagicHomeActivity, RecyclerView.HORIZONTAL)
        this.adapter = adapter; itemAnimator = null; clipToPadding = false; clipChildren = false
        setPadding(dp(3), dp(3), dp(24), dp(3))
        layoutParams = LinearLayout.LayoutParams(-1, height)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, 0xFF081033.toInt(), BG_BOTTOM))
        }

        // Ray ikòn agoch
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val profile = text(13f, Color.WHITE, true).apply {
            text = "M"; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dpf(6f) }
        }
        rail.addView(profile, LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(56) })
        fun railBtn(icon: Int, label: Int, onClick: () -> Unit) {
            val v = ImageView(this).apply {
                setImageResource(icon); setColorFilter(MUTED); scaleType = ImageView.ScaleType.CENTER
                isFocusable = true; contentDescription = getString(label)
                background = focusBg(Color.TRANSPARENT, 8f)
                setOnClickListener { onClick() }
                setOnFocusChangeListener { x, has -> (x as ImageView).setColorFilter(if (has) Color.WHITE else MUTED) }
            }
            rail.addView(v, LinearLayout.LayoutParams(dp(38), dp(38)).apply { bottomMargin = dp(8) })
        }
        railBtn(R.drawable.mg_search, R.string.mg_search) { startActivity(Intent(this, SearchActivity::class.java)) }
        railBtn(R.drawable.mg_home, R.string.mg_home) { scroll.smoothScrollTo(0, 0); recentList.getChildAt(0)?.requestFocus() }
        railBtn(R.drawable.mg_guide, R.string.mg_guide) { startActivity(Intent(this, EpgActivity::class.java)) }
        railBtn(R.drawable.mg_movie, R.string.mg_movies) { openVod("movie") }
        railBtn(R.drawable.mg_series, R.string.mg_series) { openVod("series") }
        railBtn(R.drawable.mg_live, R.string.mg_live) { previewCh?.let { openFull(it) } }
        railBtn(R.drawable.mg_settings, R.string.mg_settings) { settingsMenu.show() }
        root.addView(rail, FrameLayout.LayoutParams(dp(58), -1))

        // Kontni an (li desann ak remòt la)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true; clipChildren = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(40)); clipChildren = false }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(72) })

        // Anlè: "Ale nan Gid la", logo, "TV ou a, a …"
        val head = FrameLayout(this)
        head.addView(text(11f, DIM).apply { text = getString(R.string.mg_go_guide); gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(10); rightMargin = dp(72) })
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo_full); scaleType = ImageView.ScaleType.FIT_END; adjustViewBounds = true },
            FrameLayout.LayoutParams(dp(110), dp(40), Gravity.END or Gravity.TOP).apply { topMargin = dp(8); rightMargin = dp(24) })
        title = text(19f, Color.WHITE, true)
        head.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { topMargin = dp(38) })
        content.addView(head, LinearLayout.LayoutParams(-1, dp(72)))

        // Dènye chanèl yo
        recentList = hList(recentAdapter, dp(52))
        content.addView(recentList)

        // Gwo kare + "Plis tankou…"
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        tile = FrameLayout(this).apply {
            isFocusable = true; foreground = focusBg(Color.TRANSPARENT, 6f).also { }
            background = GradientDrawable().apply { setColor(0xFF12357A.toInt()); cornerRadius = dpf(6f) }
            clipToOutline = true
            setOnClickListener { previewCh?.let { openFull(it) } }
        }
        playerView = PlayerView(this).apply {
            useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.TRANSPARENT); isFocusable = false
        }
        tile.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        tile.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF2060B24.toInt(), 0x00060B24))
        }, FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM))
        tile.addView(badge(getString(R.string.mg_new), ACCENT, Color.WHITE), FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8); topMargin = dp(8) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val l1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        l1.addView(badge(getString(R.string.mg_live_badge), LIVE_GREEN, 0xFF04241A.toInt()))
        tileLeft = text(11f, Color.WHITE, true)
        l1.addView(tileLeft, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        info.addView(l1)
        tileTitle = text(15f, Color.WHITE, true)
        info.addView(tileTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        val l3 = FrameLayout(this)
        tileMeta = text(10f, DIM)
        l3.addView(tileMeta, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM))
        tileLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_END }
        l3.addView(tileLogo, FrameLayout.LayoutParams(dp(70), dp(26), Gravity.END or Gravity.BOTTOM))
        info.addView(l3, LinearLayout.LayoutParams(-1, dp(28)))
        tileProgress = bar(LIVE_GREEN)
        info.addView(tileProgress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
        tile.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(10) })
        tile.setOnFocusChangeListener { _, has -> tile.foreground = if (has) GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dpf(6f); setStroke(dp(3), Color.WHITE) } else null }
        mid.addView(tile, LinearLayout.LayoutParams(dp(416), dp(222)).apply { topMargin = dp(14) })

        val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        moreTitle = text(11f, DIM).apply { setTypeface(typeface, Typeface.ITALIC) }
        more.addView(moreTitle, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(3); topMargin = dp(10); bottomMargin = dp(4) })
        moreList = hList(moreAdapter, dp(196))
        more.addView(moreList)
        mid.addView(more, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(15) })
        content.addView(mid)

        // Ranje anba yo
        content.addView(sectionTitle(getString(R.string.mg_live_you_like)))
        content.addView(hList(liveAdapter, dp(120)))
        promo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; isFocusable = true; visibility = View.GONE
            background = focusBg(CARD, 8f); setPadding(dp(3), dp(3), dp(3), dp(3)); clipToOutline = true
            setOnClickListener { previewCh?.let { openFull(it) } }
        }
        content.addView(promo, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(18); rightMargin = dp(24) })
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        content.addView(rowsBox)

        // Kouch panel la: ticker, mesaj, blokaj…
        overlayBinding = OverlayPanelBinding.inflate(layoutInflater, root, false)
        root.addView(overlayBinding.root, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun bar(color: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; isIndeterminate = false
        progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dpf(2f) },
            android.graphics.drawable.ClipDrawable(GradientDrawable().apply { setColor(color); cornerRadius = dpf(2f) }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
    }

    private val settingsMenu by lazy { SettingsMenu(this, prefs) { refreshAll(PanelState.config) } }

    // ------------------------------------------------------------ Done yo

    private fun channelById(id: Int) = ChannelStore.all.firstOrNull { it.streamId == id }

    private fun refreshAll(cfg: PanelConfig) {
        val all = ChannelStore.all
        if (all.isEmpty()) return
        // Dènye chanèl yo (oswa premye chanèl yo pou yon kliyan tou nèf)
        val recent = prefs.recentChannels.mapNotNull { channelById(it) }
        recentAdapter.items = (recent + all.filter { c -> recent.none { it.streamId == c.streamId } }).take(12)
        recentAdapter.notifyDataSetChanged()
        // Chanèl an dirèk: favori yo an premye, apre sa yon chanèl nan chak kategori
        val fav = prefs.favorites
        val favCh = all.filter { it.streamId in fav }
        val oneEach = all.groupBy { it.categoryId }.values.mapNotNull { it.firstOrNull() }
        liveAdapter.items = (favCh + oneEach).distinctBy { it.streamId }.take(20)
        liveAdapter.notifyDataSetChanged()
        refreshMore()
        // Bann pwomosyon: premye banner ki gen yon imaj nan panel la
        val b = cfg.banners.firstOrNull { it.imageUrl.isNotBlank() }
        if (b != null) { promo.visibility = View.VISIBLE; promo.load(b.imageUrl) } else promo.visibility = View.GONE
    }

    private fun refreshMore() {
        val c = previewCh ?: return
        moreTitle.text = getString(R.string.mg_more_like_fmt, EpgRepository.nowFor(c)?.title ?: c.name)
        moreAdapter.items = ChannelStore.all.filter { it.categoryId == c.categoryId && it.streamId != c.streamId }.take(12)
        moreAdapter.notifyDataSetChanged()
    }

    private fun refreshEpgViews() {
        previewCh?.let { fillTile(it) }
        recentAdapter.notifyDataSetChanged()
        moreAdapter.notifyDataSetChanged()
        liveAdapter.notifyDataSetChanged()
    }

    private fun categoryName(c: Channel) = ChannelStore.categories.firstOrNull { it.id == c.categoryId }?.name ?: ""

    private fun fillTile(c: Channel) {
        val p = EpgRepository.nowFor(c)
        tileTitle.text = p?.title?.ifBlank { null } ?: c.name
        tileMeta.text = listOf(c.name, categoryName(c)).filter { it.isNotBlank() }.joinToString(" · ")
        if (p != null) {
            val min = ((p.end - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            tileLeft.text = getString(R.string.mg_min_left_fmt, min.toInt())
            tileProgress.progress = p.progress(); tileProgress.visibility = View.VISIBLE
        } else { tileLeft.text = ""; tileProgress.visibility = View.INVISIBLE }
        tileLogo.load(c.icon)
    }

    /** Chanje chanèl nan gwo kare a (remòt la rete sou kat la yon ti moman anvan videyo a chanje). */
    private fun showTile(c: Channel, delayMs: Long = 0) {
        if (previewCh?.streamId == c.streamId) return
        previewCh = c
        fillTile(c)
        refreshMore()
        pendingCh = c
        handler.removeCallbacks(startPending)
        handler.postDelayed(startPending, delayMs)
    }

    private fun play(c: Channel) {
        val p = player ?: ExoPlayer.Builder(this, Renderers.factory(this))
            .setTrackSelector(Renderers.trackSelector(this))
            .setBandwidthMeter(Renderers.bandwidth(this))
            .setLoadControl(PlayerActivity.loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(XtreamApi.http).setUserAgent(XtreamApi.USER_AGENT).also { factory = it }))
            .build().also { player = it; playerView.player = it }
        val cmd = c.directUrl?.takeIf { com.galaxytvstick.app.data.StreamResolver.needsResolve(it) }
        if (cmd == null) { p.setMediaItem(PlayerActivity.buildItem(api, c)); p.prepare(); p.playWhenReady = true; return }
        val acc = prefs.account ?: return
        lifecycleScope.launch {
            val url = runCatching { com.galaxytvstick.app.data.StreamResolver.resolve(acc, cmd) }.getOrNull() ?: return@launch
            if (previewCh?.streamId != c.streamId || player !== p) return@launch
            p.setMediaItem(PlayerActivity.buildItem(api, c.copy(directUrl = url))); p.prepare(); p.playWhenReady = true
        }
    }

    /** Plen ekran: si ti kare a deja ap jwe chanèl la, bay gwo ekran an menm player a (touswit). */
    private fun openFull(c: Channel) {
        handler.removeCallbacks(startPending)
        val p = player
        if (p != null && previewCh?.streamId == c.streamId && p.playerError == null &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)) {
            playerView.player = null
            PlayerHandoff.put(p, factory, c.streamId)
        } else p?.release()
        player = null
        ChannelStore.current = ChannelStore.all
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM_ID, c.streamId))
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun openVod(kind: String) = startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, kind))

    // ------------------------------------------------------------ Fim ak seri

    private val contAdapter = PosterAdapter()
    private var contRow: View? = null

    private fun refreshContinue() {
        val list = history.continueWatching().take(15)
        contAdapter.cont = list; contAdapter.items = emptyList(); contAdapter.notifyDataSetChanged()
        contRow?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addRow(name: String, adapter: PosterAdapter, h: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        box.addView(sectionTitle(name.uppercase(Locale.getDefault())))
        box.addView(hList(adapter, h))
        rowsBox.addView(box)
        return box
    }

    private suspend fun loadVodRows() {
        val acc = prefs.account ?: return
        contRow = addRow(getString(R.string.mg_continue), contAdapter, dp(112))
        refreshContinue()
        val cfg = PanelState.config
        val adultOff = ChannelStore.hideAdult || cfg.forceHideAdult
        for (kind in listOf(VodKind.MOVIE, VodKind.SERIES)) {
            val d = VodStore.cached(this, acc, kind) ?: runCatching { VodStore.load(this, acc, kind) }.getOrNull() ?: continue
            val blocked = if (kind == VodKind.MOVIE) cfg.blockedMovies else cfg.blockedSeries
            val okCats = d.categories.filter { c -> !(adultOff && ChannelStore.isAdult(c.name)) && c.name.trim().uppercase() !in blocked }
            val okIds = okCats.map { it.id }.toHashSet()
            val rows = withContext(Dispatchers.Default) {
                val newest = d.items.filter { it.categoryId in okIds }.sortedByDescending { it.added }.take(20)
                val byCat = okCats.map { it to d.inCategory(it.id).take(20) }.filter { it.second.size >= 4 }.take(5)
                newest to byCat
            }
            if (rows.first.isNotEmpty()) addRow(getString(if (kind == VodKind.MOVIE) R.string.mg_new_movies else R.string.mg_new_series), PosterAdapter(rows.first), dp(160))
            for ((cat, items) in rows.second) addRow(cat.name, PosterAdapter(items), dp(160))
        }
    }

    // ------------------------------------------------------------ Remòt la

    private fun onRemoteCommand(cmd: RemoteCommand) {
        when (cmd.type) {
            "play" -> channelById(cmd.channelId)?.let { openFull(it) }
            "reload" -> go(MainActivity::class.java)
            "restart" -> go(LoginActivity::class.java)
            "logout" -> {
                prefs.account = null
                startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ready && overlayBinding.blocker.visibility == View.VISIBLE && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        if (ready && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    startActivity(Intent(this, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_VOICE, true)); return true
                }
                // Anlè nèt (sou dènye chanèl yo) → Gid la, tankou "⌃ Ale nan Gid la"
                KeyEvent.KEYCODE_DPAD_UP -> if (currentFocus?.parent === recentList) {
                    startActivity(Intent(this, EpgActivity::class.java)); return true
                }
                KeyEvent.KEYCODE_GUIDE -> { startActivity(Intent(this, EpgActivity::class.java)); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Desann nan paj la: Back remonte anlè an premye
        if (scroll.scrollY > 0) { scroll.smoothScrollTo(0, 0); recentList.getChildAt(0)?.requestFocus(); return }
        BackToExit.press(this)
    }

    // ------------------------------------------------------------ Kat yo

    enum class Style { LOGO, PORTRAIT, WIDE }

    private inner class ChannelAdapter(val style: Style) : RecyclerView.Adapter<ChannelAdapter.VH>() {
        var items: List<Channel> = emptyList()

        inner class VH(val root: FrameLayout, val logo: ImageView, val name: TextView?, val prog: TextView?, val bar: ProgressBar?) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = FrameLayout(parent.context).apply { isFocusable = true; isClickable = true }
            val logo = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            return when (style) {
                Style.LOGO -> {
                    root.background = focusBg(0x22FFFFFF, 6f)
                    root.layoutParams = RecyclerView.LayoutParams(dp(88), dp(44)).apply { rightMargin = dp(8) }
                    root.addView(logo, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) })
                    val name = text(11f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                    root.addView(name, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(6), 0, dp(6), 0) })
                    VH(root, logo, name, null, null)
                }
                Style.PORTRAIT -> {
                    root.background = focusBg(CARD, 6f)
                    root.layoutParams = RecyclerView.LayoutParams(dp(106), dp(188)).apply { rightMargin = dp(10) }
                    root.addView(badge(getString(R.string.mg_live_badge), LIVE_GREEN, 0xFF04241A.toInt()), FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7); topMargin = dp(7) })
                    root.addView(logo, FrameLayout.LayoutParams(-1, dp(70), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36); setMargins(dp(12), dp(36), dp(12), 0) })
                    val prog = text(12f, Color.WHITE, true).apply { maxLines = 3; gravity = Gravity.CENTER_HORIZONTAL }
                    root.addView(prog, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(22)) })
                    val bar = bar(LIVE_GREEN)
                    root.addView(bar, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(10)) })
                    VH(root, logo, null, prog, bar)
                }
                Style.WIDE -> {
                    root.background = focusBg(CARD, 6f)
                    root.layoutParams = RecyclerView.LayoutParams(dp(172), dp(112)).apply { rightMargin = dp(10) }
                    root.addView(logo, FrameLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(14), dp(12), dp(14), 0) })
                    val prog = text(11f, Color.WHITE, true)
                    root.addView(prog, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(24)) })
                    root.addView(badge(getString(R.string.mg_live_badge), LIVE_GREEN, 0xFF04241A.toInt()), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(8), dp(24)) })
                    val bar = bar(LIVE_GREEN)
                    root.addView(bar, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
                    VH(root, logo, null, prog, bar)
                }
            }
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            val now = EpgRepository.nowFor(c)
            h.name?.text = if (c.icon.isNullOrBlank()) c.name else ""
            h.logo.load(c.icon) { listener(onError = { _, _ -> h.name?.text = c.name }) }
            h.prog?.text = now?.title?.ifBlank { null } ?: c.name
            h.bar?.let { if (now != null) { it.progress = now.progress(); it.visibility = View.VISIBLE } else it.visibility = View.INVISIBLE }
            h.root.contentDescription = c.name
            h.root.setOnClickListener { openFull(c) }
            h.root.setOnFocusChangeListener { _, has ->
                // Dènye chanèl yo: gwo kare a chanje sou chanèl remòt la chwazi a
                if (has && style == Style.LOGO) showTile(c, 700)
            }
        }
    }

    private inner class PosterAdapter(var items: List<VodItem> = emptyList()) : RecyclerView.Adapter<PosterAdapter.VH>() {
        var cont: List<WatchProgress> = emptyList()

        inner class VH(val root: FrameLayout, val img: ImageView, val name: TextView, val bar: ProgressBar) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = if (cont.isNotEmpty()) cont.size else items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val wide = cont.isNotEmpty()
            val root = FrameLayout(parent.context).apply {
                isFocusable = true; isClickable = true; background = focusBg(CARD, 6f); setPadding(dp(3), dp(3), dp(3), dp(3))
                layoutParams = RecyclerView.LayoutParams(if (wide) dp(172) else dp(100), if (wide) dp(104) else dp(150)).apply { rightMargin = dp(10) }
            }
            val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
            root.addView(img, FrameLayout.LayoutParams(-1, -1))
            val name = text(11f, Color.WHITE, true).apply { maxLines = 2; setShadowLayer(dpf(3f), 0f, dpf(1f), 0xE6000000.toInt()) }
            root.addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(6), 0, dp(6), dp(8)) })
            val bar = bar(0xFFE5202E.toInt())
            root.addView(bar, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
            return VH(root, img, name, bar)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            if (cont.isNotEmpty()) {
                val p = cont[position]
                h.img.load(Img.poster(p.poster)) { error(R.drawable.ic_tv) }
                h.name.text = p.title
                h.bar.progress = p.percent; h.bar.visibility = View.VISIBLE
                h.root.setOnClickListener { resume(p) }
                return
            }
            val it = items[position]
            h.img.load(Img.poster(it.poster)) { error(R.drawable.ic_tv) }
            h.name.text = if (it.poster.isNullOrBlank()) it.name else ""
            h.bar.visibility = View.GONE
            h.root.contentDescription = it.name
            h.root.setOnClickListener { _ -> startActivity(VodDetailActivity.intent(this@MagicHomeActivity, it)) }
        }
    }

    private fun resume(p: WatchProgress) {
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "movie")
                .putExtra(VodPlayerActivity.EXTRA_URL, p.url)
                .putExtra(VodPlayerActivity.EXTRA_KEY, p.key)
                .putExtra(VodPlayerActivity.EXTRA_TITLE, p.title)
                .putExtra(VodPlayerActivity.EXTRA_POSTER, p.poster)
                .putExtra(VodPlayerActivity.EXTRA_PARENT_ID, p.parentId)
        )
    }
}
