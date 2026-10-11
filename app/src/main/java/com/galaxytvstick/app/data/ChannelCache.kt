package com.galaxytvstick.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.async

/**
 * Kenbe dènye lis chanèl la sou aparèy la, pou app la ka louvri TV a touswit
 * san l pa tann sèvè playlist la. Lis la mete ajou an silans pou pwochen ouvèti a.
 * Tout bagay fèt fichye → lekti objè pa objè, pou gwo playlist pa plen memwa Fire Stick la.
 */
object ChannelCache {
    private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L   // pi ansyen pase sa: rechaje nòmalman (sinon: louvri touswit, mete ajou an aryè plan)
    const val REFRESH_AFTER_MS = 3 * 60 * 60 * 1000L          // pi ansyen pase sa: mete ajou an silans

    class Entry(val categories: List<Category>, val channels: List<Channel>, val ageMs: Long)

    private fun key(a: Account) = Integer.toHexString("${a.server}|${a.username}|${a.password}".hashCode())
    private fun cats(ctx: Context, a: Account) = File(ctx.filesDir, "cats_${key(a)}.json")
    private fun chans(ctx: Context, a: Account) = File(ctx.filesDir, "chans_${key(a)}.json")

    private fun bin(ctx: Context, a: Account) = File(ctx.filesDir, "list_${key(a)}.bin")
    private const val BIN_VERSION = 1

    fun read(ctx: Context, a: Account): Entry? {
        // 1) Fichye binè a: plizyè fwa pi rapid pase JSON (kèk santèn milisegonn menm pou 30 000+ chanèl)
        readBin(ctx, a)?.let { return it }
        // 2) Ansyen fichye JSON yo (premye fwa apre mizajou a): li yo epi kreye fichye binè a
        return runCatching {
            val c = cats(ctx, a); val s = chans(ctx, a)
            if (!c.exists() || !s.exists()) return null
            val age = System.currentTimeMillis() - s.lastModified()
            if (age < 0 || age > MAX_AGE_MS) return null
            val channels = XtreamApi.readStreams(s)
            if (channels.isEmpty()) return null
            val cats = XtreamApi.readCategories(c)
            writeBin(ctx, a, cats, channels)
            Entry(cats, channels, age)
        }.getOrNull()
    }

    private fun readBin(ctx: Context, a: Account): Entry? = runCatching {
        val f = bin(ctx, a)
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age < 0 || age > MAX_AGE_MS) return null
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 1 shl 16)).use { d ->
            if (d.readInt() != BIN_VERSION) return null
            val nc = d.readInt()
            val cats = ArrayList<Category>(nc)
            repeat(nc) { cats.add(Category(d.readUTF(), d.readUTF())) }
            val n = d.readInt()
            val chans = ArrayList<Channel>(n)
            repeat(n) {
                val id = d.readInt(); val num = d.readInt(); val name = d.readUTF()
                val icon = d.readUTF().ifEmpty { null }; val cat = d.readUTF(); val epg = d.readUTF().ifEmpty { null }
                val arch = d.readBoolean(); val days = d.readByte().toInt()
                chans.add(Channel(id, num, name, icon, cat, epg, null, arch, days))
            }
            if (chans.isEmpty()) null else Entry(cats, chans, age)
        }
    }.getOrNull()

    private fun writeBin(ctx: Context, a: Account, cats: List<Category>, chans: List<Channel>) = runCatching {
        val tmp = File(ctx.filesDir, "list.bin.tmp")
        val cut = { v: String -> if (v.length > 8000) v.take(8000) else v }
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
            o.writeInt(BIN_VERSION)
            o.writeInt(cats.size); cats.forEach { o.writeUTF(cut(it.id)); o.writeUTF(cut(it.name)) }
            o.writeInt(chans.size)
            chans.forEach { c ->
                o.writeInt(c.streamId); o.writeInt(c.num); o.writeUTF(cut(c.name)); o.writeUTF(cut(c.icon.orEmpty()))
                o.writeUTF(cut(c.categoryId)); o.writeUTF(cut(c.epgChannelId.orEmpty())); o.writeBoolean(c.tvArchive); o.writeByte(c.archiveDays.coerceIn(0, 127))
            }
        }
        tmp.renameTo(bin(ctx, a))
    }

    /** Telechaje lis la sou sèvè playlist la, sove l, epi retounen l. Voye yon erè si sèvè a pa reponn. */
    suspend fun fetch(ctx: Context, a: Account): Entry {
        val api = XtreamApi(a)
        val tc = File(ctx.filesDir, "cats.tmp"); val ts = File(ctx.filesDir, "chans.tmp")
        // Kategori yo ak chanèl yo telechaje an menm tan (pa youn apre lòt)
        kotlinx.coroutines.coroutineScope {
            val c = async(kotlinx.coroutines.Dispatchers.IO) { api.downloadLiveCategories(tc) }
            api.downloadLiveStreams(ts)
            c.await()
        }
        val categories = XtreamApi.readCategories(tc)
        val channels = XtreamApi.readStreams(ts)
        if (channels.isNotEmpty()) runCatching {
            // Efase ansyen lis lòt playlist yo pou pa plen memwa aparèy la
            ctx.filesDir.listFiles()?.forEach {
                if ((it.name.startsWith("cats_") || it.name.startsWith("chans_")) && !it.name.contains(key(a))) it.delete()
            }
            tc.renameTo(cats(ctx, a)); ts.renameTo(chans(ctx, a))
            ctx.filesDir.listFiles()?.forEach { if (it.name.startsWith("list_") && !it.name.contains(key(a))) it.delete() }
            writeBin(ctx, a, categories, channels)
        }
        return Entry(categories, channels, 0)
    }
}
