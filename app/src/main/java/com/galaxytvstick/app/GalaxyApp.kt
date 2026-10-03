package com.galaxytvstick.app

import android.app.Application
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

/**
 * Chaje font imoji ki anndan app la (Noto Color Emoji), pou tout imoji ak senbòl
 * panel la voye (ticker, mesaj, pop-up) parèt kòrèkteman sou tout Fire TV / Android TV,
 * menm sou ansyen vèsyon Android ki pa konnen nouvo imoji yo.
 */
class GalaxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EmojiCompat.init(
            BundledEmojiCompatConfig(this)
                .setReplaceAll(true) // menm desen imoji sou tout aparèy
        )
    }
}
