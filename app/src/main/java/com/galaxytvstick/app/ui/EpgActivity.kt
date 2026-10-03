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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gid TV an griy (chanèl × lè), tankou TiviMate.
 * Fenèt la montre 2 èdtan; ◀ ▶ nan kwen yo deplase l 30 minit.
 */
class EpgActivity : AppCompatActivity() {

    companion object {
        private const val SLOT_MS = 30 * 60 * 1000L
        private const val WINDOW_MS = 4 * SLOT_MS           // 2 èdtan
        private const val MAX_BACK_MS = 2 * 60 * 60 * 1000L  // pa ale plis pase 2 è anvan
        private const val MAX_FORWARD_MS = 22 * 60 * 60 * 1000L
    }

    private lateinit var b: ActivityEpgBinding
    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var overlay: OverlayController

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private var channels: List<Channel> = emptyList()
    private var windowStart = floorToSlot(System.currentTimeMillis())
    /** Konbyen tan nou ka rekile: jiska achiv catch-up la (maks 7 jou), sinon 2 è. */
    private var maxBackMs = MAX_BACK_MS
    private lateinit var adapter: RowAdapter

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
        // Lè kliyan an desann nan lis la pandan l nan tan ki pase: chaje achiv nouvo chanèl yo
        b.rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) ensureArchive((currentFocus?.tag as? Cell)?.rowPos ?: -1)
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
                delay(60_000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        overlay.start()
        // Di panel la kliyan an ap gade gid la
        val last = ChannelStore.all.firstOrNull { it.streamId == prefs.lastChannelId }
        val body = org.json.JSONObject().put("screen", "epg").put("playlistName", prefs.account?.name ?: "")
        if (last != null) body.put("channel", org.json.JSONObject().put("id", last.streamId).put("num", last.num).put("name", last.name))
        lifecycleScope.launch { runCatching { com.galaxytvstick.app.data.PanelApi(prefs).status(body) } }
    }

    override fun onStop() {
        super.onStop()
        overlay.stop()
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
                if (fresh || !b.rows.hasFocus()) {
                    adapter.notifyDataSetChanged()
                    focusStartRow()
                }
                val hasAny = channels.any { EpgRepository.programsFor(it).isNotEmpty() }
                b.message.text = getString(R.string.epg_empty)
                b.message.visibility = if (hasAny) View.GONE else View.VISIBLE
            }.onFailure {
                b.message.text = getString(R.string.epg_error, it.message ?: "")
                b.message.visibility = View.VISIBLE
                focusStartRow()
            }
        }
    }

    /** Mete fokis sou dènye chanèl yo t ap gade a, sou pwogram k ap pase kounye a. */
    private fun focusStartRow() {
        val pos = channels.indexOfFirst { it.streamId == prefs.lastChannelId }.coerceAtLeast(0)
        (b.rows.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 120)
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
                        shiftWindow(-SLOT_MS, cell.rowPos, focusLast = true)
                        return true // pa kite fokis la soti nan griy la
                    }
                    // ⏪ ⏩ sou remòt la: deplase 2 è alafwa (pou rive vit nan jou ki pase yo)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { shiftWindow(-WINDOW_MS, cell.rowPos, focusLast = false); return true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { shiftWindow(WINDOW_MS, cell.rowPos, focusLast = false); return true }
                }
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
                (if (ch.canCatchup(p)) "    ${getString(R.string.epg_catchup_hint)}" else "")
            b.detailDesc.text = p.desc
        } else {
            b.detailTitle.text = getString(if (EpgRepository.isLoaded) R.string.epg_no_info else R.string.epg_loading)
            b.detailTime.text = ""
            b.detailDesc.text = ""
        }
    }

    /** Kòmand sèvis kliyan an pandan kliyan an nan gid la. */
    private fun onRemoteCommand(cmd: com.galaxytvstick.app.data.RemoteCommand) {
        when (cmd.type) {
            "play" -> ChannelStore.all.firstOrNull { it.streamId == cmd.channelId }?.let { play(it) }
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

    private fun play(ch: Channel) {
        ChannelStore.current = channels
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, ch.streamId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
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
                    setMargins(3, 0, 3, 0)
                }
                tag = cell
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_focus)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 0, 6, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                textSize = 12f
                val catchup = p != null && ch.canCatchup(p)
                text = p?.let { "${if (catchup) "⏪ " else ""}${it.title}\n${timeFmt.format(Date(it.start))} - ${timeFmt.format(Date(it.end))}" }
                    ?: getString(R.string.epg_no_info)
                val live = p?.isNow() == true
                setTextColor(ContextCompat.getColor(ctx, if (live) R.color.accent2 else if (p == null) R.color.text_dim else R.color.text))
                isActivated = live
                setOnFocusChangeListener { _, has -> if (has) showDetail(ch, p) }
                setOnClickListener { if (catchup && p != null) playCatchup(ch, p) else play(ch) }
            }
        }
    }
}
