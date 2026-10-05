package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.PanelConfig
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.RemoteCommand
import com.galaxytvstick.app.data.WatchHistory
import com.galaxytvstick.app.data.WatchProgress
import com.galaxytvstick.app.databinding.ActivityHomeBinding
import com.galaxytvstick.app.databinding.ItemHomeCatBinding
import com.galaxytvstick.app.databinding.ItemHomeContinueBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekran akèy la: meni agoch, "Kontinye gade", 5 kat (Live TV, Fim, Seri, Catch Up, Favori),
 * kategori Live TV ak kantite chanèl yo, epi kont kliyan an (non, plan, ekspirasyon) ki soti nan panel la.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private lateinit var history: WatchHistory
    private lateinit var overlay: OverlayController
    private var ready = false

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    private val contAdapter = ContAdapter()
    private val catAdapter = CatAdapter()

    /** Yon kategori sou ekran akèy la: id (null = tout chanèl), non, kantite chanèl. */
    private class HomeCat(val id: String?, val name: String, val count: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.account == null) { go(LoginActivity::class.java, clear = true); return }
        // Sistèm nan te fèmen app la an aryè plan: lis chanèl la pa la ankò, rechaje l
        if (ChannelStore.all.isEmpty()) { go(MainActivity::class.java, clear = true); return }
        history = WatchHistory(this)

        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        ready = true

        b.menuHome.isSelected = true
        b.menuHome.setOnClickListener { b.tileLive.requestFocus() }
        b.menuLive.setOnClickListener { startActivity(Intent(this, EpgActivity::class.java)) }
        b.menuMovies.setOnClickListener { openVod("movie") }
        b.menuSeries.setOnClickListener { openVod("series") }
        b.menuFav.setOnClickListener { openFavorites() }
        b.menuCatchup.setOnClickListener { startActivity(Intent(this, EpgActivity::class.java)) }
        b.menuSettings.setOnClickListener { showSettings() }

        b.tileLive.setOnClickListener { openLive() }
        b.tileMovies.setOnClickListener { openVod("movie") }
        b.tileSeries.setOnClickListener { openVod("series") }
        b.tileCatchup.setOnClickListener { startActivity(Intent(this, EpgActivity::class.java)) }
        b.tileFav.setOnClickListener { openFavorites() }
        // Kat remòt la chwazi a vin yon ti jan pi gwo
        for (v in listOf(b.tileLive, b.tileMovies, b.tileSeries, b.tileCatchup, b.tileFav)) {
            v.setOnFocusChangeListener { x, has -> x.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start() }
        }

        b.contList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.contList.adapter = contAdapter
        b.contList.itemAnimator = null
        b.catList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.catList.adapter = catAdapter
        b.catList.itemAnimator = null

        showAccount(PanelState.config)
        refreshCategories()
        b.tileLive.requestFocus()

        overlay = OverlayController(
            this, b.overlay, lifecycleScope, inPlayer = false,
            onConfig = { cfg ->
                b.status.text = getString(R.string.home_connected)
                ChannelStore.applyPanel(cfg) // chanèl admin nan kache / chanje non
                showAccount(cfg)
                refreshCategories()
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

    override fun onStart() {
        super.onStart()
        if (!ready) return
        overlay.start()
        refreshContinue() // li ka chanje apre kliyan an fin gade yon fim
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

    // ------------------------------------------------------------ Kontni

    /** Kont kliyan an (soti nan panel la). San kliyan lye: non playlist la sèlman. */
    private fun showAccount(cfg: PanelConfig) {
        b.accName.text = cfg.accountName.ifBlank { prefs.account?.name ?: prefs.account?.username ?: "" }
        b.accPlan.text = if (cfg.accountPlan.isBlank()) "" else getString(R.string.home_plan_fmt, cfg.accountPlan)
        b.accPlan.visibility = if (cfg.accountPlan.isBlank()) View.GONE else View.VISIBLE
        val exp = cfg.accountExpiry.replace("T", " ")
        b.accExp.text = if (exp.isBlank()) "" else getString(R.string.home_exp_fmt, exp)
        b.accExp.visibility = if (exp.isBlank()) View.GONE else View.VISIBLE
    }

    private fun refreshContinue() {
        val list = history.continueWatching().take(12)
        contAdapter.items = list
        contAdapter.notifyDataSetChanged()
        b.contList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        b.contEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshCategories() {
        val all = ChannelStore.all
        val counts = HashMap<String, Int>()
        for (c in all) counts[c.categoryId] = (counts[c.categoryId] ?: 0) + 1
        val list = ArrayList<HomeCat>()
        list.add(HomeCat(null, getString(R.string.home_all_channels), all.size))
        for (c in ChannelStore.categories) list.add(HomeCat(c.id, c.name, counts[c.id] ?: 0))
        catAdapter.items = list
        catAdapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------ Aksyon

    /** Dènye chanèl yo t ap gade a, plen ekran. */
    private fun openLive(streamId: Int? = null) {
        val all = ChannelStore.all
        if (all.isEmpty()) { Toast.makeText(this, R.string.empty_list, Toast.LENGTH_LONG).show(); return }
        val last = prefs.lastChannelId
        val startId = streamId ?: all.firstOrNull { it.streamId == last }?.streamId ?: all.first().streamId
        ChannelStore.current = all
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, startId)
                .putExtra(PlayerActivity.EXTRA_OPEN_LIST, streamId == null && startId != last)
        )
    }

    /** Louvri yon lis chanèl (yon kategori oswa favori yo) ak lis la sou ekran an. */
    private fun openList(list: List<Channel>) {
        if (list.isEmpty()) { Toast.makeText(this, R.string.empty_list, Toast.LENGTH_LONG).show(); return }
        ChannelStore.current = list
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_INDEX, 0)
                .putExtra(PlayerActivity.EXTRA_OPEN_LIST, true)
        )
    }

    private fun openFavorites() {
        val fav = prefs.favorites
        val list = ChannelStore.all.filter { it.streamId in fav }
        if (list.isEmpty()) Toast.makeText(this, R.string.home_no_fav, Toast.LENGTH_LONG).show() else openList(list)
    }

    private fun openVod(kind: String) {
        startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, kind))
    }

    /** Reprann yon fim / epizòd kote kliyan an te rete a. */
    private fun resume(p: WatchProgress) {
        startActivity(
            Intent(this, VodPlayerActivity::class.java)
                .putExtra(VodPlayerActivity.EXTRA_KIND, "movie")
                .putExtra(VodPlayerActivity.EXTRA_URL, p.url)
                .putExtra(VodPlayerActivity.EXTRA_KEY, p.key)
                .putExtra(VodPlayerActivity.EXTRA_TITLE, p.title)
                .putExtra(VodPlayerActivity.EXTRA_POSTER, p.poster)
                .putExtra(VodPlayerActivity.EXTRA_PARENT_ID, p.parentId)
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

    // ------------------------------------------------------------ Lis yo

    private fun leftText(p: WatchProgress): String {
        val min = ((p.durationMs - p.positionMs).coerceAtLeast(0) / 60_000).toInt()
        val t = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
        return getString(R.string.home_left_fmt, t)
    }

    private inner class ContAdapter : RecyclerView.Adapter<ContAdapter.VH>() {
        var items: List<WatchProgress> = emptyList()

        inner class VH(val b: ItemHomeContinueBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHomeContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = items[position]
            h.b.title.text = p.title
            h.b.left.text = leftText(p)
            h.b.progress.progress = p.percent
            h.b.poster.load(p.poster) { error(R.drawable.ic_tv) }
            h.b.root.setOnClickListener { resume(p) }
        }
    }

    private inner class CatAdapter : RecyclerView.Adapter<CatAdapter.VH>() {
        var items: List<HomeCat> = emptyList()

        inner class VH(val b: ItemHomeCatBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHomeCatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            h.b.name.text = c.name
            h.b.count.text = c.count.toString()
            h.b.root.setOnClickListener {
                openList(if (c.id == null) ChannelStore.all else ChannelStore.all.filter { it.categoryId == c.id })
            }
        }
    }
}
