package com.galaxytvstick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextPaint
import androidx.appcompat.widget.AppCompatTextView

/**
 * Polis ak estil tèks panel la (logo bug, ticker, chyron). Menm lis ak panel la (gfx.js):
 * estil: plain, classic, outline, glow, neon, box, gradient, metal, fire, retro, 3d
 * polis: sans, condensed, black, serif, mono, script + polis ki nan assets/fonts (Bebas, Oswald, Anton…)
 */
object TextFx {
    private val bundled = setOf("bebas", "oswald", "anton", "teko", "russo", "orbitron", "audiowide", "righteous",
        "bangers", "marker", "pacifico", "lobster", "greatvibes", "playfair", "abril")
    /** Polis varyab yo (pwa a chwazi ak "wght"). */
    private val variable = mapOf("oswald" to 700, "orbitron" to 700, "teko" to 600, "playfair" to 800)
    private val cache = HashMap<String, Typeface>()

    fun typeface(ctx: Context, font: String, italic: Boolean, bold: Boolean = true): Typeface {
        val key = "$font|$italic|$bold"
        cache[key]?.let { return it }
        val base: Typeface = if (font in bundled) loadAsset(ctx, font) ?: Typeface.DEFAULT_BOLD else {
            val family = when (font) {
                "condensed" -> "sans-serif-condensed"; "black" -> "sans-serif-black"; "serif" -> "serif"
                "mono" -> "monospace"; "script" -> "cursive"; else -> "sans-serif"
            }
            Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        // Polis ki deja epè (Bebas, Anton…) pa bezwen "bold" sentetik: sèlman italik si yo mande l
        val style = when {
            font in bundled -> if (italic) Typeface.ITALIC else Typeface.NORMAL
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val tf = if (style == Typeface.NORMAL && font in bundled) base else Typeface.create(base, style)
        cache[key] = tf
        return tf
    }

    private fun loadAsset(ctx: Context, font: String): Typeface? = runCatching {
        val path = "fonts/$font.ttf"
        val w = variable[font]
        if (w != null && Build.VERSION.SDK_INT >= 26)
            Typeface.Builder(ctx.assets, path).setFontVariationSettings("'wght' $w").build()
        else Typeface.createFromAsset(ctx.assets, path)
    }.getOrNull()

    fun color(s: String?, d: Int) = runCatching { Color.parseColor(s) }.getOrDefault(d)

    /** Estil ki bezwen yon degrade nan lèt yo. */
    fun isGradient(style: String) = style == "gradient" || style == "metal" || style == "fire"

    fun gradientShader(style: String, c1: Int, c2: Int, top: Float, bottom: Float): Shader? = when (style) {
        "gradient" -> LinearGradient(0f, top, 0f, bottom, c1, c2, Shader.TileMode.CLAMP)
        "metal" -> LinearGradient(0f, top, 0f, bottom, intArrayOf(Color.WHITE, c1, c2, c1, Color.WHITE),
            floatArrayOf(0f, 0.35f, 0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        "fire" -> LinearGradient(0f, top, 0f, bottom, intArrayOf(0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFFE53935.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        else -> null
    }

    /**
     * Estil sou yon Paint (ticker a desine tèks li dirèkteman sou canvas la).
     * Kontou ak 3D pa posib ak yon sèl Paint: yo vin yon lonbraj ki gen dezyèm koulè a.
     */
    fun applyPaint(p: TextPaint, style: String, c2: Int, density: Float, lineTop: Float, lineBottom: Float, c1: Int = Color.WHITE) {
        val k = (p.textSize / 14f).coerceAtLeast(1f)
        p.shader = gradientShader(style, c1, c2, lineTop, lineBottom)
        when (style) {
            "plain" -> p.clearShadowLayer()
            "glow" -> p.setShadowLayer((k * 6).coerceAtMost(25f), 0f, 0f, c2)
            "neon" -> p.setShadowLayer((k * 8).coerceAtMost(25f), 0f, 0f, c2)
            "outline" -> p.setShadowLayer(k * 1.5f, 0f, 0f, c2)
            "retro" -> p.setShadowLayer(0.5f, k * 2, k * 2, c2)
            "3d" -> p.setShadowLayer(0.5f, k * 2.5f, k * 2.5f, c2)
            "fire" -> p.setShadowLayer((k * 4).coerceAtMost(25f), 0f, 0f, 0xFFFF6D00.toInt())
            "gradient", "metal" -> p.setShadowLayer(3 * density, 0f, 2 * density, 0x99000000.toInt())
            else -> p.setShadowLayer(3f * density, 0f, 1.5f * density, 0xE6000000.toInt())
        }
    }
}

/** TextView ki ka desine tout estil yo (kontou ak 3D desine plizyè fwa). */
class FxTextView(context: Context, private val style: String, private val c1: Int, private val c2: Int) : AppCompatTextView(context) {
    private val dp = resources.displayMetrics.density
    private var passes = false

    init {
        setTextColor(c1)
        includeFontPadding = false
    }

    /** Rele apre setTextSize. */
    fun applyStyle(): FxTextView {
        val px = textSize
        val k = (px / 14f).coerceAtLeast(1f)
        when (style) {
            "plain", "outline" -> setShadowLayer(0f, 0f, 0f, 0)
            "3d" -> { setShadowLayer(0f, 0f, 0f, 0); val d = k.toInt() * 3 + 1; setPadding(paddingLeft, paddingTop, paddingRight + d, paddingBottom + d) }
            "glow" -> setShadowLayer((k * 6).coerceAtMost(25f), 0f, 0f, c2)
            "neon" -> setShadowLayer((k * 8).coerceAtMost(25f), 0f, 0f, c2)
            "fire" -> setShadowLayer((k * 4).coerceAtMost(25f), 0f, 0f, 0xFFFF6D00.toInt())
            "retro" -> { setShadowLayer(0.5f, k * 2, k * 2, c2); setPadding(paddingLeft, paddingTop, paddingRight + (k * 2).toInt() + 1, paddingBottom + (k * 2).toInt() + 1) }
            "box" -> {
                setShadowLayer(0f, 0f, 0f, 0)
                background = GradientDrawable().apply { setColor(c2); cornerRadius = px * 0.25f }
                val ph = (px * 0.45f).toInt(); val pv = (px * 0.18f).toInt()
                setPadding(ph, pv, ph, pv)
            }
            "gradient", "metal" -> setShadowLayer(3 * dp, 0f, 2 * dp, 0x99000000.toInt())
            else -> setShadowLayer(3 * dp, 0f, 2 * dp, 0xB3000000.toInt())
        }
        return this
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = TextFx.gradientShader(style, c1, c2, paddingTop.toFloat(), (h - paddingBottom).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        when (style) {
            // Kontou: tèks la an "stroke" ak dezyèm koulè a anba, epi tèks nòmal la pa anwo
            "outline" -> {
                passes = true
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = (textSize / 7f).coerceAtLeast(2f)
                setTextColor(c2)
                super.onDraw(canvas)
                paint.style = Paint.Style.FILL
                setTextColor(c1)
                passes = false
                super.onDraw(canvas)
            }
            // 3D: plizyè kopi dezyèm koulè a youn dèyè lòt (an dyagonal), epi tèks la pa anwo
            "3d" -> {
                val depth = (textSize / 14f).coerceAtLeast(1f).toInt() * 3
                passes = true
                setTextColor(c2)
                for (i in depth downTo 1) { canvas.save(); canvas.translate(i.toFloat(), i.toFloat()); super.onDraw(canvas); canvas.restore() }
                setTextColor(c1)
                passes = false
                super.onDraw(canvas)
            }
            else -> super.onDraw(canvas)
        }
    }

    // setTextColor nan onDraw pa dwe relanse yon lòt desen (bouk san fen)
    override fun invalidate() { if (!passes) super.invalidate() }
}
