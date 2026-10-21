package com.galaxytvstick.app.ui

/**
 * Imaj pi klè: anpil founisè bay afich/fon ki soti nan TMDB nan ti gwosè (w185, w300…).
 * Sou yon gwo ekran TV yo parèt flou lè yo agrandi. Nou mande menm imaj la nan pi gwo gwosè.
 */
object Img {
    private val tmdb = Regex("(image\\.tmdb\\.org/t/p/)(w\\d+|original)(/)")

    /** Afich nan lis yo: w500 (klè sou TV, pa twò lou pou dè santèn afich). Gwo afich paj detay la: w780. */
    fun poster(url: String?, big: Boolean = false): String? = url?.let { tmdb.replace(it) { m -> "${m.groupValues[1]}${if (big) "w780" else "w500"}${m.groupValues[3]}" } }

    /** Fon (backdrop) plen ekran: w1280 (1080p). */
    fun backdrop(url: String?): String? = url?.let { tmdb.replace(it) { m -> "${m.groupValues[1]}w1280${m.groupValues[3]}" } }
}
