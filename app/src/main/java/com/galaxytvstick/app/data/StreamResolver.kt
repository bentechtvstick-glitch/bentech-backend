package com.galaxytvstick.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Kèk chanèl pa gen yon lyen videyo dirèk:
 *  - pòtal Stalker ("stalker:…") → mande lyen an bay pòtal la
 *  - paj YouTube (youtube.com/watch, youtu.be, /live, /embed) → pran lyen HLS (.m3u8) live la nan paj la
 * Lòt lyen yo jwe jan yo ye.
 */
object StreamResolver {
    private val YT = Regex("""^https?://([a-z0-9-]+\.)?(youtube\.com|youtu\.be|youtube-nocookie\.com)/""", RegexOption.IGNORE_CASE)
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    /** Lyen YouTube ki deja chèche (lyen HLS la valab plizyè èdtan): chanje chanèl vit. */
    private val cache = HashMap<String, Pair<Long, String>>()
    private const val CACHE_MS = 2 * 60 * 60 * 1000L

    fun isYouTube(url: String?) = url != null && YT.containsMatchIn(url.trim())

    /** true si lyen chanèl la bezwen chèche anvan li jwe. */
    fun needsResolve(url: String?) = url != null && (url.startsWith(Stalker.PREFIX) || isYouTube(url))

    /** Lyen videyo a pou jwe. Voye yon IOException ak yon mesaj klè si li pa mache. */
    suspend fun resolve(acc: Account, url: String): String = withContext(Dispatchers.IO) {
        when {
            url.startsWith(Stalker.PREFIX) -> Stalker.forAccount(acc).createLink(url.removePrefix(Stalker.PREFIX))
            isYouTube(url) -> youTube(url.trim())
            else -> url
        }
    }

    private fun youTube(url: String): String {
        synchronized(cache) { cache[url]?.let { (at, u) -> if (System.currentTimeMillis() - at < CACHE_MS) return u } }
        val req = Request.Builder().url(watchUrl(url))
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
            .build()
        val html = XtreamApi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("YouTube HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val m = Regex("\"hlsManifestUrl\"\\s*:\\s*\"([^\"]+)\"").find(html)
            ?: throw IOException(
                if (html.contains("\"isLiveContent\":true") || html.contains("\"isUpcoming\":true")) "This YouTube live stream has not started yet."
                else "This YouTube link is not a live stream. Only YouTube live channels can play here."
            )
        val hls = m.groupValues[1].replace("\\u0026", "&").replace("\\/", "/")
        synchronized(cache) { cache[url] = System.currentTimeMillis() to hls }
        return hls
    }

    /** youtu.be/ID, /embed/ID, /live/ID, /shorts/ID → youtube.com/watch?v=ID. Lòt fòm (/@chanel/live, /channel/…/live) rete jan yo ye. */
    private fun watchUrl(url: String): String {
        val id = Regex("""youtu\.be/([\w-]{11})""").find(url)?.groupValues?.get(1)
            ?: Regex("""/(?:embed|live|shorts|v)/([\w-]{11})(?:[?/#]|$)""").find(url)?.groupValues?.get(1)
        return if (id != null) "https://www.youtube.com/watch?v=$id" else url.replace("://m.youtube.com", "://www.youtube.com")
    }
}
