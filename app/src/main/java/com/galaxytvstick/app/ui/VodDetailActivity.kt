package com.galaxytvstick.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Episode
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.SeriesDetail
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityVodDetailBinding
import com.galaxytvstick.app.databinding.ItemEpisodeBinding
import kotlinx.coroutines.launch

/** Detay yon fim (Gade / Kontinye) oswa yon seri (sezon, epizòd, "pwochen epizòd"). */
class VodDetailActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, item: VodItem): Intent = Intent(ctx, VodDetailActivity::class.java)
            .putExtra("id", item.id).putExtra("kind", item.kind.name).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("rating", item.rating).putExtra("year", item.year)
            .putExtra("ext", item.ext).putExtra("cat", item.categoryId).putExtra("added", item.added)
    }

    private lateinit var b: ActivityVodDetailBinding
    private lateinit var api: XtreamApi
    private lateinit var history: WatchHistory
    private lateinit var item: VodItem

    // Fim
    private var movieUrl: String? = null
    // Seri: tout epizòd yo nan lòd (sezon, nimewo), pou epizòd swivan an travèse sezon yo
    private var allEpisodes: List<Episode> = emptyList()
    private var seasons: Map<Int, List<Episode>> = emptyMap()
    private var season = 1
    private val epAdapter = EpisodeAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        val account = Prefs(this).account ?: run { finish(); return }
        api = XtreamApi(account)
        history = WatchHistory(this)
        item = VodItem(
            intent.getIntExtra("id", 0),
            if (intent.getStringExtra("kind") == VodKind.SERIES.name) VodKind.SERIES else VodKind.MOVIE,
            intent.getStringExtra("name").orEmpty(), intent.getStringExtra("poster"), intent.getDoubleExtra("rating", 0.0),
            intent.getStringExtra("cat").orEmpty(), intent.getStringExtra("ext").orEmpty(), intent.getLongExtra("added", 0L),
            intent.getStringExtra("year").orEmpty()
        )

        b.title.text = item.name
        b.poster.load(item.poster) { crossfade(true); error(R.drawable.ic_tv) }
        b.backdrop.load(item.poster)
        b.meta.text = meta(item.year, item.rating, 0, "")
        b.episodes.layoutManager = LinearLayoutManager(this)
        b.episodes.adapter = epAdapter
        b.btnFav.setOnClickListener {
            history.rememberItem(item)
            history.toggleFavorite(item.key)
            updateFav()
        }
        b.btnPlay.isEnabled = false
        updateFav()
        load()
    }

    override fun onResume() {
        super.onResume()
        // Retounen soti nan player a: mete bouton an ak ba pwogrè yo ajou
        updatePlayButtons()
        epAdapter.notifyDataSetChanged()
    }

    private fun meta(year: String, rating: Double, durationSecs: Int, genre: String): String = listOfNotNull(
        year.takeIf { it.isNotBlank() },
        rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        durationSecs.takeIf { it > 0 }?.let { d -> if (d >= 3600) "${d / 3600}h %02dmin".format((d % 3600) / 60) else "${d / 60} min" },
        genre.takeIf { it.isNotBlank() }
    ).joinToString("   ·   ")

    private fun credits(director: String, cast: String): String = listOfNotNull(
        director.takeIf { it.isNotBlank() }?.let { getString(R.string.vod_director, it) },
        cast.takeIf { it.isNotBlank() }?.let { getString(R.string.vod_cast, it) }
    ).joinToString("\n")

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            if (item.kind == VodKind.MOVIE) {
                val r = runCatching { api.vodInfo(item.id) }
                b.progress.visibility = View.GONE
                val d = r.getOrNull()
                if (d != null) {
                    b.plot.text = d.plot
                    b.credits.text = credits(d.director, d.cast)
                    b.meta.text = meta(item.year.ifBlank { d.releaseDate.take(4) }, if (item.rating > 0) item.rating else d.rating, d.durationSecs, d.genre)
                    d.backdrop?.let { b.backdrop.load(it) }
                    if (item.poster == null) d.poster?.let { b.poster.load(it) }
                }
                // Menm si detay yo pa vini, fim nan ka jwe
                movieUrl = api.movieUrl(item.id, d?.ext ?: item.ext.ifBlank { "mp4" })
                b.btnPlay.isEnabled = true
                updatePlayButtons()
                b.btnPlay.requestFocus()
            } else {
                val r = runCatching { api.seriesInfo(item.id) }
                b.progress.visibility = View.GONE
                r.onSuccess { showSeries(it) }
                    .onFailure { b.message.text = getString(R.string.error_network, it.message ?: ""); b.message.visibility = View.VISIBLE }
            }
        }
    }

    // ------------------------------------------------------------ Fim

    private fun playMovie(fromStart: Boolean) {
        val url = movieUrl ?: return
        history.rememberItem(item)
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "movie")
                .putExtra(VodPlayerActivity.EXTRA_URL, url)
                .putExtra(VodPlayerActivity.EXTRA_KEY, "m:${item.id}")
                .putExtra(VodPlayerActivity.EXTRA_TITLE, item.name)
                .putExtra(VodPlayerActivity.EXTRA_SUBTITLE, b.meta.text.toString())
                .putExtra(VodPlayerActivity.EXTRA_POSTER, item.poster)
                .putExtra(VodPlayerActivity.EXTRA_PARENT_ID, item.id)
                .putExtra(VodPlayerActivity.EXTRA_FROM_START, fromStart)
        )
    }

    // ------------------------------------------------------------ Seri

    private fun showSeries(d: SeriesDetail) {
        b.plot.text = d.plot
        b.credits.text = credits(d.director, d.cast)
        b.meta.text = meta(item.year.ifBlank { d.releaseDate.take(4) }, if (item.rating > 0) item.rating else d.rating, 0, d.genre)
        d.backdrop?.let { b.backdrop.load(it) }
        seasons = d.seasons
        allEpisodes = d.seasons.keys.sorted().flatMap { d.seasons[it].orEmpty() }
        if (allEpisodes.isEmpty()) {
            b.message.text = getString(R.string.vod_no_episodes)
            b.message.visibility = View.VISIBLE
            return
        }
        b.spacer.visibility = View.GONE
        b.episodes.visibility = View.VISIBLE
        b.seasonsScroll.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        // Louvri sezon epizòd pou kontinye a
        season = nextUp()?.first?.season ?: seasons.keys.minOrNull() ?: 1
        buildSeasonButtons()
        selectSeason(season)
        b.btnPlay.isEnabled = true
        updatePlayButtons()
        b.btnPlay.requestFocus()
    }

    private fun buildSeasonButtons() {
        b.seasons.removeAllViews()
        for (s in seasons.keys.sorted()) {
            val btn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (38 * resources.displayMetrics.density).toInt()).apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
                setBackgroundResource(R.drawable.bg_focus)
                isAllCaps = false
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
                text = getString(R.string.vod_season, s)
                tag = s
                setOnClickListener { selectSeason(s) }
                setOnFocusChangeListener { _, has -> if (has) selectSeason(s) }
            }
            b.seasons.addView(btn)
        }
    }

    private fun selectSeason(s: Int) {
        if (season == s && epAdapter.items.isNotEmpty()) return
        season = s
        for (i in 0 until b.seasons.childCount) b.seasons.getChildAt(i).isActivated = b.seasons.getChildAt(i).tag == s
        epAdapter.items = seasons[s].orEmpty()
    }

    /** Pwochen epizòd pou gade: sa w t ap gade a (si l poko fini), oswa sa ki vini apre a. */
    private fun nextUp(): Pair<Episode, Boolean>? {
        if (allEpisodes.isEmpty()) return null
        val withProgress = allEpisodes.mapIndexedNotNull { i, e -> history.get("e:${e.id}")?.let { Triple(i, e, it) } }
        val last = withProgress.maxByOrNull { it.third.updatedAt } ?: return allEpisodes.first() to false
        return if (last.third.finished) (allEpisodes.getOrNull(last.first + 1) ?: allEpisodes.first()) to false
        else last.second to last.third.resumable
    }

    private fun playEpisode(ep: Episode, fromStart: Boolean) {
        history.rememberItem(item)
        val idx = allEpisodes.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "episode")
                .putExtra(VodPlayerActivity.EXTRA_TITLE, item.name)
                .putExtra(VodPlayerActivity.EXTRA_POSTER, item.poster)
                .putExtra(VodPlayerActivity.EXTRA_PARENT_ID, item.id)
                .also { VodPlayerActivity.pendingEpisodes = allEpisodes }
                .putExtra(VodPlayerActivity.EXTRA_EP_INDEX, idx)
                .putExtra(VodPlayerActivity.EXTRA_FROM_START, fromStart)
        )
    }

    // ------------------------------------------------------------ Bouton

    private fun updateFav() {
        b.btnFav.text = getString(if (item.key in history.favorites) R.string.vod_in_favorites else R.string.vod_add_favorite)
    }

    private fun updatePlayButtons() {
        if (!b.btnPlay.isEnabled) { b.btnPlay.text = getString(R.string.vod_play); return }
        if (item.kind == VodKind.MOVIE) {
            val p = history.get("m:${item.id}")?.takeIf { it.resumable }
            b.btnPlay.text = if (p != null) getString(R.string.vod_resume, fmt(p.positionMs)) else getString(R.string.vod_play)
            b.btnRestart.visibility = if (p != null) View.VISIBLE else View.GONE
            b.btnPlay.setOnClickListener { playMovie(fromStart = false) }
            b.btnRestart.setOnClickListener { playMovie(fromStart = true) }
        } else {
            val (ep, resumable) = nextUp() ?: return
            val label = "S%02d E%02d".format(ep.season, ep.num)
            b.btnPlay.text = if (resumable) getString(R.string.vod_resume, "$label · ${fmt(history.get("e:${ep.id}")?.positionMs ?: 0L)}")
                             else getString(R.string.vod_play_episode, label)
            b.btnRestart.visibility = if (resumable) View.VISIBLE else View.GONE
            b.btnPlay.setOnClickListener { playEpisode(ep, fromStart = false) }
            b.btnRestart.setOnClickListener { playEpisode(ep, fromStart = true) }
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------ Epizòd

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        var items: List<Episode> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        inner class VH(val b: ItemEpisodeBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val p = history.get("e:${e.id}")
            h.b.title.text = "E%02d".format(e.num) + (if (e.title.isNotBlank()) "  ·  ${e.title}" else "") + (if (p?.finished == true) "   ✓" else "")
            h.b.plot.text = e.plot
            h.b.plot.visibility = if (e.plot.isBlank()) View.GONE else View.VISIBLE
            h.b.duration.text = if (e.durationSecs > 0) "${e.durationSecs / 60} min" else ""
            h.b.thumb.load(e.image ?: item.poster) { crossfade(true); error(R.drawable.ic_tv) }
            val pct = if (p?.finished == true) 100 else p?.percent ?: 0
            h.b.watched.visibility = if (pct > 0) View.VISIBLE else View.GONE
            h.b.watched.progress = pct
            h.b.root.setOnClickListener { playEpisode(e, fromStart = p?.finished == true) }
        }
    }
}
