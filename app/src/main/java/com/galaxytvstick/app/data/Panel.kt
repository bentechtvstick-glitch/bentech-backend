package com.galaxytvstick.app.data

import android.os.Build
import com.galaxytvstick.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// ---------------------------------------------------------------------------
// Modèl done panel BenTech la voye bay app la (GET /api/devices/:mac/config)
// ---------------------------------------------------------------------------

/** Imoji k ap kouri sou ba ticker a. speed 1–10; flip = vire l (miwa). */
data class TickerRunner(val emoji: String, val speed: Int, val flip: Boolean)

/** Yon mesaj nan ticker a; color vid = koulè tèks jeneral la. */
data class TickerItem(val text: String, val color: String)

/** Ticker style chèn TV: etikèt agoch (BREAKING NEWS), separatè ant mesaj yo, lè adwat. */
data class Ticker(
    val text: String,
    val textColor: String,
    val bgColor: String,
    val speed: Int,
    val items: List<TickerItem> = emptyList(),
    val separator: String = "•",
    val label: String = "",
    val labelBg: String = "#E50914",
    val labelColor: String = "#FFFFFF",
    val showClock: Boolean = false,
    /** "12" = 8:45 PM, "24" = 20:45 */
    val clockFormat: String = "24",
    /** Gwosè tèks la an sp (12–40). */
    val textSize: Int = 20,
    /** Fon transparan: tèks la jwenn yon lonbraj pou l rete lizib sou videyo a. */
    val transparent: Boolean = false,
    /** Imoji yo bouje (drapo flote, machin kouri, balon vire…). */
    val animateEmoji: Boolean = true,
    /** Animasyon k ap kouri sou ba a (egz: 🏎️💨); null = pa gen. */
    val runner: TickerRunner? = null,
    /** "left" = tèks la ale sou bò gòch (dwat → gòch, tankou chèn TV yo); "right" = gòch → dwat. */
    val direction: String = "left",
    /** true = mesaj yo repete san rete (ba a toujou plen); false = chak mesaj pase yon sèl fwa epi rekòmanse. */
    val repeat: Boolean = false,
    /** Mo kle (miniskil): chanèl/gwoup ki deja gen pwòp ticker yo → kache pa nou an. */
    val hideOn: List<String> = emptyList(),
    /** Mo kle (miniskil): chanèl/gwoup kote ticker pa nou an monte anlè ekran an. */
    val topOn: List<String> = emptyList()
) {
    /** 0 = nòmal (anba), 1 = kache, 2 = anlè, pou chanèl sa a. */
    fun modeFor(channelName: String, categoryName: String): Int {
        if (hideOn.isEmpty() && topOn.isEmpty()) return 0
        val hay = "$channelName\n$categoryName".lowercase()
        return when {
            hideOn.any { hay.contains(it) } -> 1
            topOn.any { hay.contains(it) } -> 2
            else -> 0
        }
    }
}
/** Mizajou app la panel la voye: nimewo bati a (versionCode), lyen APK a, obligatwa oswa non. */
data class AppUpdate(val build: Int, val version: String, val url: String, val force: Boolean, val pushAt: String)
data class Chyron(val title: String, val subtitle: String, val logoUrl: String?)

/** Yon chyron / lower third jan panel la voye l (paj "Chyron / Lower Third"). Gwosè yo an sp. */
data class ChyronItem(
    val id: String,
    val headline: String,
    val name: String,
    val title: String,
    val logoUrl: String?,
    /** bottom-left, bottom-center, bottom-right, top-left, top-center, top-right */
    val position: String = "bottom-left",
    /** slide, fade, wipe, none */
    val animation: String = "slide",
    /** Segonn sou ekran an; 0 = toutan. */
    val duration: Int = 10,
    /** Opasite fon an (%). */
    val opacity: Int = 92,
    val bgColor: String = "#0B1630",
    val textColor: String = "#FFFFFF",
    val accentColor: String = "#E50914",
    val headlineSize: Int = 14,
    val nameSize: Int = 26,
    val titleSize: Int = 16,
    /** Gwo imoji anime bò kote chyron an (egz: 🇭🇹 k ap flote). */
    val sticker: String = "",
    /** Imoji nan tèks yo bouje. */
    val animateEmoji: Boolean = true
)

