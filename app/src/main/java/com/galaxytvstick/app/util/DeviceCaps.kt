package com.galaxytvstick.app.util

import android.media.MediaCodecList

/**
 * Tcheke ki pi gwo rezolisyon dekodè videyo aparèy la ka jwe.
 * 8K mache sèlman si aparèy la gen yon dekodè ki sipòte 7680x4320.
 */
object DeviceCaps {

    private val sizes = listOf(
        Triple(7680, 4320, "8K"),
        Triple(3840, 2160, "4K"),
        Triple(1920, 1080, "Full HD"),
        Triple(1280, 720, "HD")
    )

    val maxSupported: String by lazy {
        try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            for ((w, h, label) in sizes) {
                val ok = codecs.any { info ->
                    !info.isEncoder && info.supportedTypes.any { type ->
                        type.startsWith("video/") && runCatching {
                            info.getCapabilitiesForType(type).videoCapabilities
                                ?.isSizeSupported(w, h) == true
                        }.getOrDefault(false)
                    }
                }
                if (ok) return@lazy label
            }
            "SD"
        } catch (e: Exception) {
            "?"
        }
    }

    /** Bay yon etikèt selon wotè videyo a (egz: 4320 → 8K). */
    fun labelForHeight(height: Int): String = when {
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height > 0 -> "SD"
        else -> ""
    }
}
