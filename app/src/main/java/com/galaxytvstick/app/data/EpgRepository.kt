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

    fun programsFor(ch: Channel): List<Program> {
        val x = ch.epgChannelId?.let { programs[it] } ?: emptyList()
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

    /** Chaje EPG a si li poko chaje oswa si li twò vye. Retounen true si gen nouvo done. */
    suspend fun ensureLoaded(api: XtreamApi, channels: List<Channel>, force: Boolean = false): Boolean =
        mutex.withLock {
            if (!force && isLoaded && System.currentTimeMillis() - loadedAt < REFRESH_MS) return@withLock false
            val wanted = channels.mapNotNull { it.epgChannelId }.toHashSet()
            if (wanted.isEmpty()) return@withLock false
            // Chanèl catch-up: kenbe pwogram ki pase yo pandan tout tan achiv la (maks 7 jou)
            val keepPast = HashMap<String, Long>()
            for (c in channels) {
                val id = c.epgChannelId ?: continue
                // Maks 2 jou nan XMLTV (memwa Fire Stick); jou ki pi lwen yo chaje lè kliyan an ale la (loadArchive)
                if (c.tvArchive) keepPast[id] = maxOf(keepPast[id] ?: 0L, c.archiveDays.coerceIn(1, 2) * 86_400_000L)
            }
            archive.clear()
            val result = withContext(Dispatchers.IO) { download(api.xmltvUrl(), wanted, keepPast) }
            programs = result
            loadedAt = System.currentTimeMillis()
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
                        val ch = parser.getAttributeValue(null, "channel")
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
