package com.galaxytvstick.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import coil.load
import com.galaxytvstick.app.data.GfxBug
import com.galaxytvstick.app.data.GfxBumper
import com.galaxytvstick.app.data.GfxCountdown
import com.galaxytvstick.app.data.GfxIdent
import com.galaxytvstick.app.data.GfxScoreboard
import com.galaxytvstick.app.data.GfxWatermark
import com.galaxytvstick.app.data.GfxWeather
import com.galaxytvstick.app.data.Graphics

/**
 * Grafik TV tankou chèn televizyon yo, anlè videyo a:
 *  - logo bug, scoreboard, meteyo, countdown: nan youn nan 6 kwen yo (plizyè nan menm kwen an anpile)
 *  - watermark: tèks pal (egz: MAC kliyan an), ki ka chanje kwen chak 30 s
 *  - channel ident: logo/non chèn nan nan mitan ekran an pou kèk segonn, chak X minit
 *  - bumper: ekran plen anvan/apre koupi piblisite a (PlayerActivity ki lanse l)
 *
 * Menm desen ak "Live Preview" nan panel la (public/galaxy/gfx.js); mezi yo an dp/sp pou yon TV 960×540dp.
 */
class GraphicsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private var gfx: Graphics? = null
    private var signature = ""
    private val stacks = HashMap<String, LinearLayout>()
    private var wmView: TextView? = null
    private var wmCorner = -1
    private var cdView: TextView? = null
    /** Kouch plen ekran pou ident ak bumper (toujou anlè lòt grafik yo). */
    private val full = FrameLayout(context).apply { visibility = GONE }
    private var lastIdentAt = SystemClock.elapsedRealtime()
    private var identShowing = false
    private var bumperShowing = false
    private var ticking = false

    /** Espas anba a (pou grafik anba yo rete anlè ticker a). */
    var bottomInset: Int = dp(60f)
        set(value) {
            if (field == value) return
            field = value
            stacks.forEach { (pos, v) -> if (pos.startsWith("bottom")) (v.layoutParams as LayoutParams).let { it.bottomMargin = value; v.layoutParams = it } }
            wmView?.let { placeWatermark(it, gfx?.watermark) }
        }

    /** true pandan piblisite: kache bug, scoreboard, elatriye (tankou sou TV), epi pa lanse ident. */
    var suppressed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && identShowing) { ui.removeCallbacks(endIdent); hideFull() } // piblisite kòmanse: retire ident la
            applyVisibility()
        }

    init {
        isFocusable = false
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(full, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Mete grafik panel la voye yo. Si anyen pa chanje, sa ki sou ekran an rete jan l ye. */
    fun setGraphics(g: Graphics?) {
        val hadIdent = gfx?.ident != null
        gfx = g
        if (g?.ident != null && !hadIdent) lastIdentAt = SystemClock.elapsedRealtime()
        val sig = if (g == null) "" else listOf(g.bug, g.watermark, g.scoreboard, g.weather, g.countdown).toString()
        if (sig != signature) { signature = sig; rebuild(g) }
        val needTick = g != null && (g.countdown != null || g.watermark?.move == true || (g.ident?.everyMin ?: 0) > 0)
        if (needTick && !ticking) { ticking = true; ui.post(tick) }
        if (!needTick && ticking) { ticking = false; ui.removeCallbacks(tick) }
        visibility = if (g == null && !bumperShowing) GONE else VISIBLE
    }

    fun stop() {
        ticking = false
        ui.removeCallbacksAndMessages(null)
        hideFull()
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    // ------------------------------------------------------------ Grafik ki rete sou ekran an
    private fun rebuild(g: Graphics?) {
        stacks.values.forEach { removeView(it) }
        stacks.clear()
        wmView?.let { removeView(it) }
        wmView = null; cdView = null; wmCorner = -1
        if (g == null) return
        g.bug?.let { put(it.position, "top-right", bug(it)) }
        g.scoreboard?.let { put(it.position, "top-left", scoreboard(it)) }
        g.weather?.let { put(it.position, "top-right", weather(it)) }
        g.countdown?.let { put(it.position, "top-center", countdown(it)) }
        g.watermark?.let { w ->
            val v = label(w.text, w.size.toFloat(), Color.WHITE, bold = true).apply {
                alpha = w.opacity / 100f
                setShadowLayer(dp(1f).toFloat(), 0f, dp(1f).toFloat(), 0x80000000.toInt())
            }
            addView(v, childCount - 1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            placeWatermark(v, w)
            wmView = v
        }
        applyVisibility()
        updateTick()
    }

    private fun applyVisibility() {
        val vis = if (suppressed) INVISIBLE else VISIBLE
        stacks.values.forEach { it.visibility = vis }
        wmView?.visibility = vis
    }

    /** Mete yon grafik nan kwen li. Plizyè grafik nan menm kwen an anpile (anba yo: soti anba monte). */
    private fun put(position: String, fallback: String, v: View) {
        val pos = if (position in POSITIONS) position else fallback
        val stack = stacks.getOrPut(pos) {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false; clipToPadding = false
                gravity = hGravity(pos)
                val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                lp.gravity = (if (pos.startsWith("top")) Gravity.TOP else Gravity.BOTTOM) or hGravity(pos)
                lp.marginStart = dp(36f); lp.marginEnd = dp(36f)
                lp.topMargin = dp(30f); lp.bottomMargin = bottomInset
                this@GraphicsView.addView(this, this@GraphicsView.childCount - 1, lp)
            }
        }
        val lp = (v.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val bottom = pos.startsWith("bottom")
        if (stack.childCount > 0) { if (bottom) lp.bottomMargin = dp(8f) else lp.topMargin = dp(8f) }
        if (bottom) stack.addView(v, 0, lp) else stack.addView(v, lp)
    }

    private fun hGravity(pos: String) = when {
        pos.endsWith("right") -> Gravity.END
        pos.endsWith("center") -> Gravity.CENTER_HORIZONTAL
        else -> Gravity.START
    }

    private fun bug(b: GfxBug): View {
        val url = b.imageUrl
        return if (url != null) ImageView(context).apply {
            adjustViewBounds = true
            maxWidth = dp(320f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = b.opacity / 100f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(b.size.toFloat()))
            load(url)
        } else label(b.text, b.size * 0.6f, Color.WHITE, bold = true).apply {
            alpha = b.opacity / 100f
            letterSpacing = 0.04f
            setShadowLayer(dp(3f).toFloat(), 0f, dp(2f).toFloat(), 0xB3000000.toInt())
        }
    }

    private fun scoreboard(s: GfxScoreboard): View {
        val dark = 0xF0080C1A.toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(dark); cornerRadius = dp(6f).toFloat() }
            clipToOutline = true
            elevation = dp(4f).toFloat()
        }
        if (s.league.isNotBlank()) root.addView(label(s.league.uppercase(), 11f, 0xFFCBD5F5.toInt(), bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun team(name: String, color: String) = label(name, 18f, Color.WHITE, bold = true).apply {
            maxWidth = dp(150f)
            setBackgroundColor(parse(color, 0xFF1D4ED8.toInt()))
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        fun score(n: String) = label(n, 18f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(42f)
            fontFeatureSettings = "tnum"
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        row.addView(team(s.home, s.homeColor))
        row.addView(score(s.homeScore))
        row.addView(score(s.awayScore))
        row.addView(team(s.away, s.awayColor))
        if (s.clock.isNotBlank()) row.addView(label(s.clock, 14f, 0xFF00E5FF.toInt(), bold = true).apply {
            fontFeatureSettings = "tnum"
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        })
        root.addView(row)
        return root
    }

    private fun weather(w: GfxWeather): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(0xB8080C1A.toInt()); cornerRadius = dp(10f).toFloat() }
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        if (w.icon.isNotBlank()) addView(label(w.icon, 24f, Color.WHITE, bold = false), gap(8f))
        addView(label(w.temp, 20f, Color.WHITE, bold = true), gap(8f))
        addView(label(w.city, 13f, 0xD9FFFFFF.toInt(), bold = false).apply { maxWidth = dp(160f) })
    }

    private fun countdown(c: GfxCountdown): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply { setColor(0xCC080C1A.toInt()); cornerRadius = dp(10f).toFloat() }
        setPadding(dp(16f), dp(6f), dp(16f), dp(8f))
        if (c.title.isNotBlank()) addView(label(c.title.uppercase(), 11f, 0xD9FFFFFF.toInt(), bold = true).apply { letterSpacing = 0.1f })
        val time = label(countdownText(c), 26f, Color.WHITE, bold = true).apply { fontFeatureSettings = "tnum" }
        addView(time)
        cdView = time
    }

    private fun gap(end: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(end) }

    private fun placeWatermark(v: View, w: GfxWatermark?) {
        val pos = if (w?.move == true && wmCorner >= 0) WM_CORNERS[wmCorner % 4] else w?.position ?: "bottom-right"
        val lp = v.layoutParams as LayoutParams
        lp.gravity = when (pos) {
            "center" -> Gravity.CENTER
            "top-left" -> Gravity.TOP or Gravity.START
            "top-right" -> Gravity.TOP or Gravity.END
            "bottom-left" -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        lp.marginStart = dp(48f); lp.marginEnd = dp(48f)
        lp.topMargin = dp(90f); lp.bottomMargin = bottomInset + dp(60f)
        v.layoutParams = lp
    }

    // ------------------------------------------------------------ Chak segonn
    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            updateTick()
            ui.postDelayed(this, 1000)
        }
    }

    private fun updateTick() {
        val g = gfx ?: return
        val c = g.countdown
        val cd = cdView
        if (c != null && cd != null) { val t = countdownText(c); if (cd.text.toString() != t) cd.text = t }
        val w = g.watermark
        val wv = wmView
        if (w != null && w.move && wv != null) {
            val corner = ((System.currentTimeMillis() / 30_000L) % 4).toInt()
            if (corner != wmCorner) { wmCorner = corner; placeWatermark(wv, w) }
        }
        val i = g.ident
        if (i != null && i.everyMin > 0 && !suppressed && !identShowing && !bumperShowing &&
            SystemClock.elapsedRealtime() - lastIdentAt >= i.everyMin * 60_000L) showIdent()
    }

    private fun countdownText(c: GfxCountdown): String {
        val left = c.targetAt - System.currentTimeMillis() / 1000
        if (left <= 0) return c.doneText.ifBlank { "00:00" }
        val d = left / 86400; val h = (left % 86400) / 3600; val m = (left % 3600) / 60; val s = left % 60
        return buildString {
            if (d > 0) append(d).append("d ")
            if (d > 0 || h > 0) append("%02d:".format(h))
            append("%02d:%02d".format(m, s))
        }
    }

    // ------------------------------------------------------------ Channel ident
    /** Kliyan an chanje chanèl: montre ident la si panel la mande sa. */
    fun onChannelChanged() {
        val i = gfx?.ident ?: return
        if (i.onChannelChange && !suppressed && !identShowing && !bumperShowing) showIdent()
    }

    private fun showIdent() {
        val i: GfxIdent = gfx?.ident ?: return
        lastIdentAt = SystemClock.elapsedRealtime()
        identShowing = true
        full.removeAllViews()
        full.setBackgroundColor(0x66080C1A)
        full.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(80f), dp(40f), dp(80f), dp(40f))
            i.imageUrl?.let { url ->
                addView(ImageView(context).apply {
                    adjustViewBounds = true
                    maxWidth = dp(420f); maxHeight = dp(220f)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    load(url)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (i.text.isNotBlank()) addView(label(i.text, 54f, Color.WHITE, bold = true).apply {
                letterSpacing = 0.04f
                maxWidth = dp(800f)
                setShadowLayer(dp(9f).toFloat(), 0f, dp(4f).toFloat(), 0x99000000.toInt())
            })
            if (i.tagline.isNotBlank()) addView(label(i.tagline, 22f, 0xE6FFFFFF.toInt(), bold = false).apply {
                maxWidth = dp(800f)
                setShadowLayer(dp(4f).toFloat(), 0f, dp(2f).toFloat(), 0x99000000.toInt())
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14f)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        full.animate().cancel()
        full.alpha = 0f
        full.scaleX = 0.92f; full.scaleY = 0.92f
        full.visibility = VISIBLE
        full.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600).start()
        ui.removeCallbacks(endIdent)
        ui.postDelayed(endIdent, i.durationSec * 1000L - 700L)
    }

    private val endIdent = Runnable {
        if (identShowing) full.animate().alpha(0f).setDuration(700).withEndAction { if (identShowing) hideFull() }.start()
    }

    // ------------------------------------------------------------ Bumper (anvan / apre piblisite)
    /** Montre bumper a plen ekran. Retounen false si pa gen bumper pou bò sa a. */
    fun showBumper(out: Boolean): Boolean {
        val b: GfxBumper = gfx?.bumper ?: return false
        if (!b.has(out)) return false
        ui.removeCallbacks(endIdent)
        identShowing = false
        bumperShowing = true
        full.animate().cancel()
        full.removeAllViews()
        full.scaleX = 1f; full.scaleY = 1f
        full.setBackgroundColor(parse(b.bgColor, 0xFF0B1630.toInt()) or 0xFF000000.toInt())
        val url = if (out) b.outUrl else b.inUrl
        if (url != null) {
            full.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                load(url)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            full.addView(label(if (out) b.outText else b.inText, 48f, parse(b.textColor, Color.WHITE), bold = true).apply {
                maxLines = 3
                maxWidth = dp(800f)
                gravity = Gravity.CENTER
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        full.alpha = 0f
        full.visibility = VISIBLE
        visibility = VISIBLE
        full.animate().alpha(1f).setDuration(350).start()
        return true
    }

    fun hideBumper() {
        if (!bumperShowing) return
        hideFull()
        if (gfx == null) visibility = GONE
    }

    private fun hideFull() {
        full.animate().cancel()
        full.visibility = GONE
        full.removeAllViews()
        full.alpha = 1f; full.scaleX = 1f; full.scaleY = 1f
        identShowing = false
        bumperShowing = false
    }

    // ------------------------------------------------------------ Zouti
    /** TextView ki montre tout imoji yo (AppCompat itilize EmojiCompat otomatikman). */
    private fun label(s: String, sizeSp: Float, color: Int, bold: Boolean) = AppCompatTextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun parse(s: String, d: Int): Int = runCatching { Color.parseColor(s.trim()) }.getOrDefault(d)

    companion object {
        private val POSITIONS = setOf("top-left", "top-center", "top-right", "bottom-left", "bottom-center", "bottom-right")
        private val WM_CORNERS = arrayOf("top-left", "bottom-right", "top-right", "bottom-left")
    }
}
