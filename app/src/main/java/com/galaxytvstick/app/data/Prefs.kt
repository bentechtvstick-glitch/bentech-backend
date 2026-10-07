package com.galaxytvstick.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/** Sove kont lan, favori yo, dènye chanèl la ak idantite aparèy la. */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("galaxy_prefs", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val server = sp.getString("server", null) ?: return null
            val user = sp.getString("user", null) ?: return null
            val pass = sp.getString("pass", null) ?: return null
            return Account(
                server, user, pass,
                name = sp.getString("pl_name", null),
                playlistId = sp.getString("pl_id", null)
            )
        }
        set(value) {
            sp.edit().apply {
                if (value == null) {
                    remove("server"); remove("user"); remove("pass"); remove("pl_name"); remove("pl_id")
                } else {
                    putString("server", value.server)
                    putString("user", value.username)
                    putString("pass", value.password)
                    putString("pl_name", value.name)
                    putString("pl_id", value.playlistId)
                }
            }.apply()
        }

    var favorites: Set<Int>
        get() = sp.getStringSet("favorites", emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("favorites", value.map { it.toString() }.toSet()).apply()

    fun toggleFavorite(streamId: Int): Boolean {
        val fav = favorites.toMutableSet()
        val added = if (fav.contains(streamId)) { fav.remove(streamId); false } else { fav.add(streamId); true }
        favorites = fav
        return added
    }

    /** Zòn lè sèvè Xtream la (egz: "America/New_York"), pou lyen catch-up yo. */
    var serverTimezone: String?
        get() = sp.getString("server_tz", null)
        set(value) = sp.edit().putString("server_tz", value).apply()

    /** Dènye chanèl kliyan an te gade (pi resan an premye), pou ba rapid la sou plen ekran. */
    var recentChannels: List<Int>
        get() = (sp.getString("recent_ch", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        set(value) = sp.edit().putString("recent_ch", value.joinToString(",")).apply()

    fun pushRecent(streamId: Int) {
        recentChannels = (listOf(streamId) + recentChannels.filter { it != streamId }).take(12)
    }

    var lastChannelId: Int
        get() = sp.getInt("last_channel", -1)
        set(value) = sp.edit().putInt("last_channel", value).apply()

    /**
     * "MAC address" aparèy la (egz: 3A:7F:12:C4:9E:05).
     *
     * Depi Android 6, app yo pa gen dwa li vrè MAC la (Android toujou bay 02:00:00:00:00:00),
     * donk nou kreye yon MAC estab apati ANDROID_ID aparèy la, menm jan ak IBO Player
     * ak lòt app IPTV. Li pa chanje menm si w efase app la epi re-enstale l
     * (sof si aparèy la fè factory reset).
     */
    val mac: String
        get() {
            sp.getString("mac", null)?.let { return it }
            val hash = digest("galaxy:")
            val bytes = hash.copyOf(6)
            // Premye byte: "locally administered", unicast (tankou yon MAC vityèl)
            bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
            val value = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            sp.edit().putString("mac", value).apply()
            return value
        }

    /**
     * Kle 6 chif ki konfime aparèy la nan panel la (pou pèsonn pa ka pran MAC yon lòt moun).
     * Li soti nan ID aparèy la tou, donk li pa chanje si kliyan an re-enstale app la
     * (sinon panel la t ap refize aparèy la apre yon re-enstalasyon).
     */
    val deviceKey: String
        get() {
            sp.getString("device_key", null)?.let { return it }
            val h = digest("galaxy-key:")
            val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
                ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            val key = (100000 + (n % 900000)).toString()
            sp.edit().putString("device_key", key).apply()
            return key
        }

    /** SHA-256 ID aparèy la ak yon "sèl" diferan pou chak itilizasyon. */
    @SuppressLint("HardwareIds")
    private fun digest(salt: String): ByteArray {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } // ID ki gen bug sou kèk aparèy
            ?: sp.getString("fallback_id", null)
            ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("fallback_id", it).apply() }
        return MessageDigest.getInstance("SHA-256").digest((salt + androidId).toByteArray())
    }

    /** Dènye "Force Refresh" panel la app la deja fè. */
    var lastForceRefresh: String
        get() = sp.getString("force_refresh", "") ?: ""
        set(value) = sp.edit().putString("force_refresh", value).apply()

    /** Idantifyan panel la itilize pou aparèy sa a = MAC la. */
    val deviceId: String get() = mac

    /** Pop-up ki gen "showOnce" ke itilizatè a deja wè. */
    fun popupSeen(id: String): Boolean = sp.getStringSet("seen_popups", emptySet())!!.contains(id)

    fun markPopupSeen(id: String) {
        val s = sp.getStringSet("seen_popups", emptySet())!!.toMutableSet()
        s.add(id)
        sp.edit().putStringSet("seen_popups", s).apply()
    }

    /** Reglaj ki mache pou jwe videyo yon sèvè playlist (fòm lyen, sèvè videyo, User-Agent). */
    fun tune(server: String, key: String): String? = sp.getString("tune_${server.hashCode()}_$key", null)
    fun setTune(server: String, key: String, value: String?) {
        sp.edit().apply { if (value == null) remove("tune_${server.hashCode()}_$key") else putString("tune_${server.hashCode()}_$key", value) }.apply()
    }
}
