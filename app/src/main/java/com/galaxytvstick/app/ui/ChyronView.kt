package com.galaxytvstick.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import coil.load
import com.galaxytvstick.app.data.ChyronItem

/**
 * Chyron / Lower third tankou chèn nouvèl yo:
 *
 *   [ BREAKING NEWS ]            ← gwo tit (koulè aksan)
 *   ▌ Jean-Pierre Louis          ← non (gwo, gra)
 *   ▌ Minis Kilti                ← tit / fonksyon
 *
 * - 6 pozisyon (anba/anlè × agoch/mitan/adwat), 4 animasyon (slide, fade, wipe, none).
 * - Chak chyron rete [ChyronItem.duration] segonn (0 = toutan). Si gen plizyè chyron aktif,
 *   yo pase youn apre lòt, ak yon ti poz ant yo.
 * - Menm desen ak "Live Preview" nan panel la (mezi yo an dp/sp pou yon TV 960×540dp).
 */
class ChyronView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm).toInt()
    private val ui = Handler(Looper.getMainLooper())

    private var items: List<ChyronItem> = emptyList()
    private var signature = ""
    private var index = 0
    private var current: LinearLayout? = null
    private var running = false

    /** Espas anba a (pou chyron an rete anlè ticker a). */
    var bottomInset: Int = dp(60f)
        set(value) {
            if (field == value) return
            field = value
            current?.let { (it.layoutParams as? LayoutParams)?.let { lp -> place(lp, items.getOrNull(index)); it.layoutParams = lp } }
        }

    init {
        clipChildren = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Mete lis chyron aktif yo. Si lis la pa chanje, sa k ap pase a kontinye san rekòmanse. */
    fun setChyrons(list: List<ChyronItem>) {
        val sig = list.toString()
        if (sig == signature && running) return
        signature = sig
        items = list
        stopCycle()
        if (list.isEmpty()) { visibility = GONE; return }
        visibility = VISIBLE
        index = 0
        running = true
        showCurrent()
    }

    fun stop() { stopCycle(); signature = "" }

    override fun onDetachedFromWindow() { stopCycle(); super.onDetachedFromWindow() }

    private fun stopCycle() {
        running = false
        ui.removeCallbacksAndMessages(null)
        current?.animate()?.cancel()
        removeAllViews()
        current = null
    }

    // ------------------------------------------------------------ Sik la
    private fun showCurrent() {
        if (!running || items.isEmpty()) return
        val c = items[index % items.size]
        val v = build(c)
        addView(v)
        current = v
        // Tann mezi a pou konnen ki kote pou l soti
        v.visibility = INVISIBLE
        v.post {
            if (current !== v) return@post
            v.visibility = VISIBLE
            animateIn(v, c)
            val stay = when {
                c.duration > 0 -> c.duration * 1000L
                items.size > 1 -> ROTATE_MS // 0 = toutan, men si gen lòt chyron, pase bay yo
                else -> return@post // yon sèl chyron ki la toutan
            }
            ui.postDelayed({ hideCurrent() }, stay)
        }
    }

    private fun hideCurrent() {
        val v = current ?: return
        val c = items[index % items.size]
        animateOut(v, c) {
            if (current !== v) return@animateOut // lis la chanje pandan animasyon an: yon lòt sik deja kòmanse
            removeView(v)
            if (current === v) current = null
            index = (index + 1) % items.size.coerceAtLeast(1)
            // Si chyron an te la toutan (0) epi gen plizyè, pa fè poz
            val gap = if (c.duration > 0) GAP_MS else 400L
            ui.postDelayed({ showCurrent() }, gap)
        }
    }

    // ------------------------------------------------------------ Desen an
    private fun build(c: ChyronItem): LinearLayout {
        val right = c.position.endsWith("right")
        val center = c.position.endsWith("center")
        val text = parse(c.textColor, Color.WHITE)
        val accent = parse(c.accentColor, 0xFFE50914.toInt())
        val bg = parse(c.bgColor, 0xFF0B1630.toInt())
        val bgA = Color.argb((c.opacity.coerceIn(10, 100) * 2.55f).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            gravity = when { right -> Gravity.END; center -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START }
        }
        if (c.headline.isNotBlank()) {
            root.addView(line(c.headline.uppercase(), c.headlineSize, text, bold = true, animate = c.animateEmoji, spacing = 0.08f).apply {
                setBackgroundColor(accent)
                setPadding(dp(12f), dp(3f), dp(12f), dp(3f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (c.name.isNotBlank() || c.title.isNotBlank() || c.logoUrl != null || c.sticker.isNotBlank()) {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false; clipToPadding = false
                gravity = Gravity.CENTER_VERTICAL
                minimumWidth = dp(220f)
                layoutDirection = if (right) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
                background = stripe(bgA, accent, if (center) "bottom" else if (right) "right" else "left")
                val s = dp(5f)
                when {
                    center -> setPadding(dp(26f), dp(8f), dp(26f), dp(9f) + dp(4f))
                    right -> setPadding(dp(22f), dp(8f), dp(14f) + s, dp(9f))
                    else -> setPadding(dp(14f) + s, dp(8f), dp(22f), dp(9f))
                }
                elevation = dp(6f).toFloat()
            }
            // Sticker anime (egz: 🇭🇹 k ap flote, ⚽ k ap vire)
            if (c.sticker.isNotBlank()) {
                body.addView(label(c.sticker, (c.nameSize * 1.5f).toInt(), text, bold = false).apply {
                    maxWidth = Int.MAX_VALUE
                    EmojiFx.animate(this, EmojiFx.first(c.sticker))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12f) })
            }
            c.logoUrl?.let { url ->
                body.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    load(url)
                }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginEnd = dp(12f) })
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false; clipToPadding = false
                layoutDirection = LAYOUT_DIRECTION_LTR
                gravity = when { right -> Gravity.END; center -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START }
            }
            if (c.name.isNotBlank()) col.addView(line(c.name, c.nameSize, text, bold = true, animate = c.animateEmoji))
            if (c.title.isNotBlank()) col.addView(line(c.title, c.titleSize, text, bold = false, animate = c.animateEmoji).apply {
                alpha = 0.88f
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(2f)
            })
            body.addView(col)
            root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        place(lp, c)
        root.layoutParams = lp
        return root
    }

    private fun place(lp: LayoutParams, c: ChyronItem?) {
        val pos = c?.position ?: "bottom-left"
        val v = if (pos.startsWith("top")) Gravity.TOP else Gravity.BOTTOM
        val h = when { pos.endsWith("right") -> Gravity.END; pos.endsWith("center") -> Gravity.CENTER_HORIZONTAL; else -> Gravity.START }
        lp.gravity = v or h
        lp.marginStart = dp(36f); lp.marginEnd = dp(36f)
        lp.topMargin = dp(30f)
        lp.bottomMargin = bottomInset
    }

    /**
     * Yon liy tèks. Si gen imoji anime ladan l (drapo, machin, balon…), liy lan koupe an moso:
     * tèks nòmal yo nan yon TextView, chak imoji anime nan pwòp TextView pa l ki bouje.
     */
    private fun line(s: String, sizeSp: Int, color: Int, bold: Boolean, animate: Boolean, spacing: Float = 0f): View {
        val parts = if (animate) EmojiFx.clusters(s) else emptyList()
        if (parts.none { EmojiFx.of(it.second) != null }) return label(s, sizeSp, color, bold).apply { letterSpacing = spacing }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { row.addView(label(buf.toString(), sizeSp, color, bold).apply { letterSpacing = spacing; maxWidth = Int.MAX_VALUE }); buf.setLength(0) } }
        for ((_, cl) in parts) {
            val fx = EmojiFx.of(cl)
            if (fx == null) { buf.append(cl); continue }
            flush()
            row.addView(label(cl, sizeSp, color, bold).apply { maxWidth = Int.MAX_VALUE; EmojiFx.animate(this, fx) })
        }
        flush()
        return row
    }

    /** TextView ki montre tout imoji yo (AppCompat itilize EmojiCompat otomatikman). */
    private fun label(s: String, sizeSp: Int, color: Int, bold: Boolean) = AppCompatTextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(600f)
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Fon ak opasite + yon ba koulè aksan sou kote a (oswa anba pou pozisyon mitan). Mache sou tout vèsyon Android. */
    private fun stripe(bg: Int, accent: Int, side: String): Drawable = object : Drawable() {
        private val bgPaint = android.graphics.Paint().apply { color = bg }
        private val acPaint = android.graphics.Paint().apply { color = accent }
        private val s = if (side == "bottom") dp(4f) else dp(5f)
        override fun draw(canvas: android.graphics.Canvas) {
            val r = bounds
            canvas.drawRect(r, bgPaint)
            when (side) {
                "left" -> canvas.drawRect(r.left.toFloat(), r.top.toFloat(), (r.left + s).toFloat(), r.bottom.toFloat(), acPaint)
                "right" -> canvas.drawRect((r.right - s).toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), acPaint)
                else -> canvas.drawRect(r.left.toFloat(), (r.bottom - s).toFloat(), r.right.toFloat(), r.bottom.toFloat(), acPaint)
            }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    // ------------------------------------------------------------ Animasyon
    private fun from(c: ChyronItem, v: View): Pair<Float, Float> {
        val p = c.position
        return when {
            p.endsWith("left") -> -(v.width + dp(60f)).toFloat() to 0f
            p.endsWith("right") -> (v.width + dp(60f)).toFloat() to 0f
            p.startsWith("top") -> 0f to -(v.height + dp(60f)).toFloat()
            else -> 0f to (v.height + dp(60f)).toFloat()
        }
    }

    private fun animateIn(v: View, c: ChyronItem) {
        when (c.animation) {
            "slide" -> { val (x, y) = from(c, v); v.translationX = x; v.translationY = y; v.alpha = 0f
                v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(600).setInterpolator(DecelerateInterpolator(2f)).start() }
            "fade" -> { v.alpha = 0f; v.animate().alpha(1f).setDuration(700).start() }
            "wipe" -> wipe(v, reveal = true, end = null)
            else -> Unit
        }
    }

    private fun animateOut(v: View, c: ChyronItem, end: () -> Unit) {
        when (c.animation) {
            "slide" -> { val (x, y) = from(c, v)
                v.animate().translationX(x).translationY(y).alpha(0f).setDuration(450).withEndAction(end).start() }
            "fade" -> v.animate().alpha(0f).setDuration(500).withEndAction(end).start()
            "wipe" -> wipe(v, reveal = false, end = end)
            else -> end()
        }
    }

    /** Wipe: chak moso (gwo tit, kò) parèt/disparèt de gòch a dwat ak clipBounds. */
    private fun wipe(root: View, reveal: Boolean, end: (() -> Unit)?) {
        val parts = (root as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
        if (parts.isEmpty()) { end?.invoke(); return }
        parts.forEachIndexed { i, part ->
            val w = part.width; val h = part.height
            part.clipBounds = if (reveal) Rect(0, 0, 0, h) else Rect(0, 0, w, h)
            ValueAnimator.ofFloat(if (reveal) 0f else 1f, if (reveal) 1f else 0f).apply {
                duration = if (reveal) 750 else 500
                startDelay = if (reveal) i * 120L else (parts.size - 1 - i) * 90L
                addUpdateListener { part.clipBounds = Rect(0, 0, (w * (it.animatedValue as Float)).toInt(), h) }
                if (i == parts.lastIndex || (!reveal && i == 0)) addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        if (reveal) parts.forEach { it.clipBounds = null }
                        if (!reveal && i == 0) end?.invoke()
                    }
                })
                start()
            }
        }
    }

    private fun parse(s: String, d: Int): Int = runCatching { Color.parseColor(s.trim()) }.getOrDefault(d)

    companion object {
        /** Poz ant yon chyron ki fin pase ak pwochen an. */
        const val GAP_MS = 20_000L
        /** Si yon chyron "toutan" (0) pa sèl, li pase bay lòt la apre tan sa a. */
        const val ROTATE_MS = 15_000L
    }
}
