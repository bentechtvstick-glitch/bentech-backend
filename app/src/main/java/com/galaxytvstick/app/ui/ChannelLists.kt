package com.galaxytvstick.app.ui

import android.content.Context
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Category
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.Prefs

/** Lojik kategori pataje ant player a ak gid la. */
object ChannelLists {
    const val CAT_EVENTS = "__events"
    const val CAT_FAV = "__fav"
    const val CAT_ALL = "__all"

    /** "🔴 Evènman" (si panel la gen), "★ Favori", "Tout chanèl", epi kategori ki gen chanèl vizib. */
    fun categories(ctx: Context): List<Category> {
        val special = mutableListOf<Category>()
        if (PanelState.config.liveEvents.isNotEmpty() && eventChannels().isNotEmpty()) special += Category(CAT_EVENTS, ctx.getString(R.string.live_events))
        special += Category(CAT_FAV, ctx.getString(R.string.cat_favorites))
        special += Category(CAT_ALL, ctx.getString(R.string.cat_all))
        return special + customCategories() + ChannelStore.categories
    }

    fun channelsFor(categoryId: String, prefs: Prefs): List<Channel> {
        val all = ChannelStore.all
        return when (categoryId) {
            CAT_ALL -> all
            CAT_FAV -> prefs.favorites.let { fav -> all.filter { it.streamId in fav } }
            CAT_EVENTS -> eventChannels()
            else -> if (categoryId.startsWith(CG)) customChannels(categoryId) else all.filter { it.categoryId == categoryId }
        }
    }

    /**
     * Live Events panel la: chak evènman vin yon chanèl nan lis la.
     * - Si l gen pwòp lyen stream (streamUrl), app la jwe lyen sa a dirèk.
     * - Sinon li chèche chanèl Xtream ki gen menm ID oswa menm non an.
     */
    fun eventChannels(): List<Channel> {
        // Pa gen evènman: pa fè okenn travay (lis sa a kalkile chak fwa gid la oswa lis chanèl la louvri)
        if (PanelState.config.liveEvents.isEmpty()) return emptyList()
        val now = System.currentTimeMillis() / 1000
        val byId = ChannelStore.all.associateBy { it.streamId }
        val byName by lazy { ChannelStore.all.associateBy { norm(it.name) } }
        return PanelState.config.liveEvents
            .filter { it.endsAt == 0L || it.endsAt >= now }
            .sortedWith(compareByDescending<com.galaxytvstick.app.data.LiveEvent> { it.featured }.thenBy { it.startsAt })
            .mapNotNull { e ->
                val base = byId[e.channelId] ?: e.channelName.takeIf { it.isNotBlank() }?.let { byName[norm(it)] }
                val live = e.startsAt in 1..now
                val prefix = if (live) "🔴 " else "⏰ "
                when {
                    e.streamUrl.isNotBlank() -> Channel(
                        streamId = -((e.id.hashCode() and 0x7fffffff) % 1_000_000_000) - 1, // ID negatif = pa yon chanèl Xtream
                        num = 0,
                        name = prefix + e.title,
                        icon = e.imageUrl ?: base?.icon,
                        categoryId = CAT_EVENTS,
                        epgChannelId = base?.epgChannelId,
                        directUrl = e.streamUrl
                    )
                    base != null -> base.copy(name = "$prefix${e.title}  ·  ${base.name}")
                    else -> null
                }
            }
            .distinctBy { it.streamId }
    }

    // ---- Gwoup pa admin nan (pa mo kle): yon chanèl ka nan gwoup orijinal li ak nan youn oswa plis gwoup sa yo ----
    const val CG = "__cg:"
    private fun fold(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    @Volatile private var cgCache: Pair<Int, Map<String, List<Channel>>>? = null
    private fun customMap(): Map<String, List<Channel>> {
        val groups = PanelState.config.customGroups
        if (groups.isEmpty()) return emptyMap()
        val all = ChannelStore.all
        val sig = listOf(groups, System.identityHashCode(all), all.size).hashCode()
        cgCache?.let { if (it.first == sig) return it.second }
        val catName = ChannelStore.categories.associate { it.id to fold(it.name) }
        val folded = all.map { fold(it.name) + " | " + (catName[it.categoryId] ?: "") }
        val out = LinkedHashMap<String, List<Channel>>()
        groups.forEachIndexed { i, (name, keys) ->
            val ks = keys.map { fold(it).trim() }.filter { it.isNotEmpty() }
            val list = all.filterIndexed { j, _ -> ks.any { folded[j].contains(it) } }
            if (list.isNotEmpty()) out["$CG$i:$name"] = list
        }
        cgCache = sig to out
        return out
    }
    fun customCategories(): List<Category> = customMap().keys.map { Category(it, it.substringAfter(':').substringAfter(':')) }
    fun customChannels(id: String): List<Channel> = customMap()[id] ?: emptyList()

    /** Konpare non chanèl san majiskil, espas oswa senbòl ("Sports Max" == "SPORTS-MAX HD"?). */
    private val rxQuality = Regex("\\b(hd|fhd|uhd|4k|sd)\\b")
    private val rxOther = Regex("[^a-z0-9]")
    private fun norm(s: String) = s.lowercase().replace(rxQuality, "").replace(rxOther, "")
}
