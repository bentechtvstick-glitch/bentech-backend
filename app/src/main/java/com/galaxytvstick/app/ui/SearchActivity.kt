package com.galaxytvstick.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import java.text.Normalizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.Channel
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.VodStore
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.VodItem
import com.galaxytvstick.app.data.VodKind
import com.galaxytvstick.app.data.XtreamApi
import com.galaxytvstick.app.databinding.ActivitySearchBinding
import com.galaxytvstick.app.databinding.ItemCategoryBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Chèche: klavye sou ekran an agoch, rezilta (chanèl, fim, seri) adwat pandan kliyan an ap tape.
 * Rechèch vwa (gratis): Android TV/Google TV → rekonesans vwa sistèm nan; Fire TV → klavye sistèm nan,
 * kote kliyan an kenbe bouton 🎤 remòt la pou l dikte. Rezilta yo chèche mo pa mo, san aksan.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var b: ActivitySearchBinding
    private var query = ""
    private var movies: List<VodItem> = emptyList()
    private var series: List<VodItem> = emptyList()
    private val adapter = ResAdapter()

    private class Res(val label: String, val channel: Channel?, val vod: VodItem?)

    private val voiceLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) setQuery(text)
    }
    /** Fire TV: yon ti chan tèks envizib; klavye sistèm nan (ak dikte vwa a) ekri ladan l. */
    private var dictation: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = Prefs(this).account ?: run { finish(); return }
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.results.layoutManager = TvLinearLayoutManager(this)
        b.results.adapter = adapter
        b.results.itemAnimator = null
        buildKeys()
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) b.root.post { startVoice(auto = true) }

        // Endèks chanèl yo an aryè plan (rapid), pou rechèch la pa janm bloke ekran an
        lifecycleScope.launch {
            chIdx = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ChannelStore.all.map { Idx(it, norm(it.name)) } }
            refresh()
        }
        // Fim ak seri yo chaje dèyè; chanèl yo disponib touswit
        lifecycleScope.launch {
            // Kontwòl paran: pa montre sa ki nan kategori pou granmoun
            val cfg = PanelState.config
            val hide = ChannelStore.hideAdult || cfg.forceHideAdult
            for (k in VodKind.values()) {
                val d = VodStore.cached(this@SearchActivity, account, k)
                    ?: runCatching { VodStore.fetch(this@SearchActivity, account, k) }.getOrNull() ?: continue
                val blocked = if (k == VodKind.MOVIE) cfg.blockedMovies else cfg.blockedSeries
                val bad = d.categories.filter { (hide && ChannelStore.isAdult(it.name)) || it.name.trim().uppercase() in blocked }.map { it.id }.toHashSet()
                val l = d.items.filter { it.categoryId !in bad }
                if (k == VodKind.MOVIE) movies = l else series = l
                val idx = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { l.map { Idx(it, norm(it.name)) } }
                if (k == VodKind.MOVIE) mvIdx = idx else srIdx = idx
                refresh()
            }
        }
    }

    private fun buildKeys() {
        val d = resources.displayMetrics.density
        val rows = listOf(listOf("VOICE", "KBD")) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6).map { r -> r.map { it.toString() } } + listOf(listOf("SPACE", "DEL", "CLEAR"))
        var first: View? = null
        for (r in rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).toInt()).apply { bottomMargin = (5 * d).toInt() }
            }
            for (k in r) {
                val v = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = (5 * d).toInt() }
                    gravity = Gravity.CENTER
                    text = when (k) { "VOICE" -> getString(R.string.search_voice_key); "KBD" -> getString(R.string.search_kbd_key); else -> k }
                    textSize = if (k == "VOICE" || k == "KBD") 16f else if (k.length > 1) 13f else 18f
                    setTextColor(ContextCompat.getColorStateList(this@SearchActivity, R.color.epg_cell_text))
                    setBackgroundResource(R.drawable.bg_epg_cell)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { press(k) }
                }
                if (first == null) first = v
                line.addView(v)
            }
            b.keys.addView(line)
        }
        firstKey = first
        first?.requestFocus()
    }

    private fun press(k: String) {
        if (k == "VOICE") { startVoice(); return }
        if (k == "KBD") { dictate(showTip = false); return }
        query = when (k) {
            "DEL" -> query.dropLast(1)
            "CLEAR" -> ""
            "SPACE" -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
            else -> if (query.length < 24) query + k else query
        }
        b.query.text = query
        refresh()
    }

    // ---- Vwa ----
    private var firstKey: View? = null
    /** Remèt fokis la sou klavye ekran an (pou remòt la toujou ka tape). */
    private fun focusKeys() { dictation?.let { hideIme(it); it.clearFocus() }; firstKey?.requestFocus() }

    private fun startVoice(auto: Boolean = false) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice_prompt))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val ok = i.resolveActivity(packageManager) != null && runCatching { voiceLauncher.launch(i) }.isSuccess
        // Fire TV (pa gen rekonesans vwa): pa kache fokis la nan yon chan envizib lè ekran an fèk louvri;
        // kliyan an rete sou klavye a, epi l ka kenbe 🎤 sou remòt la oswa peze «⌨️ Keyboard».
        if (!ok) { if (auto) { firstKey?.requestFocus(); Toast.makeText(this, getString(R.string.search_voice_hold), Toast.LENGTH_LONG).show() } else dictate() }
    }

    /** Pa gen rekonesans vwa (Fire TV): louvri klavye sistèm nan; kliyan an kenbe 🎤 sou remòt la pou l dikte. */
    private fun dictate(showTip: Boolean = true) {
        val e = dictation ?: EditText(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            alpha = 0f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { if (hasFocus()) setQuery(s?.toString().orEmpty()) }
            })
            setOnEditorActionListener { v, _, _ -> hideIme(v); if (adapter.itemCount > 0) b.results.requestFocus() else focusKeys(); true }
            // Flèch remòt la: soti nan chan envizib la, retounen sou klavye ekran an
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK)) { focusKeys(); true } else false
            }
            (b.root as ViewGroup).addView(this)
            dictation = this
        }
        e.setText(query)
        e.requestFocus()
        e.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }
        if (showTip) Toast.makeText(this, getString(R.string.search_voice_hold), Toast.LENGTH_LONG).show()
    }

    private fun hideIme(v: View) { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun setQuery(text: String) {
        query = text.trim().take(60)
        b.query.text = query
        refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Bouton rechèch / vwa remòt la (Android TV): kòmanse rechèch vwa a
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) { startVoice(); return true }
        // Klavye fizik, app remòt telefòn nan, oswa bouton chif remòt la: tape dirèkteman (pa sèlman ak klavye sou ekran an)
        if (dictation?.hasFocus() != true) {
            when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { press(('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()); return true }
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> { press(('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()); return true }
                KeyEvent.KEYCODE_SPACE -> { press("SPACE"); return true }
                KeyEvent.KEYCODE_DEL -> { press("DEL"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val rxMarks = Regex("\\p{Mn}+")
    private fun norm(t: String) = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(rxMarks, "")

    /**
     * Endèks rechèch: non yo normalize YON SÈL FWA, an aryè plan (anvan: chak lèt ou tape te refè sa pou
     * dè dizèn milye chanèl/fim sou fil prensipal la → ekran an te bloke epi app la te kole).
     */
    private class Idx<T>(val item: T, val n: String)
    @Volatile private var chIdx: List<Idx<Channel>>? = null
    @Volatile private var mvIdx: List<Idx<VodItem>> = emptyList()
    @Volatile private var srIdx: List<Idx<VodItem>> = emptyList()
    private var searchJob: kotlinx.coroutines.Job? = null

    /** Pwen: konbyen mo kesyon an ki nan non an (tout mo yo = pi bon). 0 = pa koresponn. */
    private fun score(n: String, words: List<String>, whole: String): Int {
        if (n.contains(whole)) return 100
        val hit = words.count { n.contains(it) }
        return if (hit == words.size) 50 + hit else if (words.size >= 3 && hit >= words.size - 1) hit else 0
    }

    private fun refresh() {
        val whole = norm(query.trim())
        val words = whole.split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.length >= 2 }
        val moviesLabel = getString(R.string.home_movies); val seriesLabel = getString(R.string.home_series)
        searchJob?.cancel()
        if (whole.isEmpty()) { adapter.items = emptyList(); adapter.notifyDataSetChanged(); b.empty.visibility = View.GONE; return }
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(150) // tann ti moman: si kliyan an tape yon lòt lèt, pa kalkile pou granmesi
            val ch = chIdx ?: emptyList(); val mv = mvIdx; val sr = srIdx
            val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val res = ArrayList<Res>()
                val scope = this
                if (words.isNotEmpty() || whole.length == 1) {
                    val w = words.ifEmpty { listOf(whole) }
                    fun <T> best(src: List<Idx<T>>, max: Int): List<T> {
                        val hits = ArrayList<Pair<T, Int>>()
                        for (x in src) { if (!scope.isActive) break; val sc = score(x.n, w, whole); if (sc > 0) hits.add(x.item to sc) }
                        return hits.sortedByDescending { it.second }.take(max).map { it.first }
                    }
                    best(ch, 40).forEach { res.add(Res("TV   ·   ${it.name}", it, null)) }
                    best(mv, 30).forEach { res.add(Res("$moviesLabel   ·   ${it.name}", null, it)) }
                    best(sr, 30).forEach { res.add(Res("$seriesLabel   ·   ${it.name}", null, it)) }
                }
                res
            }
            adapter.items = out
            adapter.notifyDataSetChanged()
            b.empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(r: Res) {
        val ch = r.channel
        if (ch != null) {
            ChannelStore.current = ChannelStore.all
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_STREAM_ID, ch.streamId))
            finish()
            return
        }
        r.vod?.let { startActivity(VodDetailActivity.intent(this, it)) }
    }

    private inner class ResAdapter : RecyclerView.Adapter<ResAdapter.VH>() {
        var items: List<Res> = emptyList()

        inner class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            h.b.name.text = r.label
            h.b.root.setOnClickListener { open(r) }
            Marquee.bind(h.b.root, h.b.name)
        }
    }

    companion object {
        /** true = louvri ekran rechèch la epi kòmanse vwa a touswit (bouton rechèch remòt la). */
        const val EXTRA_VOICE = "voice"
    }
}
