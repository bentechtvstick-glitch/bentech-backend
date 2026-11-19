package com.galaxytvstick.app.ui

import android.app.Activity
import android.widget.Toast
import com.galaxytvstick.app.R

/**
 * Tankou TiviMate: pa gen fenèt "Ou vle soti?". Premye Back la montre yon ti mesaj anba
 * ("Peze Back ankò pou soti"); si kliyan an peze Back ankò nan 2.5 segonn, app la fèmen.
 */
object BackToExit {
    private var lastAt = 0L
    private var toast: Toast? = null

    fun press(a: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastAt < 2_500) {
            toast?.cancel()
            lastAt = 0L
            a.finishAffinity()
            return
        }
        lastAt = now
        toast?.cancel()
        toast = Toast.makeText(a, R.string.press_back_exit, Toast.LENGTH_SHORT).also { it.show() }
    }
}
