package com.galaxytvstick.app.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Telechaje epi kenbe gid EPG la (xmltv.php Xtream).
 * Li li fichye a an "streaming" pou l pa itilize twòp memwa sou Fire Stick,
 * epi li kenbe sèlman pwogram 2 è anvan jiska 24 è apre kounye a.
 */
object EpgRepository {

    private const val REFRESH_MS = 3 * 60 * 60 * 1000L   // 3 èdtan
    private const val KEEP_BEFORE_MS = 2 * 60 * 60 * 1000L
    private const val KEEP_AFTER_MS = 24 * 60 * 60 * 1000L
    // Limit memwa pou ti aparèy (Fire Stick): gwo playlist ka gen dè santèn milye pwogram
    private const val MAX_PROGRAMS = 60_000
    private const val MAX_DESC = 220

    @Volatile private var programs: Map<String, List<Program>> = emptyMap()
    /** EPG achiv (catch-up) pa chanèl, chaje lè kliyan an ale nan tan ki pase nan gid la. */
    private val archive = java.util.concurrent.ConcurrentHashMap<Int, List<Program>>()
    private val archiveLoading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    @Volatile var loadedAt = 0L
        private set
    private val mutex = Mutex()

    val isLoaded get() = loadedAt > 0

    /** Gwo fichye XMLTV a echwe: make "chaje" pou gid la kontinye ak demann chanèl pa chanèl. */
    fun markLoaded() { if (loadedAt == 0L) loadedAt = System.currentTimeMillis() }

    /** EPG chanèl pa chanèl (lè gwo fichye XMLTV a pa gen chanèl la): chaje sèlman pou chanèl ki sou ekran an. */
    private val single = java.util.concurrent.ConcurrentHashMap<Int, List<Program>>()
    private val singleTried = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    /** true si chanèl la poko gen okenn pwogram epi nou poko eseye mande sèvè a dirèkteman. */
    fun needsSingle(ch: Channel) = ch.directUrl == null && ch.streamId > 0 && !singleTried.contains(ch.streamId) && programsFor(ch).isEmpty()

    /** Mande sèvè a gid yon sèl chanèl. Retounen true si li jwenn pwogram. */
    suspend fun loadSingle(api: XtreamApi, ch: Channel): Boolean {
        if (!singleTried.add(ch.streamId)) return false
        val now = System.currentTimeMillis()
        val list = runCatching { api.archiveEpg(ch.streamId) }.getOrDefault(emptyList())
            .filter { it.end > now - KEEP_BEFORE_MS && it.start < now + KEEP_AFTER_MS }
            .map { if (it.desc.length > MAX_DESC) it.copy(desc = it.desc.take(MAX_DESC)) else it }
        if (list.isEmpty()) return false
        if (single.size > 600) single.clear()
        single[ch.streamId] = list
        return true
    }

    fun programsFor(ch: Channel): List<Program> {
        val x = ch.epgChannelId?.let { programs[it.lowercase()] } ?: single[ch.streamId] ?: emptyList()
        val a = archive[ch.streamId] ?: return x
        if (x.isEmpty()) return a
        // Pwogram achiv yo ki pase anvan premye pwogram XMLTV a
        val first = x.first().start
        return a.filter { it.end <= first } + x
    }

    fun hasArchive(ch: Channel) = archive.containsKey(ch.streamId)

    /** Chaje pwogram ki pase yo pou yon chanèl catch-up (yon sèl fwa). Retounen true si gen nouvo done. */
    suspend fun loadArchive(api: XtreamApi, ch: Channel): Boolean {
        if (!ch.tvArchive || archive.containsKey(ch.streamId) || !archiveLoading.add(ch.streamId)) return false
        return try {
            val list = runCatching { api.archiveEpg(ch.streamId) }.getOrDefault(emptyList())
            archive[ch.streamId] = list
            list.isNotEmpty()
        } finally { archiveLoading.remove(ch.streamId) }
    }

    fun nowFor(ch: Channel, now: Long = System.currentTimeMillis()): Program? =
        programsFor(ch).firstOrNull { it.isNow(now) }

    fun nextFor(ch: Channel, now: Long = System.currentTimeMillis()): Program? =
        programsFor(ch).firstOrNull { it.start >= now }

    // ------------------------------------------------------------ Kopi sou disk
    // Gid la sove sou aparèy la apre chak telechajman: lè app la louvri, li parèt touswit
    // (olye tann gwo fichye XMLTV a telechaje + li chak fwa), epi li mete tèt li ajou an aryè plan.
    private var cacheFile: java.io.File? = null
    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    fun init(ctx: android.content.Context) { cacheFile = java.io.File(ctx.filesDir, "epg_cache.bin") }

    private fun cacheKey(api: XtreamApi) = api.xmltvUrl().hashCode().toString()

    /** Chaje gid ki sou disk la (si li pou menm playlist la epi li gen mwens pase 24 è). Retounen true si li chaje l. */
    suspend fun loadDisk(api: XtreamApi): Boolean = mutex.withLock {
        if (isLoaded) return@withLock true
        readDisk(cacheKey(api))
    }