/** Banner imaj (imageUrl) oswa tèks (text). */
data class Banner(val id: String, val imageUrl: String, val text: String, val position: String)
data class Popup(val id: String, val title: String, val message: String, val imageUrl: String?, val showOnce: Boolean)
data class Ad(
    val id: String,
    val type: String,          // "image" oswa "video"
    val url: String,
    val placement: String,     // "preroll", "corner", "fullscreen", "break"
    val durationSec: Int,
    val skipAfterSec: Int,
    val name: String = ""
)

/**
 * Koupi piblisite (tankou chèn TV): spot yo pase plen ekran pandan kliyan an ap gade live,
 * epi TV a tounen sou chanèl la.
 */
data class AdBreak(
    /** true = TV a lanse koupi yo poukont li (chak [everyMin] minit ak nan [times]). */
    val auto: Boolean,
    val everyMin: Int,
    /** Lè fiks "HH:mm" (lè TV a). */
    val times: List<String>,
    val spotsPerBreak: Int,
    /** 0 = kliyan an pa ka sote piblisite a. */
    val skipAfterSec: Int,
    val spots: List<Ad>
)
data class Broadcast(val id: String, val message: String, val level: String)

/** Kòmand sèvis kliyan an voye soti nan panel la (chanje chanèl, mesaj, rechaje…). */
data class RemoteCommand(
    val id: String, val type: String, val text: String, val channelId: Int,
    /** Pou "adbreak": spot pou jwe yo. */
    val ads: List<Ad> = emptyList(),
    val skipAfterSec: Int = 0
)

/** Erè kòd aktivasyon: [code] = not_found, revoked, used, no_playlist, too_many. */

/** Playlist admin nan ajoute pou aparèy la nan panel la. */
data class PanelPlaylist(
    val id: String,
    val name: String,
    val server: String,   // vid = itilize DEFAULT_SERVER app la
    val username: String,
    val password: String
)

/**
 * Evènman an dirèk. Li ka lye ak yon chanèl Xtream (channelId oswa channelName),
 * oswa gen pwòp lyen stream pa l (streamUrl).
 */
data class LiveEvent(
    val id: String,
    val title: String,
    val channelId: Int,
    val channelName: String,
    val streamUrl: String,
    val startsAt: Long,
    val endsAt: Long,
    val imageUrl: String?,
    val featured: Boolean
)

// ---------------------------------------------------------------- Grafik TV
/** Ti logo chèn nan nan yon kwen (imaj oswa tèks). [size] = wotè an dp, [opacity] an %. */
data class GfxBug(val imageUrl: String?, val text: String, val position: String, val size: Int, val opacity: Int)
/** Tèks pal sou imaj la (egz: MAC kliyan an). [move] = chanje kwen chak 30 s. */
data class GfxWatermark(val text: String, val position: String, val size: Int, val opacity: Int, val move: Boolean)
data class GfxScoreboard(
    val league: String, val home: String, val away: String, val homeScore: String, val awayScore: String,
    val clock: String, val homeColor: String, val awayColor: String, val position: String
)
/** [temp] deja fòmate pa sèvè a (egz: "31°C"); [icon] se yon imoji. */
data class GfxWeather(val city: String, val temp: String, val icon: String, val position: String)
/** [targetAt] an segonn epoch. Apre lè a: [doneText] (si genyen). */
data class GfxCountdown(val title: String, val targetAt: Long, val doneText: String, val position: String)
/** Logo/non chèn nan nan mitan ekran an pou [durationSec] segonn, chak [everyMin] minit (0 = pa otomatik). */
data class GfxIdent(val imageUrl: String?, val text: String, val tagline: String, val everyMin: Int, val durationSec: Int, val onChannelChange: Boolean)
/** Ekran anvan ("in") ak apre ("out") koupi piblisite a. */
data class GfxBumper(
    val inUrl: String?, val inText: String, val outUrl: String?, val outText: String,
    val durationSec: Int, val bgColor: String, val textColor: String
) {
    fun has(out: Boolean) = if (out) outUrl != null || outText.isNotBlank() else inUrl != null || inText.isNotBlank()
}
data class Graphics(
    val bug: GfxBug? = null, val watermark: GfxWatermark? = null, val scoreboard: GfxScoreboard? = null,
    val weather: GfxWeather? = null, val countdown: GfxCountdown? = null, val ident: GfxIdent? = null, val bumper: GfxBumper? = null
)

