package com.galaxytvstick.app.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * AFR (Auto Frame Rate): mete vitès ekran TV a sou vitès videyo a (egz: chanèl 50 → ekran 50 Hz),
 * pou mouvman yo lis san ti sote. Otomatik: li chanje sèlman lè sa nesesè epi lè TV a gen bon mòd la.
 * Lè ekran videyo a fèmen, Android remete vitès nòmal la poukont li.
 */
object Afr {
    fun apply(act: Activity, fps: Float, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 23 || act.isFinishing) return
        val win = act.window ?: return
        if (!enabled || fps !in 10f..70f) { setMode(act, 0); return }
        @Suppress("DEPRECATION")
        val d: Display = act.windowManager.defaultDisplay
        val cur = d.mode
        // Deja bon (egz: 30 fps sou 60 Hz, 25 sou 50): pa chanje anyen → pa gen ekran nwa
        if (fits(cur.refreshRate, fps)) return
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight && fits(it.refreshRate, fps) }
            .maxByOrNull { it.refreshRate } ?: return // TV a pa gen mòd sa a: kite l jan l ye
        if (win.attributes.preferredDisplayModeId != best.modeId) setMode(act, best.modeId)
    }

    /** Vitès ekran an se yon miltip vitès videyo a (24→24/48, 25→50, 29.97→59.94, 50→50, 60→60). */
    private fun fits(hz: Float, fps: Float): Boolean {
        val k = (hz / fps).roundToInt()
        return k >= 1 && abs(hz - k * fps) < 0.12f * k
    }

    private fun setMode(act: Activity, id: Int) {
        val w = act.window ?: return
        if (w.attributes.preferredDisplayModeId == id) return
        w.attributes = w.attributes.apply { preferredDisplayModeId = id }
    }
}
