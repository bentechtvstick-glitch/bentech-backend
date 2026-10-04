package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.databinding.ActivityHomeBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran akèy la: gwo kat "TV an dirèk" (dènye chanèl la plen ekran), gid chanèl la, fim, seri, timoun ak reglaj.
 * BACK nan player a tounen isit la.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private var ready = false

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.account == null) { go(LoginActivity::class.java, clear = true); return }
        // Sistèm nan te fèmen app la an aryè plan: lis chanèl la pa la ankò, rechaje l
        if (ChannelStore.all.isEmpty()) { go(MainActivity::class.java, clear = true); return }

        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        ready = true

        b.hero.setOnClickListener { openLive() }
        b.tileGuide.setOnClickListener { startActivity(Intent(this, EpgActivity::class.java)) }
        b.tileMovies.setOnClickListener { openVod("movie") }
        b.tileSeries.setOnClickListener { openVod("series") }
        b.tileKids.setOnClickListener { openKids() }
        b.tileSettings.setOnClickListener { showSettings() }
        // Sa remòt la chwazi a vin yon ti jan pi gwo
        for (v in listOf(b.hero, b.tileGuide, b.tileMovies, b.tileSeries, b.tileKids, b.tileSettings)) {
            v.setOnFocusChangeListener { x, has ->
                val s = if (has) (if (x === b.hero) 1.02f else 1.05f) else 1f
                x.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
        updateCount()
        b.hero.requestFocus()

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = false,
            onConfig = { cfg ->
                b.status.text = getString(R.string.home_connected)
                ChannelStore.applyPanel(cfg) // chanèl admin nan kache / chanje non
                updateCount()
            },
            onCommand = { cmd -> onRemoteCommand(cmd) }
        )

        lifecycleScope.launch {
            while (isActive) {
                val now = Date()
                b.clock.text = timeFmt.format(now)
                b.date.text = dateFmt.format(now)
                delay(20_000)
            }
        }
    }

    private fun updateCount() {
        b.heroSub.text = getString(R.string.home_channels_ready, ChannelStore.all.size)
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        overlay.start()
        val body = org.json.JSONObject().put("screen", "home").put("playlistName", prefs.account?.name ?: "")
        lifecycleScope.launch { runCatching { PanelApi(prefs).status(body) } }
    }

    override fun onStop() {
        super.onStop()
        if (ready) overlay.stop()
    }

    private fun go(cls: Class<*>, clear: Boolean = false) {
        val i = Intent(this, cls)
        if (clear) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        if (clear) finish()
    }

    // ------------------------------------------------------------ Aksyon

    /** Gwo kat la: dènye chanèl yo t ap gade a, plen ekran. */
    private fun openLive(streamId: Int? = null) {
        val all = ChannelStore.all
        if (all.isEmpty()) { Toast.makeText(this, R.string.empty_list, Toast.LENGTH_LONG).show(); return }
        val last = prefs.lastChannelId
        val startId = streamId ?: all.firstOrNull { it.streamId == last }?.streamId ?: all.first().streamId
        ChannelStore.current = all
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, startId)
                // Premye fwa (pa gen dènye chanèl): louvri lis chanèl la dirèk
                .putExtra(PlayerActivity.EXTRA_OPEN_LIST, streamId == null && startId != last)
        )
    }

    private fun openVod(kind: String) {
        startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, kind))
    }

    /** Chanèl timoun yo: kategori playlist la ki gen non timoun (kids, enfants, niños, cartoon…). */
    private fun openKids() {
        val rx = Regex("kid|enfant|niñ|nino|timoun|cartoon|junior|bébé|bebe|toon|disney|nick", RegexOption.IGNORE_CASE)
        val cats = ChannelStore.categories.filter { rx.containsMatchIn(it.name) }.map { it.id }.toHashSet()
        val list = ChannelStore.all.filter { it.categoryId in cats }
        if (list.isEmpty()) { Toast.makeText(this, R.string.home_no_kids, Toast.LENGTH_LONG).show(); return }
        ChannelStore.current = list
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, 0)
                .putExtra(PlayerActivity.EXTRA_OPEN_LIST, true)
        )
    }

    private fun showSettings() {
        val items = arrayOf(getString(R.string.set_reload), getString(R.string.set_change_playlist), getString(R.string.set_device_info))
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.home_settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> go(MainActivity::class.java, clear = true)
                    1 -> {
                        startActivity(
                            Intent(this, LoginActivity::class.java)
                                .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                    else -> AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
                        .setTitle(R.string.set_device_info)
                        .setMessage(getString(R.string.set_info_fmt, prefs.account?.name ?: prefs.account?.username ?: "", prefs.mac, prefs.deviceKey, BuildConfig.VERSION_NAME))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .show()
    }

    /** Kòmand sèvis kliyan an pandan kliyan an sou ekran akèy la. */
    private fun onRemoteCommand(cmd: RemoteCommand) {
        when (cmd.type) {
            "play" -> ChannelStore.all.firstOrNull { it.streamId == cmd.channelId }?.let { openLive(it.streamId) }
            "reload" -> go(MainActivity::class.java, clear = true)
            "restart" -> go(LoginActivity::class.java, clear = true)
            "logout" -> {
                prefs.account = null
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Aparèy bloke / ekspire: ekran blokaj la kouvri tout bagay, remòt la pa louvri anyen
        if (ready && b.overlay.blocker.visibility == View.VISIBLE && event.keyCode != KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        AlertDialog.Builder(this, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> finishAffinity() }
            .setNegativeButton(R.string.no, null)
            .show()
    }
}
