package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Account
import com.galaxytvstick.app.data.Category
import com.galaxytvstick.app.data.PanelApi
import com.galaxytvstick.app.data.PanelConfig
import com.galaxytvstick.app.data.PanelPlaylist
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivityLoginBinding
import com.galaxytvstick.app.util.DeviceCaps
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ekran aktivasyon: montre MAC Address ak Device Key aparèy la,
 * epi tann admin nan ajoute yon playlist pou MAC sa a nan panel la.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        /** true = pa konekte otomatikman (egz: kliyan an fèk dekonekte). */
        const val EXTRA_NO_AUTO = "no_auto"
        private const val POLL_MS = 5_000L
    }

    private lateinit var b: ActivityLoginBinding
    private lateinit var prefs: Prefs
    private lateinit var panel: PanelApi

    private var pollJob: Job? = null
    private var registered = false
    private var connecting = false
    private var noAuto = false
    private val failedIds = mutableSetOf<String>()
    private var playlists: List<PanelPlaylist> = emptyList()

    private val playlistAdapter = CategoryAdapter { cat ->
        playlists.firstOrNull { it.id == cat.id }?.let { connect(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        panel = PanelApi(prefs)
        noAuto = intent.getBooleanExtra(EXTRA_NO_AUTO, false)

        // Si yon playlist deja konekte, ale dirèk nan chanèl yo
        if (prefs.account != null && !noAuto) {
            openMain()
            return
        }

        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.macText.text = prefs.mac
        b.keyText.text = prefs.deviceKey
        b.deviceInfo.text = getString(R.string.device_short_fmt, prefs.mac, DeviceCaps.maxSupported)

        b.playlists.layoutManager = LinearLayoutManager(this)
        b.playlists.adapter = playlistAdapter

        if (BuildConfig.ALLOW_MANUAL_LOGIN) {
            b.manualBox.visibility = View.VISIBLE
            b.btnLogin.setOnClickListener { manualLogin() }
            b.password.setOnEditorActionListener { _, _, _ -> manualLogin(); true }
        }
        b.btnRefresh.setOnClickListener {
            noAuto = false
            failedIds.clear()
            startPolling()
        }
        b.btnRefresh.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (::b.isInitialized) startPolling()
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
    }

    // ------------------------------------------------------------ Panel

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (!registered) {
                    registered = runCatching { panel.register(null) }.isSuccess
                    // Panel la ap montre aparèy sa a "sou ekran aktivasyon an"
                    if (registered) runCatching { panel.status(org.json.JSONObject().put("screen", "activation")) }
                }
                val cfg = runCatching { panel.config() }.getOrNull()
                if (cfg == null) {
                    setStatus(getString(R.string.panel_offline), loading = true)
                } else {
                    PanelState.config = cfg
                    onConfig(cfg)
                }
                delay(POLL_MS)
            }
        }
    }

    private fun onConfig(cfg: PanelConfig) {
        if (connecting) return
        if (!cfg.known) {
            // Sèvè a poko konnen aparèy la (oswa li pèdi done li yo): re-anrejistre l
            registered = false
            showPlaylists(emptyList())
            setStatus(getString(R.string.waiting_playlist), loading = true)
            return
        }
        when (cfg.status) {
            "blocked" -> { setStatus(getString(R.string.device_blocked), loading = false); showPlaylists(emptyList()); return }
            "expired" -> { setStatus(getString(R.string.device_expired), loading = false); showPlaylists(emptyList()); return }
            "maintenance" -> {
                setStatus(cfg.statusMessage.ifBlank { getString(R.string.maintenance_title) }, loading = false)
                showPlaylists(emptyList())
                return
            }
        }

        showPlaylists(cfg.playlists)
        if (cfg.playlists.isEmpty()) {
            setStatus(getString(R.string.waiting_playlist), loading = true)
            return
        }

        // Yon sèl playlist: konekte otomatikman
        val auto = cfg.playlists.singleOrNull()?.takeIf { it.id !in failedIds }
        if (auto != null && !noAuto) {
            connect(auto)
        } else {
            setStatus(getString(R.string.choose_playlist), loading = false)
        }
    }

    private fun showPlaylists(list: List<PanelPlaylist>) {
        val changed = list.map { it.id to it.name } != playlists.map { it.id to it.name }
        playlists = list
        b.playlistBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (changed) {
            playlistAdapter.items = list.map { Category(it.id, "▶  ${it.name.ifBlank { it.username }}") }
            if (list.isNotEmpty()) b.playlists.post { b.playlists.getChildAt(0)?.requestFocus() }
        }
    }

    // ------------------------------------------------------------ Koneksyon

    private fun connect(pl: PanelPlaylist) {
        if (connecting) return
        val label = pl.name.ifBlank { pl.username }
        val serverRaw = pl.server.ifBlank { BuildConfig.DEFAULT_SERVER }
        if (serverRaw.isBlank()) {
            failedIds += pl.id
            showError(getString(R.string.error_no_server, label))
            return
        }
        val account = Account(XtreamApi.normalizeServer(serverRaw), pl.username.trim(), pl.password.trim(), label, pl.id)
        login(account, label) { failedIds += pl.id }
    }

    private fun manualLogin() {
        val server = b.server.text.toString()
        val user = b.username.text.toString().trim()
        val pass = b.password.text.toString().trim()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) {
            showError(getString(R.string.error_fill_all))
            return
        }
        login(Account(XtreamApi.normalizeServer(server), user, pass), user) {}
    }

    private fun login(account: Account, label: String, onFail: () -> Unit) {
        connecting = true
        b.error.visibility = View.GONE
        setStatus(getString(R.string.connecting_playlist, label), loading = true)
        lifecycleScope.launch {
            val result = runCatching { XtreamApi(account).login() }
            connecting = false
            result.onSuccess { ok ->
                if (ok) {
                    prefs.account = account
                    runCatching { panel.register(account.username) }
                    openMain()
                } else {
                    onFail()
                    showError(getString(R.string.error_playlist_bad, label))
                    setStatus(getString(R.string.choose_playlist), loading = false)
                }
            }.onFailure {
                onFail()
                showError(getString(R.string.error_network, it.message ?: ""))
                setStatus(getString(R.string.choose_playlist), loading = false)
            }
        }
    }

    private fun setStatus(text: String, loading: Boolean) {
        b.statusText.text = text
        b.progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
    }

    private fun showError(msg: String) {
        b.error.text = msg
        b.error.visibility = View.VISIBLE
    }

    private fun openMain() {
        pollJob?.cancel()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
