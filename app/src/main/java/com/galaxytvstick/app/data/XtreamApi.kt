package com.galaxytvstick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kliyan pou API Xtream Codes (player_api.php).
 */
class XtreamApi(private val account: Account) {

    companion object {
        /** Menm fòm ak lòt app ki bati sou ExoPlayer/Media3 (kèk sèvè IPTV refize non yo pa konnen). */
        val USER_AGENT = "GalaxyTvStick/1.0 (Linux;Android ${android.os.Build.VERSION.RELEASE}) AndroidXMedia3/1.4.1"

        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun parseCategories(text: String): List<Category> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Category(o.optString("category_id"), o.optString("category_name"))
            }
        }

        fun parseStreams(text: String): List<Channel> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    streamId = o.optInt("stream_id"),
                    num = o.optInt("num", i + 1),
                    name = o.optString("name"),
                    icon = o.optString("stream_icon").takeIf { it.isNotBlank() && it != "null" },
                    categoryId = o.optString("category_id"),
                    epgChannelId = o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                    tvArchive = o.optInt("tv_archive", 0) == 1,
                    archiveDays = o.optInt("tv_archive_duration", 0).coerceIn(0, 30)
                )
            }
        }

        /** Vre sèvè videyo a pa playlist (li nan server_info). */
        private val altBases = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** true = chanèl yo jwe sou sèvè server_info a, pa sou adrès playlist la. */
        @Volatile var preferAlt = false

        /** Mete http:// si li manke epi retire "/" nan fen an. */
        fun normalizeServer(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
                s = "http://$s"
            }
            return s
        }
    }

    private fun apiUrl(action: String?, extra: Map<String, String> = emptyMap()): String {
        val b = "${account.server}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", account.username)
            .addQueryParameter("password", account.password)
        action?.let { b.addQueryParameter("action", it) }
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sèvè a reponn HTTP ${r.code}")
            r.body?.string() ?: throw IOException("Sèvè a voye yon repons vid")
        }
    }

    /** Retounen true si username/password la bon. */
    suspend fun login(): Boolean {
        val json = JSONObject(get(apiUrl(null)))
        val info = json.optJSONObject("user_info") ?: return false
        return info.optInt("auth", 0) == 1
    }

    suspend fun liveCategoriesRaw(): String = get(apiUrl("get_live_categories"))
    suspend fun liveStreamsRaw(): String = get(apiUrl("get_live_streams"))

    suspend fun liveCategories(): List<Category> = parseCategories(liveCategoriesRaw())

    /** categoryId = null → tout chanèl yo. */
    suspend fun liveStreams(categoryId: String?): List<Channel> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        return parseStreams(get(apiUrl("get_live_streams", extra)))
    }

    private fun base(alt: Boolean) = if (alt) altBases[account.server] ?: account.server else account.server
    /** Adrès pou fim, seri ak catch-up: menm sèvè ki mache pou chanèl yo. */
    private val mediaBase get() = base(preferAlt)

    /**
     * Kèk founisè bay yon adrès pou lis la (player_api) men videyo yo sou yon lòt sèvè.
     * Vre adrès videyo a nan "server_info". null si se menm adrès la oswa si sèvè a pa di l.
     */
    suspend fun resolveAltBase(): String? {
        if (altBases.containsKey(account.server)) return altBases[account.server]
        val found = runCatching {
            val si = JSONObject(get(apiUrl(null))).optJSONObject("server_info") ?: return@runCatching null
            val host = si.optString("url").trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (host.isBlank()) return@runCatching null
            val https = si.optString("server_protocol").equals("https", true)
            val port = (if (https) si.optString("https_port") else si.optString("port")).trim()
            val b = (if (https) "https://" else "http://") + host + (if (port.isBlank() || host.contains(":") || port == (if (https) "443" else "80")) "" else ":$port")
            b.takeIf { !it.equals(account.server, true) }
        }.getOrNull()
        if (found != null) altBases[account.server] = found
        return found
    }

    /** Zòn lè sèvè a (pou catch-up). null si sèvè a pa di l. */
    suspend fun serverTimezone(): String? = runCatching {
        JSONObject(get(apiUrl(null))).optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------ Films (VOD)

    suspend fun vodCategories(): List<Category> = categories("get_vod_categories")
    suspend fun seriesCategories(): List<Category> = categories("get_series_categories")

    private suspend fun categories(action: String): List<Category> {
        val arr = JSONArray(get(apiUrl(action)))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }

    /**
     * Li yon gwo lis JSON an "streaming" (san mete tout repons lan an memwa):
     * pèmèt chaje dè dizèn milye fim sou yon Fire Stick san pwoblèm memwa.
     */
    private suspend fun streamObjects(url: String, each: (Map<String, String>) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val client = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Sèvè a reponn HTTP ${r.code}")
            val body = r.body ?: throw IOException("Sèvè a voye yon repons vid")
            android.util.JsonReader(body.charStream()).use { jr ->
                if (jr.peek() != android.util.JsonToken.BEGIN_ARRAY) { jr.skipValue(); return@use }
                jr.beginArray()
                while (jr.hasNext()) {
                    if (jr.peek() != android.util.JsonToken.BEGIN_OBJECT) { jr.skipValue(); continue }
                    val m = HashMap<String, String>(24)
                    jr.beginObject()
                    while (jr.hasNext()) {
                        val name = jr.nextName()
                        when (jr.peek()) {
                            android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> m[name] = jr.nextString()
                            android.util.JsonToken.BOOLEAN -> m[name] = jr.nextBoolean().toString()
                            android.util.JsonToken.NULL -> jr.nextNull()
                            else -> jr.skipValue()
                        }
                    }
                    jr.endObject()
                    each(m)
                }
                jr.endArray()
            }
        }
    }

    private fun Map<String, String>.clean(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun Map<String, String>.rating(): Double =
        (this["rating"]?.toDoubleOrNull() ?: ((this["rating_5based"]?.toDoubleOrNull() ?: 0.0) * 2)).takeIf { it.isFinite() } ?: 0.0

    /** Films yon kategori (null = tout). */
    suspend fun vodStreams(categoryId: String?): List<VodItem> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        val out = ArrayList<VodItem>()
        streamObjects(apiUrl("get_vod_streams", extra)) { o ->
            val id = o["stream_id"]?.toIntOrNull() ?: return@streamObjects
            val name = o["name"].orEmpty()
            out += VodItem(id, VodKind.MOVIE, name, o.clean("stream_icon"), o.rating(), o["category_id"].orEmpty(),
                o.clean("container_extension") ?: "mp4", o["added"]?.toLongOrNull() ?: 0L, yearOf(name, o["year"].orEmpty()))
        }
        return out
    }

    suspend fun vodInfo(id: Int): VodDetail {
        val o = JSONObject(get(apiUrl("get_vod_info", mapOf("vod_id" to id.toString()))))
        val info = o.optJSONObject("info") ?: JSONObject()
        val data = o.optJSONObject("movie_data") ?: JSONObject()
        return VodDetail(
            plot = info.cleanString("plot") ?: info.cleanString("description") ?: "",
            cast = info.cleanString("cast") ?: info.cleanString("actors") ?: "",
            director = info.cleanString("director") ?: "",
            genre = info.cleanString("genre") ?: "",
            releaseDate = info.cleanString("releasedate") ?: info.cleanString("release_date") ?: "",
            durationSecs = info.optInt("duration_secs", 0),
            rating = info.optString("rating").toDoubleOrNull() ?: 0.0,
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() } ?: info.cleanString("cover_big"),
            poster = info.cleanString("movie_image") ?: info.cleanString("cover_big"),
            ext = data.cleanString("container_extension")
        )
    }

    /** Seri yon kategori (null = tout). */
    suspend fun series(categoryId: String?): List<VodItem> {
        val extra = if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        val out = ArrayList<VodItem>()
        streamObjects(apiUrl("get_series", extra)) { o ->
            val id = o["series_id"]?.toIntOrNull() ?: return@streamObjects
            val name = o["name"].orEmpty()
            out += VodItem(id, VodKind.SERIES, name, o.clean("cover"), o.rating(), o["category_id"].orEmpty(), "",
                o["last_modified"]?.toLongOrNull() ?: 0L, yearOf(name, o["releaseDate"] ?: o["release_date"].orEmpty()))
        }
        return out
    }

    suspend fun seriesInfo(id: Int): SeriesDetail {
        val o = JSONObject(get(apiUrl("get_series_info", mapOf("series_id" to id.toString()))))
        val info = o.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        // "episodes" ka yon objè {"1":[…]} oswa yon lis lis
        val eps = o.opt("episodes")
        val groups: List<JSONArray> = when (eps) {
            is JSONObject -> eps.keys().asSequence().mapNotNull { eps.optJSONArray(it) }.toList()
            is JSONArray -> (0 until eps.length()).mapNotNull { eps.optJSONArray(it) }
            else -> emptyList()
        }
        for (g in groups) {
            for (i in 0 until g.length()) {
                val e = g.optJSONObject(i) ?: continue
                val ei = e.optJSONObject("info") ?: JSONObject()
                val season = e.optInt("season", 1)
                val ep = Episode(
                    id = e.optString("id"), season = season, num = e.optInt("episode_num", i + 1),
                    title = e.cleanString("title") ?: "", ext = e.cleanString("container_extension") ?: "mp4",
                    plot = ei.cleanString("plot") ?: "", durationSecs = ei.optInt("duration_secs", 0),
                    image = ei.cleanString("movie_image")
                )
                seasons[season] = (seasons[season] ?: emptyList()) + ep
            }
        }
        return SeriesDetail(
            plot = info.cleanString("plot") ?: "",
            cast = info.cleanString("cast") ?: "",
            director = info.cleanString("director") ?: "",
            genre = info.cleanString("genre") ?: "",
            releaseDate = info.cleanString("releaseDate") ?: info.cleanString("release_date") ?: "",
            rating = info.optString("rating").toDoubleOrNull() ?: 0.0,
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() },
            poster = info.cleanString("cover"),
            seasons = seasons.mapValues { (_, l) -> l.sortedBy { it.num } }
        )
    }

    fun movieUrl(id: Int, ext: String): String = "$mediaBase/movie/${account.username}/${account.password}/$id.$ext"
    fun episodeUrl(ep: Episode): String = "$mediaBase/series/${account.username}/${account.password}/${ep.id}.${ep.ext}"

    // ------------------------------------------------------------ Catch-up

    /**
     * Lyen catch-up (timeshift) Xtream: /timeshift/user/pass/MINIT/YYYY-MM-DD:HH-MM/ID.ts
     * Lè kòmansman an dwe nan zòn lè sèvè a.
     */
    fun catchupUrl(ch: Channel, startMs: Long, minutes: Int, serverTz: String?): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply {
            timeZone = serverTz?.let { java.util.TimeZone.getTimeZone(it) } ?: java.util.TimeZone.getDefault()
        }
        return "$mediaBase/timeshift/${account.username}/${account.password}/${minutes.coerceAtLeast(1)}/${fmt.format(java.util.Date(startMs))}/${ch.streamId}.ts"
    }

    /** Tout EPG yon chanèl, ak pwogram ki pase yo (pou catch-up). Tit yo an base64. */
    suspend fun archiveEpg(streamId: Int): List<Program> {
        val o = JSONObject(get(apiUrl("get_simple_data_table", mapOf("stream_id" to streamId.toString()))))
        val arr = o.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val start = e.optLong("start_timestamp", 0L) * 1000
            val end = e.optLong("stop_timestamp", 0L) * 1000
            if (start <= 0 || end <= start) null else Program(start, end, b64(e.optString("title")), b64(e.optString("description")))
        }.sortedBy { it.start }
    }

    private fun b64(s: String): String = runCatching {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrDefault(s).trim()

    private fun JSONObject.cleanString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" && it != "[]" }

    private fun yearOf(name: String, year: String): String =
        year.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: Regex("""\((19|20)\d{2}\)""").find(name)?.value?.trim('(', ')') ?: ""

    /** Gid EPG konplè a nan fòma XMLTV. */
    fun xmltvUrl(): String = "${account.server}/xmltv.php".toHttpUrl().newBuilder()
        .addQueryParameter("username", account.username)
        .addQueryParameter("password", account.password)
        .build().toString()

    /** ext = "m3u8" (HLS) oswa "ts" (MPEG-TS). */
    fun streamUrl(channel: Channel, ext: String = "m3u8", alt: Boolean = false): String =
        channel.directUrl
            ?: "${base(alt)}/live/${account.username}/${account.password}/${channel.streamId}.$ext"

    private fun hide(s: String): String {
        var out = s
        if (account.username.isNotBlank()) out = out.replace(account.username, "***")
        if (account.password.isNotBlank()) out = out.replace(account.password, "***")
        return out
    }

    /** Ansyen fòm lyen Xtream (san "/live" ni ekstansyon): kèk sèvè bay sèlman sa a. */
    fun streamUrlBare(channel: Channel, alt: Boolean = false): String =
        channel.directUrl ?: "${base(alt)}/${account.username}/${account.password}/${channel.streamId}"

    /**
     * Gade sa sèvè a reponn vre pou yon lyen stream (pou esplike poukisa yon chanèl pa jwe).
     * Pa janm retounen username/password.
     */
    suspend fun probe(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                val type = r.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                val buf = ByteArray(2048)
                var n = 0
                r.body?.byteStream()?.let { ins ->
                    while (n < buf.size) { val k = ins.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
                }
                val kind = when {
                    n == 0 -> "vid"
                    buf[0] == 0x47.toByte() && (n <= 188 || buf[188] == 0x47.toByte()) -> "MPEG-TS"
                    String(buf, 0, minOf(n, 7)) == "#EXTM3U" -> "HLS"
                    else -> {
                        val all = String(buf, 0, n)
                        val title = Regex("<title[^>]*>([^<]{1,80})", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1)?.trim()
                        val txt = (title?.let { "paj web: $it" } ?: all.take(90)).replace(Regex("[^\\x20-\\x7E]"), " ").replace(Regex("\\s+"), " ").trim()
                        "\"" + hide(txt) + "\""
                    }
                }
                "HTTP ${r.code} $type $kind".replace("  ", " ")
            }
        }.getOrElse { hide(it.javaClass.simpleName + " " + (it.message ?: "")).take(120) }
    }
}