data class PanelConfig(
    /** false = sèvè a pa konnen aparèy sa a (egz: li pèdi done li yo). */
    val known: Boolean,
    val status: String,                 // active, pending, blocked, expired, maintenance
    val statusMessage: String,
    val deviceName: String,
    val maxChannels: Int,               // 0 = pa gen limit
    val hiddenChannels: Set<Int>,
    val hiddenCategories: Set<String>,
    /** Non admin nan chanje nan panel la: ID kategori → non, ID chanèl → non. */
    val categoryNames: Map<String, String> = emptyMap(),
    val channelNames: Map<Int, String> = emptyMap(),
    /** Kont kliyan an nan panel la (vid si aparèy la pa lye ak yon kliyan). */
    val accountName: String = "",
    val accountPlan: String = "",
    val accountExpiry: String = "",
    val ticker: Ticker?,
    val chyron: Chyron?,
    /** Tout chyron aktif yo (vèsyon pwofesyonèl la). */
    val chyrons: List<ChyronItem> = emptyList(),
    val banners: List<Banner>,
    val popups: List<Popup>,
    val ads: List<Ad>,
    /** Koupi piblisite otomatik (null = pa gen spot). */
    val adBreak: AdBreak? = null,
    /** Grafik TV: logo bug, watermark, scoreboard, meteyo, countdown, ident, bumper. */
    val graphics: Graphics? = null,
    val broadcast: Broadcast?,
    val liveEvents: List<LiveEvent>,
    val refreshSec: Int,
    val playlists: List<PanelPlaylist> = emptyList(),
    val forceRefreshAt: String = "",
    /** Mizajou admin nan voye depi panel la; null = pa gen. */
    val update: AppUpdate? = null
) {
    /**
     * Sèlman desizyon admin nan bloke TV a (bloke, ekspire, mentenans).
     * "pending" pa janm bloke yon kliyan k ap gade TV deja.
     */
    val isBlocking get() = known && status in setOf("blocked", "expired", "maintenance")

    /** Aplike règ panel la: kache chanèl/kategori epi limite kantite chanèl. */
    fun filterChannels(all: List<Channel>): List<Channel> {
        val visible = all.filter { it.streamId !in hiddenChannels && it.categoryId !in hiddenCategories }
        val limited = if (maxChannels > 0) visible.take(maxChannels) else visible
        return if (channelNames.isEmpty()) limited else limited.map { c -> channelNames[c.streamId]?.let { c.copy(name = it) } ?: c }
    }

    fun filterCategories(all: List<Category>): List<Category> =
        all.filter { it.id !in hiddenCategories }.map { c -> categoryNames[c.id]?.let { Category(c.id, it) } ?: c }

    companion object {
        /** Konfig pa defo si panel la pa reponn (app la kontinye mache nòmal). */
        val OFFLINE = PanelConfig(
            known = false, status = "pending", statusMessage = "", deviceName = "", maxChannels = 0,
            hiddenChannels = emptySet(), hiddenCategories = emptySet(), ticker = null, chyron = null,
            banners = emptyList(), popups = emptyList(), ads = emptyList(), broadcast = null,
            liveEvents = emptyList(), refreshSec = 60
        )

        fun parse(o: JSONObject): PanelConfig = PanelConfig(
            known = o.optBoolean("known", true),
            status = o.optString("status", "pending"),
            statusMessage = o.optString("statusMessage"),
            deviceName = o.optString("deviceName"),
            maxChannels = o.optInt("maxChannels", 0),
            hiddenChannels = o.optJSONArray("hiddenChannels").ints().toSet(),
            hiddenCategories = o.optJSONArray("hiddenCategories").strings().toSet(),
            categoryNames = o.optJSONObject("categoryNames").stringMap(),
            accountName = o.optJSONObject("account")?.optString("name").orEmpty(),
            accountPlan = o.optJSONObject("account")?.optString("plan").orEmpty(),
            accountExpiry = o.optJSONObject("account")?.optString("expiry").orEmpty(),
            channelNames = o.optJSONObject("channelNames").stringMap().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap(),
            ticker = o.optJSONObject("ticker")?.takeIf { it.optBoolean("enabled", true) }?.let {
                Ticker(
                    text = it.optString("text"),
                    textColor = it.optString("textColor", "#FFFFFF"),
                    bgColor = it.optString("bgColor", "#CC7C4DFF"),
                    speed = it.optInt("speed", 5),
                    items = it.optJSONArray("items").objects().map { x -> TickerItem(x.optString("text"), x.optString("color")) }
                        .filter { x -> x.text.isNotBlank() },
                    separator = it.optString("separator", "•"),
                    label = it.optString("label"),
                    labelBg = it.optString("labelBg", "#E50914"),
                    labelColor = it.optString("labelColor", "#FFFFFF"),
                    showClock = it.optBoolean("showClock", false),
                    clockFormat = if (it.optString("clockFormat") == "12") "12" else "24",
                    textSize = it.optInt("textSize", 20).coerceIn(12, 40),
                    transparent = it.optBoolean("transparent", false),
                    animateEmoji = it.optBoolean("animateEmoji", true),
                    runner = it.optJSONObject("runner")?.let { r ->
                        TickerRunner(r.optString("emoji").trim(), r.optInt("speed", 6).coerceIn(1, 10), r.optBoolean("flip", false))
                    }?.takeIf { r -> r.emoji.isNotBlank() },
                    direction = it.optString("direction", "left"),
                    repeat = it.optBoolean("repeat", false),
                    hideOn = it.optJSONArray("hideOn").strings(),
                    topOn = it.optJSONArray("topOn").strings()
                )
            }?.takeIf { it.text.isNotBlank() },
            chyron = o.optJSONObject("chyron")?.takeIf { it.optBoolean("enabled", true) }?.let {
                Chyron(it.optString("title"), it.optString("subtitle"), it.optStringOrNull("logoUrl"))
            }?.takeIf { it.title.isNotBlank() },
            chyrons = o.optJSONArray("chyrons").objects().map {
                ChyronItem(
                    id = it.optString("id"),
                    headline = it.optString("headline"),
                    name = it.optString("name"),
                    title = it.optString("title"),
                    logoUrl = it.optStringOrNull("logoUrl"),
                    position = it.optString("position", "bottom-left"),
                    animation = it.optString("animation", "slide"),
                    duration = it.optInt("duration", 10).coerceIn(0, 3600),
                    opacity = it.optInt("opacity", 92).coerceIn(10, 100),
                    bgColor = it.optString("bgColor", "#0B1630"),
                    textColor = it.optString("textColor", "#FFFFFF"),
                    accentColor = it.optString("accentColor", "#E50914"),
                    headlineSize = it.optInt("headlineSize", 14).coerceIn(8, 40),
                    nameSize = it.optInt("nameSize", 26).coerceIn(10, 60),
                    titleSize = it.optInt("titleSize", 16).coerceIn(8, 40),
                    sticker = it.optString("sticker").trim(),
                    animateEmoji = it.optBoolean("animateEmoji", true)
                )
            }.filter { it.headline.isNotBlank() || it.name.isNotBlank() || it.title.isNotBlank() }
                // Ansyen backend: sèlman "chyron" (tit + sou-tit)
                .ifEmpty {
                    o.optJSONObject("chyron")?.takeIf { it.optString("title").isNotBlank() }?.let {
                        listOf(ChyronItem("legacy", "", it.optString("title"), it.optString("subtitle"), it.optStringOrNull("logoUrl"),
                            duration = 0, accentColor = "#7C4DFF"))
                    } ?: emptyList()
                },
            banners = o.optJSONArray("banners").objects().map {
                Banner(
                    it.optString("id"), it.optString("imageUrl"), it.optString("text"),
                    it.optString("position", "top")
                )
            }.filter { it.imageUrl.isNotBlank() || it.text.isNotBlank() },
            popups = o.optJSONArray("popups").objects().map {
                Popup(
                    it.optString("id"), it.optString("title"), it.optString("message"),
                    it.optStringOrNull("imageUrl"), it.optBoolean("showOnce", true)
                )
            }.filter { it.title.isNotBlank() || it.message.isNotBlank() },
            ads = o.optJSONArray("ads").objects().map {
                Ad(
                    it.optString("id"), it.optString("type", "image"), it.optString("url"),
                    it.optString("placement", "corner"), it.optInt("durationSec", 10),
                    it.optInt("skipAfterSec", 5), it.optString("name")
                )
            }.filter { it.url.isNotBlank() },
            adBreak = o.optJSONObject("adBreak")?.let { ab ->
                AdBreak(
                    auto = ab.optBoolean("auto", false),
                    everyMin = ab.optInt("everyMin", 15).coerceIn(0, 240), // 0 = pa gen entèval (sèlman lè fiks)
                    times = ab.optJSONArray("times").strings(),
                    spotsPerBreak = ab.optInt("spotsPerBreak", 2).coerceIn(1, 10),
                    skipAfterSec = ab.optInt("skipAfterSec", 0).coerceIn(0, 120),
                    spots = ab.optJSONArray("spots").objects().map { parseAd(it, "break") }.filter { it.url.isNotBlank() }
                )
            }?.takeIf { it.spots.isNotEmpty() },
            graphics = o.optJSONObject("graphics")?.let { g ->
                Graphics(
                    bug = g.optJSONObject("bug")?.let {
                        GfxBug(it.optStringOrNull("imageUrl"), it.optString("text"), it.optString("position", "top-right"),
                            it.optInt("size", 44).coerceIn(16, 160), it.optInt("opacity", 85).coerceIn(10, 100))
                    }?.takeIf { it.imageUrl != null || it.text.isNotBlank() },
                    watermark = g.optJSONObject("watermark")?.let {
                        GfxWatermark(it.optString("text"), it.optString("position", "bottom-right"),
                            it.optInt("size", 16).coerceIn(8, 60), it.optInt("opacity", 25).coerceIn(3, 100), it.optBoolean("move", false))
                    }?.takeIf { it.text.isNotBlank() },
                    scoreboard = g.optJSONObject("scoreboard")?.let {
                        GfxScoreboard(it.optString("league"), it.optString("home"), it.optString("away"),
                            it.optString("homeScore", "0"), it.optString("awayScore", "0"), it.optString("clock"),
                            it.optString("homeColor", "#1D4ED8"), it.optString("awayColor", "#DC2626"), it.optString("position", "top-left"))
                    },
                    weather = g.optJSONObject("weather")?.let {
                        GfxWeather(it.optString("city"), it.optString("temp"), it.optString("icon"), it.optString("position", "top-right"))
                    }?.takeIf { it.temp.isNotBlank() },
                    countdown = g.optJSONObject("countdown")?.let {
                        GfxCountdown(it.optString("title"), it.optLong("targetAt"), it.optString("doneText"), it.optString("position", "top-center"))
                    }?.takeIf { it.targetAt > 0 },
                    ident = g.optJSONObject("ident")?.let {
                        GfxIdent(it.optStringOrNull("imageUrl"), it.optString("text"), it.optString("tagline"),
                            it.optInt("everyMin", 30).coerceIn(0, 240), it.optInt("durationSec", 5).coerceIn(2, 15), it.optBoolean("onChannelChange", false))
                    }?.takeIf { it.imageUrl != null || it.text.isNotBlank() },
                    bumper = g.optJSONObject("bumper")?.let {
                        GfxBumper(it.optStringOrNull("inUrl"), it.optString("inText"), it.optStringOrNull("outUrl"), it.optString("outText"),
                            it.optInt("durationSec", 4).coerceIn(2, 15), it.optString("bgColor", "#0B1630"), it.optString("textColor", "#FFFFFF"))
                    }?.takeIf { it.has(false) || it.has(true) }
                )
            },
            broadcast = o.optJSONObject("broadcast")?.let {
                Broadcast(it.optString("id"), it.optString("message"), it.optString("level", "info"))
            }?.takeIf { it.message.isNotBlank() },
            liveEvents = o.optJSONArray("liveEvents").objects().map {
                LiveEvent(
                    id = it.optString("id"),
                    title = it.optString("title"),
                    channelId = it.optInt("channelId", 0),
                    channelName = it.optString("channelName"),
                    streamUrl = it.optString("streamUrl"),
                    startsAt = it.optLong("startsAt"),
                    endsAt = it.optLong("endsAt"),
                    imageUrl = it.optStringOrNull("imageUrl"),
                    featured = it.optBoolean("featured", false)
                )
            },
            update = o.optJSONObject("update")?.let { u ->
                AppUpdate(u.optInt("build", 0), u.optString("version"), u.optString("url"), u.optBoolean("force", false), u.optString("pushAt"))
            }?.takeIf { it.build > 0 && it.url.startsWith("http") },
            refreshSec = o.optInt("refreshSec", 60).coerceIn(5, 600), // 5 s lè yon mesaj ticker pral kòmanse/fini
            playlists = o.optJSONArray("playlists").objects().map {
                PanelPlaylist(
                    it.optString("id"), it.optString("name"), it.optString("server"),
                    it.optString("username"), it.optString("password")
                )
            }.filter { it.id.isNotBlank() && it.username.isNotBlank() },
            forceRefreshAt = o.optString("forceRefreshAt")
        )
    }
}

