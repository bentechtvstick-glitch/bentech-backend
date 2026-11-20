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
import com.galaxytvstick.app.data.GfxSnipe
import com.galaxytvstick.app.data.GfxWeatherBar
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
    /** Snipe: imaj anime anba ekran an (kouch pa l, anlè ticker a). */
    private var snipeView: View? = null
    private var snipeImg: ImageView? = null
    private var snipeIndex = 0
    /** Animasyon k ap woule pandan snipe la sou ekran an (pilse, flote, balanse). */
    private var snipeLoop: android.animation.Animator? = null
    private var snipeShowing = false
    private var lastSnipeAt = SystemClock.elapsedRealtime()
    private var snipeSig = ""
    /** Lè pou montre snipe la yon fwa apre panel la fin chanje l (0 = non). */
    private var snipePreviewAt = 0L
    /** Bann meteyo a (anba oswa anlè ekran an). */
    private var wbView: LinearLayout? = null
    private var wbCells: LinearLayout? = null
    private var wbShowing = false
    private var wbPage = 0
    private var wbShownAt = 0L
    private var wbPageAt = 0L
    private var lastWbAt = SystemClock.elapsedRealtime()
    private var wbSig = ""
    private var wbPreviewAt = 0L

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
            if (value && snipeShowing) hideSnipe()
            applyVisibility()
        }

    init {
        isFocusable = false
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(full, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Lè ticker a anlè ekran an (panel la: "Anlè ekran an"); null = pa gen. */
    var topClock: com.galaxytvstick.app.data.Ticker? = null
    private var clockView: android.widget.TextView? = null
    private var clockFmt: java.text.SimpleDateFormat? = null

    /** Mete grafik panel la voye yo. Si anyen pa chanje, sa ki sou ekran an rete jan l ye. */
    fun setGraphics(g: Graphics?) {
        val hadIdent = gfx?.ident != null
        gfx = g
        if (g?.ident != null && !hadIdent) lastIdentAt = SystemClock.elapsedRealtime()
        // Bann meteyo: chanjman nan reglaj yo → montre l nan 3 s; done (tanperati) ki chanje sèlman → mete l ajou san rekòmanse
        val wbs = g?.weatherBar?.let { "${it.title}|${it.perPage}|${it.position}|${it.bgColor}|${it.accentColor}|${it.textColor}|${it.everyMin}|${it.durationSec}|${it.unit}" } ?: ""
        if (wbs != wbSig) {
            wbSig = wbs
            hideWeatherBar(false)
            if (g?.weatherBar != null) { lastWbAt = SystemClock.elapsedRealtime(); wbPreviewAt = SystemClock.elapsedRealtime() + 3_000L }
        }
        // Nouvo snipe (oswa li chanje): montre l nan 20 s pou admin nan wè l, apre sa chak X minit
        val ss = g?.snipe?.toString() ?: ""
        if (ss != snipeSig) {
            snipeSig = ss
            if (g?.snipe == null) { hideSnipe(); snipePreviewAt = 0L }
            else { lastSnipeAt = SystemClock.elapsedRealtime(); snipePreviewAt = SystemClock.elapsedRealtime() + 3_000L }
        }
        val tc = topClock
        val sig = (if (g == null) "" else listOf(g.bug, g.watermark, g.scoreboard, g.weather, g.countdown).toString()) +
            (tc?.let { listOf(it.clockPos, it.clockFormat, it.font, it.textStyle, it.fxColor, it.italic, it.textColor, it.textSize).toString() } ?: "")
        if (sig != signature) { signature = sig; rebuild(g) }
        val needTick = (g != null && (g.countdown != null || g.watermark?.move == true || (g.ident?.everyMin ?: 0) > 0 || g.snipe != null || g.weatherBar != null)) || tc != null
        if (needTick && !ticking) { ticking = true; ui.post(tick) }
        if (!needTick && ticking) { ticking = false; ui.removeCallbacks(tick) }
        visibility = if (g == null && tc == null && !bumperShowing) GONE else VISIBLE
    }

    fun stop() {
        ticking = false
        ui.removeCallbacksAndMessages(null)
        hideSnipe(); snipeSig = ""
        hideWeatherBar(false); wbSig = ""
        wxRotate = null; signature = "" // pwochen setGraphics rekonstwi l (epi relanse vil k ap vire yo)
        hideFull()
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    // ------------------------------------------------------------ Grafik ki rete sou ekran an
    private var wxRotate: Runnable? = null

    private fun rebuild(g: Graphics?) {
        wxRotate?.let { ui.removeCallbacks(it) }; wxRotate = null
        stacks.values.forEach { removeView(it) }
        stacks.clear()
        wmView?.let { removeView(it) }
        wmView = null; cdView = null; wmCorner = -1; clockView = null
        // Lè ticker a anlè ekran an
        topClock?.let { t ->
            clockFmt = java.text.SimpleDateFormat(if (t.clockFormat == "12") "h:mm a" else "HH:mm", java.util.Locale.getDefault())
            val v = FxTextView(context, t.textStyle, TextFx.color(t.textColor, Color.WHITE), TextFx.color(t.fxColor, 0xFF1D4ED8.toInt())).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textSize * 1.1f)
                typeface = TextFx.typeface(context, t.font, t.italic)
                fontFeatureSettings = "tnum"
                maxLines = 1
                text = clockFmt?.format(java.util.Date())
                applyStyle()
                if (t.textStyle == "plain") setShadowLayer(dp(3f).toFloat(), 0f, dp(1.5f).toFloat(), 0xE6000000.toInt())
            }
            put(t.clockPos, "top-right", v)
            clockView = v
        }
        if (g == null) { applyVisibility(); return }
        g.bug?.let { put(it.position, "top-right", bug(it)) }
        g.scoreboard?.let { put(it.position, "top-left", scoreboard(it)) }
        g.weather?.let { put(it.position, "top-right", weather(it)) }
        g.countdown?.let { put(it.position, "top-center", countdown(it)) }
        g.watermark?.let { w ->
            val v = FxTextView(context, w.style, TextFx.color(w.color, Color.WHITE), TextFx.color(w.color2, Color.BLACK)).apply {
                text = w.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, w.size.toFloat())
                typeface = TextFx.typeface(context, w.font, w.italic)
                maxLines = 1
                applyStyle()
                if (w.style == "plain") setShadowLayer(dp(1f).toFloat(), 0f, dp(1f).toFloat(), 0x80000000.toInt())
                alpha = w.opacity / 100f
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
        } else FxTextView(context, b.style, TextFx.color(b.color, Color.WHITE), TextFx.color(b.color2, 0xFF1D4ED8.toInt())).apply {
            text = if (b.caps) b.text.uppercase() else b.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, b.size * 0.6f)
            typeface = TextFx.typeface(context, b.font, b.italic)
            maxLines = 1
            letterSpacing = 0.04f
            applyStyle()
            alpha = b.opacity / 100f
        }
    }

    private fun scoreboard(s: GfxScoreboard): View {
        val dark = 0xB3080C1A.toInt()
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
        // San fon: tèks la sèlman, ak yon lonbraj pou l rete lizib sou nenpòt imaj
        setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        val shade = { v: android.widget.TextView -> v.apply { setShadowLayer(dp(2.5f).toFloat(), 0f, dp(1f).toFloat(), 0x8C000000.toInt()) } }
        val iconV = WxIcon(context).apply { set(w.icon) }
        val tempV = shade(label(w.temp, 20f, Color.WHITE, bold = true))
        val cityV = shade(label(w.city, 13f, Color.WHITE, bold = true)).apply { maxWidth = dp(160f) }
        if (w.icon.isNotBlank() || w.list.isNotEmpty()) addView(iconV, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(8f) })
        addView(tempV, gap(8f))
        addView(cityV)
        // Plizyè vil: youn apre lòt, ak yon ti fondu (tankou CNN)
        if (w.list.size > 1 && w.flashSec > 0) {
            // Vil prensipal la (list[0]) rete; chak {everySec} s yon lòt vil parèt {flashSec} s, epi li tounen sou vil prensipal la
            var k = 0
            val row = this
            val show = { idx: Int ->
                row.animate().alpha(0f).setDuration(220).withEndAction {
                    val (c, t, ic) = w.list[idx]
                    cityV.text = c; tempV.text = t; iconV.set(ic)
                    row.animate().alpha(1f).setDuration(220).start()
                }.start()
            }
            val r = object : Runnable {
                var away = false
                override fun run() {
                    if (!away) {
                        k = k % (w.list.size - 1) + 1
                        show(k); away = true
                        ui.postDelayed(this, w.flashSec * 1000L + 440L)
                    } else {
                        show(0); away = false
                        ui.postDelayed(this, w.everySec * 1000L)
                    }
                }
            }
            wxRotate = r
            ui.postDelayed(r, w.everySec * 1000L)
        } else if (w.list.size > 1) {
            var i = 0
            val row = this
            val r = object : Runnable {
                override fun run() {
                    i = (i + 1) % w.list.size
                    row.animate().alpha(0f).setDuration(250).withEndAction {
                        val (c, t, ic) = w.list[i]
                        cityV.text = c; tempV.text = t; iconV.set(ic)
                        row.animate().alpha(1f).setDuration(250).start()
                    }.start()
                    ui.postDelayed(this, w.everySec * 1000L)
                }
            }
            wxRotate = r
            ui.postDelayed(r, w.everySec * 1000L)
        }
    }

    private fun countdown(c: GfxCountdown): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        if (c.box) background = GradientDrawable().apply { setColor(0x73080C1A.toInt()); cornerRadius = dp(10f).toFloat() }
        setPadding(dp(16f), dp(6f), dp(16f), dp(8f))
        val c1 = TextFx.color(c.color, Color.WHITE); val c2 = TextFx.color(c.color2, 0xFF1D4ED8.toInt())
        if (c.title.isNotBlank()) addView(FxTextView(context, if (c.style == "box") "classic" else c.style, c1, c2).apply {
            text = c.title.uppercase(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = TextFx.typeface(context, c.font, c.italic)
            letterSpacing = 0.1f; maxLines = 1; applyStyle(); alpha = 0.9f
        })
        val time = FxTextView(context, c.style, c1, c2).apply {
            text = countdownText(c); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = TextFx.typeface(context, c.font, c.italic)
            fontFeatureSettings = "tnum"; maxLines = 1; applyStyle()
        }
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
        clockView?.let { cv -> val now = clockFmt?.format(java.util.Date()); if (now != null && cv.text.toString() != now) cv.text = now }
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
        val sn = g.snipe
        if (sn != null && !suppressed && !identShowing && !bumperShowing && !snipeShowing) {
            val now = SystemClock.elapsedRealtime()
            val preview = snipePreviewAt in 1..now
            val auto = sn.everyMin > 0 && now - lastSnipeAt >= sn.everyMin * 60_000L
            if (preview || auto) { snipePreviewAt = 0L; showSnipe() }
        }
        val wb = g.weatherBar
        if (wb != null) {
            val now = SystemClock.elapsedRealtime()
            if (suppressed || bumperShowing) { if (wbShowing) hideWeatherBar(false) }
            else if (!wbShowing) {
                val preview = wbPreviewAt in 1..now
                if (wb.everyMin == 0 || preview || now - lastWbAt >= wb.everyMin * 60_000L) { wbPreviewAt = 0L; showWeatherBar(wb) }
            } else if (wb.everyMin > 0 && now - wbShownAt >= wb.durationSec * 1000L) hideWeatherBar(true)
            else if (now - wbPageAt >= wb.pageSec * 1000L && wb.items.size > wb.perPage) nextWbPage(wb)
        }
    }

    // ------------------------------------------------------------ Bann meteyo (tankou BFM)
    private fun showWeatherBar(wb: GfxWeatherBar) {
        hideWeatherBar(false)
        wbShowing = true; wbPage = 0
        val now = SystemClock.elapsedRealtime()
        wbShownAt = now; wbPageAt = now; lastWbAt = now
        val txt = parse(wb.textColor, Color.WHITE)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(parse(wb.bgColor, 0xFF1D4ED8.toInt()))
            elevation = dp(5f).toFloat()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Etikèt agoch (tankou "MÉTÉO AUJOURD'HUI")
        val tag = label(wb.title.uppercase(), 15f, Color.WHITE, bold = true).apply {
            maxLines = 2; gravity = Gravity.CENTER
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setColor(parse(wb.accentColor, 0xFFF59E0B.toInt())) }
        }
        bar.addView(tag, LinearLayout.LayoutParams(dp(150f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16f); marginEnd = dp(8f) })
        val cells = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(cells, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        fillWbCells(cells, wb, txt)
        val top = wb.position == "top"
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(78f), if (top) Gravity.TOP else Gravity.BOTTOM).apply {
            if (top) topMargin = dp(0f) else bottomMargin = bottomInset
        })
        wbView = bar; wbCells = cells
        bar.translationY = if (top) -dp(90f).toFloat() else (dp(90f) + bottomInset).toFloat()
        bar.animate().translationY(0f).setDuration(500).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun fillWbCells(cells: LinearLayout, wb: GfxWeatherBar, txt: Int) {
        cells.removeAllViews()
        val pages = (wb.items.size + wb.perPage - 1) / wb.perPage
        val start = (wbPage % pages.coerceAtLeast(1)) * wb.perPage
        val list = wb.items.drop(start).take(wb.perPage)
        list.forEachIndexed { i, it ->
            if (i > 0) cells.addView(View(context).apply { setBackgroundColor(parse(wb.accentColor, 0xFFF59E0B.toInt())) },
                LinearLayout.LayoutParams(dp(2f), dp(44f)))
            val cell = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f), 0, dp(10f), 0) }
            cell.addView(label(it.icon, 30f, txt, bold = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(it.city, 18f, txt, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fun chip(v: Int?, c: Int) = label(if (v == null) "–" else "$v°", 17f, Color.WHITE, bold = true).apply {
                setPadding(dp(8f), dp(1f), dp(8f), dp(1f))
                background = GradientDrawable().apply { cornerRadius = dp(4f).toFloat(); setColor(c) }
            }
            row.addView(chip(it.min, 0xFF2563EB.toInt()))
            row.addView(chip(it.max, 0xFFF97316.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) })
            if (wb.showNow && it.now != null) row.addView(label("  ${it.now}°${wb.unit}", 14f, txt, bold = false).apply { alpha = 0.85f })
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3f) })
            cell.addView(col)
            cells.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun nextWbPage(wb: GfxWeatherBar) {
        val cells = wbCells ?: return
        wbPageAt = SystemClock.elapsedRealtime()
        cells.animate().alpha(0f).translationY(-dp(12f).toFloat()).setDuration(250).withEndAction {
            wbPage++
            fillWbCells(cells, wb, parse(wb.textColor, Color.WHITE))
            cells.translationY = dp(12f).toFloat()
            cells.animate().alpha(1f).translationY(0f).setDuration(300).start()
        }.start()
    }

    private fun hideWeatherBar(animated: Boolean) {
        val v = wbView ?: run { wbShowing = false; return }
        wbView = null; wbCells = null; wbShowing = false
        if (!animated) { v.animate().cancel(); removeView(v); return }
        val top = gfx?.weatherBar?.position == "top"
        v.animate().translationY(if (top) -v.height.toFloat() else (v.height + bottomInset).toFloat()).setDuration(450)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { removeView(v) }.start()
    }

    // ------------------------------------------------------------ Snipe
    /** Panel la mande pou snipe la parèt touswit (si pa gen piblisite oswa ident sou ekran an). */
    fun showSnipeNow() {
        if (gfx?.snipe == null || suppressed || identShowing || bumperShowing) return
        showSnipe()
    }

    private fun showSnipe() {
        val sn: GfxSnipe = gfx?.snipe ?: return
        lastSnipeAt = SystemClock.elapsedRealtime()
        hideSnipe()
        snipeShowing = true
        val h = dp(sn.size.toFloat())
        val grav = Gravity.BOTTOM or when (sn.position) { "bottom-left" -> Gravity.START; "bottom-center" -> Gravity.CENTER_HORIZONTAL; else -> Gravity.END }
        // Plizyè imaj: chak fwa se pwochen an
        val url = sn.images.takeIf { it.isNotEmpty() }?.let { it[snipeIndex++ % it.size] }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            elevation = dp(6f).toFloat()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        var img: ImageView? = null
        if (url != null) {
            img = ImageView(context).apply {
                adjustViewBounds = true
                maxHeight = h; maxWidth = dp(640f)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            box.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h))
        }
        if (sn.text.isNotBlank()) {
            val tv = label(sn.text, (sn.size / 4.2f).coerceIn(16f, 46f), parse(sn.textColor, Color.WHITE), bold = true).apply {
                maxLines = 2; maxWidth = dp(760f)
                setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
                background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(parse(sn.bgColor, 0xCC0B1630.toInt())) }
                setShadowLayer(dp(3f).toFloat(), 0f, dp(1f).toFloat(), 0x88000000.toInt())
            }
            box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (img != null) marginStart = dp(12f) })
        }
        addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, grav).apply {
            bottomMargin = bottomInset + dp(16f); marginStart = dp(48f); marginEnd = dp(48f)
        })
        snipeView = box; snipeImg = img
        if (img != null && url != null) {
            // Antre a kòmanse lè imaj la (oswa GIF la) fin chaje
            img.load(url) {
                listener(onSuccess = { _, _ -> if (snipeShowing && snipeView === box) animateSnipeIn(box, sn) },
                    onError = { _, _ -> if (sn.text.isNotBlank() && snipeView === box) { box.removeView(img); snipeImg = null; animateSnipeIn(box, sn) } else hideSnipe() })
            }
        } else animateSnipeIn(box, sn)
    }

    private fun snipeOffset(v: View, sn: GfxSnipe): Pair<Float, Float> {
        val w = (v.width.takeIf { it > 0 } ?: dp(300f)).toFloat() + dp(80f)
        val down = (v.height.coerceAtLeast(dp(sn.size.toFloat())) + bottomInset + dp(40f)).toFloat()
        return when (sn.anim) {
            "rise", "bounce", "float" -> 0f to down
            "slide", "shake", "roll" -> (if (sn.position == "bottom-left") -w else w) to 0f
            else -> 0f to 0f
        }
    }

    private fun loop(a: android.animation.Animator) { snipeLoop?.cancel(); snipeLoop = a; a.start() }

    private fun animateSnipeIn(v: View, sn: GfxSnipe) {
        v.post {
            if (!snipeShowing || snipeView !== v) return@post
            v.animate().cancel()
            v.cameraDistance = 8000 * resources.displayMetrics.density
            val (dx, dy) = snipeOffset(v, sn)
            val d = dp(1f).toFloat()
            when (sn.anim) {
                "bounce" -> {
                    v.translationY = -dp(240f).toFloat(); v.alpha = 1f
                    v.animate().translationY(0f).setDuration(1100).setInterpolator(android.view.animation.BounceInterpolator()).start()
                }
                "flip" -> {
                    v.rotationY = 90f
                    v.animate().alpha(1f).rotationY(0f).setDuration(700).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                "wipe" -> {
                    v.alpha = 1f
                    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 800
                        addUpdateListener { a -> v.clipBounds = android.graphics.Rect(0, 0, (v.width * (a.animatedValue as Float)).toInt(), v.height) }
                        addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { v.clipBounds = null } })
                    }.start()
                }
                "zoom" -> {
                    v.scaleX = 2.2f; v.scaleY = 2.2f
                    blur(v, 25f)
                    android.animation.ValueAnimator.ofFloat(1f, 0f).apply { duration = 700; addUpdateListener { a -> blur(v, 25f * (a.animatedValue as Float)) } }.start()
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                "shake" -> {
                    v.translationX = dx
                    v.animate().alpha(1f).translationX(0f).setDuration(550).setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { android.animation.ObjectAnimator.ofFloat(v, "translationX", 0f, -14 * d, 12 * d, -8 * d, 6 * d, -3 * d, 0f).setDuration(700).start() }.start()
                }
                "shine" -> {
                    v.animate().alpha(1f).setDuration(600).start()
                    val im = snipeImg
                    if (im != null) loop(android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                        duration = 1600; startDelay = 500; repeatCount = 2
                        addUpdateListener { a -> bright(im, a.animatedValue as Float) }
                        addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { im.colorFilter = null } })
                    }) else loop(android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0.6f, 1f).apply { duration = 1600; startDelay = 600; repeatCount = 2 })
                }
                "roll" -> {
                    v.translationX = dx; v.rotation = if (dx < 0) -360f else 360f
                    v.animate().alpha(1f).translationX(0f).rotation(0f).setDuration(900).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                "swing" -> {
                    v.pivotX = v.width / 2f; v.pivotY = 0f
                    v.alpha = 1f; v.rotation = 25f
                    android.animation.ObjectAnimator.ofFloat(v, "rotation", 25f, -18f, 12f, -7f, 4f, -2f, 0f).setDuration(1600).start()
                    loop(android.animation.ObjectAnimator.ofFloat(v, "rotation", 0f, 2.5f, 0f, -2.5f, 0f).apply { duration = 2400; startDelay = 1600; repeatCount = android.animation.ValueAnimator.INFINITE })
                }
                "elastic" -> {
                    v.alpha = 1f; v.scaleX = 0f; v.scaleY = 0f
                    android.animation.AnimatorSet().apply {
                        playTogether(android.animation.ObjectAnimator.ofFloat(v, "scaleX", 0f, 1.25f, 0.85f, 1.08f, 0.96f, 1f),
                            android.animation.ObjectAnimator.ofFloat(v, "scaleY", 0f, 0.8f, 1.15f, 0.94f, 1.03f, 1f))
                        duration = 1000
                    }.start()
                }
                "pulse" -> {
                    v.scaleX = 0.8f; v.scaleY = 0.8f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).start()
                    loop(android.animation.AnimatorSet().apply {
                        val sx = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.08f, 1f).apply { repeatCount = android.animation.ValueAnimator.INFINITE }
                        val sy = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.08f, 1f).apply { repeatCount = android.animation.ValueAnimator.INFINITE }
                        playTogether(sx, sy); duration = 1200; startDelay = 500
                    })
                }
                "float" -> {
                    v.translationY = dy
                    v.animate().alpha(1f).translationY(0f).setDuration(700).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    loop(android.animation.ObjectAnimator.ofFloat(v, "translationY", 0f, -9 * d, 0f).apply {
                        duration = 2200; startDelay = 700; repeatCount = android.animation.ValueAnimator.INFINITE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    })
                }
                "glitch" -> {
                    v.alpha = 1f
                    val im = snipeImg
                    android.animation.ValueAnimator.ofInt(0, 10).apply {
                        duration = 700
                        addUpdateListener { a ->
                            val k = a.animatedValue as Int
                            v.translationX = if (k < 10) ((k * 37) % 13 - 6) * d * 2 else 0f
                            v.alpha = if (k < 10 && k % 3 == 1) 0.35f else 1f
                            im?.colorFilter = if (k < 10) android.graphics.PorterDuffColorFilter(if (k % 2 == 0) 0x55FF2040 else 0x5500E5FF, android.graphics.PorterDuff.Mode.SRC_ATOP) else null
                        }
                    }.start()
                }
                "blink" -> {
                    android.animation.ObjectAnimator.ofFloat(v, "alpha", 0f, 1f, 0f, 1f, 0f, 1f).setDuration(1200).start()
                }
                else -> {
                    v.translationX = dx; v.translationY = dy
                    if (sn.anim == "pop") { v.scaleX = 0.5f; v.scaleY = 0.5f }
                    v.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(if (sn.anim == "fade") 700 else 550)
                        .setInterpolator(android.view.animation.OvershootInterpolator(if (sn.anim == "pop") 1.6f else 0.6f)).start()
                }
            }
            ui.removeCallbacks(endSnipe)
            ui.postDelayed(endSnipe, sn.durationSec * 1000L)
        }
    }

    /** Flou (Android 12+ sèlman; sou ansyen aparèy yo zoom nan rete san flou). */
    private fun blur(v: View, r: Float) {
        if (android.os.Build.VERSION.SDK_INT >= 31) v.setRenderEffect(if (r < 0.5f) null else android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL))
    }

    /** Klète: 0 = nòmal, 1 = pi klè (pou efè "Klere"). */
    private fun bright(v: ImageView, f: Float) {
        val add = 90f * f
        v.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, add, 0f, 1f, 0f, 0f, add, 0f, 0f, 1f, 0f, add, 0f, 0f, 0f, 1f, 0f)))
    }

    private val endSnipe = Runnable {
        val v = snipeView; val sn = gfx?.snipe
        if (!snipeShowing || v == null) return@Runnable
        snipeLoop?.cancel(); snipeLoop = null
        val (dx, dy) = if (sn != null) snipeOffset(v, sn) else 0f to 0f
        when (sn?.anim) {
            "flip" -> v.animate().alpha(0f).rotationY(-90f).setDuration(600).withEndAction { hideSnipe() }.start()
            "wipe" -> android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                addUpdateListener { a -> v.clipBounds = android.graphics.Rect((v.width * (a.animatedValue as Float)).toInt(), 0, v.width, v.height) }
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { hideSnipe() } })
            }.start()
            "zoom" -> {
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 500; addUpdateListener { a -> blur(v, 20f * (a.animatedValue as Float)) } }.start()
                v.animate().alpha(0f).scaleX(1.6f).scaleY(1.6f).setDuration(500).withEndAction { hideSnipe() }.start()
            }
            "roll" -> v.animate().alpha(0f).translationX(dx).rotation(if (dx < 0) -360f else 360f).setDuration(700).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { hideSnipe() }.start()
            "elastic", "pulse" -> v.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(450).setInterpolator(android.view.animation.AnticipateInterpolator()).withEndAction { hideSnipe() }.start()
            "glitch", "blink" -> android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0f, 1f, 0f, 0.6f, 0f).setDuration(600).apply {
                addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(animation: android.animation.Animator) { hideSnipe() } })
            }.start()
            "swing" -> v.animate().alpha(0f).rotation(30f).setDuration(500).withEndAction { hideSnipe() }.start()
            else -> v.animate().alpha(0f).translationX(dx).translationY(dy).scaleX(if (sn?.anim == "pop") 0.5f else 1f).scaleY(if (sn?.anim == "pop") 0.5f else 1f)
                .setDuration(500).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { hideSnipe() }.start()
        }
    }

    private fun hideSnipe() {
        ui.removeCallbacks(endSnipe)
        snipeLoop?.cancel(); snipeLoop = null
        snipeView?.let { it.animate().cancel(); removeView(it) }
        snipeView = null; snipeImg = null
        snipeShowing = false
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
        val sn = gfx?.snipe
        if (sn != null && sn.onChannelChange && !suppressed && !identShowing && !bumperShowing && !snipeShowing) ui.postDelayed({ if (!snipeShowing) showSnipe() }, 1500)
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
        val bc = parse(b.bgColor, 0xFF0B1630.toInt())
        full.setBackgroundColor(if (Color.alpha(bc) == 0) Color.TRANSPARENT else bc or 0xFF000000.toInt()) // transparan: videyo a parèt dèyè
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
