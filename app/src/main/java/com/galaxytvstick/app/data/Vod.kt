package com.galaxytvstick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class VodKind { MOVIE, SERIES }

/** Yon fim oswa yon seri nan lis la (afich + non). */
data class VodItem(
    val id: Int,
    val kind: VodKind,
    val name: String,
    val poster: String?,
    val rating: Double,
    val categoryId: String,
    /** Ekstansyon fichye fim nan (mp4, mkv…); vid pou seri. */
    val ext: String,
    /** Dat li ajoute (epoch segonn) pou "Nouvo". */
    val added: Long,
    val year: String
) {
    val key: String get() = "${if (kind == VodKind.MOVIE) "m" else "s"}:$id"
}

data class VodDetail(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val durationSecs: Int,
    val rating: Double,
    val backdrop: String?,
    val poster: String?,
    val ext: String?
)

data class Episode(
    val id: String,
    val season: Int,
    val num: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val durationSecs: Int,
    val image: String?
)

data class SeriesDetail(
    val plot: String,
    val cast: String,
    val director: String,
    val genre: String,
    val releaseDate: String,
    val rating: Double,
    val backdrop: String?,
    val poster: String?,
    val seasons: Map<Int, List<Episode>>
)

/** Kote kliyan an te rive nan yon fim/epizòd (pou "Kontinye gade"). */
data class WatchProgress(
    /** "m:123" pou yon fim, "e:4567" pou yon epizòd. */
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    /** Pou montre l nan "Kontinye gade" epi relanse l. */
    val title: String,
    val poster: String?,
    val kind: VodKind,
    /** ID fim nan oswa ID seri a (pou louvri detay la). */
    val parentId: Int,
    val url: String
) {
    val percent: Int get() = if (durationMs <= 0) 0 else ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
    /** Pa konte kòm "kòmanse" anba 1 minit, ni "fini" apre 95%. */
    val resumable: Boolean get() = positionMs > 60_000 && percent < 95
    val finished: Boolean get() = durationMs > 0 && percent >= 95
}

/**
 * Sove pwogrè VOD yo nan SharedPreferences (JSON), jiska 200 eleman.
 * "Kontinye gade" = sa ki kòmanse men ki poko fini, dènye a an premye.
 */
class WatchHistory(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("galaxy_vod", Context.MODE_PRIVATE)

    companion object {
        /** Kopi an memwa: pa re-li JSON a chak fwa (lis epizòd long yo ta bloke ekran an). */
        @Volatile private var mem: LinkedHashMap<String, WatchProgress>? = null
    }

    private fun load(): MutableMap<String, WatchProgress> = mem ?: parse().also { mem = it }

    private fun parse(): LinkedHashMap<String, WatchProgress> {
        val out = LinkedHashMap<String, WatchProgress>()
        val arr = runCatching { JSONArray(sp.getString("progress", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = WatchProgress(
                o.optString("k"), o.optLong("p"), o.optLong("d"), o.optLong("u"), o.optString("t"),
                o.optString("i").takeIf { it.isNotBlank() }, if (o.optString("kind") == "SERIES") VodKind.SERIES else VodKind.MOVIE,
                o.optInt("pid"), o.optString("url")
            )
            out[p.key] = p
        }
        return out
    }

    private fun save(map: Map<String, WatchProgress>) {
        mem = LinkedHashMap(map)
        val arr = JSONArray()
        map.values.sortedByDescending { it.updatedAt }.take(200).forEach { p ->
            arr.put(JSONObject().put("k", p.key).put("p", p.positionMs).put("d", p.durationMs).put("u", p.updatedAt)
                .put("t", p.title).put("i", p.poster ?: "").put("kind", p.kind.name).put("pid", p.parentId).put("url", p.url))
        }
        sp.edit().putString("progress", arr.toString()).apply()
    }

    fun get(key: String): WatchProgress? = load()[key]

    fun put(p: WatchProgress) {
        val m = load(); m[p.key] = p; save(m)
    }

    fun remove(key: String) {
        val m = load(); if (m.remove(key) != null) save(m)
    }

    /** Fim/epizòd ki kòmanse men ki poko fini (yon sèl epizòd pa seri). */
    fun continueWatching(): List<WatchProgress> =
        load().values.filter { it.resumable }.sortedByDescending { it.updatedAt }
            .distinctBy { if (it.kind == VodKind.SERIES) "s:${it.parentId}" else it.key }
            .take(40)

    /** Favori VOD yo ("m:123", "s:45"). */
    var favorites: Set<String>
        get() = sp.getStringSet("fav", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("fav", v).apply()

    fun toggleFavorite(key: String): Boolean {
        val f = favorites.toMutableSet()
        val added = if (!f.add(key)) { f.remove(key); false } else true
        favorites = f
        return added
    }

    /** Dènye fim/seri yo louvri (pou favori ak "Kontinye" nan lis la). */
    fun rememberItem(item: VodItem) {
        val arr = runCatching { JSONObject(sp.getString("items", "{}")) }.getOrDefault(JSONObject())
        arr.put(item.key, JSONObject().put("id", item.id).put("kind", item.kind.name).put("n", item.name).put("p", item.poster ?: "")
            .put("r", if (item.rating.isFinite()) item.rating else 0.0).put("c", item.categoryId).put("e", item.ext).put("a", item.added).put("y", item.year))
        // Kenbe dènye 300 yo sèlman
        if (arr.length() > 300) arr.remove(arr.keys().next())
        sp.edit().putString("items", arr.toString()).apply()
    }

    fun rememberedItems(keys: Collection<String>): List<VodItem> {
        val arr = runCatching { JSONObject(sp.getString("items", "{}")) }.getOrDefault(JSONObject())
        return keys.mapNotNull { k ->
            val o = arr.optJSONObject(k) ?: return@mapNotNull null
            VodItem(o.optInt("id"), if (o.optString("kind") == "SERIES") VodKind.SERIES else VodKind.MOVIE, o.optString("n"),
                o.optString("p").takeIf { it.isNotBlank() }, o.optDouble("r", 0.0), o.optString("c"), o.optString("e"),
                o.optLong("a"), o.optString("y"))
        }
    }
}
