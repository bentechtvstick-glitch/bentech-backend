package com.galaxytvstick.app

import android.app.Application
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

/**
 * Chaje font imoji ki anndan app la (Noto Color Emoji), pou tout imoji ak senbòl
 * panel la voye (ticker, mesaj, pop-up) parèt kòrèkteman sou tout Fire TV / Android TV,
 * menm sou ansyen vèsyon Android ki pa konnen nouvo imoji yo.
 */
class GalaxyApp : Application() {
    companion object {
        /** Travay an aryè plan ki dwe kontinye menm lè yon ekran fèmen. */
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        // Si app la fèmen sibitman, sove rezon an pou montre l pwochen fwa (ede jwenn pwoblèm nan)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                var root: Throwable = e
                while (root.cause != null && root.cause !== root) root = root.cause!!
                val frame = root.stackTrace.firstOrNull { it.className.startsWith("com.galaxytvstick") } ?: root.stackTrace.firstOrNull()
                val where = frame?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "?"
                val text = "v${BuildConfig.VERSION_NAME} · ${root.javaClass.simpleName}: ${(root.message ?: "").take(120)} · $where · ${thread.name}"
                getSharedPreferences("galaxy_crash", MODE_PRIVATE).edit().putString("last", text).commit()
            }
            previous?.uncaughtException(thread, e)
        }
        com.galaxytvstick.app.data.EpgRepository.init(this)
        com.galaxytvstick.app.data.ChannelStore.hideAdult = com.galaxytvstick.app.data.Prefs(this).parentalOn
        com.galaxytvstick.app.data.ChannelStore.sortAz = com.galaxytvstick.app.data.Prefs(this).sortAz
        // Mizajou an silans: lè app la pa sou ekran an ankò (kliyan an peze Home, oswa TV a etenn),
        // enstale vèsyon ki deja telechaje a. Pwochen fwa kliyan an louvri app la, se nouvo vèsyon an.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private val ui = android.os.Handler(android.os.Looper.getMainLooper())
            private val bg = Runnable { if (started == 0) com.galaxytvstick.app.ui.Updater.installIfReady(this@GalaxyApp) }
            override fun onActivityStarted(a: android.app.Activity) { started++; ui.removeCallbacks(bg) }
            override fun onActivityStopped(a: android.app.Activity) { started = (started - 1).coerceAtLeast(0); if (started == 0) ui.postDelayed(bg, 4000) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        EmojiCompat.init(
            BundledEmojiCompatConfig(this)
                .setReplaceAll(true) // menm desen imoji sou tout aparèy
        )
    }
}
