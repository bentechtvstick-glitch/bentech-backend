package com.galaxytvstick.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Lis Films ak Séries yo, prè davans pou pa gen okenn chajman lè w louvri yo:
 *  - yon sèl telechajman "tout" pa kalite; chak kategori se jis yon filt sou lis sa a (pa gen rezo);
 *  - kenbe sou aparèy la (fichye binè) → louvri touswit menm apre TV a etenn;
 *  - mete ajou an silans an aryè plan (pa janm bloke ekran an).
 */
object VodStore {
    private const val VERSION = 1
    private const val REFRESH_AFTER_MS = 3 * 60 * 60 * 1000L

    class Data(val categories: List<Category>, val items: List<VodItem>, val savedAt: Long) {
        private val byCat: Map<String, List<VodItem>> by lazy { items.groupBy { it.categoryId } }
        fun inCategory(id: String): List<VodItem> = byCat[id] ?: emptyList()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mem = HashMap<VodKind, Data>()
    private val locks = mapOf(VodKind.MOVIE to Mutex(), VodKind.SERIES to Mutex())
    private var memAccount = ""
    /** Rele lè yon lis fin mete ajou (VodActivity koute pou rafrechi ekran an an silans). */
    @Volatile var onUpdated: ((VodKind) -> Unit)? = null
    private val running = HashMap<VodKind, Job>()

    private fun accKey(a: Account) = Integer.toHexString("${a.server}|${a.username}|${a.password}".hashCode())
    private fun file(ctx: Context, a: Account, k: VodKind) = File(ctx.filesDir, "vod_${k.name.lowercase()}_${accKey(a)}.bin")

    private fun checkAccount(a: Account) {
        val k = accKey(a)
        if (k != memAccount) synchronized(mem) { mem.clear(); memAccount = k }
    }

    /** Sa ki deja an memwa (pa janm tann). */
    fun peek(a: Account, k: VodKind): Data? { checkAccount(a); return synchronized(mem) { mem[k] } }

    /** Memwa → disk. Pa itilize rezo. */
    suspend fun cached(ctx: Context, a: Account, k: VodKind): Data? {
        peek(a, k)?.let { return it }
        return locks.getValue(k).withLock {
            synchronized(mem) { mem[k] } ?: kotlinx.coroutines.withContext(Dispatchers.IO) { read(file(ctx, a, k), k) }
                ?.also { synchronized(mem) { mem[k] = it } }
        }
    }

    /** Telechaje lis la (si l pa la oswa li twò ansyen) an aryè plan. */
    private val sentCats = java.util.Collections.synchronizedSet(HashSet<String>())

    fun prefetch(ctx: Context, a: Account, force: Boolean = false) {
        if (!a.isXtream) return
        val app = ctx.applicationContext
        for (k in VodKind.values()) refresh(app, a, k, force)
    }

    fun refresh(ctx: Context, a: Account, k: VodKind, force: Boolean = false): Job? {
        if (!a.isXtream) return null
        synchronized(running) { running[k]?.takeIf { it.isActive }?.let { return it } }
        val job = scope.launch {
            val cur = cached(ctx, a, k)
            if (!force && cur != null && System.currentTimeMillis() - cur.savedAt < REFRESH_AFTER_MS) return@launch
            runCatching { fetch(ctx, a, k) }
        }
        synchronized(running) { running[k] = job }
        return job
    }

    /** Telechaje kounye a (sèvi sèlman lè pa gen anyen ditou sou aparèy la). */
    suspend fun fetch(ctx: Context, a: Account, k: VodKind): Data {
        val api = XtreamApi(a)
        val cats = if (k == VodKind.MOVIE) api.vodCategories() else api.seriesCategories()
        val items = if (k == VodKind.MOVIE) api.vodStreams(null) else api.series(null)
        val d = Data(cats, items.sortedByDescending { it.added }, System.currentTimeMillis())
        checkAccount(a)
        // Voye non kategori yo bay panel la (yon fwa pa lis), pou admin nan ka wè/kache kontni granmoun yo
        val sig = "${k.name}:${cats.joinToString("|") { it.name }.hashCode()}"
        if (sentCats.add(sig)) {
            val prefs = Prefs(ctx.applicationContext)
            com.galaxytvstick.app.GalaxyApp.scope.launch { runCatching { PanelApi(prefs).uploadVodCats(if (k == VodKind.MOVIE) "movies" else "series", cats) } }
        }
        synchronized(mem) { mem[k] = d }
        runCatching { write(file(ctx, a, k), d) }
        onUpdated?.invoke(k)
        return d
    }

