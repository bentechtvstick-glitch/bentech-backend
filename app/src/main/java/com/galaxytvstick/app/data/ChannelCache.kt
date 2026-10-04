package com.galaxytvstick.app.data

import android.content.Context
import java.io.File

/**
 * Kenbe dènye lis chanèl la sou aparèy la, pou app la ka louvri TV a touswit
 * san l pa tann sèvè playlist la. Lis la mete ajou an silans pou pwochen ouvèti a.
 * Tout bagay fèt fichye → lekti objè pa objè, pou gwo playlist pa plen memwa Fire Stick la.
 */
object ChannelCache {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L       // pi ansyen pase sa: rechaje nòmalman
    const val REFRESH_AFTER_MS = 3 * 60 * 60 * 1000L          // pi ansyen pase sa: mete ajou an silans

    class Entry(val categories: List<Category>, val channels: List<Channel>, val ageMs: Long)

    private fun key(a: Account) = Integer.toHexString("${a.server}|${a.username}|${a.password}".hashCode())
    private fun cats(ctx: Context, a: Account) = File(ctx.filesDir, "cats_${key(a)}.json")
    private fun chans(ctx: Context, a: Account) = File(ctx.filesDir, "chans_${key(a)}.json")

    fun read(ctx: Context, a: Account): Entry? = runCatching {
        val c = cats(ctx, a); val s = chans(ctx, a)
        if (!c.exists() || !s.exists()) return null
        val age = System.currentTimeMillis() - s.lastModified()
        if (age < 0 || age > MAX_AGE_MS) return null
        val channels = XtreamApi.readStreams(s)
        if (channels.isEmpty()) return null
        Entry(XtreamApi.readCategories(c), channels, age)
    }.getOrNull()

    /** Telechaje lis la sou sèvè playlist la, sove l, epi retounen l. Voye yon erè si sèvè a pa reponn. */
    suspend fun fetch(ctx: Context, a: Account): Entry {
        val api = XtreamApi(a)
        val tc = File(ctx.filesDir, "cats.tmp"); val ts = File(ctx.filesDir, "chans.tmp")
        api.downloadLiveCategories(tc)
        api.downloadLiveStreams(ts)
        val categories = XtreamApi.readCategories(tc)
        val channels = XtreamApi.readStreams(ts)
        if (channels.isNotEmpty()) runCatching {
            // Efase ansyen lis lòt playlist yo pou pa plen memwa aparèy la
            ctx.filesDir.listFiles()?.forEach {
                if ((it.name.startsWith("cats_") || it.name.startsWith("chans_")) && !it.name.contains(key(a))) it.delete()
            }
            tc.renameTo(cats(ctx, a)); ts.renameTo(chans(ctx, a))
        }
        return Entry(categories, channels, 0)
    }
}
