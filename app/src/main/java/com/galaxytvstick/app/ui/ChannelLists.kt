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
        if (eventChannels().isNotEmpty()) special += Category(CAT_EVENTS, ctx.getString(R.string.live_events))
        special += Category(CAT_FAV, ctx.getString(R.string.cat_favorites))
        special += Category(CAT_ALL, ctx.getString(R.string.cat_all, ChannelStore.all.size))
        return special + ChannelStore.categories
    }

    fun channelsFor(categoryId: String, prefs: Prefs): List<Channel> {
        val all = ChannelStore.all
        return when (categoryId) {
            CAT_ALL -> all
            CAT_FAV -> prefs.favorites.let { fav -> all.filter { it.streamId in fav } }
            CAT_EVENTS -> eventChannels()
            else -> all.filter { it.categoryId == categoryId }
        }
    }

    /**
     * Live Events panel la: chak evènman vin yon chanèl nan lis la.
     * - Si l gen pwòp lyen stream (streamUrl), app la jwe lyen sa a dirèk.
     * - Sinon li chèche chanèl Xtream ki gen menm ID oswa menm non an.
     */
    fun eventChannels(): List<Channel> {
        val now = System.currentTimeMillis() / 1000
        val byId = ChannelStore.all.associateBy { it.streamId }
        val byName = ChannelStore.all.associateBy { norm(it.name) }
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

    /** Konpare non chanèl san majiskil, espas oswa senbòl ("Sports Max" == "SPORTS-MAX HD"?). */
    private fun norm(s: String) = s.lowercase()
        .replace(Regex("\\b(hd|fhd|uhd|4k|sd)\\b"), "")
        .replace(Regex("[^a-z0-9]"), "")
}
