package com.galaxytvstick.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.galaxytvstick.app.BuildConfig
import com.galaxytvstick.app.GalaxyApp
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.AppUpdate
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.data.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Mizajou app la san re-enstale alamen: lè admin nan "voye mizajou" depi panel la,
 * kliyan an wè yon mesaj, app la telechaje APK a epi li louvri enstalatè Fire Stick la.
 */
object Updater {
    @Volatile private var showing = false
    @Volatile private var downloading = false
    /** Mizajou ki te deja parèt pandan app la louvri a (pa repete l chak minit). */
    @Volatile private var promptedPush: String? = null
    /** APK ki telechaje men ki tann otorizasyon "Install unknown apps" la. */
    @Volatile private var pendingFile: File? = null

    // ---------------------------------------------------------------- Mizajou an silans (Android 12+)
    /**
     * Android 12+ kite yon app mete tèt li ajou san okenn ekran, depi se li menm ki te enstale vèsyon ki la a
     * (sa vrè apre premye mizajou ki fèt pa app la). Lè sa a: telechaje an aryè plan pandan kliyan an ap gade,
     * epi enstale lè app la pa sou ekran an (Home, TV etenn). Kliyan an pa wè anyen.
     */
    fun canSilent(ctx: android.content.Context): Boolean = runCatching {
        Build.VERSION.SDK_INT >= 31 && ctx.packageManager.canRequestPackageInstalls() &&
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName
    }.getOrDefault(false)

    @Volatile private var readyFile: File? = null
    @Volatile private var readyBuild = 0
    @Volatile private var preparing = false

