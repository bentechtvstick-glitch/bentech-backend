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
    private lateinit var root: LinearLayout
    private lateinit var heroBox: LinearLayout
    private lateinit var appsRow: LinearLayout
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

    // ------------------------------------------------------------ Ekran an
    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(28f), dp(48f), dp(24f))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B1630.toInt(), 0xFF060A14.toInt()))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(BuildConfig.HOME_TITLE, 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        clock = text("", 16f, 0xFFB8C4DE.toInt(), false)
        top.addView(clock)
        root.addView(top)

        heroBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(heroBox, LinearLayout.LayoutParams(-1, dp(230f)).apply { topMargin = dp(22f) })

        root.addView(text(getString(R.string.other_apps), 17f, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26f); bottomMargin = dp(10f) })
        appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(dp(4f), dp(6f), dp(4f), dp(6f)) }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(appsRow) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(120f)))

        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        hint = text("", 13f, 0xFF93A0BD.toInt(), false)
        bottom.addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(pill("📶  " + getString(R.string.wifi)) { openSafe(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        bottom.addView(pill("⚙  " + getString(R.string.settings)) { askPin() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12f) })
        root.addView(bottom)
        setContentView(root)
    }

    private fun refresh() {
        val brands = brands()
        heroBox.removeAllViews()
        if (brands.isEmpty()) {
            heroBox.addView(text(getString(R.string.no_brand, BuildConfig.HOME_TITLE), 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-1, -1))
        } else for ((i, b) in brands.withIndex()) {
            heroBox.addView(hero(b), LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = dp(20f) })
        }
        appsRow.removeAllViews()
        val others = otherApps()
        if (others.isEmpty()) appsRow.addView(text(getString(R.string.no_apps), 14f, 0xFF93A0BD.toInt(), false))
        for ((i, a) in others.withIndex()) appsRow.addView(tile(a), LinearLayout.LayoutParams(dp(176f), dp(99f)).apply { if (i > 0) leftMargin = dp(14f) })
        hint.text = if (isDefaultHome()) "" else getString(R.string.set_default, getString(R.string.app_name))
        heroBox.getChildAt(0)?.takeIf { it.isFocusable }?.requestFocus()
    }

    private fun hero(a: AppEntry): View {
        val card = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = cardBg(0xFF13203F.toInt(), false)
            setOnFocusChangeListener { v, f -> v.background = cardBg(0xFF13203F.toInt(), f); v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(120).start() }
            setOnClickListener { launch(a) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22f), dp(18f), dp(22f), dp(18f)) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(a.banner ?: a.icon) }
        row.addView(img, LinearLayout.LayoutParams(0, -1, 1.2f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20f), 0, 0, 0) }
        col.addView(text(a.label, 24f, Color.WHITE, true))
        col.addView(text("▶  " + getString(R.string.open), 16f, 0xFF0B1630.toInt(), true).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(22f).toFloat() }
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14f) })
        row.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        card.addView(row, FrameLayout.LayoutParams(-1, -1))
        return card
    }

    private fun tile(a: AppEntry): View {
        val box = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = cardBg(0xFF1A2440.toInt(), false)
            setOnFocusChangeListener { v, f -> v.background = cardBg(0xFF1A2440.toInt(), f); v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start() }
            setOnClickListener { launch(a) }
        }
        if (a.banner != null) {
            box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(a.banner); setPadding(dp(4f), dp(4f), dp(4f), dp(4f)) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            col.addView(ImageView(this).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(44f), dp(44f)))
            col.addView(text(a.label, 13f, Color.WHITE, false).apply { maxLines = 1; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
            box.addView(col, FrameLayout.LayoutParams(-1, -1))
        }
        return box
    }

    private fun pill(label: String, onClick: () -> Unit) = text(label, 15f, Color.WHITE, true).apply {
        isFocusable = true; isClickable = true
        setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        background = cardBg(0xFF1A2440.toInt(), false)
        setOnFocusChangeListener { v, f -> v.background = cardBg(0xFF1A2440.toInt(), f) }
        setOnClickListener { onClick() }
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
