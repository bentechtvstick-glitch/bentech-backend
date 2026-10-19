package com.galaxytvstick.app.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AlertDialog
import coil.load
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Ad
import com.galaxytvstick.app.data.Banner
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.PanelConfig
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.databinding.OverlayPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Dènye konfig panel la, pataje ant ekran yo. */
object PanelState {
    var config: PanelConfig = PanelConfig.OFFLINE
}

/** Dènye fwa avètisman ekspirasyon an te parèt (pou tout app la: pa repete l chak fwa ekran an chanje). */
private var lastNoticeAt = 0L

/**
 * Montre sa panel BenTech la voye: ticker, chyron, banner, ads nan kwen,
 * pop-up, broadcast, epi bloke ekran an si admin nan bloke aparèy la.
 */
class OverlayController(
    private val activity: Activity,
    private val v: OverlayPanelBinding,
    private val scope: CoroutineScope,
    private val inPlayer: Boolean,
    /** false = pa montre ticker a (egz: pandan yon fim, pou l pa kouvri ba tan an). */
    private val showTicker: Boolean = true,
    private val onConfig: (PanelConfig) -> Unit = {},
    /** Kòmand panel la ekran an dwe fè li menm (play, reload, restart, logout). */
    private val onCommand: (RemoteCommand) -> Unit = {},
    /** true = banner yo toujou parèt (egz: nan gid la), pa sèlman lè lis chanèl la ouvè. */
    private val bannersAlways: Boolean = false,
    private val onBannerShown: (Boolean) -> Unit = {}
) {
    private val prefs = Prefs(activity)
    private val api = PanelApi(prefs)
    private var pollJob: Job? = null
    private var commandJob: Job? = null
    private var bannerJob: Job? = null
    private var adJob: Job? = null
    private var dialogShowing = false
    private var bannerKey: Any? = null
    private var adKey: Any? = null

    /** Banner yo parèt sèlman lè lis chanèl la ouvè (pou yo pa kouvri TV a). */
    private var listOpen = false

    fun start() {
        apply(PanelState.config)
        startPolling()
        startCommands()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val cfg = runCatching { api.config() }.getOrNull()
                if (cfg != null) {
                    PanelState.config = cfg
                    // Playlist la efase oswa chanje nan panel la: kite l touswit, nenpòt ki ekran TV a ye
                    if (playlistGone(cfg)) return@launch
                    apply(cfg)
                    onConfig(cfg)
                }
                delay(PanelState.config.refreshSec * 1000L)
            }
        }
    }

    /**
     * Playlist app la ap itilize a pa nan panel la ankò (admin nan efase l) → tounen sou ekran playlist yo.
     * Si se kont lan (sèvè, itilizatè, modpas) ki chanje → rechaje ak nouvo kont lan.
     * Retounen true si ekran an fèmen.
     */
    private fun playlistGone(cfg: PanelConfig): Boolean {
        if (activity.isFinishing) return true
        val acc = prefs.account ?: return false
        val id = acc.playlistId ?: return false
        // Sèvè a pa rekonèt TV a (li fèk reenstale, done li yo poko la, oswa yon pàn): PA dekonekte kliyan an.
        // Kliyan an kontinye gade ak playlist li te genyen an jiskaske panel la konnen l ankò.
        if (!cfg.known && !cfg.deleted) return false
        val pl = cfg.playlists.firstOrNull { it.id == id }
        val target: Class<*> = if (pl == null) {
            android.widget.Toast.makeText(activity, R.string.playlist_removed, android.widget.Toast.LENGTH_LONG).show()
            prefs.account = null
            LoginActivity::class.java
        } else {
            val want = pl.toAccount(com.galaxytvstick.app.BuildConfig.DEFAULT_SERVER) ?: return false
            if (want.server == acc.server && want.username == acc.username && want.password == acc.password &&
                want.type == acc.type && want.epgUrl == acc.epgUrl) return false
            prefs.account = want
            prefs.lastChannelId = -1
            MainActivity::class.java
        }
        stop()
        activity.startActivity(android.content.Intent(activity, target)
            .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        activity.finish()
        return true
    }

    /** Kòmand panel la: long-poll ki toujou ouvè pandan ekran an vizib. */
    private fun startCommands() {
        commandJob?.cancel()
        commandJob = scope.launch {
            while (isActive) {
                val cmds = runCatching { api.commands(20) }.getOrNull()
                if (cmds == null) { delay(5_000); continue }
                cmds.forEach { handleCommand(it) }
            }
        }
    }

    private fun handleCommand(cmd: RemoteCommand) {
        if (activity.isFinishing) return
        // Panel la chanje yon bagay ki parèt sou TV a (ticker, chyron, banner…): mete l ajou san mesaj
        if (cmd.type == "sync") { startPolling(); return }
        // Panel la mande lis chanèl konplè a (bouton 🔄 nan paj Channels la): voye l an silans
        if (cmd.type == "channels") {
            val chans = com.galaxytvstick.app.data.ChannelStore.rawChannels
            val cats = com.galaxytvstick.app.data.ChannelStore.rawCategories
            if (chans.isNotEmpty()) com.galaxytvstick.app.GalaxyApp.scope.launch { runCatching { api.uploadChannels(chans, cats) } }
            return
        }
        // Chanjman yo (chanèl, pakè, reglaj) fèt an silans: kliyan an pa wè okenn mesaj.
        // Sèlman lè ekran an ap chanje nèt (redemaraj, dekoneksyon) nou di l poukisa.
        val what = when (cmd.type) {
            "restart" -> activity.getString(R.string.remote_restart)
            "logout" -> activity.getString(R.string.remote_logout)
            else -> null
        }
        if (what != null) android.widget.Toast.makeText(activity, what, android.widget.Toast.LENGTH_LONG).show()
        when (cmd.type) {
            "message" -> showDialog(activity.getString(R.string.remote_message_title), cmd.text, null) {}
            "refresh" -> startPolling() // chèche config la kounye a
            "reload" -> reloadSilently() // rechaje chanèl yo an aryè plan: videyo a pa kanpe
            else -> onCommand(cmd)
        }
    }

    /**
     * Rechaje lis chanèl founisè a san kliyan an pa remake: pa gen ekran chajman, videyo a kontinye.
     * Lè l fini, ekran an mete l ajou ak menm chemen an tankou yon chanjman panel (onConfig).
     */
    fun reloadSilently() {
        val acc = prefs.account ?: return
        val ctx = activity.applicationContext
        com.galaxytvstick.app.GalaxyApp.scope.launch {
            val e = runCatching { com.galaxytvstick.app.data.ChannelCache.fetch(ctx, acc) }.getOrNull() ?: return@launch
            if (e.channels.isEmpty()) return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                com.galaxytvstick.app.data.ChannelStore.rawCategories = e.categories
                com.galaxytvstick.app.data.ChannelStore.rawChannels = e.channels
                com.galaxytvstick.app.data.ChannelStore.applyPanel(PanelState.config)
                if (!activity.isFinishing && !activity.isDestroyed) onConfig(PanelState.config)
            }
            runCatching { api.uploadChannels(e.channels, e.categories) }
        }
    }

    fun stop() {
        pollJob?.cancel()
        commandJob?.cancel()
        bannerJob?.cancel(); bannerKey = null
        v.chyron.stop()
        v.gfx.stop()
        adJob?.cancel(); adKey = null
        noticeJob?.cancel(); v.expiryNotice.visibility = View.GONE
    }

    /** Chanèl kliyan an ap gade plen ekran (non, non gwoup): règ "kache / anlè" ticker a depann de li. */
    private var watchName: String? = null
    private var watchCategory = ""
    fun setWatching(channelName: String?, categoryName: String = "") {
        if (watchName == channelName && watchCategory == categoryName) return
        watchName = channelName
        watchCategory = categoryName
        apply(PanelState.config)
    }

    fun setListOpen(open: Boolean) {
        if (listOpen == open) return
        listOpen = open
        startBanners(PanelState.config)
    }

    private fun apply(cfg: PanelConfig) {
        // --- Bloke (admin nan bloke, abònman ekspire, oswa mentenans) ---
        if (cfg.isBlocking) {
            v.gfx.topClock = null
            v.gfx.setGraphics(null)
            v.blocker.visibility = View.VISIBLE
            v.blockerTitle.text = when (cfg.status) {
                "blocked" -> activity.getString(R.string.device_blocked)
                "expired" -> activity.getString(R.string.device_expired)
                else -> activity.getString(R.string.maintenance_title)
            }
            v.blockerHelp.text = if (cfg.status == "maintenance" && cfg.statusMessage.isNotBlank())
                cfg.statusMessage else activity.getString(R.string.blocker_help)
            v.blockerDevice.text = activity.getString(R.string.device_id_fmt, prefs.mac)
            v.blocker.requestFocus()
            return
        }
        v.blocker.visibility = View.GONE

        // --- Ticker ---
        val mode = watchName?.let { cfg.ticker?.modeFor(it, watchCategory) } ?: 0
        val t = cfg.ticker.takeIf { showTicker && mode != 1 }
        (v.ticker.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            val g = if (mode == 2) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
            if (lp.gravity != g) { lp.gravity = g; v.ticker.layoutParams = lp }
        }
        if (t != null) {
            v.ticker.visibility = View.VISIBLE
            v.ticker.setBackgroundColor(parseColor(t.bgColor, 0xCC7C4DFF.toInt()))
            v.ticker.setTicker(t, this::parseColor)
            // Chyron an rete anlè ticker a menm si tèks la pi gwo
            v.chyron.bottomInset = (if (mode == 2) 0 else v.ticker.barHeightFor(t)) + (20 * v.root.resources.displayMetrics.density).toInt()
        } else {
            v.ticker.visibility = View.GONE
            v.chyron.bottomInset = (30 * v.root.resources.displayMetrics.density).toInt()
        }

        // --- Chyron / Lower third (sèlman nan player) ---
        // Plizyè chyron: chak ak pozisyon, animasyon, dire ak koulè pa l; yo pase youn apre lòt
        v.chyron.setChyrons(if (inPlayer) cfg.chyrons else emptyList())

        // --- Grafik TV: logo bug, watermark, scoreboard, meteyo, countdown, ident (sèlman nan player) ---
        v.gfx.bottomInset = v.chyron.bottomInset
        // Lè ticker a anlè ekran an (pa nan ba a)
        v.gfx.topClock = t?.takeIf { it.showClock && it.clockPos.startsWith("top") && inPlayer }
        v.gfx.setGraphics(if (inPlayer) cfg.graphics else null)

        startBanners(cfg)
        startCornerAds(cfg)
        showMessages(cfg)
        showExpiryNotice(cfg)
    }

    // ------------------------------------------------------------ Plan an prèske fini

    private var noticeJob: Job? = null

    /** Mesaj ki parèt dousman anlè ekran an, rete kèk segonn, epi disparèt; li repete chak X minit. */
    private fun showExpiryNotice(cfg: PanelConfig) {
        val n = cfg.expiryNotice
        if (n == null) { noticeJob?.cancel(); v.expiryNotice.visibility = View.GONE; return }
        if (noticeJob?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastNoticeAt != 0L && now - lastNoticeAt < n.repeatMin * 60_000L) return
        noticeJob = scope.launch {
            delay(3_000) // kite ekran an fin parèt anvan
            lastNoticeAt = android.os.SystemClock.elapsedRealtime()
            val t = v.expiryNotice
            t.text = n.text
            t.alpha = 0f
            t.visibility = View.VISIBLE
            t.animate().alpha(1f).setDuration(900).start()
            delay(n.showSec * 1000L)
            t.animate().alpha(0f).setDuration(1200).withEndAction { t.visibility = View.GONE }.start()
        }
    }

    // ------------------------------------------------------------ Banner

    private fun startBanners(cfg: PanelConfig) {
        val banners: List<Banner> = if ((inPlayer && listOpen) || bannersAlways) cfg.banners else emptyList()
        if (banners == bannerKey && bannerJob?.isActive == true) return
        bannerKey = banners
        bannerJob?.cancel()
        v.bannerBox.visibility = View.GONE
        onBannerShown(banners.isNotEmpty())
        if (banners.isEmpty()) return

        bannerJob = scope.launch {
            var i = 0
            while (isActive) {
                val b = banners[i % banners.size]
                if (b.imageUrl.isNotBlank()) {
                    v.bannerText.visibility = View.GONE
                    v.bannerImage.visibility = View.VISIBLE
                    v.bannerImage.load(b.imageUrl)
                } else {
                    v.bannerImage.visibility = View.GONE
                    v.bannerText.visibility = View.VISIBLE
                    v.bannerText.text = b.text
                    styleBanner(PanelState.config.bannerStyle)
                }
                v.bannerBox.visibility = View.VISIBLE
                delay(8_000)
                i++
            }
        }
    }

    // ------------------------------------------------------------ Ads nan kwen

    private fun startCornerAds(cfg: PanelConfig) {
        val ads: List<Ad> = if (inPlayer) cfg.ads.filter { it.placement == "corner" && it.type == "image" } else emptyList()
        if (ads == adKey && adJob?.isActive == true) return
        adKey = ads
        adJob?.cancel()
        v.cornerAd.visibility = View.GONE
        if (ads.isEmpty()) return

        adJob = scope.launch {
            var i = 0
            while (isActive) {
                val ad = ads[i % ads.size]
                v.cornerAd.load(ad.url)
                v.cornerAd.visibility = View.VISIBLE
                delay(ad.durationSec.coerceAtLeast(3) * 1000L)
                // Kache ad la yon ti moman ant chak piblisite
                v.cornerAd.visibility = View.GONE
                delay(20_000)
                i++
            }
        }
    }

    // ------------------------------------------------------------ Broadcast & Pop-up

    private fun showMessages(cfg: PanelConfig) {
        if (dialogShowing || activity.isFinishing) return
        // Mizajou panel la voye a pase anvan lòt mesaj yo
        if (Updater.maybePrompt(activity, cfg.update, prefs)) return

        cfg.broadcast?.let { b ->
            val key = "b:${b.id}"
            if (!prefs.popupSeen(key)) {
                showDialog(
                    title = when (b.level) {
                        "warning" -> "⚠ " + activity.getString(R.string.broadcast)
                        "urgent" -> "🔴 " + activity.getString(R.string.broadcast)
                        else -> "📢 " + activity.getString(R.string.broadcast)
                    },
                    message = b.message,
                    imageUrl = null
                ) { prefs.markPopupSeen(key) }
                return
            }
        }

        val popup = cfg.popups.firstOrNull { !(it.showOnce && prefs.popupSeen("p:${it.id}")) } ?: return
        showDialog(popup.title, popup.message, popup.imageUrl) { prefs.markPopupSeen("p:${popup.id}") }
    }

    /** Polis, koulè ak estil panel la chwazi pou tèks banner yo. */
    private var bannerDefault: Triple<android.graphics.Typeface?, Int, android.graphics.drawable.Drawable?>? = null
    private fun styleBanner(spec: com.galaxytvstick.app.data.TextSpec?) {
        val tv = v.bannerText
        if (bannerDefault == null) bannerDefault = Triple(tv.typeface, tv.currentTextColor, tv.background)
        val d = bannerDefault!!
        tv.paint.shader = null
        if (spec == null) { tv.typeface = d.first; tv.setTextColor(d.second); tv.background = d.third; return }
        val c1 = TextFx.color(spec.color, Color.WHITE); val c2 = TextFx.color(spec.color2, 0xFF1D4ED8.toInt())
        tv.typeface = TextFx.typeface(activity, spec.font, spec.italic)
        tv.setTextColor(c1)
        tv.background = if (spec.style == "box") android.graphics.drawable.GradientDrawable().apply { setColor(c2); cornerRadius = tv.textSize * 0.4f } else d.third
        tv.post {
            val top = tv.paddingTop.toFloat(); val bottom = (tv.height - tv.paddingBottom).toFloat().coerceAtLeast(top + tv.textSize)
            TextFx.applyPaint(tv.paint, spec.style, c2, activity.resources.displayMetrics.density, top, bottom, c1)
            tv.invalidate()
        }
    }

    private fun showDialog(title: String, message: String, imageUrl: String?, onClose: () -> Unit) {
        dialogShowing = true
        val ps = PanelState.config.popupStyle
        val builder = AlertDialog.Builder(activity, R.style.Theme_Galaxy_Dialog)
        if (ps != null && title.isNotBlank()) {
            // Tit la ak estil panel la chwazi a
            val dp = activity.resources.displayMetrics.density
            builder.setCustomTitle(FxTextView(activity, ps.style, TextFx.color(ps.color, Color.WHITE), TextFx.color(ps.color2, 0xFF1D4ED8.toInt())).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = TextFx.typeface(activity, ps.font, ps.italic)
                setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
                applyStyle()
            })
        } else builder.setTitle(title)
        builder
            .setMessage(message)
            .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                dialogShowing = false
                onClose()
            }
        if (imageUrl != null) {
            val img = android.widget.ImageView(activity).apply {
                adjustViewBounds = true
                setPadding(40, 20, 40, 0)
                load(imageUrl)
            }
            builder.setView(img)
        }
        builder.show()
    }

    private fun parseColor(s: String, fallback: Int): Int =
        runCatching { Color.parseColor(s) }.getOrDefault(fallback)
}
