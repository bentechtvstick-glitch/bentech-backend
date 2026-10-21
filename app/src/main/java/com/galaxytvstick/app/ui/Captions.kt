package com.galaxytvstick.app.ui

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import com.galaxytvstick.app.data.Prefs

/**
 * Soutit / caption (Settings → Captions). "off" = etenn, "any" = nenpòt lang ki disponib
 * (lang TV a an premye), oswa yon lang presi ("en", "fr", "es", "ht"…).
 * Li mache pou fim/seri ki gen soutit, ak chanèl live ki voye closed captions (CC).
 */
object Captions {
    val CODES = listOf("off", "any", "en", "fr", "es", "ht", "pt", "de", "it", "nl", "ar", "ru", "zh", "ja", "ko", "hi", "tr", "pl")
    val NAMES = listOf("", "", "English", "Français", "Español", "Kreyòl", "Português", "Deutsch", "Italiano", "Nederlands",
        "العربية", "Русский", "中文", "日本語", "한국어", "हिन्दी", "Türkçe", "Polski")

    /** Ekran player ki ouvè kounye a: aplike chanjman an touswit san rekòmanse videyo a. */
    @Volatile var onChanged: (() -> Unit)? = null
    /** Player k ap jwe kounye a (pou di kliyan an si videyo a gen soutit). */
    @Volatile var current: java.lang.ref.WeakReference<Player>? = null

    /** Konbyen soutit/CC videyo k ap jwe a genyen (null = pa gen videyo k ap jwe). */
    fun available(): Int? {
        val p = current?.get() ?: return null
        return p.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
    }

    fun apply(ctx: Context, p: Player) {
        current = java.lang.ref.WeakReference(p)
        val pref = Prefs(ctx).captions
        val b = p.trackSelectionParameters.buildUpon()
        if (pref == "off") {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val device = java.util.Locale.getDefault().language
            val langs = if (pref == "any") (listOf(device) + CODES.drop(2)).distinct() else listOf(pref)
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguages(*langs.toTypedArray())
                .setSelectUndeterminedTextLanguage(true)
                .setIgnoredTextSelectionFlags(0)
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_SUBTITLE)
        }
        p.trackSelectionParameters = b.build()
    }
}
