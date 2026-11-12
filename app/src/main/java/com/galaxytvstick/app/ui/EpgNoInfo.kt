package com.galaxytvstick.app.ui

import android.content.Context
import android.content.res.Configuration
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import java.util.Locale

/**
 * Tèks "pa gen enfòmasyon" nan gid la, nan LANG KATEGORI chanèl la:
 * egz. "EU | FRANCE" → franse, "US | NEWS" → angle, "LATINO" → espanyòl, "HAITI" → kreyòl.
 * Sa mèt la ekri nan panel la pou lang sa a pase an premye; sinon tèks app la nan lang sa a.
 */
object EpgNoInfo {
    private val FR = Regex("""(?i)(^|[^A-Z])(FR|FRANCE|FRENCH|FRANCAIS|FRANÇAIS|BELGI|SUISSE|QUEBEC|QUÉBEC|CA\s*FR|AFRIQUE)([^A-Z]|$)""")
    private val ES = Regex("""(?i)(^|[^A-Z])(ES|ESP|SPAIN|ESPA[NÑ]A|LATIN|LATINO|MEXIC|MX|ARGENT|COLOMBI|CHILE|PERU|DOMINIC|RD|CUBA|PUERTO|SPANISH)([^A-Z]|$)""")
    private val HT = Regex("""(?i)(^|[^A-Z])(HT|HAITI|HAÏTI|AYITI|KREYOL|KREYÒL|CREOLE)([^A-Z]|$)""")
    private val EN = Regex("""(?i)(^|[^A-Z])(US|USA|AM|AMERICA|UK|GB|ENGLISH|EN|CA|CANADA|AUSTRALIA|AU|NFL|NBA|NHL|MLB|ESPN|USA\s*NEWS)([^A-Z]|$)""")
    private val cache = HashMap<String, String?>()

    /** Lang kategori a (ht/fr/es/en), oswa null si nou pa konnen. */
    fun langOf(categoryName: String): String? = cache.getOrPut(categoryName) {
        when {
            HT.containsMatchIn(categoryName) -> "ht"
            FR.containsMatchIn(categoryName) -> "fr"
            ES.containsMatchIn(categoryName) -> "es"
            EN.containsMatchIn(categoryName) -> "en"
            else -> null
        }
    }

    fun text(ctx: Context, ch: Channel?): String {
        val cfg = PanelState.config
        val cat = ch?.let { c -> ChannelStore.rawCategories.firstOrNull { it.id == c.categoryId }?.name }.orEmpty()
        val lang = langOf(cat)
        // Lang kategori a konnen (egz: «EU | FRANCE» → fr): tèks mèt la nan LANG SA A, sinon tèks app la nan lang sa a.
        // (Pa janm mete yon tèks angle sou yon kategori franse.) Kaz 🌐 la: sèlman pou kategori lang lan pa konnen.
        if (lang != null) return cfg.epgNoInfoL[lang] ?: localized(ctx, lang)
        return cfg.epgNoInfo.ifBlank { ctx.getString(R.string.epg_no_info) }
    }

    private val strCache = HashMap<String, String>()
    private fun localized(ctx: Context, lang: String): String = strCache.getOrPut(lang) {
        val conf = Configuration(ctx.resources.configuration).apply { setLocale(Locale(lang)) }
        ctx.createConfigurationContext(conf).getString(R.string.epg_no_info)
    }
}
