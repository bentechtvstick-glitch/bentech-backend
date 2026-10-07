package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityMainBinding
import com.galaxytvstick.app.GalaxyApp
import com.galaxytvstick.app.data.Account
import com.galaxytvstick.app.data.ChannelCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ekran chajman (style TiviMate): li chaje playlist la epi li ouvri TV a plen ekran
 * sou dènye chanèl yo t ap gade a. Pa gen ekran lis apa, tout bagay fèt nan player a.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var useCache = false

    companion object { const val EXTRA_USE_CACHE = "use_cache" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val account = prefs.account ?: run { toActivation(); return }

        useCache = intent.getBooleanExtra(EXTRA_USE_CACHE, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.playlistName.text = account.name ?: account.username
        b.btnRetry.setOnClickListener { load() }
        b.btnPlaylists.setOnClickListener { toActivation() }
        load()
    }

    private fun load() {
        val account = prefs.account ?: return
        val api = XtreamApi(account)
        b.progress.visibility = View.VISIBLE
        b.status.text = getString(R.string.loading_channels)
        b.errorBox.visibility = View.GONE

        lifecycleScope.launch {
            // Règ panel la (chanèl kache, limit…) ak playlist la chaje an menm tan, pa youn apre lòt
            val cfgJob = async { runCatching { PanelApi(prefs).config() }.getOrNull() }

            // 1) Ouvèti rapid: lis chanèl ki sove sou aparèy la
            val cached = if (useCache) withContext(Dispatchers.IO) { ChannelCache.read(applicationContext, account) } else null
            useCache = false // "Eseye ankò" toujou rechaje sou sèvè a
            if (cached != null) {
                withTimeoutOrNull(2_500) { cfgJob.await() }?.let { PanelState.config = it }
                ChannelStore.rawCategories = cached.categories
                ChannelStore.rawChannels = cached.channels
                ChannelStore.applyPanel(PanelState.config)
                if (cached.ageMs > ChannelCache.REFRESH_AFTER_MS) refreshInBackground(account)
                openTv()
                return@launch
            }

            // 2) Chajman nòmal (telechaje nan fichye, li objè pa objè: pa plen memwa a)
            val result = runCatching {
                val e = withContext(Dispatchers.IO) { ChannelCache.fetch(applicationContext, account) }
                e.categories to e.channels
            }
            result.onSuccess { (cats, chans) ->
                cfgJob.await()?.let { PanelState.config = it }
                ChannelStore.rawCategories = cats
                ChannelStore.rawChannels = chans
                ChannelStore.applyPanel(PanelState.config)
                // Voye lis chanèl yo bay panel la pou admin nan ka hide/show yo
                GalaxyApp.scope.launch { runCatching { PanelApi(prefs).uploadChannels(chans, cats) } }
                // Zòn lè sèvè a pou catch-up: an aryè plan, pou pa fè TV a tann
                if (chans.any { it.tvArchive }) GalaxyApp.scope.launch { api.serverTimezone()?.let { prefs.serverTimezone = it } }
                openTv()
            }.onFailure {
                b.progress.visibility = View.GONE
                b.status.text = ""
                b.errorText.text = getString(R.string.error_network, it.message ?: "")
                b.errorBox.visibility = View.VISIBLE
                b.btnRetry.requestFocus()
            }
        }
    }

    /** Mete lis ki sove a ajou an silans; nouvo lis la parèt pwochen fwa app la ouvri. */
    private fun refreshInBackground(account: Account) {
        val ctx = applicationContext
        val p = prefs
        GalaxyApp.scope.launch {
            // Kite TV a kòmanse jwe anvan (pa fè de gwo travay an menm tan sou yon ti aparèy)
            kotlinx.coroutines.delay(90_000)
            runCatching {
                val e = ChannelCache.fetch(ctx, account)
                if (e.channels.isNotEmpty()) PanelApi(p).uploadChannels(e.channels, e.categories)
            }
        }
    }

    private fun openTv() {
        val all = ChannelStore.all
        if (all.isEmpty()) {
            b.progress.visibility = View.GONE
            b.status.text = ""
            b.errorText.text = getString(R.string.empty_list)
            b.errorBox.visibility = View.VISIBLE
            b.btnRetry.requestFocus()
            return
        }
        ChannelStore.current = all
        // Ekran akèy la (meni, Kontinye gade, Live TV, fim, seri, kategori)
        startActivity(Intent(this, EpgActivity::class.java))
        finish()
    }

    private fun toActivation() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_NO_AUTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
