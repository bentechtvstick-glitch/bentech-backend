package com.galaxytvstick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.emoji2.text.EmojiCompat
import com.galaxytvstick.app.data.Ticker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ticker style chèn TV:
 *  [ BREAKING NEWS ]  mesaj 1  ●  mesaj 2 (an wouj)  ●  mesaj 3 …   [ 21:45 ]
 *
 * - Chak mesaj ka gen pwòp koulè li; sinon li pran koulè tèks jeneral la.
 * - Tout imoji ak senbòl yo desine ak font imoji ki anndan app la (EmojiCompat),
 *   gras a StaticLayout (canvas.drawText pa ta montre nouvo imoji yo sou ansyen Android).
 */
class TickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelPaint = TextPaint(textPaint).apply { textSize = 17f * resources.displayMetrics.scaledDensity }
    private val clockPaint = TextPaint(labelPaint).apply { color = Color.WHITE }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clockPattern = "HH:mm"

    private var ticker: Ticker? = null
    private var colorOf: (String, Int) -> Int = { _, d -> d }
    private var scrollLayout: StaticLayout? = null
    private var labelLayout: StaticLayout? = null
    private var clockLayout: StaticLayout? = null
    private var clockText = ""
    private var offset = Float.NaN
    private var pxPerFrame = 3f
    private var signature = ""

    init {
        // Lè font imoji a fin chaje, rekonstwi tèks la pou imoji yo parèt
        if (runCatching { EmojiCompat.get().loadState }.getOrNull() != EmojiCompat.LOAD_STATE_SUCCEEDED) {
            runCatching {
                EmojiCompat.get().registerInitCallback(object : EmojiCompat.InitCallback() {
                    override fun onInitialized() { signature = ""; rebuild() }
                })
            }
        }
    }

    /** Wotè ba a an piksèl selon gwosè tèks la (40dp pou 20sp). */
    fun barHeightFor(t: Ticker): Int = (t.textSize.coerceIn(12, 40) * 2f * resources.displayMetrics.scaledDensity).toInt()

    // Imoji anime yo (plas vid nan tèks la + desen apa ak efè a)
    private var scrollGlyphs: List<AnimGlyph> = emptyList()
    private var labelGlyphs: List<AnimGlyph> = emptyList()
    // Animasyon k ap kouri sou ba a (egz: 🏎️💨)
    private val runnerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var runnerLayout: StaticLayout? = null
    private var runnerFx = Fx.DRIVE
    private var runnerX = Float.NaN
    private var runnerPx = 3f

    fun setTicker(t: Ticker, parseColor: (String, Int) -> Int) {
        ticker = t
        colorOf = parseColor
        // Lè a: 12h (8:45 PM) oswa 24h (20:45), jan panel la chwazi
        val pattern = if (t.clockFormat == "12") "h:mm a" else "HH:mm"
        if (pattern != clockPattern) { clockPattern = pattern; clockFmt = SimpleDateFormat(pattern, Locale.getDefault()); clockText = ""; clockLayout = null }
        pxPerFrame = t.speed.coerceIn(1, 10) * 0.4f * density
        runnerPx = (t.runner?.speed ?: 6).coerceIn(1, 10) * 0.6f * density // menm vitès ak preview panel la (vitès × 36 dp/s)
        // Gwosè tèks la ak wotè ba a
        val sd = resources.displayMetrics.scaledDensity
        val size = t.textSize.coerceIn(12, 40) * sd
        if (textPaint.textSize != size) {
            textPaint.textSize = size; labelPaint.textSize = size * 0.85f; clockPaint.textSize = size * 0.85f; signature = ""; clockLayout = null
        }
        // Fon transparan (oswa prèske): lonbraj anba tèks la pou l li byen sou nenpòt imaj
        val bgAlpha = Color.alpha(parseColor(t.bgColor, 0xCC7C4DFF.toInt()))
        val shadow = t.transparent || bgAlpha < 110
        listOf(textPaint, labelPaint, clockPaint).forEach {
            if (shadow) it.setShadowLayer(3f * density, 0f, 1.5f * density, 0xE6000000.toInt()) else it.clearShadowLayer()
        }
        if (shadow != lastShadow) { lastShadow = shadow; signature = ""; clockLayout = null }
        val h = barHeightFor(t)
        layoutParams?.let { lp -> if (lp.height != h) { lp.height = h; layoutParams = lp } }
        rebuild()
    }
    private var lastShadow = false

    private fun emoji(cs: CharSequence): CharSequence = runCatching {
        if (EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) EmojiCompat.get().process(cs) ?: cs else cs
    }.getOrDefault(cs)

    private fun layoutOf(cs: CharSequence, paint: TextPaint): StaticLayout {
        val w = Layout.getDesiredWidth(cs, paint).toInt().coerceAtLeast(1) + 2
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(cs, 0, cs.length, paint, w).setIncludePad(false).setMaxLines(1).build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(cs, paint, w, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
    }

    private fun rebuild() {
        val t = ticker ?: return
        val baseColor = colorOf(t.textColor, Color.WHITE)
        val items = t.items.ifEmpty { listOf(com.galaxytvstick.app.data.TickerItem(t.text, "")) }
        val sig = listOf(items, t.separator, t.label, t.labelColor, baseColor, t.showClock, t.direction, t.animateEmoji, t.runner, textPaint.textSize).toString()
        if (sig != signature) {
            signature = sig
            val sb = SpannableStringBuilder()
            val sep = t.separator.ifBlank { "•" }
            textPaint.color = baseColor
            val glyphs = ArrayList<AnimGlyph>()
            items.forEach { item ->
                val start = sb.length
                appendAnimated(sb, item.text.trim(), textPaint, glyphs, t.animateEmoji)
                val c = if (item.color.isNotBlank()) colorOf(item.color, baseColor) else baseColor
                sb.setSpan(ForegroundColorSpan(c), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val s2 = sb.length
                sb.append("     "); appendAnimated(sb, sep, textPaint, glyphs, t.animateEmoji); sb.append("     ")
                sb.setSpan(ForegroundColorSpan(baseColor), s2, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val scroll = layoutOf(emoji(sb), textPaint)
            glyphs.forEach { it.x = scroll.getPrimaryHorizontal(it.offset) }
            scrollLayout = scroll; scrollGlyphs = glyphs
            labelPaint.color = colorOf(t.labelColor, Color.WHITE)
            val lg = ArrayList<AnimGlyph>()
            labelLayout = t.label.takeIf { it.isNotBlank() }?.let {
                val lsb = SpannableStringBuilder(); appendAnimated(lsb, it.trim(), labelPaint, lg, t.animateEmoji)
                layoutOf(emoji(lsb), labelPaint).also { l -> lg.forEach { g -> g.x = l.getPrimaryHorizontal(g.offset) } }
            }
            labelGlyphs = lg
            // Runner: 25% pi gwo pase tèks la
            runnerPaint.set(textPaint); runnerPaint.textSize = textPaint.textSize * 1.25f
            runnerLayout = t.runner?.let { layoutOf(emoji(it.emoji), runnerPaint) }
            runnerFx = t.runner?.let { EmojiFx.first(it.emoji) } ?: Fx.DRIVE
            runnerX = Float.NaN
            offset = Float.NaN
        }
        clockText = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = ticker ?: return
        val scroll = scrollLayout ?: return
        val h = height.toFloat()
        val pad = 14 * density

        // --- Etikèt agoch (egz: 🔴 LIVE, BREAKING NEWS) ---
        var left = 0f
        labelLayout?.let { lab ->
            val boxW = lab.width + pad * 2
            boxPaint.color = colorOf(t.labelBg, 0xFFE50914.toInt())
            canvas.drawRect(0f, 0f, boxW, h, boxPaint)
            canvas.save(); canvas.translate(pad, (h - lab.height) / 2f); lab.draw(canvas); drawGlyphs(canvas, labelGlyphs, lab.height); canvas.restore()
            left = boxW
        }

        // --- Lè adwat ---
        var right = width.toFloat()
        if (t.showClock) {
            val now = clockFmt.format(Date())
            if (now != clockText || clockLayout == null) { clockText = now; clockLayout = layoutOf(now, clockPaint) }
            val clock = clockLayout!!
            val boxW = clock.width + pad * 2
            boxPaint.color = if (t.transparent) Color.TRANSPARENT else 0xE6000000.toInt()
            canvas.drawRect(right - boxW, 0f, right, h, boxPaint)
            canvas.save(); canvas.translate(right - boxW + pad, (h - clock.height) / 2f); clock.draw(canvas); canvas.restore()
            right -= boxW
        }

        // --- Tèks ki defile ant etikèt la ak lè a ---
        val areaW = right - left
        if (areaW <= 0) return
        val w = scroll.width.toFloat()
        if (w <= 0f) return
        if (offset.isNaN()) offset = 0f
        canvas.save()
        canvas.clipRect(RectF(left, 0f, right, h))
        // Premye kopi a kòmanse anvan kwen gòch la pou bann lan toujou plen
        var x = offset % w
        if (x > 0f) x -= w
        while (x < areaW) {
            canvas.save(); canvas.translate(left + x, (h - scroll.height) / 2f); scroll.draw(canvas); drawGlyphs(canvas, scrollGlyphs, scroll.height); canvas.restore()
            x += w
        }
        // Runner (egz: 🏎️💨) ki travèse ba a, pi vit pase tèks la
        runnerLayout?.let { r ->
            val rw = r.width.toFloat()
            val toRight = t.direction == "right"
            if (runnerX.isNaN()) runnerX = if (toRight) -rw else areaW
            canvas.save()
            canvas.translate(left + runnerX, (h - r.height) / 2f)
            // Imoji machin yo gade agoch: vire yo lè yo kouri adwat (flip envèse sa)
            if (toRight != (t.runner?.flip == true)) canvas.scale(-1f, 1f, rw / 2f, 0f)
            runnerPaint.alpha = EmojiFx.transform(canvas, runnerFx, rw, r.height.toFloat(), density)
            r.draw(canvas)
            canvas.restore()
            runnerX += if (toRight) runnerPx else -runnerPx
            if (toRight && runnerX > areaW) runnerX = -rw
            if (!toRight && runnerX < -rw) runnerX = areaW
        }
        canvas.restore()
        offset = (if (t.direction == "right") offset + pxPerFrame else offset - pxPerFrame) % w
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    /** Ajoute tèks la; chak imoji ki gen yon efè vin yon plas vid (GapSpan) ki desine apa. */
    private fun appendAnimated(sb: SpannableStringBuilder, text: String, paint: TextPaint, out: MutableList<AnimGlyph>, on: Boolean) {
        if (!on) { sb.append(text); return }
        for ((_, cl) in EmojiFx.clusters(text)) {
            val fx = EmojiFx.of(cl)
            if (fx == null) { sb.append(cl); continue }
            val p = TextPaint(paint)
            val lay = layoutOf(emoji(cl), p)
            out += AnimGlyph(sb.length, lay, p, fx)
            sb.append('\uFFFC')
            sb.setSpan(GapSpan(lay.width), sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Desine imoji anime yo (canvas la deja deplase sou kòmansman liy lan). */
    private fun drawGlyphs(canvas: Canvas, glyphs: List<AnimGlyph>, lineH: Int) {
        if (glyphs.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        for (g in glyphs) {
            canvas.save()
            canvas.translate(g.x, (lineH - g.layout.height) / 2f)
            g.paint.alpha = EmojiFx.transform(canvas, g.fx, g.layout.width.toFloat(), g.layout.height.toFloat(), density, now)
            g.layout.draw(canvas)
            canvas.restore()
        }
    }
}

/** Yon imoji anime nan ticker a: kote l ye nan liy lan (x) ak desen pa l. */
private class AnimGlyph(val offset: Int, val layout: StaticLayout, val paint: TextPaint, val fx: Fx) { var x = 0f }

/** Plas vid ki gen menm lajè ak imoji a (imoji a desine apa ak animasyon an). */
private class GapSpan(private val w: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return w
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
}
