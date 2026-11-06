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

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // Premye fwa apre box la fin limen: louvri app TV a dirèk
        if (!autoOpened && SystemClock.elapsedRealtime() < 10 * 60_000L) {
            autoOpened = true
            brands().firstOrNull()?.let { ui.postDelayed({ launch(it) }, 600) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        fetchConfig()
        ui.post(tick)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* Ekran akèy: pa gen anyen pou l retounen */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date())
            ui.postDelayed(this, 20_000)
        }
    }

    // ------------------------------------------------------------ Ekran an (stil Fire TV: meni anlè, gwo afich, ranje app)
    private fun buildUi() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true
            setBackgroundColor(0xFF0A0F1C.toInt()); addView(content)
        }

        // ---- Gwo afich la (imaj mak la adwat, tèks agoch)
        val hero = FrameLayout(this)
        hero.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(R.drawable.hero) },
            FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.6f).toInt(), -1, Gravity.END))
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0A0F1C.toInt(), 0xFF0A0F1C.toInt(), 0x990A0F1C.toInt(), 0x000A0F1C))
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0A0F1C.toInt(), 0x000A0F1C))
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
        left.addView(text(words[0], 50f, Color.WHITE, true).apply { setShadowLayer(12f, 0f, 3f, 0xAA000000.toInt()) })
        if (words.size > 1) left.addView(text(words[1], 30f, 0xFF4FA8FF.toInt(), true).apply { setShadowLayer(10f, 0f, 2f, 0xAA000000.toInt()) })
        left.addView(text(getString(R.string.tagline), 18f, 0xFFE6ECF8.toInt(), false), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        hero.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { topMargin = dp(30f) })
        content.addView(hero, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.52f).toInt()))

        // ---- Ranje app yo
        appsRow = row()
        content.addView(hscroll(appsRow), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        moreTitle = text(getString(R.string.other_apps), 17f, 0xFFE6ECF8.toInt(), false)
        content.addView(moreTitle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(10f) })
        moreRow = row()
        content.addView(hscroll(moreRow))
        hint = text("", 13f, 0xFF93A0BD.toInt(), false)
        content.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(48f); topMargin = dp(8f); bottomMargin = dp(16f) })
        setContentView(scroll)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(dp(44f), dp(10f), dp(44f), dp(10f)) }
    private fun hscroll(r: LinearLayout) = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(r) }

    private fun refresh() {
        val brand = brands().firstOrNull()
        val others = otherApps()
        appsRow.removeAllViews(); moreRow.removeAllViews()
        // Premye ranje: app TV mak la + 6 premye app panel la chwazi yo; rès yo nan dezyèm ranje a
        val first = others.take(6); val rest = others.drop(6)
        val lp = { i: Int -> LinearLayout.LayoutParams(dp(178f), dp(100f)).apply { if (i > 0) leftMargin = dp(14f) } }
        if (brand != null) appsRow.addView(brandTile(brand), lp(0))
        else appsRow.addView(text(getString(R.string.no_brand, BuildConfig.HOME_TITLE), 15f, Color.WHITE, true))
        first.forEachIndexed { i, a -> appsRow.addView(tile(a), lp(i + 1)) }
        rest.forEachIndexed { i, a -> moreRow.addView(tile(a), lp(i)) }
        moreTitle.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        moreRow.visibility = moreTitle.visibility
        if (others.isEmpty() && brand != null) appsRow.addView(text(getString(R.string.no_apps), 14f, 0xFF93A0BD.toInt(), false).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-2, dp(100f)).apply { leftMargin = dp(20f) })
        hint.text = if (isDefaultHome()) "" else getString(R.string.set_default, getString(R.string.app_name))
        (appsRow.getChildAt(0)?.takeIf { it.isFocusable } ?: homePill).requestFocus()
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

    /** Galaxy ak Ben Tech (sa ki enstale yo). */
    private fun brands(): List<AppEntry> = launchables().filter { it.activityInfo.packageName in BRAND_PKGS }
        .sortedBy { BRAND_PKGS.indexOf(it.activityInfo.packageName) }.map { entry(it) }

    /** Lòt app admin nan pèmèt nan panel la (pa non), sèlman sa ki enstale. */
    private fun otherApps(): List<AppEntry> {
        val words = (prefs.getString("apps", null) ?: "YouTube, Netflix").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return launchables().filter { it.activityInfo.packageName !in BRAND_PKGS }.map { entry(it) }
            .filter { a -> words.any { w -> a.label.lowercase().contains(w) } }
            .sortedBy { a -> words.indexOfFirst { w -> a.label.lowercase().contains(w) } }
    }

    private fun launch(a: AppEntry) = openSafe(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun openSafe(i: Intent) {
        runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun isDefaultHome(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    // ------------------------------------------------------------ Panel la (lis app yo + PIN)
    private fun fetchConfig() {
        val last = prefs.getLong("fetchedAt", 0L)
        if (System.currentTimeMillis() - last < 3 * 60_000L) return
        Thread {
            val j = runCatching { http("GET", "/launcher/config", null) }.getOrNull() ?: return@Thread
            val before = prefs.getString("apps", null)
            prefs.edit().putString("apps", j.optString("apps", "YouTube, Netflix")).putBoolean("hasPin", j.optBoolean("hasPin", false))
                .putLong("fetchedAt", System.currentTimeMillis()).apply()
            if (before != prefs.getString("apps", null)) ui.post { if (!isFinishing) refresh() }
        }.start()
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> checkPin(input.text.toString()) }
            .show()
        input.requestFocus()
    }

    private fun checkPin(pin: String) {
        Thread {
            val r = runCatching { http("POST", "/launcher/unlock", JSONObject().put("pin", pin).toString()) }
            ui.post {
                val j = r.getOrNull()
                when {
                    j == null -> {
                        // San entènèt: sèlman si panel la pa janm mete PIN
                        if (!prefs.getBoolean("hasPin", true)) openSafe(Intent(Settings.ACTION_SETTINGS))
                        else toast(if (r.exceptionOrNull()?.message == "429") R.string.pin_many else R.string.pin_net)
                    }
                    j.optBoolean("ok") -> openSafe(Intent(Settings.ACTION_SETTINGS))
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
        @Volatile private var autoOpened = false
    }
}
