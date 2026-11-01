package com.galaxytvstick.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Lang app la (Kreyòl, English, Français, Español), menm sou tout ekran yo.
 * Sou Android ansyen (< 13), AppCompat ka louvri premye ekran an (egz: gid la apre TV a limen)
 * anvan li fin li lang ki te sove a: ekran sa a te parèt an Kreyòl. Nou sove lang nan tou
 * epi nou aplike l touswit lè app la kòmanse, anvan okenn ekran.
 */
object Lang {
    private const val PREFS = "galaxy_lang"
    private const val KEY = "code"

    fun set(context: Context, code: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
    }

    /** Rele nan Application.onCreate: remete lang kliyan an te chwazi a. */
    fun restore(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!saved.isNullOrBlank()) {
            if (!cur.startsWith(saved)) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved))
        } else if (cur.isNotBlank()) {
            // Ansyen vèsyon: lang nan te sove pa AppCompat sèlman; kenbe l pou pwochen fwa yo
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, cur.take(2)).apply()
        }
    }
}
