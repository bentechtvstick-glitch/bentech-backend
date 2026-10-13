package com.galaxytvstick.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.emoji2.text.EmojiCompat

/**
 * Yon ti wout kote yon imoji (egz: 🏎️💨) ap kouri de yon bò a lòt sou chyron an.
 * Menm vitès ak preview panel la: vitès × 20 dp pa segonn.
 */
class RunnerTrackView(
    context: Context,
    emoji: String,
    sizeSp: Float,
    speed: Int,
    private val toRight: Boolean,
    flip: Boolean
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
    }
    private val layout: StaticLayout
    private val fx = EmojiFx.first(emoji)
    /** Imoji machin yo gade agoch: vire yo lè yo kouri adwat (flip envèse sa). */
    private val mirror = toRight != flip
    private val pxPerMs = speed.coerceIn(1, 10) * 20f * density / 1000f
    private var x = Float.NaN
    /** Koulè machin desine a si runner = "car:RRGGBB". */
    private val car = CarFx.parse(emoji)
    private var last = 0L

    init {
        val cs: CharSequence = runCatching {
            if (EmojiCompat.get().loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) EmojiCompat.get().process(emoji) ?: emoji else emoji
        }.getOrDefault(emoji)
        val w = Layout.getDesiredWidth(cs, paint).toInt().coerceAtLeast(1) + 2
        layout = if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(cs, 0, cs.length, paint, w).setIncludePad(false).setMaxLines(1).build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(cs, paint, w, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); if (w <= 0f) return
        val carH = height * 0.92f
        val rw = if (car != null) carH * car.ratio else layout.width.toFloat()
        val now = SystemClock.uptimeMillis()
        if (x.isNaN()) { x = if (toRight) -rw else w; last = now }
        val dt = (now - last).coerceIn(0L, 100L); last = now
        x += if (toRight) dt * pxPerMs else -dt * pxPerMs
        if (toRight && x > w) x = -rw
        if (!toRight && x < -rw) x = w
        canvas.save()
        canvas.clipRect(0f, 0f, w, height.toFloat())
        if (car != null) {
            canvas.translate(x, (height - carH) / 2f)
            if (mirror) canvas.scale(-1f, 1f, rw / 2f, 0f)
            CarFx.draw(canvas, carH, car, now)
        } else {
            canvas.translate(x, (height - layout.height) / 2f)
            if (mirror) canvas.scale(-1f, 1f, rw / 2f, 0f)
            paint.alpha = EmojiFx.transform(canvas, fx, rw, layout.height.toFloat(), density, now)
            layout.draw(canvas)
        }
        canvas.restore()
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