    /** Telechaje nouvo vèsyon an an silans (pa gen okenn ekran). */
    fun prepareSilent(ctx: android.content.Context, build: Int, url: String) {
        if (build <= BuildConfig.VERSION_CODE || build == readyBuild || preparing) return
        preparing = true
        val app = ctx.applicationContext
        GalaxyApp.scope.launch {
            val f = File(app.filesDir, "update-$build.apk")
            val ok = runCatching {
                XtreamApi.http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        f.outputStream().use { out -> r.body!!.byteStream().copyTo(out, 64 * 1024) }
                        if (f.length() < 1_000_000) error("file too small")
                    }
            }.isSuccess
            if (ok) { readyFile = f; readyBuild = build } else f.delete()
            preparing = false
        }
    }

    /** App la pa sou ekran an: enstale vèsyon ki pare a an silans. */
    fun installIfReady(ctx: android.content.Context) {
        val f = readyFile ?: return
        if (!f.exists() || readyBuild <= BuildConfig.VERSION_CODE || !canSilent(ctx)) return
        readyFile = null
        GalaxyApp.scope.launch { installSession(ctx.applicationContext, f) }
    }

    /** Retounen true si yon mesaj mizajou parèt (oswa deja sou ekran an). */
    fun maybePrompt(activity: Activity, u: AppUpdate?, prefs: Prefs): Boolean {
        if (u == null || u.build <= BuildConfig.VERSION_CODE) return false
        // Mizajou an silans posib: pa deranje kliyan an, telechaje l an aryè plan
        if (canSilent(activity)) { prepareSilent(activity, u.build, u.url.ifBlank { APK_URL }); return false }
        if (showing || downloading) return true
        // Kliyan an te otorize enstalasyon an nan Settings: enstale APK ki t ap tann nan touswit
        pendingFile?.let { f ->
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) {
                pendingFile = null
                if (f.exists()) { install(activity, f); return true }
            }
        }
        // Pa obligatwa: li parèt yon fwa chak fwa kliyan an louvri app la (tankou TiviMate), jiskaske l fè mizajou a.
        // (Anvan: yon sèl fwa pou tout tan — si kliyan an te peze "Later", li pa t janm wè l ankò.)
        if (!u.force && (u.pushAt.isBlank() || u.pushAt == promptedPush)) return false
        if (activity.isFinishing) return false
        showing = true
        promptedPush = u.pushAt
        prefs.updatePushSeen = u.pushAt
        runCatching { showDialog(activity, u) }.onFailure { showing = false }
        return true
    }

    private const val APK_URL = "https://github.com/bentechtvstick-glitch/bentech-backend/releases/download/apk-latest/GalaxyTvStick.apk"
    private const val VERSION_URL = "https://github.com/bentechtvstick-glitch/bentech-backend/releases/download/apk-latest/version.json"

    /**
     * Bouton "Mizajou app la" nan Settings: kliyan an tcheke li menm si gen yon nouvo vèsyon,
     * san l pa tann panel la voye l. Si gen youn, menm fenèt mizajou a parèt.
     */
    @Volatile private var lastAutoCheck = 0L
    @Volatile private var autoPromptedBuild = 0

    /**
     * Otomatik: app la tcheke poukont li (chak 6 è, ak lè l louvri) si gen yon nouvo vèsyon sou GitHub,
     * menm si admin nan poko peze "Voye mizajou" nan panel la. Li montre mesaj la yon fwa pa vèsyon pandan app la louvri.
     */
    fun autoCheck(activity: Activity) {
        if (showing || downloading) return
        val now = System.currentTimeMillis()
        if (now - lastAutoCheck < 6 * 3600_000L) return
        lastAutoCheck = now
        GalaxyApp.scope.launch {
            val b = runCatching {
                XtreamApi.http.newCall(Request.Builder().url("$VERSION_URL?t=$now").build()).execute().use { r ->
                    if (!r.isSuccessful) 0 else org.json.JSONObject(r.body?.string().orEmpty()).optInt("build")
                }
            }.getOrDefault(0)
            if (b <= BuildConfig.VERSION_CODE || b == autoPromptedBuild) return@launch
            if (canSilent(activity)) { prepareSilent(activity, b, APK_URL); return@launch }
            withContext(Dispatchers.Main) {
                if (showing || downloading || activity.isFinishing || activity.isDestroyed) return@withContext
                autoPromptedBuild = b
                showing = true
                runCatching { showDialog(activity, AppUpdate(b, "1.0.$b", APK_URL, false, "auto", "")) }.onFailure { showing = false }
            }
        }
    }

    fun checkNow(activity: Activity, pushed: AppUpdate?) {
        if (showing || downloading) return
        val app = activity.applicationContext
        Toast.makeText(app, R.string.update_checking, Toast.LENGTH_SHORT).show()
        GalaxyApp.scope.launch {
            val build = runCatching {
                XtreamApi.http.newCall(Request.Builder().url("$VERSION_URL?t=${System.currentTimeMillis()}").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    org.json.JSONObject(r.body?.string().orEmpty()).optInt("build")
                }
            }
            withContext(Dispatchers.Main) {
                val b = build.getOrNull() ?: 0
                when {
                    build.isFailure || b <= 0 -> Toast.makeText(app, app.getString(R.string.update_failed, build.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                    b <= BuildConfig.VERSION_CODE -> Toast.makeText(app, app.getString(R.string.update_latest, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show()
                    activity.isFinishing -> {}
                    else -> {
                        showing = true
                        // Nòt "Sa ki nouvo" panel la si se menm vèsyon an
                        val notes = pushed?.takeIf { it.build == b }?.notes.orEmpty()
                        runCatching { showDialog(activity, AppUpdate(b, "1.0.$b", pushed?.takeIf { it.build == b }?.url ?: APK_URL, false, "manual", notes)) }.onFailure { showing = false }
                    }
                }
            }
        }
    }

    /** Ekran mizajou a (style TiviMate): icon, vèsyon, sa ki nouvo, ba pwogresyon nan menm fenèt la. */
    private fun showDialog(activity: Activity, u: AppUpdate) {
        val v = com.galaxytvstick.app.databinding.DialogUpdateBinding.inflate(activity.layoutInflater)
        val dialog = android.app.Dialog(activity, R.style.Theme_Galaxy_Dialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(v.root)
        dialog.setCancelable(!u.force)
        dialog.setOnDismissListener { showing = false }
        v.upVersion.text = activity.getString(R.string.update_version_fmt, u.version, BuildConfig.VERSION_NAME)
        v.upNotes.text = u.notes.ifBlank { activity.getString(R.string.update_default_notes) }
        v.upLater.visibility = if (u.force) android.view.View.GONE else android.view.View.VISIBLE
        v.upLater.setOnClickListener { dialog.dismiss() }
        v.upNow.setOnClickListener {
            v.upButtons.visibility = android.view.View.GONE
            v.upProgressBox.visibility = android.view.View.VISIBLE
            v.upProgress.progress = 0
            v.upPct.text = activity.getString(R.string.update_downloading, 0)
            dialog.setCancelable(false)
            download(activity, u, onProgress = { pct ->
                if (pct < 0) v.upProgress.isIndeterminate = true else { v.upProgress.isIndeterminate = false; v.upProgress.progress = pct }
                v.upPct.text = activity.getString(R.string.update_downloading, pct.coerceAtLeast(0))
            }, onDone = { ok ->
                if (ok) { v.upPct.text = activity.getString(R.string.update_installing); runCatching { dialog.dismiss() } }
                else { v.upButtons.visibility = android.view.View.VISIBLE; v.upProgressBox.visibility = android.view.View.GONE; dialog.setCancelable(!u.force); v.upNow.requestFocus() }
            })
        }
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        v.upNow.requestFocus()
    }

    private fun download(activity: Activity, u: AppUpdate, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        val app = activity.applicationContext
        GalaxyApp.scope.launch {
            val file = File(app.externalCacheDir ?: app.cacheDir, "GalaxyTvStick-update.apk")
            val ok = runCatching {
                XtreamApi.http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(u.url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val body = r.body ?: error("empty")
                        val total = body.contentLength()
                        var done = 0L
                        var lastPct = -2
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            val input = body.byteStream()
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { runCatching { onProgress(pct) } }
                                }
                            }
                        }
                        if (file.length() < 1_000_000) error("file too small")
                    }
            }
            withContext(Dispatchers.Main) {
                downloading = false
                runCatching { onDone(ok.isSuccess) }
                if (ok.isSuccess) install(activity, file)
                else Toast.makeText(app, app.getString(R.string.update_failed, ok.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Enstale APK a ak PackageInstaller. Retounen false si aparèy la pa sipòte l (lè sa a nou sèvi ak ACTION_VIEW). */
    private fun installSession(ctx: android.content.Context, file: File): Boolean = runCatching {
        val installer = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Android 12+: mizajou app la li menm te enstale → pa bezwen konfimasyon
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val pi = android.app.PendingIntent.getBroadcast(ctx, id, Intent(ctx, com.galaxytvstick.app.UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
        true
    }.getOrDefault(false)

    private fun install(activity: Activity, file: File) {
        val ctx = activity.applicationContext
        ctx.getSharedPreferences("galaxy_update", android.content.Context.MODE_PRIVATE).edit().putBoolean("relaunch", true).apply()
        // Fire OS / Android 8+: fòk aparèy la otorize app la enstale APK (yon sèl fwa).
        // Louvri paj otorizasyon an dirèkteman; lè kliyan an tounen, enstalasyon an kontinye poukont li.
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            pendingFile = file
            Toast.makeText(ctx, R.string.update_allow, Toast.LENGTH_LONG).show()
            val opened = runCatching {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            }.isSuccess
            if (opened) return
        }
        // 1) Enstalatè sistèm nan dirèkteman (PackageInstaller): yon sèl ti konfimasyon, oswa okenn sou Android 12+.
        //    Men Android 10+ entèdi yon app relouvri tèt li apre yon mizajou an silans (sof si TV a ba l pèmisyon
        //    "afiche sou lòt app"). San pèmisyon sa a, sèvi ak ekran enstalasyon Android la: li fini ak bouton "Open"
        //    ki gen fokis la, kliyan an jis peze OK pou retounen nan app la.
        //    Android 12+: enstalatè sistèm nan dirèkteman. Apre premye fwa sa a, app la vin "enstalatè" pa l,
        //    epi tout lòt mizajou yo fèt an silans (an aryè plan, lè kliyan an pa sou app la).
        //    Android 11 oswa mwens: ekran enstalasyon Android la, ak bouton "Open" a lafen.
        val useSession = Build.VERSION.SDK_INT >= 31
        if (useSession && installSession(ctx, file)) return
        // 2) Si sa pa mache: ansyen fason an (ekran enstalasyon Android la)
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.files", file)
        else { file.setReadable(true, false); Uri.fromFile(file) }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            // Ekran enstalasyon an pa louvri sou aparèy sa a: eseye enstalatè sistèm nan dirèkteman
            if (!installSession(ctx, file)) Toast.makeText(ctx, ctx.getString(R.string.update_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
