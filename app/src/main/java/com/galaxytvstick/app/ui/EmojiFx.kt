package com.galaxytvstick.app.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Animasyon imoji yo — menm règ ak panel la (public/galaxy/fx.js):
 *  WAVE drapo flote · DRIVE machin/avyon kouri · SPIN balon vire · PULSE kè/🔥 bat
 *  BLINK 🔴/🚨 kliyote · SHAKE 📢/🔔 souke · BOUNCE moun/bèt sote
 */
enum class Fx { WAVE, DRIVE, BOUNCE, SPIN, PULSE, BLINK, SHAKE }

object EmojiFx {
    private val groups = mapOf(
        Fx.DRIVE to "🏎🚗🚕🚙🚓🚑🚒🚐🛻🚚🚛🚜🏍🛵🚲🛴🚌🚎🚍🚘🚖🚔🚂🚆🚄🚅🚈✈🛩🚀🛸🚁⛵🚤🛥🚢🛶🛺🚋",
        Fx.BOUNCE to "🐎🏇🏃🐕🐆🐇🦘⛹🤸💃🕺🐸🦅🐦🐬🐒🦓🦒🐘👟🤾🏄🚴",
        Fx.SPIN to "⚽🏀🏈⚾🎾🏐🏉🥎🎱🌀🎡💿📀🪀⚙🌍🌎🌏🪙🥏🎯",
        Fx.PULSE to "❤🧡💛💚💙💜🖤🤍🤎💖💗💓💕💞❣🔥⭐🌟✨💥💯🎉🎊🏆🥇💰💎👑🎁💝",
        Fx.BLINK to "🔴🟥🚨🆕⚡❗‼⚠🟢🟡🔵🆓🆙📍⭕",
        Fx.SHAKE to "📢📣📞☎📱⏰🔊🔔👋🙌👏🥁🎺📯🎤📺",
    )
    private val map: Map<Int, Fx> = buildMap {
        for ((fx, chars) in groups) {
            var i = 0
            while (i < chars.length) { val cp = chars.codePointAt(i); put(cp, fx); i += Character.charCount(cp) }
        }
    }
    private val waveStarts = setOf(0x1F3F4, 0x1F3F3, 0x1F3C1, 0x1F6A9, 0x1F38C) // 🏴 🏳 🏁 🚩 🎌

    /** Efè yon grap imoji (null = pa anime). */
    fun of(cluster: String): Fx? {
        if (cluster.isEmpty()) return null
        val cp = cluster.codePointAt(0)
        if (cp in 0x1F1E6..0x1F1FF || cp in waveStarts) return Fx.WAVE
        return map[cp]
    }

    /** Premye efè nan yon tèks (pou "runner" oswa sticker), BOUNCE si pa gen. */
    fun first(text: String): Fx = clusters(text).firstNotNullOfOrNull { of(it.second) } ?: Fx.BOUNCE

    /** Koupe tèks la an grap (lèt, imoji konplè, drapo) ak pozisyon yo. */
    fun clusters(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        if (Build.VERSION.SDK_INT >= 24) {
            val it = android.icu.text.BreakIterator.getCharacterInstance(); it.setText(text)
            var s = it.first(); var e = it.next()
            while (e != android.icu.text.BreakIterator.DONE) { out += s to text.substring(s, e); s = e; e = it.next() }
        } else {
            // Android 5–6: drapo = 2 lèt rejyonal; rès la pa kodpwen + FE0F/ZWJ
            var i = 0
            while (i < text.length) {
                val start = i
                var cp = text.codePointAt(i); i += Character.charCount(cp)
                if (cp in 0x1F1E6..0x1F1FF && i < text.length && text.codePointAt(i) in 0x1F1E6..0x1F1FF) i += 2
                while (i < text.length) {
                    cp = text.codePointAt(i)
                    if (cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) { i += Character.charCount(cp); continue }
                    if (cp == 0x200D && i + 1 < text.length) { i += 1; i += Character.charCount(text.codePointAt(i)); continue }
                    break
                }
                out += start to text.substring(start, i)
            }
        }
        return out
    }

    /**
     * Aplike efè a sou canvas la pou yon imoji w × h. Retounen alpha (0–255) pou BLINK.
     * Rele l ant canvas.save() ak canvas.restore().
     */
    fun transform(canvas: Canvas, fx: Fx, w: Float, h: Float, density: Float, t: Long = SystemClock.uptimeMillis()): Int {
        fun wave(period: Long) = sin(2 * PI * (t % period) / period).toFloat()
        when (fx) {
            Fx.WAVE -> { val s = wave(1500); canvas.rotate(s * 5.5f, w * 0.12f, h * 0.88f); canvas.skew(0f, -s * 0.09f); canvas.scale(1f - 0.05f * (1 + s), 1f, w * 0.12f, h / 2) }
            Fx.DRIVE -> { val s = wave(480); canvas.translate((1 + s) * density, -abs(s) * 2 * density); canvas.rotate(s * 3f, w / 2, h / 2) }
            Fx.BOUNCE -> canvas.translate(0f, -abs(wave(1100)) * h * 0.18f)
            Fx.SPIN -> canvas.rotate(360f * (t % 1300) / 1300f, w / 2, h / 2)
            Fx.PULSE -> { val k = 1f + 0.11f * (1 + wave(1000)); canvas.scale(k, k, w / 2, h / 2) }
            Fx.SHAKE -> { val p = (t % 1200) / 1200f; val a = if (p < 0.5f) sin(p * 8 * PI).toFloat() * 14f else 0f; canvas.rotate(a, w / 2, h * 0.9f) }
            Fx.BLINK -> return (255 * (0.625f + 0.375f * sin(2 * PI * (t % 1000) / 1000 + PI / 2)).toFloat()).toInt()
        }
        return 255
    }

    /** Anime yon View (pou chyron an). Animasyon an kanpe poukont li lè View a retire sou ekran an. */
    fun animate(view: View, fx: Fx) {
        val period = when (fx) { Fx.WAVE -> 1500L; Fx.DRIVE -> 480L; Fx.BOUNCE -> 1100L; Fx.SPIN -> 1300L; Fx.PULSE -> 1000L; Fx.BLINK -> 1000L; Fx.SHAKE -> 1200L }
        val d = view.resources.displayMetrics.density
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = period; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                val s = sin(2 * PI * p).toFloat()
                view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
                when (fx) {
                    Fx.WAVE -> { view.pivotX = view.width * 0.12f; view.pivotY = view.height * 0.88f; view.rotation = s * 5.5f; view.scaleX = 1f - 0.05f * (1 + s); view.rotationY = s * 14f }
                    Fx.DRIVE -> { view.translationX = (1 + s) * d; view.translationY = -abs(s) * 2 * d; view.rotation = s * 3f }
                    Fx.BOUNCE -> view.translationY = -abs(s) * view.height * 0.18f
                    Fx.SPIN -> view.rotation = 360f * p
                    Fx.PULSE -> { val k = 1f + 0.11f * (1 + s); view.scaleX = k; view.scaleY = k }
                    Fx.SHAKE -> { view.pivotY = view.height * 0.9f; view.rotation = if (p < 0.5f) sin(p * 8 * PI).toFloat() * 14f else 0f }
                    Fx.BLINK -> view.alpha = 0.625f + 0.375f * sin(2 * PI * p + PI / 2).toFloat()
                }
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (!anim.isStarted) anim.start() }
            override fun onViewDetachedFromWindow(v: View) { anim.cancel() }
        })
        if (view.isAttachedToWindow) anim.start()
    }
}
