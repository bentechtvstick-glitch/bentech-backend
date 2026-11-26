package com.galaxytvstick.app.ui

import com.galaxytvstick.app.BuildConfig

/** Diferans ant mak yo ki pa ka fèt ak resous sèlman. */
object Brand {
    val isMagic: Boolean get() = BuildConfig.BRAND == "magic"
    /** Premye ekran apre playlist la chaje: Magic gen akèy "TV ou a" pa l; lòt mak yo louvri gid la (tankou TiviMate). */
    val home: Class<*> get() = if (isMagic) MagicHomeActivity::class.java else EpgActivity::class.java
}