// ---------------------------------------------------------------------------
// Kliyan HTTP pou backend panel la
// ---------------------------------------------------------------------------

class PanelApi(private val prefs: Prefs) {

    private val deviceId get() = prefs.deviceId

    private val base = BuildConfig.PANEL_URL.trimEnd('/')
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        XtreamApi.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Panel HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /**
     * Anrejistre aparèy la nan panel la.
     * restore = playlist app la ap itilize kounye a; sèvè a remete l si l te pèdi done li yo.
     */
    suspend fun register(xtreamUser: String?, restore: Account? = null) {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("mac", prefs.mac)
            .put("deviceKey", prefs.deviceKey)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("xtreamUser", xtreamUser ?: JSONObject.NULL)
        if (restore?.playlistId != null) {
            body.put(
                "restorePlaylist", JSONObject()
                    .put("id", restore.playlistId)
                    .put("name", restore.name ?: restore.username)
                    .put("server", restore.server)
                    .put("username", restore.username)
                    .put("password", restore.password)
            )
        }
        call(Request.Builder().url("$base/devices/register").post(body.toString().toRequestBody(jsonType)).build())
    }

    /** Chèche konfigirasyon aparèy la (playlist, règ chanèl, ticker, ads, elatriye). */
    suspend fun config(): PanelConfig {
        val text = call(
            Request.Builder().url("$base/devices/${enc(deviceId)}/config")
                .header("X-Device-Key", prefs.deviceKey).get().build()
        )
        return PanelConfig.parse(JSONObject(text))
    }

