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
import kotlinx.coroutines.launch

/**
 * Ekran chajman (style TiviMate): li chaje playlist la epi li ouvri TV a plen ekran
 * sou dènye chanèl yo t ap gade a. Pa gen ekran lis apa, tout bagay fèt nan player a.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val account = prefs.account ?: run { toActivation(); return }

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
            // Chèche règ panel la an premye (chanèl kache, limit…)
            runCatching { PanelApi(prefs).config() }.getOrNull()?.let { PanelState.config = it }

            val result = runCatching { api.liveCategories() to api.liveStreams(null) }
            result.onSuccess { (cats, chans) ->
                ChannelStore.rawCategories = cats
                ChannelStore.rawChannels = chans
                ChannelStore.applyPanel(PanelState.config)
                // Voye lis chanèl yo bay panel la pou admin nan ka hide/show yo
                launch { runCatching { PanelApi(prefs).uploadChannels(chans, cats) } }
                // Zòn lè sèvè a pou catch-up (maks 4 s pou pa fè TV a tann)
                if (chans.any { it.tvArchive }) kotlinx.coroutines.withTimeoutOrNull(4_000) { api.serverTimezone() }?.let { prefs.serverTimezone = it }
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
        val last = prefs.lastChannelId
        val startId = all.firstOrNull { it.streamId == last }?.streamId ?: all.firstOrNull()?.streamId
        ChannelStore.current = all
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_STREAM_ID, startId ?: -1)
                // Premye fwa (pa gen dènye chanèl): louvri lis chanèl la dirèk
                .putExtra(PlayerActivity.EXTRA_OPEN_LIST, last < 0 || startId != last)
        )
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
