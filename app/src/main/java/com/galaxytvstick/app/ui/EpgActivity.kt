package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.EpgRepository
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.Program
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityEpgBinding
import com.galaxytvstick.app.databinding.ItemEpgRowBinding
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.galaxytvstick.app.data.Category
import com.galaxytvstick.app.databinding.ItemCategoryBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran TV prensipal la: meni agoch, kategori, ti apèsi videyo an dirèk ak enfo pwogram nan anlè,
 * epi gid la an griy (chanèl × lè) anba. Fenèt la montre 1 è 30; ◀ ▶ nan kwen yo deplase l 30 minit.
 * OK sou yon chanèl = mete l nan apèsi a; OK ankò = plen ekran.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EpgActivity : AppCompatActivity() {

    companion object {
        private const val SLOT_MS = 30 * 60 * 1000L
        private const val WINDOW_MS = 3 * SLOT_MS           // 1 è 30
        private const val MAX_BACK_MS = 2 * 60 * 60 * 1000L  // pa ale plis pase 2 è anvan
        private const val MAX_FORWARD_MS = 22 * 60 * 60 * 1000L
        private const val CAT_CATCHUP = "__catchup"
    }

    private lateinit var b: ActivityEpgBinding
    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var overlay: OverlayController

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private var channels: List<Channel> = emptyList()
    private var windowStart = floorToSlot(System.currentTimeMillis())
    /** Fenèt "kounye a": ◀ sou premye selil la louvri kategori yo sèlman lè gid la sou fenèt sa a. */
    private var homeStart = windowStart
    /** 0 = gid la, 1 = kategori yo vizib, 2 = meni an vizib. */
    private var zone = 0
    /** Konbyen tan nou ka rekile: jiska achiv catch-up la (maks 7 jou), sinon 2 è. */
    private var maxBackMs = MAX_BACK_MS
    private lateinit var adapter: RowAdapter

    // Kategori
    private val catAdapter = CatAdapter()
    private var selectedCat = ChannelLists.CAT_ALL
    private var pendingCat = ChannelLists.CAT_ALL
    private val handler = Handler(Looper.getMainLooper())
    private val applyPendingCat = Runnable { if (pendingCat != selectedCat) applyCategory(pendingCat) }

    // Ti apèsi videyo a
    private var player: ExoPlayer? = null
    private var previewCh: Channel? = null
    private var previewRetries = 0
    private val startPreviewLater = Runnable { previewCh?.let { startPreview(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val account = prefs.account ?: run { finish(); return }
        api = XtreamApi(account)

        b = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(b.root)

        channels = ChannelStore.all
        channels.filter { it.tvArchive && it.directUrl == null }.maxOfOrNull { it.archiveDays }?.let { d ->
            if (d > 0) maxBackMs = d.coerceAtMost(7) * 24 * 60 * 60 * 1000L
        }
        adapter = RowAdapter()
        b.rows.layoutManager = LinearLayoutManager(this)
        b.rows.adapter = adapter
        b.rows.itemAnimator = null
        adapter.items = channels

        b.categories.layoutManager = LinearLayoutManager(this)
        b.categories.adapter = catAdapter
        b.categories.itemAnimator = null
        catAdapter.items = ChannelLists.categories(this)
        catAdapter.notifyDataSetChanged()

        b.menuSearch.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        b.menuTv.setOnClickListener { pendingCat = if (selectedCat == CAT_CATCHUP || selectedCat == ChannelLists.CAT_FAV) ChannelLists.CAT_ALL else selectedCat; resetWindow(); focusGrid() }
        b.menuMovies.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, "movie")) }
        b.menuSeries.setOnClickListener { startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, "series")) }
        b.menuFav.setOnClickListener {
            if (channelsOf(ChannelLists.CAT_FAV).isEmpty()) android.widget.Toast.makeText(this, R.string.home_no_fav, android.widget.Toast.LENGTH_LONG).show()
            else { pendingCat = ChannelLists.CAT_FAV; resetWindow(); focusGrid() }
        }
        b.menuCatchup.setOnClickListener { openCatchup() }
        b.menuSettings.setOnClickListener { showSettings() }
        val cfg = PanelState.config
        b.accName.text = cfg.accountName
        b.accName.visibility = if (cfg.accountName.isBlank()) View.GONE else View.VISIBLE
        b.accPlan.text = if (cfg.accountPlan.isBlank()) "" else getString(R.string.home_plan_fmt, cfg.accountPlan)
        b.accPlan.visibility = if (cfg.accountPlan.isBlank()) View.GONE else View.VISIBLE
        b.accExp.text = if (cfg.accountExpiry.isBlank()) "" else getString(R.string.home_exp_fmt, cfg.accountExpiry.replace("T", " "))
        b.accExp.visibility = if (cfg.accountExpiry.isBlank()) View.GONE else View.VISIBLE
        b.rows.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateNowLine() }

        previewCh = ChannelStore.all.firstOrNull { it.streamId == prefs.lastChannelId } ?: channels.firstOrNull()
        previewCh?.let { b.previewLogo.load(it.icon) { error(R.drawable.ic_tv) } }
        // Lè kliyan an desann nan lis la pandan l nan tan ki pase: chaje achiv nouvo chanèl yo
        b.rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) { ensureArchive((currentFocus?.tag as? Cell)?.rowPos ?: -1); fillMissing() }
            }
        })

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = false,
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )

        updateHeader()
        loadEpg()

        // Revèy la ak liy "kounye a" mete yo ajou chak minit
        lifecycleScope.launch {
            while (isActive) {
                b.clock.text = timeFmt.format(Date())
                updateNowLine()
                delay(60_000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        overlay.start()
        // Tann yon ti moman pou player plen ekran an lage koneksyon l anvan (playlist ki pèmèt 1 sèl ekran)
        ChannelStore.all.firstOrNull { it.streamId == prefs.lastChannelId }?.let { previewCh = it }
        handler.postDelayed(startPreviewLater, 800)
        // Di panel la kliyan an ap gade gid la
        val last = ChannelStore.all.firstOrNull { it.streamId == prefs.lastChannelId }
        val body = org.json.JSONObject().put("screen", "epg").put("playlistName", prefs.account?.name ?: "")
        if (last != null) body.put("channel", org.json.JSONObject().put("id", last.streamId).put("num", last.num).put("name", last.name))
        lifecycleScope.launch { runCatching { com.galaxytvstick.app.data.PanelApi(prefs).status(body) } }
    }

    override fun onStop() {
        super.onStop()
        overlay.stop()
        handler.removeCallbacks(startPreviewLater)
        handler.removeCallbacks(applyPendingCat)
        player?.release()
        player = null
        b.preview.player = null
        b.previewLogo.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ Ti apèsi videyo

    private val previewListener = object : Player.Listener {
        override fun onRenderedFirstFrame() { b.previewLogo.visibility = View.GONE }
        override fun onPlayerError(error: PlaybackException) {
            b.previewLogo.visibility = View.VISIBLE
            // Ti koupi: relanse apèsi a (maks 5 fwa youn dèyè lòt)
            if (previewRetries++ < 5) { handler.removeCallbacks(startPreviewLater); handler.postDelayed(startPreviewLater, 2_500) }
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) previewRetries = 0
            if (state == Player.STATE_ENDED) { handler.removeCallbacks(startPreviewLater); handler.postDelayed(startPreviewLater, 1_500) }
        }
    }

    private fun startPreview(ch: Channel) {
        previewCh = ch
        b.previewLogo.visibility = View.VISIBLE
        b.previewLogo.load(ch.icon) { error(R.drawable.ic_tv) }
        val p = player ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(XtreamApi.http).setUserAgent(XtreamApi.USER_AGENT))
            )
            .build().also {
                player = it
                b.preview.player = it
                it.addListener(previewListener)
            }
        p.setMediaItem(PlayerActivity.buildItem(api, ch))
        p.prepare()
        p.playWhenReady = true
    }

    // ------------------------------------------------------------ Kategori

    private fun channelsOf(id: String): List<Channel> =
        if (id == CAT_CATCHUP) ChannelStore.all.filter { it.tvArchive && it.archiveDays > 0 && it.directUrl == null }
        else ChannelLists.channelsFor(id, prefs)

    /** Remete gid la sou lè kounye a. */
    private fun resetWindow() {
        val now = floorToSlot(System.currentTimeMillis())
        if (windowStart == now && homeStart == now) return
        windowStart = now
        homeStart = now
        updateHeader()
        adapter.notifyDataSetChanged()
    }

    /** Montre/kache meni an ak kolòn kategori yo. */
    private fun setZone(z: Int) {
        zone = z
        b.menu.visibility = if (z >= 1) View.VISIBLE else View.GONE
        b.catPanel.visibility = if (z >= 1) View.VISIBLE else View.GONE
        // Premye ◀: sèlman siy yo (chèche, TV, fim…) ak kategori yo. Dezyèm ◀: meni an louvri ak non yo.
        val open = z == 2
        val d = resources.displayMetrics.density
        b.menu.layoutParams = b.menu.layoutParams.apply { width = ((if (open) 200 else 64) * d).toInt() }
        b.menuLogo.visibility = if (open) View.VISIBLE else View.GONE
        b.accBox.visibility = if (open) View.VISIBLE else View.INVISIBLE
        val current = when (selectedCat) { ChannelLists.CAT_FAV -> b.menuFav; CAT_CATCHUP -> b.menuCatchup; else -> b.menuTv }
        val labels = intArrayOf(R.string.menu_search, R.string.menu_tv, R.string.home_movies, R.string.home_series, R.string.menu_favorites, R.string.menu_catchup, R.string.home_settings)
        val icons = intArrayOf(R.drawable.ic_m_search, R.drawable.ic_m_tv, R.drawable.ic_m_movie, R.drawable.ic_m_series, R.drawable.ic_m_star, R.drawable.ic_m_history, R.drawable.ic_m_settings)
        val pad = (14 * d).toInt()
        listOf(b.menuSearch, b.menuTv, b.menuMovies, b.menuSeries, b.menuFav, b.menuCatchup, b.menuSettings).forEachIndexed { i, v ->
            v.text = if (open) getString(labels[i]) else ""
            v.gravity = Gravity.CENTER_VERTICAL
            v.setCompoundDrawablesWithIntrinsicBounds(icons[i], 0, 0, 0)
            v.compoundDrawablePadding = pad
            // Fèmen: siy la nan mitan kare a (kare 44dp, siy 24dp)
            v.setPadding(if (open) pad else (10 * d).toInt(), 0, 0, 0)
            v.isSelected = v === current
        }
    }

    private fun showMenu() {
        setZone(2)
        val item = when (selectedCat) { ChannelLists.CAT_FAV -> b.menuFav; CAT_CATCHUP -> b.menuCatchup; else -> b.menuTv }
        item.post { item.requestFocus() }
    }

    /** Catch Up: sèlman chanèl ki gen achiv, epi gid la kòmanse 1 è 30 dèyè. */
    private fun openCatchup() {
        if (channelsOf(CAT_CATCHUP).isEmpty()) {
            android.widget.Toast.makeText(this, R.string.catchup_unavailable, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        pendingCat = CAT_CATCHUP
        applyCategory(CAT_CATCHUP)
        val now = floorToSlot(System.currentTimeMillis())
        homeStart = now
        windowStart = maxOf(now - WINDOW_MS, floorToSlot(System.currentTimeMillis() - maxBackMs))
        updateHeader()
        adapter.notifyDataSetChanged()
        setZone(0)
        b.rows.post { focusCellInRow(0, first = false); ensureArchive(0) }
    }

    private fun confirmExit() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_confirm)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> finishAffinity() }
            .show()
    }

    private fun applyCategory(id: String) {
        selectedCat = id
        channels = channelsOf(id)
        adapter.items = channels
        b.rows.scrollToPosition(0)
        for (i in 0 until b.categories.childCount) {
            val h = b.categories.getChildViewHolder(b.categories.getChildAt(i)) as? CatAdapter.VH ?: continue
            val pos = h.bindingAdapterPosition
            if (pos >= 0) h.b.root.isSelected = catAdapter.items[pos].id == selectedCat
        }
        if (channels.isEmpty()) {
            b.message.text = getString(R.string.empty_list)
            b.message.visibility = View.VISIBLE
        } else b.message.visibility = View.GONE
        b.rows.post { fillMissing() }
    }

    private fun focusCategories() {
        setZone(1)
        val pos = catAdapter.items.indexOfFirst { it.id == selectedCat }.coerceAtLeast(0)
        b.categories.scrollToPosition(pos)
        b.categories.post { b.categories.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    private fun focusGrid() {
        if (channels.isEmpty()) return
        handler.removeCallbacks(applyPendingCat)
        if (pendingCat != selectedCat) applyCategory(pendingCat)
        if (channels.isEmpty()) return
        val pos = channels.indexOfFirst { it.streamId == previewCh?.streamId }.coerceAtLeast(0)
        setZone(0)
        (b.rows.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        b.rows.post { focusCellInRow(pos, first = true) }
    }

    private inner class CatAdapter : RecyclerView.Adapter<CatAdapter.VH>() {
        var items: List<Category> = emptyList()

        inner class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.b.name.text = c.name
            h.b.root.isSelected = c.id == selectedCat
            // Tankou yon gid TV: lis chanèl la chanje pandan w ap pase sou kategori yo
            h.b.root.setOnFocusChangeListener { _, has ->
                if (has) {
                    pendingCat = c.id
                    handler.removeCallbacks(applyPendingCat)
                    handler.postDelayed(applyPendingCat, 300)
                }
            }
            h.b.root.setOnClickListener { pendingCat = c.id; focusGrid() }
        }
    }

    private fun loadEpg() {
        if (channels.isEmpty()) {
            b.progress.visibility = View.GONE
            b.message.text = getString(R.string.empty_list)
            b.message.visibility = View.VISIBLE
            return
        }
        b.progress.visibility = if (EpgRepository.isLoaded) View.GONE else View.VISIBLE
        if (EpgRepository.isLoaded) focusStartRow()
        lifecycleScope.launch {
            val result = runCatching { EpgRepository.ensureLoaded(api, channels) }
            b.progress.visibility = View.GONE
            result.onSuccess { fresh ->
                if (zone == 0 && (fresh || !b.rows.hasFocus())) {
                    adapter.notifyDataSetChanged()
                    focusStartRow()
                }
                b.message.visibility = View.GONE
                b.rows.post { fillMissing() }
            }.onFailure {
                // Gwo fichye gid la pa disponib: gid la toujou mache chanèl pa chanèl
                EpgRepository.markLoaded()
                focusStartRow()
                b.rows.post { fillMissing() }
            }
        }
    }

    /** Mete fokis sou dènye chanèl yo t ap gade a, sou pwogram k ap pase kounye a. */
    private fun focusStartRow() {
        val pos = channels.indexOfFirst { it.streamId == prefs.lastChannelId }.coerceAtLeast(0)
        (b.rows.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        b.rows.post { focusCellInRow(pos, first = true) }
    }

    // ------------------------------------------------------------ Fenèt lè

    private fun floorToSlot(t: Long): Long = t - (t % SLOT_MS)

    private fun updateHeader() {
        b.dayLabel.text = dayFmt.format(Date(windowStart))
        b.timeHeader.removeAllViews()
        for (i in 0 until (WINDOW_MS / SLOT_MS).toInt()) {
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 0, 0, 0)
                text = timeFmt.format(Date(windowStart + i * SLOT_MS))
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                textSize = 13f
            }
            b.timeHeader.addView(tv)
        }
        updateNowLine()
    }

    /** Liy vètikal ki montre lè li ye kounye a nan griy la. */
    private fun updateNowLine() {
        val frac = (System.currentTimeMillis() - windowStart).toFloat() / WINDOW_MS
        val w = b.rows.width
        val chanW = 184 * resources.displayMetrics.density
        if (w <= chanW || frac < 0f || frac > 1f || channels.isEmpty()) { b.nowLine.visibility = View.GONE; return }
        b.nowLine.translationX = chanW + (w - chanW) * frac
        b.nowLine.visibility = View.VISIBLE
    }

    private fun shiftWindow(deltaMs: Long, rowPos: Int, focusLast: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val target = windowStart + deltaMs
        if (target < floorToSlot(now - maxBackMs) || target > now + MAX_FORWARD_MS) return false
        windowStart = target
        updateHeader()
        adapter.notifyDataSetChanged()
        b.rows.post { focusCellInRow(rowPos, first = !focusLast); ensureArchive(rowPos) }
        return true
    }

    private var fillJob: kotlinx.coroutines.Job? = null

    /** Chanèl ki sou ekran an ki pa gen pwogram nan gwo fichye gid la: mande sèvè a youn pa youn. */
    private fun fillMissing() {
        if (!EpgRepository.isLoaded) return
        fillJob?.cancel()
        fillJob = lifecycleScope.launch {
            delay(400)
            val lm = b.rows.layoutManager as LinearLayoutManager
            val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
            val need = (first..last).mapNotNull { channels.getOrNull(it) }.filter { EpgRepository.needsSingle(it) }
            if (need.isEmpty()) return@launch
            var any = false
            for (ch in need) if (EpgRepository.loadSingle(api, ch)) any = true
            if (any) {
                val row = (currentFocus?.tag as? Cell)?.rowPos ?: -1
                adapter.notifyDataSetChanged()
                if (row >= 0 && zone == 0) b.rows.post { focusCellInRow(row, first = true) }
            }
        }
    }

    /**
     * Catch-up: lè fenèt la nan tan ki pase (plis pase 2 è), chaje EPG achiv chanèl ki vizib yo
     * (XMLTV a pa toujou gen pwogram ki pase yo).
     */
    private fun ensureArchive(focusRow: Int) {
        if (windowStart >= System.currentTimeMillis() - MAX_BACK_MS) return
        val lm = b.rows.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
        val need = (first..last).mapNotNull { channels.getOrNull(it) }.filter { it.tvArchive && !EpgRepository.hasArchive(it) }
        if (need.isEmpty()) return
        lifecycleScope.launch {
            var any = false
            for (ch in need) if (EpgRepository.loadArchive(api, ch)) any = true
            if (any) {
                val row = (currentFocus?.tag as? Cell)?.rowPos ?: focusRow
                adapter.notifyDataSetChanged()
                if (row >= 0) b.rows.post { focusCellInRow(row, first = true) }
            }
        }
    }

    private fun focusCellInRow(pos: Int, first: Boolean) {
        val holder = b.rows.findViewHolderForAdapterPosition(pos) as? RowAdapter.VH ?: return
        val cells = holder.b.programs
        if (cells.childCount == 0) return
        // "first" = pwogram k ap pase kounye a si l nan fenèt la, sinon premye selil la
        val target = if (first) {
            (0 until cells.childCount).map { cells.getChildAt(it) }
                .firstOrNull { (it.tag as? Cell)?.program?.isNow() == true } ?: cells.getChildAt(0)
        } else cells.getChildAt(cells.childCount - 1)
        target.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && b.overlay.blocker.visibility != View.VISIBLE) {
            val focused = currentFocus
            val cell = focused?.tag as? Cell
            if (cell != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (cell.isLast) {
                        shiftWindow(SLOT_MS, cell.rowPos, focusLast = false)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (cell.isFirst) {
                        // Sou fenèt "kounye a": louvri kategori yo. Nan tan ki pase (catch-up): rekile.
                        if (windowStart == homeStart || !shiftWindow(-SLOT_MS, cell.rowPos, focusLast = true)) focusCategories()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> { showMenu(); return true }
                    // ⏪ ⏩ sou remòt la: deplase 2 è alafwa (pou rive vit nan jou ki pase yo)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { shiftWindow(-WINDOW_MS, cell.rowPos, focusLast = false); return true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shiftWindow(WINDOW_MS, cell.rowPos, focusLast = false); return true }
                }
            } else if (focused != null && focused.parent === b.categories) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK -> { focusGrid(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { showMenu(); return true }
                }
            } else if (focused != null && focused.parent === b.menuItems) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { focusCategories(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> return true
                    KeyEvent.KEYCODE_BACK -> { confirmExit(); return true }
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (zone == 2) confirmExit() else showMenu()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ Aksyon

    private fun showDetail(ch: Channel, p: Program?) {
        b.detailChannel.text = "${ch.num}  ${ch.name}"
        if (p != null) {
            b.detailTitle.text = p.title
            b.detailTime.text = "${dayFmt.format(Date(p.start))}  ${timeFmt.format(Date(p.start))} - ${timeFmt.format(Date(p.end))}" +
                (if (p.isNow()) "   ·   ${((p.end - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)} min" else "") +
                (if (ch.canCatchup(p)) "    ${getString(R.string.epg_catchup_hint)}" else "")
            b.detailDesc.text = p.desc
        } else {
            b.detailTitle.text = getString(if (EpgRepository.isLoaded) R.string.epg_no_info else R.string.epg_loading)
            b.detailTime.text = ""
            b.detailDesc.text = ""
        }
    }

    /** Reglaj: rechaje chanèl yo, chanje playlist, enfòmasyon aparèy la, soti. */
    private fun onOff(on: Boolean) = getString(if (on) R.string.switch_on else R.string.switch_off)

    private fun info(title: Int, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showSettings() {
        val cfg = PanelState.config
        val exp = cfg.accountExpiry.replace("T", " ").ifBlank { "—" }
        val version = com.galaxytvstick.app.BuildConfig.VERSION_NAME
        val items = arrayOf(
            getString(R.string.set_expiry, exp),
            getString(R.string.set_device_info),
            getString(if (prefs.parentalOn) R.string.set_parental_on else R.string.set_parental_off),
            getString(R.string.set_autostart, onOff(prefs.autoStart)),
            getString(R.string.set_last_channel, onOff(prefs.startLastChannel)),
            getString(R.string.set_reload),
            getString(R.string.set_change_playlist),
            getString(R.string.set_about, version),
            getString(R.string.exit_title)
        )
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.home_settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> info(R.string.set_expiry_title, listOf(
                        cfg.accountName,
                        if (cfg.accountPlan.isBlank()) "" else getString(R.string.home_plan_fmt, cfg.accountPlan),
                        getString(R.string.home_exp_fmt, exp)
                    ).filter { it.isNotBlank() }.joinToString("\n"))
                    1 -> info(R.string.set_device_info, getString(R.string.set_info_fmt, cfg.accountName, prefs.mac, prefs.deviceKey, version))
                    2 -> parentalControl()
                    3 -> { prefs.autoStart = !prefs.autoStart; showSettings() }
                    4 -> { prefs.startLastChannel = !prefs.startLastChannel; showSettings() }
                    5 -> { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish() }
                    6 -> {
                        startActivity(
                            Intent(this, LoginActivity::class.java)
                                .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                    7 -> info(R.string.set_about_title, getString(R.string.set_about_fmt, version, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE))
                    else -> finishAffinity()
                }
            }
            .show()
    }

    /** Mande yon PIN 4 chif (klavye Fire TV a). */
    private fun askPin(title: Int, onPin: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "••••"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_dim))
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4) onPin(pin) else android.widget.Toast.makeText(this, R.string.parental_pin_4, android.widget.Toast.LENGTH_LONG).show()
            }
            .show()
        input.requestFocus()
    }

    /** Kontwòl paran: kache kategori pou granmoun. Limen l = kreye/konfime PIN; etenn li = antre PIN lan. */
    private fun parentalControl() {
        fun setParental(on: Boolean) {
            prefs.parentalOn = on
            ChannelStore.hideAdult = on
            android.widget.Toast.makeText(this, if (on) R.string.parental_now_on else R.string.parental_now_off, android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        if (prefs.parentalOn) {
            askPin(R.string.parental_enter_pin) { pin ->
                if (pin == prefs.parentalPin) setParental(false)
                else android.widget.Toast.makeText(this, R.string.parental_wrong_pin, android.widget.Toast.LENGTH_LONG).show()
            }
        } else if (prefs.parentalPin.isEmpty()) {
            askPin(R.string.parental_new_pin) { pin -> prefs.parentalPin = pin; setParental(true) }
        } else {
            askPin(R.string.parental_enter_pin) { pin ->
                if (pin == prefs.parentalPin) setParental(true)
                else android.widget.Toast.makeText(this, R.string.parental_wrong_pin, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Kòmand sèvis kliyan an pandan kliyan an nan gid la. */
    private fun onRemoteCommand(cmd: com.galaxytvstick.app.data.RemoteCommand) {
        when (cmd.type) {
            "play" -> ChannelStore.all.firstOrNull { it.streamId == cmd.channelId }?.let { openFull(it) }
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

    /** Gade yon pwogram ki fin pase (catch-up / timeshift Xtream). */
    private fun playCatchup(ch: Channel, p: Program) {
        val minutes = ((p.end - p.start) / 60_000).toInt() + 1
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "catchup")
                .putExtra(VodPlayerActivity.EXTRA_URL, api.catchupUrl(ch, p.start, minutes, prefs.serverTimezone))
                .putExtra(VodPlayerActivity.EXTRA_TITLE, p.title.ifBlank { ch.name })
                .putExtra(VodPlayerActivity.EXTRA_SUBTITLE, "${ch.name}  ·  ${dayFmt.format(Date(p.start))}  ${timeFmt.format(Date(p.start))} - ${timeFmt.format(Date(p.end))}")
                .putExtra(VodPlayerActivity.EXTRA_KEY, "c:${ch.streamId}:${p.start}")
                .putExtra(VodPlayerActivity.EXTRA_POSTER, ch.icon)
                .putExtra(VodPlayerActivity.EXTRA_STREAM_ID, ch.streamId)
        )
    }

    /** OK sou yon chanèl: premye fwa li jwe nan ti apèsi a; OK ankò sou menm chanèl la louvri l plen ekran. */
    private fun select(ch: Channel) {
        if (previewCh?.streamId == ch.streamId && previewCh?.directUrl == ch.directUrl) { openFull(ch); return }
        if (ch.directUrl == null) prefs.lastChannelId = ch.streamId
        handler.removeCallbacks(startPreviewLater)
        previewRetries = 0
        startPreview(ch)
    }

    private fun openFull(ch: Channel) {
        handler.removeCallbacks(startPreviewLater)
        player?.release()
        player = null
        ChannelStore.current = channels.ifEmpty { ChannelStore.all }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, ch.streamId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    // ------------------------------------------------------------ Adapter griy

    /** Enfo ki atache sou chak selil pwogram (pou navigasyon ak remòt la). */
    private data class Cell(val rowPos: Int, val program: Program?, val isFirst: Boolean, val isLast: Boolean)

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {

        var items: List<Channel> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        inner class VH(val b: ItemEpgRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpgRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            h.b.number.text = ch.num.toString()
            h.b.name.text = ch.name
            h.b.logo.load(ch.icon) { error(R.drawable.ic_tv) }

            val ws = windowStart
            val we = windowStart + WINDOW_MS
            val progs = EpgRepository.programsFor(ch).filter { it.end > ws && it.start < we }

            // Kalkile blòk yo: pwogram yo + espas vid ant yo
            val blocks = ArrayList<Triple<Long, Long, Program?>>()
            var cursor = ws
            for (p in progs) {
                val s = maxOf(p.start, cursor)
                val e = minOf(p.end, we)
                if (e <= s) continue
                if (s > cursor) blocks.add(Triple(cursor, s, null))
                blocks.add(Triple(s, e, p))
                cursor = e
            }
            if (cursor < we) blocks.add(Triple(cursor, we, null))

            val row = h.b.programs
            row.removeAllViews()
            blocks.forEachIndexed { i, (s, e, p) ->
                row.addView(makeCell(ch, p, s, e, Cell(position, p, i == 0, i == blocks.lastIndex)))
            }
        }

        private fun makeCell(ch: Channel, p: Program?, s: Long, e: Long, cell: Cell): View {
            val ctx = this@EpgActivity
            val weight = ((e - s) / 60_000f).coerceAtLeast(1f)
            return TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    setMargins(2, 0, 2, 0)
                }
                tag = cell
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_epg_cell)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 0, 8, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textSize = 13f
                val catchup = p != null && ch.canCatchup(p)
                text = p?.let { "${if (catchup) "⏪ " else ""}${it.title}" } ?: "—"
                val live = p?.isNow() == true
                val past = e <= System.currentTimeMillis()
                setTextColor(ContextCompat.getColorStateList(ctx, if (p == null || past) R.color.epg_cell_text_dim else R.color.epg_cell_text))
                isActivated = live
                setOnFocusChangeListener { _, has -> if (has) showDetail(ch, p) }
                setOnClickListener { if (catchup && p != null) playCatchup(ch, p) else select(ch) }
            }
        }
    }
}
