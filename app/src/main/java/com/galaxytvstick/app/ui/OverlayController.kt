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
    private val onCommand: (RemoteCommand) -> Unit = {}
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
                    apply(cfg)
                    onConfig(cfg)
                }
                delay(PanelState.config.refreshSec * 1000L)
            }
        }
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
        // Toujou montre kliyan an lè sèvis kliyan an aji sou TV li
        val what = when (cmd.type) {
            "play" -> activity.getString(R.string.remote_play)
            "reload" -> activity.getString(R.string.remote_reload)
            "restart" -> activity.getString(R.string.remote_restart)
            "logout" -> activity.getString(R.string.remote_logout)
            "refresh" -> activity.getString(R.string.remote_refresh)
            else -> null
        }
        if (what != null) android.widget.Toast.makeText(activity, what, android.widget.Toast.LENGTH_LONG).show()
        when (cmd.type) {
            "message" -> showDialog(activity.getString(R.string.remote_message_title), cmd.text, null) {}
            "refresh" -> startPolling() // chèche config la kounye a
            else -> onCommand(cmd)
        }
    }

    fun stop() {
        pollJob?.cancel()
        commandJob?.cancel()
        bannerJob?.cancel(); bannerKey = null
        v.chyron.stop()
        v.gfx.stop()
        adJob?.cancel(); adKey = null
    }

    fun setListOpen(open: Boolean) {
        if (listOpen == open) return
        listOpen = open
        startBanners(PanelState.config)
    }

    private fun apply(cfg: PanelConfig) {
        // --- Bloke (admin nan bloke, abònman ekspire, oswa mentenans) ---
        if (cfg.isBlocking) {
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
        val t = cfg.ticker.takeIf { showTicker }
        if (t != null) {
            v.ticker.visibility = View.VISIBLE
            v.ticker.setBackgroundColor(parseColor(t.bgColor, 0xCC7C4DFF.toInt()))
            v.ticker.setTicker(t, this::parseColor)
            // Chyron an rete anlè ticker a menm si tèks la pi gwo
            v.chyron.bottomInset = v.ticker.barHeightFor(t) + (20 * v.root.resources.displayMetrics.density).toInt()
        } else {
            v.ticker.visibility = View.GONE
            v.chyron.bottomInset = (30 * v.root.resources.displayMetrics.density).toInt()
        }

        // --- Chyron / Lower third (sèlman nan player) ---
        // Plizyè chyron: chak ak pozisyon, animasyon, dire ak koulè pa l; yo pase youn apre lòt
        v.chyron.setChyrons(if (inPlayer) cfg.chyrons else emptyList())

        // --- Grafik TV: logo bug, watermark, scoreboard, meteyo, countdown, ident (sèlman nan player) ---
        v.gfx.bottomInset = v.chyron.bottomInset
        v.gfx.setGraphics(if (inPlayer) cfg.graphics else null)

        startBanners(cfg)
        startCornerAds(cfg)
        showMessages(cfg)
    }

    // ------------------------------------------------------------ Banner

    private fun startBanners(cfg: PanelConfig) {
        val banners: List<Banner> = if (inPlayer && listOpen) cfg.banners else emptyList()
        if (banners == bannerKey && bannerJob?.isActive == true) return
        bannerKey = banners
        bannerJob?.cancel()
        v.bannerBox.visibility = View.GONE
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

    private fun showDialog(title: String, message: String, imageUrl: String?, onClose: () -> Unit) {
        dialogShowing = true
        val builder = AlertDialog.Builder(activity, R.style.Theme_Galaxy_Dialog)
            .setTitle(title)
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
