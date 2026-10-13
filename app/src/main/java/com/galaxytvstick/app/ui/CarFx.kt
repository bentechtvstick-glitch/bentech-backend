package com.galaxytvstick.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.sin

/**
 * Yon machin desine (pa yon imoji): kò ki sote, wou k ap vire, liy vitès dèyè l.
 * Nan panel la, "runner" = "car:E50914" (koulè a an hex). Menm desen ak public/galaxy/fx.js.
 * Machin nan gade agoch (menm jan ak imoji machin yo), konsa règ "miwa" a menm.
 */
object CarFx {
    const val VW = 122f
    const val VH = 44f
    const val RATIO = VW / VH

    private val RE = Regex("^car:#?([0-9a-fA-F]{6})$")

    /** Koulè machin nan si tèks la se "car:RRGGBB", sinon null. */
    fun parse(s: String?): Int? {
        val m = RE.matchEntire(s?.trim() ?: return null) ?: return null
        return runCatching { Color.parseColor("#" + m.groupValues[1]) }.getOrNull()
    }

    private val body = Path().apply {
        moveTo(8f, 31f); cubicTo(4f, 31f, 3f, 28f, 3.5f, 25f); lineTo(5f, 21f); cubicTo(6f, 18.5f, 9f, 17.5f, 13f, 17f)
        lineTo(30f, 15f); lineTo(40f, 6f); cubicTo(42f, 4.5f, 44f, 4f, 47f, 4f); lineTo(72f, 4f)
        cubicTo(75f, 4f, 77f, 5f, 79f, 7f); lineTo(88f, 15f); lineTo(101f, 16.5f); cubicTo(105f, 17f, 107f, 19f, 107f, 23f)
        lineTo(107f, 28f); cubicTo(107f, 30f, 106f, 31f, 103f, 31f); close()
    }
    private val winFront = Path().apply { moveTo(33f, 15f); lineTo(42f, 7.5f); lineTo(55f, 7.5f); lineTo(55f, 15f); close() }
    private val winRear = Path().apply {
        moveTo(59f, 15f); lineTo(59f, 7.5f); lineTo(72f, 7.5f); cubicTo(74f, 7.5f, 75f, 8f, 76f, 9f); lineTo(82f, 15f); close()
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /** Desine machin nan nan yon bwat (h × h·RATIO) ki kòmanse nan (0,0) sou canvas la. */
    fun draw(canvas: Canvas, h: Float, color: Int, t: Long = SystemClock.uptimeMillis()) {
        val k = h / VH
        canvas.save()
        canvas.scale(k, k)
        p.style = Paint.Style.FILL
        // Lonbraj
        p.color = 0x59000000; rect.set(10f, 38.5f, 102f, 43.5f); canvas.drawOval(rect, p)
        // Liy vitès dèyè l (bò dwat, paske machin nan gade agoch)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 1.6f
        for ((i, y) in floatArrayOf(12f, 20f, 27f).withIndex()) {
            val ph = ((t + i * 110L) % 330L) / 330f
            p.color = Color.argb((200 * (1 - ph)).toInt(), 255, 255, 255)
            val x0 = 109f + ph * 6f
            canvas.drawLine(x0, y, x0 + 7f - i, y, p)
        }
        // Kò a sote yon ti kras
        canvas.save()
        canvas.translate(0f, -0.7f * (0.5f + 0.5f * sin(2 * PI * (t % 300) / 300.0).toFloat()))
        p.style = Paint.Style.FILL
        p.color = color; canvas.drawPath(body, p)
        p.color = 0xE6BFE6FF.toInt(); canvas.drawPath(winFront, p); canvas.drawPath(winRear, p)
        p.color = 0xFFFFE066.toInt(); rect.set(3.5f, 21f, 8.5f, 24f); canvas.drawRoundRect(rect, 1f, 1f, p)
        p.color = 0xFFFF3B3B.toInt(); rect.set(104f, 19f, 107f, 23f); canvas.drawRect(rect, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f; p.color = 0x40FFFFFF; canvas.drawLine(8f, 22f, 104f, 22f, p)
        p.color = 0x40000000; canvas.drawLine(57f, 16f, 57f, 29f, p)
        canvas.restore()
        // Wou yo k ap vire (sans envès lèzegwi pou yon machin k ap ale agoch)
        val ang = -360f * (t % 400) / 400f
        for (cx in floatArrayOf(26f, 86f)) {
            p.style = Paint.Style.FILL
            p.color = 0xFF111111.toInt(); canvas.drawCircle(cx, 31f, 8f, p)
            p.color = 0xFFC9CED6.toInt(); canvas.drawCircle(cx, 31f, 4.6f, p)
            canvas.save(); canvas.rotate(ang, cx, 31f)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; p.color = 0xFF555555.toInt()
            for (a in 0 until 3) { canvas.drawLine(cx - 4.4f, 31f, cx + 4.4f, 31f, p); canvas.rotate(60f, cx, 31f) }
            canvas.restore()
            p.style = Paint.Style.FILL; p.color = 0xFF555555.toInt(); canvas.drawCircle(cx, 31f, 1.5f, p)
        }
        canvas.restore()
    }
}