    private suspend fun readDisk(key: String): Boolean = withContext(Dispatchers.IO) {
        val f = cacheFile ?: return@withContext false
        if (!f.exists()) return@withContext false
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 1 shl 16)).use { inp ->
                if (inp.readInt() != 1) return@use false
                if (inp.readUTF() != key) return@use false
                val savedAt = inp.readLong()
                if (System.currentTimeMillis() - savedAt > CACHE_MAX_AGE_MS) return@use false
                val now = System.currentTimeMillis()
                val n = inp.readInt()
                val map = HashMap<String, List<Program>>(n * 2)
                repeat(n) {
                    val id = inp.readUTF()
                    val m = inp.readInt()
                    val list = ArrayList<Program>(m)
                    repeat(m) {
                        val p = Program(inp.readLong(), inp.readLong(), inp.readUTF(), inp.readUTF())
                        if (p.end > now - 2 * KEEP_AFTER_MS) list.add(p)
                    }
                    if (list.isNotEmpty()) map[id] = list
                }
                programs = map
                loadedAt = savedAt
                true
            }
        }.getOrDefault(false)
    }

    private fun writeDisk(key: String, map: Map<String, List<Program>>, at: Long) {
        val f = cacheFile ?: return
        runCatching {
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                out.writeInt(1); out.writeUTF(key); out.writeLong(at); out.writeInt(map.size)
                for ((id, list) in map) {
                    out.writeUTF(id); out.writeInt(list.size)
                    for (p in list) { out.writeLong(p.start); out.writeLong(p.end); out.writeUTF(p.title.take(300)); out.writeUTF(p.desc.take(MAX_DESC)) }
                }
            }
            tmp.renameTo(f)
        }
    }

    /** Chaje EPG a si li poko chaje oswa si li twò vye. Retounen true si gen nouvo done. */
    suspend fun ensureLoaded(api: XtreamApi, channels: List<Channel>, force: Boolean = false): Boolean =
        mutex.withLock {
            // Premye fwa: eseye kopi ki sou disk la anvan (pa bezwen tann telechajman an)
            if (!isLoaded && readDisk(cacheKey(api)) && System.currentTimeMillis() - loadedAt < REFRESH_MS && !force) return@withLock true
            if (!force && isLoaded && System.currentTimeMillis() - loadedAt < REFRESH_MS) return@withLock false
            val wanted = channels.mapNotNull { it.epgChannelId?.lowercase() }.toHashSet()
            if (wanted.isEmpty()) { loadedAt = System.currentTimeMillis(); return@withLock false }
            // Chanèl catch-up: kenbe pwogram ki pase yo pandan tout tan achiv la (maks 7 jou)
            val keepPast = HashMap<String, Long>()
            for (c in channels) {
                val id = c.epgChannelId?.lowercase() ?: continue
                // Maks 2 jou nan XMLTV (memwa Fire Stick); jou ki pi lwen yo chaje lè kliyan an ale la (loadArchive)
                if (c.tvArchive) keepPast[id] = maxOf(keepPast[id] ?: 0L, c.archiveDays.coerceIn(1, 2) * 86_400_000L)
            }
            archive.clear()
            single.clear()
            singleTried.clear()
            val result = withContext(Dispatchers.IO) { download(api.xmltvUrl(), wanted, keepPast) }
            programs = result
            loadedAt = System.currentTimeMillis()
            val key = cacheKey(api); val at = loadedAt
            withContext(Dispatchers.IO) { writeDisk(key, result, at) }
            true
        }

    private fun download(url: String, wanted: Set<String>, keepPast: Map<String, Long>): Map<String, List<Program>> {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        // EPG a ka gwo: bay plis tan pou li
        val client = XtreamApi.http.newBuilder()
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("EPG HTTP ${r.code}")
            val body = r.body ?: throw IOException("EPG vid")
            return parse(body.byteStream(), wanted, keepPast)
        }
    }

    private fun parse(input: java.io.InputStream, wanted: Set<String>, keepPast: Map<String, Long>): Map<String, List<Program>> {
        val now = System.currentTimeMillis()
        val minTime = now - KEEP_BEFORE_MS
        val maxTime = now + KEEP_AFTER_MS
        val out = HashMap<String, MutableList<Program>>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var inProgramme = false
        var count = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                        if (ch != null && ch in wanted) {
                            start = parseTime(parser.getAttributeValue(null, "start"))
                            stop = parseTime(parser.getAttributeValue(null, "stop"))
                            val min = keepPast[ch]?.let { now - it } ?: minTime
                            inProgramme = stop > min && start < maxTime && stop > start
                            channel = ch
                            title = ""; desc = ""
                        } else {
                            inProgramme = false
                        }
                    }
                    "title" -> if (inProgramme && title.isEmpty()) title = parser.nextText().trim()
                    "desc" -> if (inProgramme && desc.isEmpty()) desc = parser.nextText().trim().take(MAX_DESC)
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    if (inProgramme && channel != null && count < MAX_PROGRAMS) {
                        out.getOrPut(channel) { ArrayList() }.add(Program(start, stop, title, desc))
                        count++
                    }
                    inProgramme = false
                    channel = null
                }
            }
            event = parser.next()
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    // Sèlman itilize anndan mutex la, donk pa gen pwoblèm thread
    private val fmtZone = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val fmtUtc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Fòma XMLTV: "20260927213000 +0000" (zòn lè a pa toujou la). */
    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val t = s.trim()
        return runCatching { fmtZone.parse(t)!!.time }
            .recoverCatching { fmtUtc.parse(t.take(14))!!.time }
            .getOrDefault(0L)
    }
}
