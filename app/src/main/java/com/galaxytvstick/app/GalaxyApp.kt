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
        EmojiCompat.init(
            BundledEmojiCompatConfig(this)
                .setReplaceAll(true) // menm desen imoji sou tout aparèy
        )
    }
}
