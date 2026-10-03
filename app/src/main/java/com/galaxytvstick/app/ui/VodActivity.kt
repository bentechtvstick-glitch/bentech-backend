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
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.WatchProgress
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityVodBinding
import com.galaxytvstick.app.databinding.ItemPosterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        /** Kenbe lis yo an memwa pandan sesyon an (pa rechaje chak fwa). */
        private val cache = HashMap<String, List<VodItem>>()
        private val catCache = HashMap<VodKind, List<Category>>()
        private var cacheAccount = ""
    }

    /** Sa yon kaz nan griy la montre: yon fim/seri oswa yon "Kontinye gade". */
    private data class Entry(val item: VodItem?, val progress: WatchProgress?)

    private lateinit var b: ActivityVodBinding
    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var history: WatchHistory
    private lateinit var overlay: OverlayController
    private var kind = VodKind.MOVIE
    private var selected = CAT_CONTINUE
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    private val catAdapter = CategoryAdapter { selectCategory(it.id, focusGrid = true) }
    private val gridAdapter = PosterAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVodBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        val account = prefs.account ?: run { finish(); return }
        api = XtreamApi(account)
        history = WatchHistory(this)
        // Lòt playlist: pa itilize lis ansyen playlist la
        val acc = "${account.server}|${account.username}"
        if (acc != cacheAccount) { cache.clear(); catCache.clear(); cacheAccount = acc }

        kind = if (intent.getStringExtra(EXTRA_KIND) == "series") VodKind.SERIES else VodKind.MOVIE
        b.categories.layoutManager = LinearLayoutManager(this)
        b.categories.adapter = catAdapter
        b.grid.layoutManager = GridLayoutManager(this, 5)
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
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )
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

    private fun applyKindLabels() {
        b.vodHeader.text = getString(if (kind == VodKind.MOVIE) R.string.movies else R.string.series)
        b.btnSwitch.text = getString(if (kind == VodKind.MOVIE) R.string.series else R.string.movies)
    }

    // ------------------------------------------------------------ Kategori

    private fun specialCategories(): List<Category> = listOf(
        Category(CAT_CONTINUE, getString(R.string.vod_continue)),
        Category(CAT_FAV, getString(R.string.cat_favorites)),
        Category(CAT_ALL, getString(R.string.vod_all))
    )

    private fun loadCategories() {
        catAdapter.items = specialCategories() + (catCache[kind] ?: emptyList())
        val start = if (history.continueWatching().any { it.kind == kind }) CAT_CONTINUE else null
        if (catCache[kind] != null) { selectCategory(start ?: catCache[kind]!!.firstOrNull()?.id ?: CAT_ALL, focusGrid = false); focusCategories(); return }
        showLoading(true)
        lifecycleScope.launch {
            val r = runCatching { if (kind == VodKind.MOVIE) api.vodCategories() else api.seriesCategories() }
            showLoading(false)
            r.onSuccess { cats ->
                catCache[kind] = cats
                catAdapter.items = specialCategories() + cats
                selectCategory(start ?: cats.firstOrNull()?.id ?: CAT_ALL, focusGrid = false)
                focusCategories()
            }.onFailure { showMessage(getString(R.string.error_network, it.message ?: "")) }
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
            else -> {
                val cacheKey = "${kind.name}:$id"
                cache[cacheKey]?.let { show(name, sortNewest(it).map { e -> Entry(e, null) }, getString(R.string.empty_list), focusGrid); return }
                gridAdapter.items = emptyList()
                b.gridTitle.text = name
                showLoading(true)
                loadJob = lifecycleScope.launch {
                    val catId = if (id == CAT_ALL) null else id
                    val r = runCatching { if (kind == VodKind.MOVIE) api.vodStreams(catId) else api.series(catId) }
                    showLoading(false)
                    r.onSuccess { list ->
                        cache[cacheKey] = list
                        if (selected == id) show(name, sortNewest(list).map { Entry(it, null) }, getString(R.string.empty_list), focusGrid)
                    }.onFailure { showMessage(getString(R.string.error_network, it.message ?: "")) }
                }
            }
        }
    }

    private fun sortNewest(list: List<VodItem>) = list.sortedByDescending { it.added }

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
            val cacheKey = "${kind.name}:$CAT_ALL"
            val all = cache[cacheKey] ?: run {
                showLoading(true)
                val r = runCatching { if (kind == VodKind.MOVIE) api.vodStreams(null) else api.series(null) }
                showLoading(false)
                r.getOrNull()?.also { cache[cacheKey] = it } ?: run { showMessage(getString(R.string.error_network, r.exceptionOrNull()?.message ?: "")); return@launch }
            }
            val found = all.filter { norm(it.name).contains(query) }.sortedByDescending { it.added }.take(300)
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
                startActivity(Intent(this, if (cmd.type == "reload") MainActivity::class.java else LoginActivity::class.java)
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
            h.b.poster.load(poster) { crossfade(true); error(R.drawable.ic_tv); placeholder(android.graphics.drawable.ColorDrawable(0xFF141A2E.toInt())) }
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
            h.b.root.setOnLongClickListener { toggleFavorite(e); true }
            h.b.root.setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
            }
        }

        private fun minutesLeft(p: WatchProgress): Int = ((p.durationMs - p.positionMs) / 60_000).toInt().coerceAtLeast(1)
    }
}
