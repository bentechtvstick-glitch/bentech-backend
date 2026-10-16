package com.galaxytvstick.app.data

import okhttp3.Request
import java.io.IOException

/**
 * Lis M3U / M3U8 (yon lyen): li lis la liy pa liy (pa janm tout an memwa), epi li fè chanèl ak gwoup yo.
 * Chak chanèl jwe ak pwòp lyen pa l (directUrl). Gid la soti nan url-tvg lis la oswa nan lyen EPG panel la.
 */
object M3u {
    /** Lyen EPG ki nan antèt lis la (#EXTM3U url-tvg="…"), pa lyen M3U. */
    val headerEpg = java.util.concurrent.ConcurrentHashMap<String, String>()

    class Result(val categories: List<Category>, val channels: List<Channel>, val epgUrl: String?)

    private val attrRx = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    // Lis M3U Xtream yo mete fim ak seri tou: kite yo deyò lis live la
    private val vodRx = Regex("""/(movie|series)/|\.(mp4|mkv|avi|mov)(\?|$)""", RegexOption.IGNORE_CASE)

    fun load(url: String): Result {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        val client = XtreamApi.http.newBuilder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server replied HTTP ${r.code}")
            val reader = (r.body ?: throw IOException("Empty list")).charStream().buffered(1 shl 16)
            val cats = LinkedHashMap<String, Category>()
            val chans = ArrayList<Channel>()
            val used = HashSet<Int>()
            var epg: String? = null
            var pending: Map<String, String>? = null
            var pendingName = ""
            var pendingGroup = ""
            var first = true
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                if (first) {
                    first = false
                    if (!line.startsWith("#EXTM3U", true)) throw IOException("This link is not an M3U list.")
                    val a = attrRx.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epg = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.startsWith("http") }
                    return@forEachLine
                }
                when {
                    line.startsWith("#EXTINF", true) -> {
                        pending = attrRx.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        pendingName = nameOf(line)
                        pendingGroup = pending?.get("group-title").orEmpty()
                    }
                    line.startsWith("#EXTGRP:", true) -> if (pendingGroup.isBlank()) pendingGroup = line.substringAfter(':').trim()
                    line.startsWith("#") -> Unit
                    else -> {
                        val a = pending ?: emptyMap()
                        pending = null
                        if (vodRx.containsMatchIn(line)) return@forEachLine
                        val group = pendingGroup.ifBlank { "Other" }
                        val catId = "m3u:" + group.lowercase()
                        cats.getOrPut(catId) { Category(catId, group) }
                        var id = (line.hashCode() and 0x7fffffff) % 1_900_000_000 + 1
                        while (!used.add(id)) id = id % 1_900_000_000 + 1
                        val name = pendingName.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { "Channel ${chans.size + 1}" }
                        chans.add(
                            Channel(
                                streamId = id,
                                num = a["tvg-chno"]?.toIntOrNull() ?: (chans.size + 1),
                                name = name,
                                icon = a["tvg-logo"]?.takeIf { it.startsWith("http") },
                                categoryId = catId,
                                epgChannelId = a["tvg-id"]?.takeIf { it.isNotBlank() } ?: a["tvg-name"]?.takeIf { it.isNotBlank() },
                                directUrl = line
                            )
                        )
                        pendingName = ""; pendingGroup = ""
                    }
                }
            }
            if (chans.isEmpty()) throw IOException("No channels in the M3U list.")
            epg?.let { headerEpg[url] = it }
            return Result(cats.values.toList(), chans, epg)
        }
    }

    /** Non chanèl la: tèks apre premye vigil ki pa nan yon atribi "…". */
    private fun nameOf(line: String): String {
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return line.substring(i + 1).trim()
        }
        return ""
    }

    /** Tès rapid pou koneksyon an: lis la kòmanse ak #EXTM3U. */
    fun check(url: String): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", XtreamApi.USER_AGENT).build()
        XtreamApi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server replied HTTP ${r.code}")
            val buf = CharArray(512)
            val n = (r.body ?: return false).charStream().read(buf)
            return n > 0 && String(buf, 0, n).trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U", true)
        }
    }
}
