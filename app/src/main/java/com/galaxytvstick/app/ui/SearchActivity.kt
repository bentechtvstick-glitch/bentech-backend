package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
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
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivitySearchBinding
import com.galaxytvstick.app.databinding.ItemCategoryBinding
import kotlinx.coroutines.launch

/** Chèche: klavye sou ekran an agoch, rezilta (chanèl, fim, seri) adwat pandan kliyan an ap tape. */
class SearchActivity : AppCompatActivity() {

    private lateinit var b: ActivitySearchBinding
    private var query = ""
    private var movies: List<VodItem> = emptyList()
    private var series: List<VodItem> = emptyList()
    private val adapter = ResAdapter()

    private class Res(val label: String, val channel: Channel?, val vod: VodItem?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = Prefs(this).account ?: run { finish(); return }
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.results.layoutManager = LinearLayoutManager(this)
        b.results.adapter = adapter
        b.results.itemAnimator = null
        buildKeys()

        // Fim ak seri yo chaje dèyè; chanèl yo disponib touswit
        val api = XtreamApi(account)
        lifecycleScope.launch {
            // Kontwòl paran: pa montre sa ki nan kategori pou granmoun
            val hide = ChannelStore.hideAdult
            val badM = if (hide) runCatching { api.vodCategories() }.getOrDefault(emptyList()).filter { ChannelStore.isAdult(it.name) }.map { it.id }.toHashSet() else HashSet()
            runCatching { api.vodStreams(null) }.onSuccess { l -> movies = l.filter { it.categoryId !in badM }; refresh() }
            val badS = if (hide) runCatching { api.seriesCategories() }.getOrDefault(emptyList()).filter { ChannelStore.isAdult(it.name) }.map { it.id }.toHashSet() else HashSet()
            runCatching { api.series(null) }.onSuccess { l -> series = l.filter { it.categoryId !in badS }; refresh() }
        }
    }

    private fun buildKeys() {
        val d = resources.displayMetrics.density
        val rows = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6).map { r -> r.map { it.toString() } } + listOf(listOf("SPACE", "DEL", "CLEAR"))
        var first: View? = null
        for (r in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()).apply { bottomMargin = (5 * d).toInt() }
            }
            for (k in r) {
                val v = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = (5 * d).toInt() }
                    gravity = Gravity.CENTER
                    text = k
                    textSize = if (k.length > 1) 13f else 18f
                    setTextColor(ContextCompat.getColorStateList(this@SearchActivity, R.color.epg_cell_text))
                    setBackgroundResource(R.drawable.bg_epg_cell)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { press(k) }
                }
                if (first == null) first = v
                line.addView(v)
            }
            b.keys.addView(line)
        }
        first?.requestFocus()
    }

    private fun press(k: String) {
        query = when (k) {
            "DEL" -> query.dropLast(1)
            "CLEAR" -> ""
            "SPACE" -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
            else -> if (query.length < 24) query + k else query
        }
        b.query.text = query
        refresh()
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        val out = ArrayList<Res>()
        if (q.isNotEmpty()) {
            ChannelStore.all.asSequence().filter { it.name.lowercase().contains(q) }.take(40)
                .forEach { out.add(Res("TV   ·   ${it.name}", it, null)) }
            movies.asSequence().filter { it.name.lowercase().contains(q) }.take(30)
                .forEach { out.add(Res("${getString(R.string.home_movies)}   ·   ${it.name}", null, it)) }
            series.asSequence().filter { it.name.lowercase().contains(q) }.take(30)
                .forEach { out.add(Res("${getString(R.string.home_series)}   ·   ${it.name}", null, it)) }
        }
        adapter.items = out
        adapter.notifyDataSetChanged()
        b.empty.visibility = if (q.isNotEmpty() && out.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(r: Res) {
        val ch = r.channel
        if (ch != null) {
            ChannelStore.current = ChannelStore.all
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM_ID, ch.streamId))
            finish()
            return
        }
        r.vod?.let { startActivity(VodDetailActivity.intent(this, it)) }
    }

    private inner class ResAdapter : RecyclerView.Adapter<ResAdapter.VH>() {
        var items: List<Res> = emptyList()

        inner class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            h.b.name.text = r.label
            h.b.root.setOnClickListener { open(r) }
        }
    }
}
