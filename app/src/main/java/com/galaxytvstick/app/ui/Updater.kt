package com.galaxytvstick.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    /** Retounen true si yon mesaj mizajou parèt (oswa deja sou ekran an). */
    fun maybePrompt(activity: Activity, u: AppUpdate?, prefs: Prefs): Boolean {
        if (u == null || u.build <= BuildConfig.VERSION_CODE) return false
        if (showing || downloading) return true
        // Pa obligatwa: yon sèl fwa pou chak "voye" panel la fè
        if (!u.force && (u.pushAt.isBlank() || u.pushAt == prefs.updatePushSeen)) return false
        if (activity.isFinishing) return false
        showing = true
        prefs.updatePushSeen = u.pushAt
        val b = AlertDialog.Builder(activity, R.style.Theme_Galaxy_Dialog)
            .setTitle(R.string.update_title)
            .setMessage(activity.getString(if (u.force) R.string.update_msg_force else R.string.update_msg, u.version))
            .setCancelable(!u.force)
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, u) }
            .setOnDismissListener { showing = false }
        if (!u.force) b.setNegativeButton(R.string.update_later, null)
        runCatching { b.show() }.onFailure { showing = false }
        return true
    }

    private fun download(activity: Activity, u: AppUpdate) {
        if (downloading) return
        downloading = true
        val app = activity.applicationContext
        val progress = runCatching {
            AlertDialog.Builder(activity, R.style.Theme_Galaxy_Dialog)
                .setTitle(R.string.update_title)
                .setMessage(activity.getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .show()
        }.getOrNull()
        GalaxyApp.scope.launch {
            val file = File(app.externalCacheDir ?: app.cacheDir, "GalaxyTvStick-update.apk")
            val ok = runCatching {
                XtreamApi.http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(u.url).build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val body = r.body ?: error("vid")
                        val total = body.contentLength()
                        var done = 0L
                        var lastPct = -1
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            val input = body.byteStream()
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                                if (pct != lastPct && pct >= 0) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { runCatching { progress?.setMessage(app.getString(R.string.update_downloading, pct)) } }
                                }
                            }
                        }
                        if (file.length() < 1_000_000) error("fichye a twò piti")
                    }
            }
            withContext(Dispatchers.Main) {
                downloading = false
                runCatching { progress?.dismiss() }
                if (ok.isSuccess) install(activity, file)
                else Toast.makeText(app, app.getString(R.string.update_failed, ok.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun install(activity: Activity, file: File) {
        val ctx = activity.applicationContext
        // Fire OS / Android 8+: fòk aparèy la otorize app la enstale APK (yon sèl fwa)
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, R.string.update_allow, Toast.LENGTH_LONG).show()
        }
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.files", file)
        else { file.setReadable(true, false); Uri.fromFile(file) }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }.onFailure {
            Toast.makeText(ctx, ctx.getString(R.string.update_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
