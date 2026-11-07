package net.gosmartservices.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animasyon sou gwo afich la (pa bloke anyen, li desine sèlman): zetwal k ap klere, limyè G a k ap bat,
 * ak yon zetwal filan detanzantan. Pozisyon limyè a an fraksyon imaj la (fx, fy) — chak mak bay pa l.
 */
class HeroFx(ctx: Context, private val fx: Float, private val fy: Float) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val rnd = Random(7)
    private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)
    private val stars = List(55) { Star(rnd.nextFloat(), rnd.nextFloat() * 0.95f, (0.8f + rnd.nextFloat() * 1.6f), 0.6f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var running = false
    private var shootAt = 0L
    private var sx = 0f; private var sy = 0f

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun start() { if (!running) { running = true; postInvalidateOnAnimation() } }
    fun stop() { running = false }

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val t = SystemClock.uptimeMillis() / 1000f
        val w = width.toFloat(); val h = height.toFloat()

        // 1) Zetwal k ap klere
        for (s in stars) {
            val a = 0.15f + 0.85f * abs(sin(t * s.speed + s.phase)).pow(3)
            p.color = Color.argb((a * 255).toInt(), 225, 235, 255)
            c.drawCircle(s.x * w, s.y * h, s.r * d, p)
        }

        // 2) Limyè G a k ap bat (respirasyon) + kwa limyè a k ap vire dousman
        val cx = fx * w; val cy = fy * h
        val pulse = 0.5f + 0.5f * sin(t * 2.2f)
        val R = (38f + 26f * pulse) * d
        glow.shader = RadialGradient(cx, cy, R, intArrayOf(Color.argb((150 + 90 * pulse).toInt(), 230, 245, 255), Color.argb(90, 80, 170, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R, glow)
        val len = (60f + 40f * pulse) * d
        val rot = t * 0.35f
        for (k in 0 until 4) {
            val ang = rot + k * (PI / 2).toFloat()
            line.strokeWidth = (if (k % 2 == 0) 2.2f else 1.4f) * d
            line.shader = RadialGradient(cx, cy, len, intArrayOf(Color.argb(220, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawLine(cx, cy, cx + cos(ang) * len, cy + sin(ang) * len, line)
        }

        // 3) Zetwal filan chak ~9 segonn (kote agoch anlè a)
        val now = SystemClock.uptimeMillis()
        if (now > shootAt + 9000) { shootAt = now; sx = 0.08f + rnd.nextFloat() * 0.35f; sy = 0.08f + rnd.nextFloat() * 0.2f }
        val k = (now - shootAt) / 900f
        if (k in 0f..1f) {
            val x0 = (sx + k * 0.22f) * w; val y0 = (sy + k * 0.12f) * h
            val tail = 70f * d
            line.strokeWidth = 2f * d
            line.shader = android.graphics.LinearGradient(x0, y0, x0 - tail, y0 - tail * 0.55f, Color.argb((255 * (1 - k)).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawLine(x0, y0, x0 - tail, y0 - tail * 0.55f, line)
        }
        if (running && isShown) postInvalidateOnAnimation()
    }
}
