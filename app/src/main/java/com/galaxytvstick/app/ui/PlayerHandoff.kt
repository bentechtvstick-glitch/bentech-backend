package com.galaxytvstick.app.ui

import android.os.SystemClock
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer

/**
 * Pase videyo a soti nan ti fenèt gid la rive sou gwo ekran an SAN rekonekte (tankou TiviMate):
 * gid la kite player li a la, epi PlayerActivity pran menm player a ki deja ap jwe chanèl la.
 * Si PlayerActivity pa pran l nan 5 segonn, li lage poukont li.
 */
object PlayerHandoff {
    private var player: ExoPlayer? = null
    private var factory: OkHttpDataSource.Factory? = null
    private var streamId = 0
    private var at = 0L

    fun put(p: ExoPlayer, f: OkHttpDataSource.Factory?, id: Int) {
        release()
        player = p; factory = f; streamId = id; at = SystemClock.elapsedRealtime()
    }

    /** Player ki t ap jwe chanèl [id] nan gid la, oswa null. */
    fun take(id: Int): Pair<ExoPlayer, OkHttpDataSource.Factory?>? {
        val p = player ?: return null
        if (streamId != id || SystemClock.elapsedRealtime() - at > 5_000) { release(); return null }
        val f = factory
        player = null; factory = null
        return p to f
    }

    fun release() {
        runCatching { player?.release() }
        player = null; factory = null
    }
}
