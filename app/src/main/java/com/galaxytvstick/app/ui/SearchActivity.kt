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
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) b.root.post { startVoice() }

        // Fim ak seri yo chaje dèyè; chanèl yo disponib touswit
        lifecycleScope.launch {
            // Kontwòl paran: pa montre sa ki nan kategori pou granmoun
            val hide = ChannelStore.hideAdult
            for (k in VodKind.values()) {
                val d = VodStore.cached(this@SearchActivity, account, k)
                    ?: runCatching { VodStore.fetch(this@SearchActivity, account, k) }.getOrNull() ?: continue
                val bad = if (hide) d.categories.filter { ChannelStore.isAdult(it.name) }.map { it.id }.toHashSet() else HashSet()
                val l = d.items.filter { it.categoryId !in bad }
                if (k == VodKind.MOVIE) movies = l else series = l
                refresh()
            }
        }
    }

    private fun buildKeys() {
        val d = resources.displayMetrics.density
        val rows = listOf(listOf("VOICE")) + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6).map { r -> r.map { it.toString() } } + listOf(listOf("SPACE", "DEL", "CLEAR"))
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
                    text = if (k == "VOICE") getString(R.string.search_voice_key) else k
                    textSize = if (k == "VOICE") 16f else if (k.length > 1) 13f else 18f
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
        first?.requestFocus()
    }

    private fun press(k: String) {
        if (k == "VOICE") { startVoice(); return }
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
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_voice_prompt))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        val ok = i.resolveActivity(packageManager) != null && runCatching { voiceLauncher.launch(i) }.isSuccess
        if (!ok) dictate()
    }

    /** Pa gen rekonesans vwa (Fire TV): louvri klavye sistèm nan; kliyan an kenbe 🎤 sou remòt la pou l dikte. */
    private fun dictate() {
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
            setOnEditorActionListener { v, _, _ -> hideIme(v); b.results.requestFocus(); true }
            (b.root as ViewGroup).addView(this)
            dictation = this
        }
        e.setText(query)
        e.requestFocus()
        e.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }
        Toast.makeText(this, getString(R.string.search_voice_hold), Toast.LENGTH_LONG).show()
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
        return super.onKeyDown(keyCode, event)
    }

    private fun norm(t: String) = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Pwen: konbyen mo kesyon an ki nan non an (tout mo yo = pi bon). 0 = pa koresponn. */
    private fun score(name: String, words: List<String>, whole: String): Int {
        val n = norm(name)
        if (n.contains(whole)) return 100
        val hit = words.count { n.contains(it) }
        return if (hit == words.size) 50 + hit else if (words.size >= 3 && hit >= words.size - 1) hit else 0
    }

    private fun refresh() {
        val whole = norm(query.trim())
        val words = whole.split(Regex("[^\\p{L}\\p{N}+]+")).filter { it.length >= 2 }
        val out = ArrayList<Res>()
        if (whole.isNotEmpty() && (words.isNotEmpty() || whole.length == 1)) {
            val w = words.ifEmpty { listOf(whole) }
            fun <T> best(src: Sequence<T>, name: (T) -> String, max: Int) =
                src.map { it to score(name(it), w, whole) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
            best(ChannelStore.all.asSequence(), { it.name }, 40).forEach { out.add(Res("TV   ·   ${it.name}", it, null)) }
            best(movies.asSequence(), { it.name }, 30).forEach { out.add(Res("${getString(R.string.home_movies)}   ·   ${it.name}", null, it)) }
            best(series.asSequence(), { it.name }, 30).forEach { out.add(Res("${getString(R.string.home_series)}   ·   ${it.name}", null, it)) }
        }
        adapter.items = out
        adapter.notifyDataSetChanged()
        b.empty.visibility = if (whole.isNotEmpty() && out.isEmpty()) View.VISIBLE else View.GONE
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