    // ---------------------------------------------------------------- detay (rezime, sezon, epizòd)

    private val movieInfo = object : LinkedHashMap<Int, VodDetail>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, VodDetail>?) = size > 150 }
    private val seriesInfo = object : LinkedHashMap<Int, SeriesDetail>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, SeriesDetail>?) = size > 80 }
    private val pending = HashMap<String, kotlinx.coroutines.Deferred<Any?>>()

    fun movieDetailNow(id: Int): VodDetail? = synchronized(movieInfo) { movieInfo[id] }
    fun seriesDetailNow(id: Int): SeriesDetail? = synchronized(seriesInfo) { seriesInfo[id] }

    private fun detailJob(a: Account, item: VodItem): kotlinx.coroutines.Deferred<Any?> = synchronized(pending) {
        val key = "${item.kind}:${item.id}"
        pending[key]?.takeIf { it.isActive || (it.isCompleted && it.getCompletionExceptionOrNull() == null) }?.let { return it }
        val api = XtreamApi(a)
        val job = scope.async {
            if (item.kind == VodKind.MOVIE) api.vodInfo(item.id).also { synchronized(movieInfo) { movieInfo[item.id] = it } }
            else api.seriesInfo(item.id).also { synchronized(seriesInfo) { seriesInfo[item.id] = it } }
        }
        job.invokeOnCompletion { synchronized(pending) { pending.remove(key) } }
        pending[key] = job
        job
    }

    /** Kòmanse chaje detay yon afich (lè remòt la kanpe sou li), pou paj la louvri touswit. */
    fun prefetchDetail(a: Account, item: VodItem) {
        if (!a.isXtream) return
        if (item.kind == VodKind.MOVIE && movieDetailNow(item.id) != null) return
        if (item.kind == VodKind.SERIES && seriesDetailNow(item.id) != null) return
        detailJob(a, item)
    }

    suspend fun movieDetail(a: Account, item: VodItem): VodDetail = movieDetailNow(item.id) ?: (detailJob(a, item).await() as VodDetail)
    suspend fun seriesDetail(a: Account, item: VodItem): SeriesDetail = seriesDetailNow(item.id) ?: (detailJob(a, item).await() as SeriesDetail)

    // ---------------------------------------------------------------- fichye

    private fun write(f: File, d: Data) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        val cut = { v: String -> if (v.length > 8000) v.take(8000) else v }
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(VERSION); o.writeLong(d.savedAt)
            o.writeInt(d.categories.size); d.categories.forEach { o.writeUTF(cut(it.id)); o.writeUTF(cut(it.name)) }
            o.writeInt(d.items.size)
            d.items.forEach { v ->
                o.writeInt(v.id); o.writeUTF(cut(v.name)); o.writeUTF(cut(v.poster.orEmpty())); o.writeDouble(v.rating)
                o.writeUTF(cut(v.categoryId)); o.writeUTF(cut(v.ext)); o.writeLong(v.added); o.writeUTF(cut(v.year))
            }
        }
        tmp.renameTo(f)
    }

    private fun read(f: File, k: VodKind): Data? = runCatching {
        if (!f.exists()) return null
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 1 shl 16)).use { d ->
            if (d.readInt() != VERSION) return null
            val at = d.readLong()
            val nc = d.readInt()
            val cats = ArrayList<Category>(nc)
            repeat(nc) { cats.add(Category(d.readUTF(), d.readUTF())) }
            val n = d.readInt()
            val items = ArrayList<VodItem>(n)
            repeat(n) {
                val id = d.readInt(); val name = d.readUTF(); val poster = d.readUTF().ifEmpty { null }; val rating = d.readDouble()
                val cat = d.readUTF(); val ext = d.readUTF(); val added = d.readLong(); val year = d.readUTF()
                items.add(VodItem(id, k, name, poster, rating, cat, ext, added, year))
            }
            Data(cats, items, at)
        }
    }.getOrNull()
}
