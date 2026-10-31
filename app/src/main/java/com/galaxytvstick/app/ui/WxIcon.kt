package com.galaxytvstick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Ikòn meteyo a desinen ak kòd (pa yon imoji): kèk TV (Android ansyen) pa gen imoji 🌤️ ⛈️ 🌫️…
 * epi yo montre yon ti kare olye. Sa a parèt menm jan sou tout TV.
 */
class WxIcon(context: Context) : View(context) {
    private enum class K { SUN, MOON, SUN_CLOUD, CLOUD, FOG, SUN_RAIN, RAIN, SNOW, STORM, THERMO }
    private var kind = K.SUN_CLOUD
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // pou lonbraj la

    /** [emoji] se sa sèvè a voye (☀️ 🌙 🌤️ ☁️ 🌫️ 🌦️ 🌧️ 🌨️ ⛈️…). */
    fun set(emoji: String) {
        val e = emoji.replace("️", "")
        kind = when {
            e.contains("☀") -> K.SUN
            e.contains("🌙") || e.contains("🌛") || e.contains("🌜") -> K.MOON
            e.contains("🌤") || e.contains("⛅") || e.contains("🌥") -> K.SUN_CLOUD
            e.contains("🌫") || e.contains("🌁") -> K.FOG
            e.contains("🌦") -> K.SUN_RAIN
            e.contains("🌧") || e.contains("☔") || e.contains("🌂") -> K.RAIN
            e.contains("🌨") || e.contains("❄") || e.contains("☃") -> K.SNOW
            e.contains("⛈") || e.contains("🌩") || e.contains("⚡") -> K.STORM
            e.contains("☁") -> K.CLOUD
            e.contains("🌡") -> K.THERMO
            else -> K.SUN_CLOUD
        }
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width, height).toFloat(); if (s <= 0f) return
        cv.save(); cv.translate((width - s) / 2f, (height - s) / 2f)
        p.setShadowLayer(s * 0.08f, 0f, s * 0.03f, 0xCC000000.toInt())
        when (kind) {
            K.SUN -> sun(cv, s * 0.5f, s * 0.5f, s * 0.22f)
            K.MOON -> moon(cv, s)
            K.SUN_CLOUD -> { sun(cv, s * 0.36f, s * 0.36f, s * 0.17f); cloud(cv, s, 0.12f) }
            K.CLOUD -> cloud(cv, s, 0f)
            K.FOG -> { cloud(cv, s, -0.08f); lines(cv, s) }
            K.SUN_RAIN -> { sun(cv, s * 0.36f, s * 0.3f, s * 0.15f); cloud(cv, s, -0.02f); drops(cv, s, 0xFF6EC1FF.toInt()) }
            K.RAIN -> { cloud(cv, s, -0.1f); drops(cv, s, 0xFF6EC1FF.toInt()) }
            K.SNOW -> { cloud(cv, s, -0.1f); flakes(cv, s) }
            K.STORM -> { cloud(cv, s, -0.1f, 0xFFB8C2D6.toInt()); bolt(cv, s) }
            K.THERMO -> thermo(cv, s)
        }
        cv.restore()
    }

    private fun sun(cv: Canvas, cx: Float, cy: Float, r: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.28f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFFFC83D.toInt()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
            cv.drawLine(cx + c * r * 1.4f, cy + sn * r * 1.4f, cx + c * r * 1.85f, cy + sn * r * 1.85f, p)
        }
        p.style = Paint.Style.FILL; p.color = 0xFFFFC83D.toInt(); cv.drawCircle(cx, cy, r, p)
    }

    private fun moon(cv: Canvas, s: Float) {
        path.reset()
        path.addCircle(s * 0.5f, s * 0.5f, s * 0.3f, Path.Direction.CW)
        val cut = Path().apply { addCircle(s * 0.64f, s * 0.38f, s * 0.26f, Path.Direction.CW) }
        path.op(cut, Path.Op.DIFFERENCE)
        p.style = Paint.Style.FILL; p.color = 0xFFF5E6A8.toInt(); cv.drawPath(path, p)
    }

    /** Nwaj: [dy] deplase l anwo (-) oswa anba (+), an fraksyon gwosè a. */
    private fun cloud(cv: Canvas, s: Float, dy: Float, color: Int = Color.WHITE) {
        val y = s * dy
        path.reset()
        rect.set(s * 0.14f, s * 0.52f + y, s * 0.86f, s * 0.78f + y); path.addRoundRect(rect, s * 0.13f, s * 0.13f, Path.Direction.CW)
        path.addCircle(s * 0.38f, s * 0.52f + y, s * 0.16f, Path.Direction.CW)
        path.addCircle(s * 0.6f, s * 0.47f + y, s * 0.2f, Path.Direction.CW)
        p.style = Paint.Style.FILL; p.color = color; cv.drawPath(path, p)
    }

    private fun drops(cv: Canvas, s: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f; p.strokeCap = Paint.Cap.ROUND; p.color = color
        for (x in floatArrayOf(0.34f, 0.5f, 0.66f)) cv.drawLine(s * x, s * 0.76f, s * (x - 0.05f), s * 0.92f, p)
    }

    private fun flakes(cv: Canvas, s: Float) {
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        for ((x, y) in listOf(0.32f to 0.8f, 0.5f to 0.88f, 0.68f to 0.8f)) cv.drawCircle(s * x, s * y, s * 0.045f, p)
    }

    private fun lines(cv: Canvas, s: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.055f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFD5DBE6.toInt()
        cv.drawLine(s * 0.18f, s * 0.8f, s * 0.82f, s * 0.8f, p)
        cv.drawLine(s * 0.28f, s * 0.9f, s * 0.72f, s * 0.9f, p)
    }

    private fun bolt(cv: Canvas, s: Float) {
        path.reset()
        path.moveTo(s * 0.54f, s * 0.6f); path.lineTo(s * 0.4f, s * 0.8f); path.lineTo(s * 0.5f, s * 0.8f)
        path.lineTo(s * 0.44f, s * 0.97f); path.lineTo(s * 0.64f, s * 0.74f); path.lineTo(s * 0.53f, s * 0.74f); path.close()
        p.style = Paint.Style.FILL; p.color = 0xFFFFD43B.toInt(); cv.drawPath(path, p)
    }

    private fun thermo(cv: Canvas, s: Float) {
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        rect.set(s * 0.42f, s * 0.12f, s * 0.58f, s * 0.7f); cv.drawRoundRect(rect, s * 0.08f, s * 0.08f, p)
        cv.drawCircle(s * 0.5f, s * 0.74f, s * 0.15f, p)
        p.color = 0xFFFF5A5A.toInt(); cv.drawCircle(s * 0.5f, s * 0.74f, s * 0.1f, p)
        rect.set(s * 0.47f, s * 0.35f, s * 0.53f, s * 0.72f); cv.drawRect(rect, p)
    }
}
