package com.galaxytvstick.app.ui

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

/**
 * Menm "renderers" pou tout lektè yo (chanèl, preview gid la, fim/seri):
 * - si dekodè TV a echwe, eseye yon lòt (decoder fallback)
 * - FFmpeg pou son TV a pa konnen (AC3/E-AC3/DTS/TrueHD): san sa, kèk fim jwe san son.
 *   "ON" = dekodè TV a an premye, FFmpeg sèlman lè TV a pa ka.
 */
@UnstableApi
object Renderers {
    fun factory(ctx: Context): DefaultRenderersFactory = DefaultRenderersFactory(ctx)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

    /**
     * Pi bon kalite imaj la toujou: si sous la gen plizyè kalite (HLS), pran pi wo a TV a ka jwe
     * olye pou l kòmanse ba epi monte dousman.
     */
    fun trackSelector(ctx: Context) = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(ctx).apply {
        setParameters(buildUponParameters()
            .setForceHighestSupportedBitrate(true)
            .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
            .setExceedVideoConstraintsIfNecessary(true)
            .setExceedRendererCapabilitiesIfNecessary(true))
    }

    /** Kòmanse ak yon estimasyon vitès entènèt wo (20 Mb/s) pou premye imaj yo pa flou. */
    fun bandwidth(ctx: Context) = androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(ctx)
        .setInitialBitrateEstimate(20_000_000L).build()
}
