package net.gosmartservices.home

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mizajou otomatik launcher a: li gade version-home.json sou GitHub (chak 6 èdtan), telechaje nouvo APK a,
 * epi enstale l ak enstalatè Android la. Android 12+: an silans apre premye fwa a. Pi ansyen: yon ti konfimasyon.
 */
object LauncherUpdater {
    private const val RELEASE = "https://github.com/bentechtvstick-glitch/bentech-backend/releases/download/apk-latest/"
    @Volatile private var busy = false

    fun check(ctx: Context) {
        val sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (busy || now - sp.getLong("updCheck", 0L) < 6 * 3600_000L) return
        sp.edit().putLong("updCheck", now).apply()
        busy = true
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val build = JSONObject(get(RELEASE + "version-home.json?t=$now").toString(Charsets.UTF_8)).optInt("build")
                if (build > BuildConfig.VERSION_CODE) {
                    val f = File(app.filesDir, "home-update.apk")
                    f.writeBytes(get(RELEASE + BuildConfig.APK_NAME))
                    if (f.length() > 200_000) install(app, f)
                    f.delete()
                }
            }
            busy = false
        }.start()
    }

    private fun get(url: String): ByteArray {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 60000
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); return@repeat }
                if (code !in 200..299) error("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Si Android mande konfimasyon pou mizajou a, montre l. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
        }
    }
}