    /** Voye lis chanèl yo bay panel la pou admin nan ka hide/show yo. */
    suspend fun uploadChannels(channels: List<Channel>, categories: List<Category>) {
        val catNames = categories.associate { it.id to it.name }
        val arr = JSONArray()
        channels.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.streamId)
                    .put("num", it.num)
                    .put("name", it.name)
                    .put("categoryId", it.categoryId)
                    .put("categoryName", catNames[it.categoryId] ?: "")
            )
        }
        val body = JSONObject().put("channels", arr)
        call(
            Request.Builder().url("$base/devices/${enc(deviceId)}/channels")
                .header("X-Device-Key", prefs.deviceKey)
                .post(body.toString().toRequestBody(jsonType)).build()
        )
    }

    /** Di panel la yon spot piblisite kòmanse ("start") oswa fini ("complete"), pou konte yo. */
    suspend fun adEvent(adId: String, event: String) {
        call(
            Request.Builder().url("$base/devices/${enc(deviceId)}/ad-events")
                .header("X-Device-Key", prefs.deviceKey)
                .post(JSONObject().put("adId", adId).put("event", event).toString().toRequestBody(jsonType)).build()
        )
    }

    /** Di panel la sa TV a ap montre kounye a (chanèl, pwogram, kalite). */
    suspend fun status(body: JSONObject) {
        call(
            Request.Builder().url("$base/devices/${enc(deviceId)}/status")
                .header("X-Device-Key", prefs.deviceKey)
                .post(body.toString().toRequestBody(jsonType)).build()
        )
    }

    /**
     * Tann kòmand panel la (long-poll). Sèvè a reponn touswit si gen yon kòmand,
     * sinon apre [waitSec] segonn ak yon lis vid. Si coroutine nan anile, demann lan anile tou
     * (pou yon lòt ekran ka pran relè a san kòmand pa pèdi).
     */
    suspend fun commands(waitSec: Int = 20): List<RemoteCommand> {
        val req = Request.Builder().url("$base/devices/${enc(deviceId)}/commands?wait=$waitSec")
            .header("X-Device-Key", prefs.deviceKey).get().build()
        val call = longPoll.newCall(req)
        val text = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (!r.isSuccessful) { cont.resumeWithException(IOException("Panel HTTP ${r.code}")); return }
                        cont.resume(r.body?.string() ?: "")
                    }
                }
            })
        }
        return JSONObject(text).optJSONArray("commands").objects().map {
            RemoteCommand(
                it.optString("id"), it.optString("type"), it.optString("text"), it.optInt("channelId", 0),
                ads = it.optJSONArray("ads").objects().map { a -> parseAd(a, "break") }.filter { a -> a.url.isNotBlank() },
                skipAfterSec = it.optInt("skipAfterSec", 0).coerceIn(0, 120)
            )
        }
    }

    private val longPoll = XtreamApi.http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    /** MAC la gen ":" ladan l, kode l pou URL la. */
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

// ---------------------------------------------------------------------------
// Ti fonksyon JSON
// ---------------------------------------------------------------------------

/** Li yon piblisite nan JSON (kòmand "adbreak" ak konfig adBreak). */
private fun parseAd(o: JSONObject, placement: String) = Ad(
    o.optString("id"), o.optString("type", "video"), o.optString("url"), o.optString("placement", placement),
    o.optInt("durationSec", 10), o.optInt("skipAfterSec", 0), o.optString("name")
)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.ints(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject?.stringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val out = HashMap<String, String>()
    val it = keys()
    while (it.hasNext()) { val k = it.next(); val v = optString(k).trim(); if (v.isNotEmpty()) out[k] = v }
    return out
}
