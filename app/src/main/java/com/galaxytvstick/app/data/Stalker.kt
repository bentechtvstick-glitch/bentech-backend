package com.galaxytvstick.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Pòtal Stalker / Ministra (MAC): app la parèt tankou yon bwat MAG ak MAC kliyan an te bay founisè a.
 * handshake → token → lis chanèl; lyen videyo a kreye nan moman kliyan an louvri chanèl la (create_link).
 */
class Stalker private constructor(private val portal: String, private val mac: String) {

    companion object {
        private val cache = HashMap<String, Stalker>()
        fun forAccount(a: Account): Stalker = synchronized(cache) {
            cache.getOrPut("${a.server}|${a.username}") { Stalker(a.server, a.username.uppercase()) }
        }
        const val PREFIX = "stalker:"
        private const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    }

    private val origin: String = portal.toHttpUrl().let { u ->
        "${u.scheme}://${u.host}" + (if ((u.scheme == "http" && u.port != 80) || (u.scheme == "https" && u.port != 443)) ":${u.port}" else "")
    }
    /** Adrès API pòtal yo konn itilize, nan lòd ki pi souvan an. */
    private val candidates: List<String> = buildList {
        if (portal.contains("stalker_portal", true)) add("$origin/stalker_portal/server/load.php")
        add("$origin/portal.php")
        add("$origin/server/load.php")
        add("$origin/stalker_portal/server/load.php")
    }.distinct()
    @Volatile private var api: String? = null
    @Volatile private var token: String = ""

    private fun call(base: String, params: Map<String, String>, auth: Boolean = true): JSONObject {
        val b = base.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        b.addQueryParameter("JsHttpRequest", "1-xml")
        val req = Request.Builder().url(b.build())
            .header("User-Agent", UA)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", portal.trimEnd('/') + "/")
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=UTC")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        XtreamApi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Portal replied HTTP ${r.code}")
            val text = r.body?.string().orEmpty()
            if (text.contains("Authorization failed", true)) throw IOException("AUTH")
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("Portal did not give a Stalker reply.") }
        }
    }

    /** Konekte ak pòtal la (chèche bon adrès API a, pran yon token). Retounen true si sa mache. */
    fun handshake(): Boolean {
        var last: Exception? = null
        for (base in listOfNotNull(api) + candidates) {
            try {
                val js = call(base, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"), auth = false).optJSONObject("js")
                val t = js?.optString("token").orEmpty()
                if (t.isBlank()) continue
                api = base; token = t
                runCatching {
                    call(base, mapOf("type" to "stb", "action" to "get_profile", "hd" to "1", "ver" to "ImageDescription: 0.2.18-r23-250; PORTAL version: 5.6.1; API Version: JS API version: 343",
                        "num_banks" to "2", "sn" to "", "stb_type" to "MAG250", "image_version" to "218", "auth_second_step" to "1", "hw_version" to "1.7-BD-00"))
                }
                return true
            } catch (e: Exception) { last = e }
        }
        throw IOException(if (last?.message == "AUTH") "Portal refused this MAC." else "Cannot connect to the portal (${last?.message ?: "no reply"}).")
    }

    private fun authed(params: Map<String, String>): JSONObject {
        if (api == null || token.isBlank()) handshake()
        return try { call(api!!, params) } catch (e: IOException) {
            if (e.message != "AUTH") throw e
            handshake(); call(api!!, params)
        }
    }

    /** Dat ekspirasyon kont lan sou pòtal la (segonn Unix), 0 = pòtal la pa di l. */
    fun expiry(): Long {
        val fields = listOf("end_date", "expire_billing_date", "expire_date", "exp_date", "phone", "account_balance")
        val sources = listOf(
            runCatching { authed(mapOf("type" to "account_info", "action" to "get_main_info")).optJSONObject("js") }.getOrNull(),
            runCatching { authed(mapOf("type" to "stb", "action" to "get_profile")).optJSONObject("js") }.getOrNull(),
        )
        for (js in sources) {
            if (js == null) continue
            for (f in fields) parseDate(js.optString(f))?.let { return it }
        }
        return 0L
    }

    private fun parseDate(raw: String?): Long? {
        val v = raw?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "null" } ?: return null
        v.toLongOrNull()?.let { n -> if (n > 1_000_000_000L) return if (n > 10_000_000_000L) n / 1000 else n }
        val pats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "MMMM d, yyyy, h:mm a", "MMMM d, yyyy", "dd.MM.yyyy", "dd/MM/yyyy", "MM/dd/yyyy")
        val txt = v.replace(Regex("^[^0-9A-Za-z]+"), "")
        for (p in pats) runCatching {
            val f = java.text.SimpleDateFormat(p, java.util.Locale.US).apply { isLenient = false }
            val d = f.parse(txt, java.text.ParsePosition(0)) ?: return@runCatching
            if (d.time > 946684800000L) return d.time / 1000
        }
        return null
    }

    private fun abs(u: String?): String? = u?.trim()?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("http")) it else "$origin/${it.trimStart('/')}" }

    /** Gwoup ak chanèl live yo. */
    fun loadChannels(): Pair<List<Category>, List<Channel>> {
        val genres = runCatching { authed(mapOf("type" to "itv", "action" to "get_genres")).optJSONArray("js") }.getOrNull() ?: JSONArray()
        val catName = HashMap<String, String>()
        for (i in 0 until genres.length()) genres.optJSONObject(i)?.let { g ->
            val id = g.optString("id"); if (id.isNotBlank() && id != "*") catName[id] = g.optString("title").ifBlank { id }
        }
        val js = authed(mapOf("type" to "itv", "action" to "get_all_channels")).optJSONObject("js")
        val data = js?.optJSONArray("data") ?: throw IOException("Portal sent no channels.")
        val cats = LinkedHashMap<String, Category>()
        val chans = ArrayList<Channel>(data.length())
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id").toIntOrNull() ?: continue
            val cmd = o.optString("cmd").trim()
            if (cmd.isBlank()) continue
            val gid = o.optString("tv_genre_id")
            val catId = "stk:$gid"
            cats.getOrPut(catId) { Category(catId, catName[gid] ?: "Other") }
            chans.add(
                Channel(
                    streamId = id,
                    num = o.optString("number").toIntOrNull() ?: (chans.size + 1),
                    name = o.optString("name").trim(),
                    icon = abs(o.optString("logo")),
                    categoryId = catId,
                    epgChannelId = o.optString("xmltv_id").trim().takeIf { it.isNotBlank() },
                    directUrl = PREFIX + cmd
                )
            )
        }
        if (chans.isEmpty()) throw IOException("Portal sent no channels.")
        return cats.values.toList() to chans
    }

    /** Lyen videyo a pou yon chanèl (pòtal la kreye l chak fwa, ak yon token). */
    fun createLink(cmd: String): String {
        val js = authed(mapOf("type" to "itv", "action" to "create_link", "cmd" to cmd, "series" to "", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0")).optJSONObject("js")
        var u = js?.optString("cmd").orEmpty().ifBlank { cmd }.trim()
        for (p in listOf("ffmpeg ", "auto ", "ffrt ")) if (u.startsWith(p, true)) u = u.substring(p.length).trim()
        if (!u.startsWith("http")) throw IOException("Portal did not give the video link.")
        return u
    }
}
