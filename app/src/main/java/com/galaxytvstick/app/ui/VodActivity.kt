package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Category
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.WatchProgress
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.data.VodStore
import com.galaxytvstick.app.databinding.ActivityVodBinding
import com.galaxytvstick.app.databinding.ItemPosterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.text.Normalizer

/**
 * Films ak Séries (tankou TiviMate Premium):
 * "▶ Kontinye gade", "★ Favori", "Tout", kategori sèvè a, rechèch, afich ak nòt.
 * Kenbe OK sou yon afich pou mete l nan favori.
 */
class VodActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind" // "movie" oswa "series"
        private const val CAT_CONTINUE = "__continue"
        private const val CAT_FAV = "__fav"
        private const val CAT_ALL = "__all"
        private const val CAT_NEW = "__new"
    }

    /** Sa yon kaz nan griy la montre: yon fim/seri oswa yon "Kontinye gade". */
    private data class Entry(val item: VodItem?, val progress: WatchProgress?)

    private lateinit var b: ActivityVodBinding
    private lateinit var prefs: Prefs
    private lateinit var account: com.galaxytvstick.app.data.Account
    /** Lis konplè a (soti nan VodStore: memwa/disk, mete ajou an silans). */
    private var data: VodStore.Data? = null
    private lateinit var history: WatchHistory
    private lateinit var overlay: OverlayController
    private var kind = VodKind.MOVIE
    private var selected = CAT_CONTINUE
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var detailPrefetch: Runnable? = null

    private val catAdapter = CategoryAdapter { selectCategory(it.id, focusGrid = true) }
    private val gridAdapter = PosterAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVodBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        account = prefs.account ?: run { finish(); return }
        history = WatchHistory(this)

        kind = if (intent.getStringExtra(EXTRA_KIND) == "series") VodKind.SERIES else VodKind.MOVIE
        b.categories.layoutManager = TvLinearLayoutManager(this)
        b.categories.adapter = catAdapter
        b.grid.layoutManager = TvGridLayoutManager(this, 5)
        b.grid.adapter = gridAdapter
        b.grid.itemAnimator = null

        b.btnLive.setOnClickListener { finish() }
        b.btnSwitch.setOnClickListener {
            startActivity(Intent(this, VodActivity::class.java).putExtra(EXTRA_KIND, if (kind == VodKind.MOVIE) "series" else "movie"))
            finish()
        }
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, bf: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onSearch(s?.toString().orEmpty()) }
        })

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = false, showTicker = false,
            onConfig = { cfg ->
                // Panel la kache / montre kontni granmoun: aplike l touswit, an silans
                val sig = adultSig(cfg)
                if (sig != lastAdultSig) { lastAdultSig = sig; data?.let { applyData(it, first = false, reselect = true) } }
            },
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )
        lastAdultSig = adultSig(PanelState.config)
        applyKindLabels()
        loadCategories()
    }

    override fun onStart() {
        super.onStart()
        overlay.start()
        val body = org.json.JSONObject().put("screen", if (kind == VodKind.MOVIE) "movies" else "series").put("playlistName", prefs.account?.name ?: "")
        lifecycleScope.launch { runCatching { PanelApi(prefs).status(body) } }
    }

    override fun onResume() {
        super.onResume()
        // Retounen soti nan player a: mete "Kontinye gade" ak ba pwogrè yo ajou
        gridAdapter.notifyDataSetChanged()
        if (selected == CAT_CONTINUE) selectCategory(CAT_CONTINUE, focusGrid = false)
    }

    override fun onStop() {
        super.onStop()
        overlay.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (VodStore.onUpdated === updListener) VodStore.onUpdated = null
    }

    private fun applyKindLabels() {
        b.vodHeader.text = getString(if (kind == VodKind.MOVIE) R.string.movies else R.string.series)
        b.btnSwitch.text = getString(if (kind == VodKind.MOVIE) R.string.series else R.string.movies)
    }

    // ------------------------------------------------------------ Kategori

    private fun specialCategories(): List<Category> = listOf(
        Category(CAT_CONTINUE, getString(R.string.vod_continue)),
        Category(CAT_FAV, getString(R.string.cat_favorites)),
        Category(CAT_NEW, getString(R.string.vod_new_releases)),
        Category(CAT_ALL, getString(R.string.vod_all))
    )

    /** Kontwòl paran: ID kategori pou granmoun yo (pa kalite). */
    private val adultCats = HashMap<VodKind, Set<String>>()
    private fun noAdult(list: List<VodItem>): List<VodItem> {
        val bad = adultCats[kind]
        return if (bad.isNullOrEmpty()) list else list.filter { it.categoryId !in bad }
    }

    private fun loadCategories() {
        catAdapter.items = specialCategories()
        val now = VodStore.peek(account, kind)
        if (now != null) applyData(now, first = true)
        else lifecycleScope.launch {
            // Disk la (kèk milisegonn); rezo a sèlman si pa janm gen anyen sou aparèy la
            val d = VodStore.cached(this@VodActivity, account, kind) ?: run {
                showLoading(true)
                val r = runCatching { VodStore.fetch(this@VodActivity, account, kind) }
                showLoading(false)
                r.getOrElse { showMessage(getString(R.string.error_network, it.message ?: "")); return@launch }
            }
            applyData(d, first = true)
        }
        // Mizajou an silans: ekran an pa janm tann li
        VodStore.onUpdated = updListener
        VodStore.refresh(this, account, kind)
    }

    private val updListener: (VodKind) -> Unit = { k -> if (k == kind) runOnUiThread { VodStore.peek(account, kind)?.let { applyData(it, first = false) } } }

    private fun adultSig(c: com.galaxytvstick.app.data.PanelConfig) = listOf(c.forceHideAdult, c.blockedMovies, c.blockedSeries).hashCode()
    private var lastAdultSig = 0

    private fun applyData(d: VodStore.Data, first: Boolean, reselect: Boolean = false) {
        if (isFinishing || isDestroyed) return
        data = d
        val raw = d.categories
        val cfg = PanelState.config
        val adultOff = ChannelStore.hideAdult || cfg.forceHideAdult
        val blocked = if (kind == VodKind.MOVIE) cfg.blockedMovies else cfg.blockedSeries
        fun isBad(c: com.galaxytvstick.app.data.Category) = (adultOff && ChannelStore.isAdult(c.name)) || c.name.trim().uppercase() in blocked
        val cats = raw.filter { !isBad(it) }
        adultCats[kind] = raw.filter { isBad(it) }.map { it.id }.toHashSet()
        val newCats = specialCategories() + cats
        if (newCats != catAdapter.items) catAdapter.items = newCats
        if (first) {
            val start = if (history.continueWatching().any { it.kind == kind }) CAT_CONTINUE else null
            selectCategory(start ?: cats.firstOrNull()?.id ?: CAT_ALL, focusGrid = false)
            focusCategories()
        } else if (reselect) {
            val sel = selected
            val ok = sel.startsWith("__") || cats.any { it.id == sel }
            selectCategory(if (ok) sel else CAT_ALL, focusGrid = false)
        }
    }

    private fun focusCategories() {
        b.categories.post {
            val pos = catAdapter.items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            b.categories.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
        }
    }

    private fun selectCategory(id: String, focusGrid: Boolean) {
        selected = id
        catAdapter.selectedId = id
        loadJob?.cancel()
        val name = catAdapter.items.firstOrNull { it.id == id }?.name.orEmpty()
        when (id) {
            CAT_CONTINUE -> {
                val list = history.continueWatching().filter { it.kind == kind }.map { Entry(null, it) }
                show(name, list, getString(R.string.vod_continue_empty), focusGrid)
            }
            CAT_FAV -> {
                val keys = history.favorites.filter { it.startsWith(if (kind == VodKind.MOVIE) "m:" else "s:") }
                show(name, history.rememberedItems(keys).map { Entry(it, null) }, getString(R.string.vod_fav_empty), focusGrid)
            }
            CAT_NEW -> {
                // Dènye ane yo: fim/seri ane sa a ak ane pase, pi resan an premye (ane, epi dat founisè a ajoute l)
                val d = data ?: return
                val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                val all = noAdult(d.items)
                var list = all.filter { (it.year.take(4).toIntOrNull() ?: 0) >= y - 1 }
                if (list.size < 30) list = all.filter { (it.year.take(4).toIntOrNull() ?: 0) >= y - 3 }
                list = list.sortedWith(compareByDescending<com.galaxytvstick.app.data.VodItem> { it.year.take(4).toIntOrNull() ?: 0 }.thenByDescending { it.added }).take(1000)
                show(name, list.map { Entry(it, null) }, getString(R.string.empty_list), focusGrid)
            }
            else -> {
                // Pa gen rezo: kategori a se yon filt sou lis konplè a ki deja sou aparèy la
                val d = data ?: return
                val list = noAdult(if (id == CAT_ALL) d.items else d.inCategory(id))
                show(name, list.map { Entry(it, null) }, getString(R.string.empty_list), focusGrid)
            }
        }
    }

    private fun show(title: String, entries: List<Entry>, empty: String, focusGrid: Boolean) {
        b.gridTitle.text = if (entries.isEmpty()) title else "$title  ·  ${entries.size}"
        gridAdapter.items = entries
        b.grid.scrollToPosition(0)
        if (entries.isEmpty()) showMessage(empty) else b.message.visibility = View.GONE
        if (focusGrid && entries.isNotEmpty()) b.grid.post { b.grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun showLoading(on: Boolean) {
        b.progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) b.message.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        b.message.text = text
        b.message.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ Rechèch

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    private fun onSearch(q: String) {
        searchJob?.cancel()
        val query = norm(q.trim())
        if (query.length < 2) {
            if (q.isBlank()) selectCategory(selected, focusGrid = false)
            return
        }
        searchJob = lifecycleScope.launch {
            delay(400)
            val all = noAdult(data?.items ?: return@launch)
            // An aryè plan: dè milye fim, pa bloke ekran an pandan kliyan an ap tape
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val out = ArrayList<com.galaxytvstick.app.data.VodItem>()
                for (it in all) { if (!isActive) break; if (norm(it.name).contains(query)) { out.add(it); if (out.size >= 300) break } }
                out
            }
            catAdapter.selectedId = null
            show(getString(R.string.vod_search_results, q.trim()), found.map { Entry(it, null) }, getString(R.string.vod_search_none), focusGrid = false)
        }
    }

    // ------------------------------------------------------------ Aksyon

    private fun open(e: Entry) {
        val item = e.item
        if (item != null) {
            history.rememberItem(item)
            startActivity(VodDetailActivity.intent(this, item))
            return
        }
        val p = e.progress ?: return
        val remembered = history.rememberedItems(listOf("${if (p.kind == VodKind.MOVIE) "m" else "s"}:${p.parentId}")).firstOrNull()
        val it = remembered ?: VodItem(p.parentId, p.kind, p.title.substringBefore(" · "), p.poster, 0.0, "", p.url.substringAfterLast('.', "mp4"), 0L, "")
        startActivity(VodDetailActivity.intent(this, it))
    }

    /** Kenbe bouton OK la sou yon fim nan «Kontinye gade»: retire l, oswa efase tout lis la. */
    private fun continueMenu(p: WatchProgress) {
        val opts = arrayOf(getString(R.string.vod_cw_remove), getString(R.string.vod_cw_clear), getString(android.R.string.cancel))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(p.title.ifBlank { getString(R.string.vod_continue) })
            .setItems(opts) { d, which ->
                when (which) {
                    0 -> history.removeContinue(p)
                    1 -> history.clearContinue(kind)
                }
                d.dismiss()
                if (which <= 1) selectCategory(CAT_CONTINUE, focusGrid = false)
            }
            .show()
    }

    private fun toggleFavorite(e: Entry) {
        val item = e.item ?: return
        history.rememberItem(item)
        val added = history.toggleFavorite(item.key)
        android.widget.Toast.makeText(this, getString(if (added) R.string.fav_added else R.string.fav_removed, item.name), android.widget.Toast.LENGTH_SHORT).show()
        if (selected == CAT_FAV) selectCategory(CAT_FAV, focusGrid = false) else gridAdapter.notifyDataSetChanged()
    }

    private fun onRemoteCommand(cmd: RemoteCommand) {
        when (cmd.type) {
            "play" -> {
                startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM_ID, cmd.channelId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
            "reload", "restart" -> {
                startActivity(Intent(this, if (cmd.type == "reload") MainActivity::class.java else LoginActivity::class.java).putExtra(MainActivity.EXTRA_USE_CACHE, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            "logout" -> {
                prefs.account = null
                startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    // ------------------------------------------------------------ Griy afich

    private inner class PosterAdapter : RecyclerView.Adapter<PosterAdapter.VH>() {
        var items: List<Entry> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        inner class VH(val b: ItemPosterBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val item = e.item
            val prog = e.progress ?: item?.let { if (it.kind == VodKind.MOVIE) history.get("m:${it.id}") else null }
            val title = item?.name ?: e.progress?.title.orEmpty()
            val poster = item?.poster ?: e.progress?.poster
            h.b.title.text = title
            h.b.poster.load(Img.poster(poster)) { crossfade(true); error(R.drawable.ic_tv); placeholder(android.graphics.drawable.ColorDrawable(0xFF141A2E.toInt())) }
            h.b.sub.text = when {
                e.progress != null -> getString(R.string.vod_left, minutesLeft(e.progress))
                item != null -> listOf(item.year).filter { it.isNotBlank() }.joinToString(" · ")
                else -> ""
            }
            val r = item?.rating ?: 0.0
            h.b.rating.visibility = if (r > 0) View.VISIBLE else View.GONE
            h.b.rating.text = "★ %.1f".format(r)
            h.b.fav.visibility = if (item != null && item.key in history.favorites) View.VISIBLE else View.GONE
            val pct = prog?.percent ?: 0
            h.b.watched.visibility = if (pct in 1..99 || prog?.finished == true) View.VISIBLE else View.GONE
            h.b.watched.progress = if (prog?.finished == true) 100 else pct
            h.b.root.setOnClickListener { open(e) }
            h.b.root.setOnLongClickListener { if (e.item == null && e.progress != null) continueMenu(e.progress) else toggleFavorite(e); true }
            h.b.root.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
                // Remòt la kanpe sou afich la: prepare paj detay la davans
                detailPrefetch?.let { v.removeCallbacks(it) }
                if (has && item != null) detailPrefetch = Runnable { VodStore.prefetchDetail(account, item) }.also { v.postDelayed(it, 350) }
            }
        }

        private fun minutesLeft(p: WatchProgress): Int = ((p.durationMs - p.positionMs) / 60_000).toInt().coerceAtLeast(1)
    }
}
