package com.galaxytvstick.app.data

data class Account(
    val server: String,
    val username: String,
    val password: String,
    /** Non playlist la jan panel la ba li (egz: "Salon"). */
    val name: String? = null,
    /** ID playlist la nan panel la. null = kont antre manyèlman. */
    val playlistId: String? = null
)

data class Category(
    val id: String,
    val name: String
)

data class Channel(
    val streamId: Int,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val epgChannelId: String? = null,
    /** Lyen stream dirèk (egz: yon Live Event panel la). null = chanèl Xtream nòmal. */
    val directUrl: String? = null,
    /** Catch-up: sèvè a anrejistre chanèl sa a (tv_archive) pandan [archiveDays] jou. */
    val tvArchive: Boolean = false,
    val archiveDays: Int = 0
) {
    /** Pwogram sa a ka gade an catch-up (li fini epi li toujou nan achiv la). */
    fun canCatchup(p: Program, now: Long = System.currentTimeMillis()): Boolean =
        tvArchive && archiveDays > 0 && directUrl == null && p.end <= now && p.start >= now - archiveDays * 86_400_000L
}

/** Yon pwogram TV nan gid EPG la (lè yo an milisegonn). */
data class Program(
    val start: Long,
    val end: Long,
    val title: String,
    val desc: String
) {
    fun isNow(now: Long = System.currentTimeMillis()) = now in start until end
    fun progress(now: Long = System.currentTimeMillis()): Int =
        if (end <= start) 0 else (((now - start) * 100) / (end - start)).toInt().coerceIn(0, 100)
}

/** Kenbe lis chanèl yo an memwa pou player a ak gid la. */
object ChannelStore {
    /** Sa sèvè Xtream la voye (anvan règ panel la). */
    var rawChannels: List<Channel> = emptyList()
    var rawCategories: List<Category> = emptyList()

    /** Kontwòl paran aktive: kategori pou granmoun yo pa parèt ditou. */
    @Volatile var hideAdult = false
    /** Chanèl yo nan lòd A → Z (san konte majiskil, aksan, ni senbòl devan non an). */
    @Volatile var sortAz = true
    private fun sortKey(name: String) = name.trimStart { !it.isLetterOrDigit() }
    private val adultRx = Regex("(xxx|porn|adult(?!\\s*swim)|adulte|adulto|18\\s*\\+|\\+\\s*18)", RegexOption.IGNORE_CASE)
    fun isAdult(name: String) = adultRx.containsMatchIn(name)

    /** Aplike règ panel la (hide/show, limit) sou lis sèvè a. */
    fun applyPanel(cfg: PanelConfig) {
        all = cfg.filterChannels(rawChannels)
        if (hideAdult) {
            val adult = rawCategories.filter { isAdult(it.name) }.map { it.id }.toHashSet()
            if (adult.isNotEmpty()) all = all.filter { it.categoryId !in adult }
        }
        if (sortAz) {
            val col = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
            all = all.sortedWith(Comparator { a, b -> col.compare(sortKey(a.name), sortKey(b.name)) })
        }
        val withChannels = all.map { it.categoryId }.toHashSet()
        categories = cfg.filterCategories(rawCategories).filter { it.id in withChannels }
        val allowedIds = all.map { it.streamId }.toHashSet()
        current = current.filter { it.streamId in allowedIds }
    }

    /** Lis kliyan an ap navige kounye a (kategori aktyèl la). */
    var current: List<Channel> = emptyList()
    /** Tout chanèl kliyan an gen dwa wè (apre règ panel la). */
    var all: List<Channel> = emptyList()
    /** Kategori ki gen omwen yon chanèl vizib. */
    var categories: List<Category> = emptyList()
}
