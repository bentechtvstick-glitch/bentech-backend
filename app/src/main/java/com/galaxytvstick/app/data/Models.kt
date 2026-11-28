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
    /** Pou ki playlist lis ki nan memwa a ye ("server|username|playlistId"). */
    var loadedFor: String = ""
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
        val sig = listOf(cfg.hiddenChannels, cfg.hiddenCategories, cfg.maxChannels, cfg.channelNames, cfg.categoryNames, cfg.pkgRules, cfg.chNumMode, cfg.chNumFixed,
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
        // Nimewo chanèl yo (kalkile nan lòd founisè a, ANVAN triye A→Z, pou panel la ka montre menm nimewo yo):
        //  - si nimewo founisè a nòmal (pifò ant 1 ak kantite chanèl yo, san repete) → kenbe yo (7 rete 7);
        //  - sinon (gwo nimewo tankou 1391305, oswa nimewo ki pa fè sans) → 1, 2, 3… nan lòd lis la.
        //  - panel la ka chwazi: nimewo FIKS pou kèk chanèl (pa non orijinal la, egz: TELE GINEN → 1), epi
        //    "list" = 1, 2, 3… nan lòd lis la pou tout lòt yo (pa nimewo founisè a).
        run {
            if (cfg.chNumMode == "list") {
                // 1, 2, 3… nan lòd kliyan an wè yo: gwoup pa gwoup (lòd gwoup yo), chanèl yo nan lòd yo nan chak gwoup.
                // Avan, nimewo yo te swiv lòd brit founisè a → nan yon gwoup, nimewo yo te sote (12, 340, 87…).
                val catIdx = HashMap<String, Int>(); rawCategories.forEachIndexed { i, c -> catIdx.putIfAbsent(c.id, i) }
                // A→Z (pa defo): nan chak gwoup, chanèl yo klase pa non ANVAN yo resevwa nimewo → 1, 2, 3… swiv A→Z
                val col = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
                val keys = if (sortAz) all.associate { it.streamId to col.getCollationKey(sortKey(it.name)) } else emptyMap()
                all = all.withIndex().sortedWith(Comparator { a, b ->
                    val ca = catIdx[a.value.categoryId] ?: Int.MAX_VALUE; val cb = catIdx[b.value.categoryId] ?: Int.MAX_VALUE
                    if (ca != cb) return@Comparator ca.compareTo(cb)
                    if (sortAz) { val k = keys[a.value.streamId]!!.compareTo(keys[b.value.streamId]!!); if (k != 0) return@Comparator k }
                    a.index.compareTo(b.index)
                }).map { it.value }
            }
            val n = all.size
            val out = IntArray(n)
            val used = HashSet<Int>()
            if (cfg.chNumFixed.isNotEmpty()) {
                val rawName = rawChannels.associate { it.streamId to it.name.trim().uppercase() }
                for (i in 0 until n) {
                    val c = all[i]
                    val f = cfg.chNumFixed[rawName[c.streamId] ?: c.name.trim().uppercase()] ?: continue
                    if (used.add(f)) out[i] = f
                }
            }
            val limit = maxOf(999, n * 3)
            val seen = HashSet<Int>()
            val okCount = all.count { val x = it.num; x in 1..limit && seen.add(x) }
            if (cfg.chNumMode != "list" && n > 0 && okCount * 10 >= n * 9) {
                // Nimewo founisè a: kenbe yo (sof sa yo ki deja pran), lòt yo apre pi gwo a
                for (i in 0 until n) { if (out[i] == 0) { val x = all[i].num; if (x in 1..limit && used.add(x)) out[i] = x } }
                var next = (used.maxOrNull() ?: 0) + 1
                for (i in 0 until n) if (out[i] == 0) { while (!used.add(next)) next++; out[i] = next }
            } else {
                var next = 1
                for (i in 0 until n) if (out[i] == 0) { while (!used.add(next)) next++; out[i] = next }
            }
            all = all.mapIndexed { i, c -> if (c.num == out[i]) c else c.copy(num = out[i]) }
        }
        // Mòd "1, 2, 3…": lis la deja klase (gwoup pa gwoup, A→Z) nan lòd nimewo yo
        if (sortAz && cfg.chNumMode != "list") {
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
