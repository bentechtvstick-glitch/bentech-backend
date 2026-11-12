package net.gosmartservices.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Galaxy Home / Ben Tech Home: ekran akèy yon box Android TV (yon launcher pou chak mak).
 * - Lè box la limen: li louvri app TV mak la poukont li.
 * - Bouton Home: ekran sa a, ak app TV mak la an premye epi sèlman app admin nan chwazi nan panel la.
 * - Paramèt Android yo mande PIN admin nan (sèvè a verifye l). Wi‑Fi a rete louvri san PIN.
 */
class HomeActivity : Activity() {

    private data class AppEntry(val pkg: String, val label: String, val intent: Intent, val banner: Drawable?, val icon: Drawable?)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("home", Context.MODE_PRIVATE) }
    private lateinit var appsRow: LinearLayout
    private lateinit var moreRow: LinearLayout
    private lateinit var moreTitle: TextView
    private lateinit var homePill: TextView
    private lateinit var clock: TextView
    private lateinit var hint: TextView
    private lateinit var heroImg: ImageView
    private lateinit var heroFade: View
    private lateinit var titleA: TextView
    private lateinit var titleB: TextView
    private lateinit var tagline: TextView
    private lateinit var dots: LinearLayout
    private lateinit var msgBar: TextView
    private var slideIdx = 0
    private var heroFx: HeroFx? = null
    /** Ben Tech: ekran akèy ak gwo logo, kontak biznis la (panel la ka chanje yo) ak 4 gwo bouton. */
    private val contactHome by lazy { resources.getBoolean(R.bool.contact_home) }
    private var dateView: TextView? = null
    private var contactsBox: LinearLayout? = null
    private var logoTile: View? = null
    private val starsBg by lazy { resources.getBoolean(R.bool.stars_bg) }
    /** Galaxy: gwo afich la se imaj konplè mak la (planèt, G, non an deja ladan l), sou tout lajè ekran an. */
    private val fullHero by lazy { resources.getBoolean(R.bool.full_hero) }
    private val bgColor get() = if (fullHero) 0xFF121419.toInt() else 0xFF0A0F1C.toInt()
    private var cached: Pair<AppEntry?, List<AppEntry>>? = null
    @Volatile private var dirty = true
    private val pkgReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty = true }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerReceiver(pkgReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        // Premye fwa apre box la fin limen: louvri app TV a dirèk
        if (!autoOpened && SystemClock.elapsedRealtime() < 10 * 60_000L) {
            autoOpened = true
            brandIntent()?.let { i -> ui.postDelayed({ openSafe(i) }, 300) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        resumed = true
        startWaiter()
        fetchConfig(force = true) // chak fwa ekran akèy la parèt: pran dènye chanjman panel la touswit
        ui.post(tick)
        applyHome()
        ui.removeCallbacks(rotate); ui.postDelayed(rotate, 7000)
        heroFx?.start()
        ui.removeCallbacks(poll); ui.postDelayed(poll, POLL_FRONT)
        LauncherUpdater.check(this)
    }

    override fun onDestroy() { runCatching { unregisterReceiver(pkgReceiver) }; super.onDestroy() }

    override fun onPause() { super.onPause(); heroFx?.stop(); ui.removeCallbacks(tick); ui.removeCallbacks(rotate)
        // An aryè plan (kliyan an ap gade TV): kontinye pale ak panel la, men pi ralanti
        resumed = false; ui.removeCallbacks(poll); ui.postDelayed(poll, POLL_BACK) }

    /** Pandan ekran akèy la louvri: mande panel la chanjman yo chak 3 minit. */
    @Volatile private var resumed = false
    @Volatile private var waiter: Thread? = null

    /**
     * Pandan ekran akèy la louvri: rete konekte ak panel la (long-poll). Depi mèt la sove yon bagay nan panel la,
     * panel la reponn touswit, epi nou pran nouvo konfigirasyon an — chanjman an parèt nan 1–2 segonn.
     */
    private fun startWaiter() {
        if (waiter?.isAlive == true) return
        waiter = Thread {
            var fails = 0
            while (resumed && !isFinishing) {
                val v = prefs.getInt("cfgV", 0)
                val c = runCatching { URL(BuildConfig.PANEL_URL + "/launcher/wait?v=$v").openConnection() as HttpURLConnection }.getOrNull()
                val nv = runCatching {
                    c!!.connectTimeout = 8000; c.readTimeout = 35_000
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("v", v)
                }.also { c?.disconnect() }.getOrNull()
                if (nv == null) { fails++; Thread.sleep((5_000L * fails).coerceAtMost(30_000L)); continue }
                fails = 0
                if (nv != v) { prefs.edit().putInt("cfgV", nv).apply(); fetchConfig(force = true) }
            }
        }.apply { isDaemon = true; start() }
    }
    /** Ekran akèy la louvri: chak 30 segonn. An aryè plan: chak 5 minit (box la rete "sou liy" nan panel la). */
    private val poll = object : Runnable { override fun run() { fetchConfig(force = true); ui.postDelayed(this, if (resumed) POLL_FRONT else POLL_BACK) } }
    /** Gwo afich la: afich mak la, apre sa imaj panel la yo, youn apre lòt. */
    private val rotate = object : Runnable { override fun run() {
        val n = slides().size + 1
        if (n > 1) { slideIdx = (slideIdx + 1) % n; showSlide() }
        ui.postDelayed(this, 7000)
    } }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* Ekran akèy: pa gen anyen pou l retounen */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (contactHome) {
                clock.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
                dateView?.text = SimpleDateFormat("MM-dd-yyyy", Locale.US).format(Date())
            } else clock.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()) + " · " +
                SimpleDateFormat("h:mm a", Locale.US).format(Date()) // toujou 12 è ak AM/PM, kèlkeswa lang box la
            ui.postDelayed(this, 20_000)
        }
    }

    // ------------------------------------------------------------ Ekran an (stil Fire TV: meni anlè, gwo afich, ranje app)
    private fun buildUi() {
        if (contactHome) { buildContactUi(); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true
            if (!starsBg) setBackgroundColor(bgColor)
            addView(content)
        }
        // Galaxy: tout ekran an plen zetwal (imaj fon an dèyè tout bagay)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF04081A.toInt()) }
        if (starsBg) root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.screen_bg) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- Gwo afich la (imaj mak la adwat, tèks agoch)
        val hero = FrameLayout(this)
        // Galaxy: G a ak sèk la parèt antye (pa koupe); Ben Tech: imaj la ranpli kote dwat la
        heroImg = ImageView(this).apply { scaleType = if (starsBg) ImageView.ScaleType.FIT_END else ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.hero) }
        hero.addView(heroImg, FrameLayout.LayoutParams(if (fullHero) -1 else brandHeroW(), -1, Gravity.END).apply { if (starsBg) { topMargin = dp(52f); rightMargin = dp(40f); bottomMargin = dp(4f) } })
        heroFade = View(this).apply { background = if (starsBg || fullHero) null else fade(true) }
        hero.addView(heroFade, FrameLayout.LayoutParams(-1, -1))
        // Animasyon (zetwal, limyè G a) sou afich mak la
        if (fullHero) {
            heroFx = HeroFx(this, getString(R.string.fx_x).toFloat(), getString(R.string.fx_y).toFloat())
            hero.addView(heroFx, FrameLayout.LayoutParams(-1, -1))
        }
        if (!starsBg) hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(bgColor, bgColor and 0x00FFFFFF))
        }, FrameLayout.LayoutParams(-1, dp(60f), Gravity.BOTTOM))

        // Meni anlè a
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(40f), dp(18f), dp(40f), 0) }
        homePill = navPill(getString(R.string.home), true) { appsRow.getChildAt(0)?.requestFocus() }
        nav.addView(homePill)
        nav.addView(navPill(getString(R.string.apps), false) { appsRow.getChildAt(1)?.requestFocus() ?: appsRow.getChildAt(0)?.requestFocus() })
        nav.addView(navPill(getString(R.string.wifi), false) { openSafe(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        nav.addView(navPill(getString(R.string.settings), false) { askPin() })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        clock = text("", 16f, 0xFFE6ECF8.toInt(), false).apply { setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt()) }
        nav.addView(clock)
        hero.addView(nav, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(56f), 0, 0, 0) }
        val words = BuildConfig.HOME_TITLE.let { t -> if (t.endsWith(" TV Stick")) listOf(t.removeSuffix(" TV Stick"), "TV Stick") else t.split(" ", limit = 2) }
        titleA = text(words[0], 38f, Color.WHITE, true).apply { setShadowLayer(12f, 0f, 3f, 0xAA000000.toInt()); maxLines = 2 }
        left.addView(titleA, LinearLayout.LayoutParams(dp(520f), -2))
        titleB = text(words.getOrElse(1) { "" }, 24f, 0xFF4FA8FF.toInt(), true).apply { setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt()); visibility = if (words.size > 1) View.VISIBLE else View.GONE }
        left.addView(titleB)
        tagline = text(getString(R.string.tagline), 16f, 0xFFE6ECF8.toInt(), false).apply { setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt()); maxLines = 2 }
        left.addView(tagline, LinearLayout.LayoutParams(dp(520f), -2).apply { topMargin = dp(8f) })
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        left.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) })
        hero.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { topMargin = dp(24f) })
        content.addView(hero, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * (if (fullHero) 0.5f else 0.36f)).toInt()))

        // ---- Mesaj panel la (pa egzanp: dat peman)
        msgBar = text("", 16f, 0xFF0A0F1C.toInt(), true).apply {
            setPadding(dp(18f), dp(9f), dp(18f), dp(9f)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xFFFFD34D.toInt()); cornerRadius = dp(10f).toFloat() }
        }
        content.addView(msgBar, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); rightMargin = dp(48f); bottomMargin = dp(4f) })

        // ---- Ranje app yo
        appsRow = row()
        content.addView(hscroll(appsRow), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        moreTitle = text(getString(R.string.other_apps), 17f, 0xFFE6ECF8.toInt(), false)
        content.addView(moreTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(10f) })
        moreRow = row()
        content.addView(hscroll(moreRow))
        hint = text("", 13f, 0xFF93A0BD.toInt(), false)
        content.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(8f); bottomMargin = dp(16f) })
        setContentView(root)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(dp(44f), dp(10f), dp(44f), dp(10f)) }
    private fun hscroll(r: LinearLayout) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(r) }

    /**
     * Lis app yo: kalkile an aryè plan (pa bloke ekran an), epi kenbe l an memwa. Ekran an parèt touswit ak
     * dènye lis la; li rebati sèlman si yon app enstale/retire oswa si panel la chanje lis la.
     */
    private fun refresh() {
        cached?.let { render(it.first, it.second) }
        if (cached != null && !dirty) { focusFirst(); return }
        dirty = false
        Thread {
            val r = runCatching { loadApps() }.getOrNull() ?: return@Thread
            ui.post { if (!isFinishing) { cached = r; render(r.first, r.second); focusFirst() } }
        }.start()
    }

    private fun focusFirst() { if (contactHome) { logoTile?.requestFocus(); return }; (appsRow.getChildAt(0)?.takeIf { it.isFocusable } ?: homePill).requestFocus() }

    private var shownSig = ""
    private fun render(brand: AppEntry?, others: List<AppEntry>) {
        val sig = (listOfNotNull(brand?.pkg) + others.map { it.pkg }).joinToString(",")
        // Pa montre kliyan an enstriksyon "Home → Always" (li pa mache sou Google TV / Firestick).
        // Panel la montre pou chak box si launcher a se ekran akèy la oswa non.
        hint.text = ""
        if (sig == shownSig && appsRow.childCount > 0) return
        shownSig = sig
        appsRow.removeAllViews(); moreRow.removeAllViews()
        // Premye ranje: app TV mak la + 6 premye app panel la chwazi yo; rès yo nan dezyèm ranje a
        val first = others.take(6); val rest = others.drop(6)
        val lp = { i: Int -> LinearLayout.LayoutParams(dp(132f), dp(76f)).apply { if (i > 0) leftMargin = dp(12f) } }
        val lp2 = { i: Int -> LinearLayout.LayoutParams(dp(150f), dp(78f)).apply { if (i > 0) leftMargin = dp(12f) } }
        if (brand != null) appsRow.addView(brandTile(brand), LinearLayout.LayoutParams(dp(156f), dp(90f)))
        else appsRow.addView(text(getString(R.string.no_brand, BuildConfig.HOME_TITLE), 15f, Color.WHITE, true))
        first.forEachIndexed { i, a -> appsRow.addView(tile(a), lp(i + 1)) }
        rest.forEachIndexed { i, a -> moreRow.addView(tile(a), lp2(i)) }
        // Dènye kat la: "App" — tout app ki sou box la (mande PIN, pou kliyan pa louvri nenpòt bagay)
        appsRow.addView(allAppsTile(), lp(first.size + 1))
        moreTitle.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        moreRow.visibility = moreTitle.visibility
        if (false && others.isEmpty() && brand != null) appsRow.addView(text(getString(R.string.no_apps), 14f, 0xFF93A0BD.toInt(), false).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-2, dp(100f)).apply { leftMargin = dp(20f) })
    }

    private fun focusAnim(v: View, f: Boolean, base: Int) {
        v.background = cardBg(base, f)
        v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
        v.elevation = if (f) dp(8f).toFloat() else 0f
    }

    private fun brandTile(a: AppEntry): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        background = cardBg(0xFF0E1A3A.toInt(), false)
        setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        setOnFocusChangeListener { v, f -> focusAnim(v, f, 0xFF0E1A3A.toInt()) }
        setOnClickListener { launch(a) }
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.tile); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(11f).toFloat()) } } },
            FrameLayout.LayoutParams(-1, -1))
    }

    private fun tile(a: AppEntry): View {
        val box = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = cardBg(0xFF1A2440.toInt(), false)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            setOnFocusChangeListener { v, f -> focusAnim(v, f, 0xFF1A2440.toInt()) }
            setOnClickListener { launch(a) }
        }
        if (a.banner != null) {
            box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(a.banner) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(42f), dp(42f)))
            col.addView(text(a.label, 13f, Color.WHITE, false).apply { maxLines = 1; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
            box.addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        return box
    }


    // ------------------------------------------------------------ Ben Tech: ekran kontak
    private fun buildContactUi() {
        // Vyou rès kòd la itilize yo (pa sou ekran an) pou lis app yo, mesaj la, elatriye.
        appsRow = LinearLayout(this); moreRow = LinearLayout(this); moreTitle = TextView(this); homePill = TextView(this)
        hint = TextView(this); heroImg = ImageView(this); heroFade = View(this); titleA = TextView(this); titleB = TextView(this)
        tagline = TextView(this); dots = LinearLayout(this)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF061028.toInt()) }
        root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.screen_bg) }, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44f), dp(20f), dp(44f), dp(24f)) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Anlè adwat: Wi‑Fi, Bluetooth, èdtan nan yon bwat
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.ic_wifi); alpha = 0.9f }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { rightMargin = dp(12f) })
        top.addView(ImageView(this).apply { setImageResource(R.drawable.ic_bt); alpha = 0.9f }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(24f) })
        val clk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18f), dp(6f), dp(18f), dp(6f))
            background = GradientDrawable().apply { setColor(0x66061028); cornerRadius = dp(10f).toFloat(); setStroke(dp(2f), 0xFF3FB8FF.toInt()) }
        }
        clock = text("", 22f, Color.WHITE, true); clk.addView(clock)
        dateView = text("", 11f, 0xFFE6ECF8.toInt(), true); clk.addView(dateView)
        top.addView(clk)
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Mitan: gwo logo agoch (peze l pou louvri Ben Tech TV), liy limyè, kontak adwat
        val mid = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = FrameLayout(this).apply {
            isFocusable = true; isClickable = true; setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            setOnFocusChangeListener { v, f ->
                v.background = if (f) GradientDrawable().apply { setColor(0x223FB8FF); cornerRadius = dp(16f).toFloat(); setStroke(dp(2f), 0xFF3FB8FF.toInt()) } else null
                v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(150).start()
            }
            setOnClickListener { brandIntent()?.let { openSafe(it) } }
            addView(ImageView(context).apply { setImageResource(R.drawable.logo_big); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
        }
        logoTile = logo
        mid.addView(logo, LinearLayout.LayoutParams(0, -1, 1.05f))
        mid.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x003FB8FF, 0xFF3FB8FF.toInt(), 0x003FB8FF))
        }, LinearLayout.LayoutParams(dp(2f), -1).apply { leftMargin = dp(18f); rightMargin = dp(28f); topMargin = dp(20f); bottomMargin = dp(20f) })
        contactsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(contactsBox, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        // Mesaj panel la
        msgBar = text("", 15f, 0xFF0A0F1C.toInt(), true).apply {
            setPadding(dp(16f), dp(7f), dp(16f), dp(7f)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xFFFFD34D.toInt()); cornerRadius = dp(10f).toFloat() }
        }
        col.addView(msgBar, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10f); gravity = Gravity.CENTER_HORIZONTAL })

        // Anba: 4 gwo bouton
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val lp = { LinearLayout.LayoutParams(0, dp(100f), 1f).apply { leftMargin = dp(7f); rightMargin = dp(7f) } }
        // Ben Tech TV an premye, apre sa YouTube ak Downloader (sèlman si yo enstale)
        findPkg(listOf(BuildConfig.BRAND_PKG))
            ?.let { row.addView(bigTile(it.loadIcon(packageManager), null, it.loadLabel(packageManager).toString(), false) { brandIntent()?.let { i -> openSafe(i) } }, lp()) }
        findPkg(listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube.googletv", "com.google.android.youtube"))
            ?.let { row.addView(bigTile(it.loadIcon(packageManager), null, "YouTube", true) { launchPkg(it.packageName) }, lp()) }
        findPkg(listOf("com.esaba.downloader"))
            ?.let { row.addView(bigTile(it.loadIcon(packageManager), null, "Downloader", true) { launchPkg(it.packageName) }, lp()) }
        row.addView(bigTile(null, R.drawable.ic_grid, getString(R.string.my_apps), false) { showMyApps() }, lp())
        findPkg(listOf("com.android.vending"))
            ?.let { row.addView(bigTile(it.loadIcon(packageManager), null, "Play Store", true) { launchPkg(it.packageName) }, lp()) }
        row.addView(bigTile(null, R.drawable.ic_gear, getString(R.string.settings), false) { askPin() }, lp())
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        paintContacts()
    }

    private fun findPkg(pkgs: List<String>): android.content.pm.ApplicationInfo? =
        pkgs.firstNotNullOfOrNull { p -> runCatching { packageManager.getApplicationInfo(p, 0) }.getOrNull()?.takeIf { packageManager.getLeanbackLaunchIntentForPackage(p) != null || packageManager.getLaunchIntentForPackage(p) != null } }

    private fun launchPkg(p: String) {
        (packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)?.let { openSafe(it) }
    }

    private fun bigTile(icon: android.graphics.drawable.Drawable?, vec: Int?, label: String, light: Boolean, onClick: () -> Unit): View {
        val base = if (light) 0xFFF2F4F8.toInt() else 0xCC0E2350.toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            fun bg(f: Boolean) = GradientDrawable().apply { setColor(base); cornerRadius = dp(10f).toFloat(); setStroke(dp(if (f) 3f else 1f), if (f) 0xFF3FB8FF.toInt() else 0x553FB8FF) }
            background = bg(false)
            setOnFocusChangeListener { v, f -> v.background = bg(f); v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start(); v.elevation = if (f) dp(8f).toFloat() else 0f }
            setOnClickListener { onClick() }
        }
        box.addView(ImageView(this).apply { if (icon != null) setImageDrawable(icon) else setImageResource(vec!!) }, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        box.addView(text(label, 14f, if (light) 0xFF1A2440.toInt() else Color.WHITE, !light).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6f), 0, dp(6f), 0) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        return box
    }

    /** Kontak yo: panel la (Sistèm → Launcher) oswa sa ki vin ak app la. Liy: "tel: …", "whatsapp: …", "adrès: … | …". */
    private fun paintContacts() {
        val box = contactsBox ?: return
        box.removeAllViews()
        val raw = prefs.getString("contacts", "").orEmpty().ifBlank { getString(R.string.default_contacts) }
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(7).forEach { line ->
            val k = line.substringBefore(':').trim().lowercase(); val v = line.substringAfter(':', line).trim()
            val (icon, color, label) = when {
                k.startsWith("whats") || k == "wa" -> Triple(R.drawable.ic_phone, 0xFF25D366.toInt(), "WhatsApp: $v")
                k.startsWith("tel") || k.startsWith("phone") || k.startsWith("telefòn") -> Triple(R.drawable.ic_phone, 0xFF1E6FE8.toInt(), "Tel: $v")
                k.startsWith("adr") || k.startsWith("addr") || k.startsWith("loc") -> Triple(R.drawable.ic_pin, 0xFF1E6FE8.toInt(), v.replace("|", "\n"))
                else -> Triple(R.drawable.ic_pin, 0xFF1E6FE8.toInt(), line.replace("|", "\n"))
            }
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                addView(ImageView(context).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(14f) })
            r.addView(text(label, 22f, Color.WHITE, true).apply { setShadowLayer(6f, 0f, 1f, 0xAA000000.toInt()) })
            box.addView(r, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(7f); bottomMargin = dp(7f) })
        }
    }

    /** "My Apps": app TV Ben Tech la + app panel la chwazi yo (san PIN). */
    private fun showMyApps() {
        Thread {
            val r = cached ?: runCatching { loadApps() }.getOrNull() ?: return@Thread
            val list = listOfNotNull(r.first) + r.second
            ui.post {
                if (isFinishing) return@post
                if (list.isEmpty()) { Toast.makeText(this, R.string.no_apps, Toast.LENGTH_SHORT).show(); return@post }
                AlertDialog.Builder(this).setTitle(R.string.my_apps)
                    .setItems(list.map { it.label }.toTypedArray()) { _, i -> launch(list[i]) }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }.start()
    }

    private fun allAppsTile(): View {
        val box = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = cardBg(0xFF2A2D35.toInt(), false)
            setOnFocusChangeListener { v, f -> focusAnim(v, f, 0xFF2A2D35.toInt()) }
            setOnClickListener { askPin { showAllApps() } }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(text("▦", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        col.addView(text(getString(R.string.apps), 14f, Color.WHITE, false).apply { gravity = Gravity.CENTER })
        box.addView(col, FrameLayout.LayoutParams(-1, -1))
        return box
    }

    /** Apre PIN lan: lis tout app ki enstale (pa egzanp Play Store, Downloader) pou admin nan louvri yo. */
    private fun showAllApps() {
        Thread {
            val pm = packageManager
            val list = launchables().map { it to it.loadLabel(pm).toString() }.sortedBy { it.second.lowercase() }
            ui.post {
                if (isFinishing) return@post
                AlertDialog.Builder(this).setTitle(R.string.apps)
                    .setItems(list.map { it.second }.toTypedArray()) { _, i -> launch(entry(list[i].first)) }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }.start()
    }

    private fun navPill(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 17f, Color.WHITE, selected).apply {
        isFocusable = true; isClickable = true
        setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        val base = if (selected) 0x33FFFFFF else 0x00000000
        background = pillBg(false, base)
        setOnFocusChangeListener { v, f -> v.background = pillBg(f, base); (v as TextView).setTextColor(if (f) 0xFF0A0F1C.toInt() else Color.WHITE) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) }
    }

    private fun pillBg(focused: Boolean, base: Int) = GradientDrawable().apply {
        setColor(if (focused) Color.WHITE else base); cornerRadius = dp(24f).toFloat()
    }

    private fun cardBg(color: Int, focused: Boolean) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(14f).toFloat()
        if (focused) setStroke(dp(3f), Color.WHITE)
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------ App yo
    private fun launchables(): List<ResolveInfo> {
        val pm = packageManager
        val tv = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return (tv + phone).distinctBy { it.activityInfo.packageName }.filter { it.activityInfo.packageName != packageName }
    }

    private fun entry(ri: ResolveInfo): AppEntry {
        val pm = packageManager
        val pkg = ri.activityInfo.packageName
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).setClassName(pkg, ri.activityInfo.name)
        val banner = runCatching { ri.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(pkg) }.getOrNull()
        return AppEntry(pkg, ri.loadLabel(pm).toString(), intent, banner, runCatching { ri.loadIcon(pm) }.getOrNull())
    }

    /** App TV mak la + app panel la pèmèt yo. Non yo chèche an premye; imaj yo chaje sèlman pou app ki chwazi yo. */
    private fun loadApps(): Pair<AppEntry?, List<AppEntry>> {
        val pm = packageManager
        val all = launchables()
        val brand = all.firstOrNull { it.activityInfo.packageName in BRAND_PKGS }?.let { entry(it) }
        val words = (prefs.getString("apps", null) ?: "YouTube, Netflix").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return brand to emptyList()
        val others = all.filter { it.activityInfo.packageName !in BRAND_PKGS }
            .map { it to it.loadLabel(pm).toString().lowercase() }
            .filter { (_, l) -> words.any { w -> l.contains(w) } }
            .sortedBy { (_, l) -> words.indexOfFirst { w -> l.contains(w) } }
            .map { entry(it.first) }
        return brand to others
    }

    private fun brandIntent(): Intent? = (packageManager.getLeanbackLaunchIntentForPackage(BuildConfig.BRAND_PKG)
        ?: packageManager.getLaunchIntentForPackage(BuildConfig.BRAND_PKG))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    // Menm drapo ak launcher Android la: si app la deja louvri an aryè plan, li retounen touswit (li pa rekòmanse)
    private fun launch(a: AppEntry) = openSafe(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))

    private fun openSafe(i: Intent) {
        runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun isDefaultHome(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    // ------------------------------------------------------------ Panel la (lis app yo + PIN)
    private fun fetchConfig(force: Boolean = false) {
        val last = prefs.getLong("fetchedAt", 0L)
        if (!force && System.currentTimeMillis() - last < 3 * 60_000L) return
        Thread {
            val id = runCatching { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
            val q = "?brand=" + BuildConfig.BRAND_KEY + "&v=" + BuildConfig.VERSION_CODE + "&id=" + java.net.URLEncoder.encode(id, "UTF-8") +
                "&model=" + java.net.URLEncoder.encode((android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim(), "UTF-8") +
                "&home=" + (if (isDefaultHome()) "1" else "0") + "&os=" + android.os.Build.VERSION.SDK_INT
            val j = runCatching { http("GET", "/launcher/config$q", null) }.getOrNull() ?: return@Thread
            val before = prefs.getString("apps", null) + "|" + prefs.getString("slides", "") + "|" + prefs.getString("message", "") + "|" + prefs.getString("contacts", "") + "|" + prefs.getString("messageL", "")
            prefs.edit().putInt("cfgV", j.optInt("v", prefs.getInt("cfgV", 0))).putString("apps", j.optString("apps", "YouTube, Netflix")).putBoolean("hasPin", j.optBoolean("hasPin", false))
                .putString("slides", (j.optJSONArray("slides") ?: org.json.JSONArray()).toString()).putString("message", j.optString("message", "")).putString("contacts", j.optString("contacts", "")).putString("messageL", (j.optJSONObject("messageL") ?: org.json.JSONObject()).toString())
                .putLong("fetchedAt", System.currentTimeMillis()).apply()
            // Panel la peze "Voye mizajou": tcheke touswit (pa tann 6 èdtan)
            val upd = j.optString("updateAt", "")
            if (upd.isNotEmpty() && upd != prefs.getString("updSeen", "")) {
                prefs.edit().putString("updSeen", upd).putLong("updCheck", 0L).apply()
                ui.post { if (resumed) LauncherUpdater.check(this) } // sinon: l ap fèt lè ekran akèy la parèt
            }
            val after = prefs.getString("apps", null) + "|" + prefs.getString("slides", "") + "|" + prefs.getString("message", "") + "|" + prefs.getString("contacts", "") + "|" + prefs.getString("messageL", "")
            if (before != after) ui.post { if (!isFinishing) { dirty = true; refresh(); applyHome() } }
        }.start()
    }

    // ------------------------------------------------------------ Gwo afich la ak mesaj la (panel la kontwole yo)
    private data class Slide(val url: String, val title: String, val text: String)

    private fun slides(): List<Slide> = runCatching {
        val a = org.json.JSONArray(prefs.getString("slides", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            var u = o.optString("imageUrl")
            if (u.startsWith("/")) u = BuildConfig.PANEL_URL.removeSuffix("/api") + u
            Slide(u, o.optString("title"), o.optString("text"))
        }.filter { it.url.startsWith("http") }
    }.getOrDefault(emptyList())

    private fun applyHome() {
        paintContacts()
        // Mesaj la nan lang box la (panel la tradui l), sinon mesaj orijinal la
        val lang = java.util.Locale.getDefault().language.let { if (it == "ht") "ht" else it }
        val m = runCatching { org.json.JSONObject(prefs.getString("messageL", "{}") ?: "{}").optString(lang, "") }.getOrDefault("").trim()
            .ifEmpty { prefs.getString("message", "").orEmpty().trim() }
        msgBar.text = "📢  $m"; msgBar.visibility = if (m.isEmpty()) View.GONE else View.VISIBLE
        val n = slides().size + 1
        if (slideIdx >= n) slideIdx = 0
        showSlide()
        slides().forEach { s -> if (cacheFile(s.url).length() == 0L) Thread { runCatching { download(s.url) } }.start() }
    }

    private fun brandHeroW() = (resources.displayMetrics.widthPixels * 0.6f).toInt()
    private fun fade(brand: Boolean) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        if (brand) intArrayOf(0xFF0A0F1C.toInt(), 0xFF0A0F1C.toInt(), 0x990A0F1C.toInt(), 0x000A0F1C)
        else intArrayOf(0xE60A0F1C.toInt(), 0x800A0F1C.toInt(), 0x000A0F1C, 0x000A0F1C))

    private fun showSlide() {
        if (contactHome) return
        val list = slides()
        dots.removeAllViews()
        if (list.isNotEmpty()) for (i in 0..list.size) dots.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(if (i == slideIdx) Color.WHITE else 0x66FFFFFF); cornerRadius = dp(4f).toFloat() }
        }, LinearLayout.LayoutParams(if (i == slideIdx) dp(18f) else dp(7f), dp(7f)).apply { rightMargin = dp(6f) })
        val words = BuildConfig.HOME_TITLE.let { t -> if (t.endsWith(" TV Stick")) listOf(t.removeSuffix(" TV Stick"), "TV Stick") else t.split(" ", limit = 2) }
        val s = if (slideIdx == 0) null else list.getOrNull(slideIdx - 1)
        val bmp = s?.let { loadBitmap(it.url) }
        if (s == null || bmp == null) {
            // Afich mak la
            heroImg.setImageResource(R.drawable.hero)
            heroImg.layoutParams = (heroImg.layoutParams as FrameLayout.LayoutParams).apply { width = if (fullHero) -1 else brandHeroW() }
            heroFade.background = if (starsBg || fullHero) null else fade(true)
            titleA.text = words[0]; titleA.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            titleB.text = words.getOrElse(1) { "" }; titleB.visibility = if (words.size > 1) View.VISIBLE else View.GONE
            tagline.text = getString(R.string.tagline); tagline.visibility = View.VISIBLE
            heroFx?.visibility = View.VISIBLE
            if (fullHero) { titleA.visibility = View.GONE; titleB.visibility = View.GONE; tagline.visibility = View.GONE } else titleA.visibility = View.VISIBLE
        } else {
            // Imaj panel la: tout lajè a, ak tit/tèks si genyen
            heroFx?.visibility = View.GONE
            heroImg.setImageBitmap(bmp)
            heroImg.layoutParams = (heroImg.layoutParams as FrameLayout.LayoutParams).apply { width = -1 }
            heroFade.background = fade(s.title.isNotEmpty() || s.text.isNotEmpty())
            if (s.title.isEmpty() && s.text.isEmpty()) heroFade.background = null
            titleA.text = s.title; titleA.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f); titleA.visibility = View.VISIBLE
            titleB.visibility = View.GONE
            tagline.text = s.text; tagline.visibility = if (s.text.isEmpty()) View.GONE else View.VISIBLE
        }
        heroImg.alpha = 0f; heroImg.animate().alpha(1f).setDuration(450).start()
    }

    private fun cacheFile(url: String) = java.io.File(cacheDir, "slide-" + Integer.toHexString(url.hashCode()) + ".img")

    private fun download(url: String) {
        val f = cacheFile(url); val tmp = java.io.File(f.path + ".tmp")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 20000
            if (c.responseCode !in 200..299) return
            tmp.outputStream().use { o -> c.inputStream.use { it.copyTo(o) } }
            if (tmp.length() > 0) tmp.renameTo(f)
        } finally { c.disconnect(); tmp.delete() }
    }

    private val bitmaps = HashMap<String, android.graphics.Bitmap>()
    private fun loadBitmap(url: String): android.graphics.Bitmap? {
        bitmaps[url]?.let { return it }
        val f = cacheFile(url); if (f.length() == 0L) return null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        var sample = 1; while (o.outWidth / (sample * 2) >= 1280) sample *= 2
        val b = runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return null
        if (bitmaps.size > 8) bitmaps.clear()
        bitmaps[url] = b
        return b
    }

    private fun askPin(onOk: () -> Unit = { openSafe(Intent(Settings.ACTION_SETTINGS)) }) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> checkPin(input.text.toString(), onOk) }
            .show()
        input.requestFocus()
    }

    private fun checkPin(pin: String, onOk: () -> Unit) {
        Thread {
            val r = runCatching { http("POST", "/launcher/unlock", JSONObject().put("pin", pin).toString()) }
            ui.post {
                val j = r.getOrNull()
                when {
                    j == null -> {
                        // San entènèt: sèlman si panel la pa janm mete PIN
                        if (!prefs.getBoolean("hasPin", true)) onOk()
                        else toast(if (r.exceptionOrNull()?.message == "429") R.string.pin_many else R.string.pin_net)
                    }
                    j.optBoolean("ok") -> onOk()
                    else -> toast(R.string.pin_wrong)
                }
            }
        }.start()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun http(method: String, path: String, body: String?): JSONObject {
        val c = URL(BuildConfig.PANEL_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode == 429) throw IllegalStateException("429")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    companion object {
        private val BRAND_PKGS = listOf(BuildConfig.BRAND_PKG)
        private const val POLL_FRONT = 30_000L
        private const val POLL_BACK = 5 * 60_000L
        @Volatile private var autoOpened = false
    }
}
