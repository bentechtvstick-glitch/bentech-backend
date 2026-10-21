package com.galaxytvstick.app.data

data class Account(
    val server: String,
    val username: String,
    val password: String,
    /** Non playlist la jan panel la ba li (egz: "Salon"). */
    val name: String? = null,
    /** ID playlist la nan panel la. null = kont antre manyèlman. */
    val playlistId: String? = null,
    /** "xtream" (sèvè + username + password), "m3u" (server = lyen lis la), "stalker" (server = pòtal, username = MAC). */
    val type: String = "xtream",
    /** Lyen EPG XMLTV apa (panel la), si founisè a pa bay gid oswa si l pa bon. */
    val epgUrl: String? = null
) {
    val isXtream get() = type == "xtream"
    val isM3u get() = type == "m3u"
    val isStalker get() = type == "stalker"
}

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
    private var lastApplySig = 0
    fun applyPanel(cfg: PanelConfig) {
        // Panel la voye konfig la chak minit: si anyen ki chanje lis la pa chanje, pa refè travay la
        // (sou dè dizèn milye chanèl, sa te fè ekran an bloke yon ti moman chak minit).
        val sig = listOf(cfg.hiddenChannels, cfg.hiddenCategories, cfg.maxChannels, cfg.channelNames, cfg.categoryNames, cfg.pkgRules,
            cfg.forceHideAdult, cfg.blockedLive, hideAdult, sortAz, System.identityHashCode(rawChannels), System.identityHashCode(rawCategories), rawChannels.size).hashCode()
        if (sig == lastApplySig && all.isNotEmpty()) return
        lastApplySig = sig
        all = cfg.filterChannels(rawChannels)
        // Pakè chanèl panel la, aplike pa NON sou tout lis la (menm gwoup panel la poko wè yo)
        cfg.pkgRules?.let { r ->
            val catName = rawCategories.associate { it.id to it.name.trim().uppercase() }
            // Non orijinal founisè a (pa non admin nan te chanje a)
            val rawName = rawChannels.associate { it.streamId to it.name.trim().uppercase() }
            all = all.filter { c ->
                val n = rawName[c.streamId] ?: c.name.trim().uppercase()
                val g = catName[c.categoryId].orEmpty()
                // Retire pou TV sa a sèlman (menm si pakè a bay li)
                if (n in r.removedChannels || g in r.removedGroups) return@filter false
                if (n in r.extraChannels) return@filter true
                val gHidden = (if (r.only) g !in r.shownGroups else g in r.hiddenGroups) && g !in r.extraGroups
                !gHidden && n !in r.hiddenChannels
            }
        }
        // Kontni granmoun (paran oswa panel la fòse l) + gwoup panel la kache pa non
        val adultOff = hideAdult || cfg.forceHideAdult
        val bad = rawCategories.filter { (adultOff && isAdult(it.name)) || it.name.trim().uppercase() in cfg.blockedLive }.map { it.id }.toHashSet()
        if (bad.isNotEmpty()) all = all.filter { it.categoryId !in bad }
        if (sortAz) {
            val col = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
            all = all.sortedWith(Comparator { a, b -> col.compare(sortKey(a.name), sortKey(b.name)) })
        }
        // Nimewo chanèl yo: kenbe nimewo orijinal founisè a lè l nòmal (egz: 7 rete 7).
        // Sèlman gwo nimewo (egz: 1391305) oswa nimewo ki repete yo pran yon nimewo apre dènye nimewo nòmal la.
        run {
            val used = HashSet<Int>()
            val keep = BooleanArray(all.size) { i -> val n = all[i].num; n in 1..9999 && used.add(n) }
            var next = (used.maxOrNull() ?: 0) + 1
            all = all.mapIndexed { i, c -> if (keep[i]) c else { while (!used.add(next)) next++; c.copy(num = next) } }
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
