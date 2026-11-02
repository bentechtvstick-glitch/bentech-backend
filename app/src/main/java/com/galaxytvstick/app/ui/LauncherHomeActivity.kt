package com.galaxytvstick.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.galaxytvstick.app.data.ChannelStore

/**
 * Mòd launcher: app la tounen ekran akèy box la (bouton Home telekòmand lan mennen isit la).
 * Aktivite sa a pa gen ekran: si app la deja chaje, li tounen sou ekran akèy app la touswit; sinon li louvri l nòmalman.
 * Li dezaktive pa defo (manifest la): admin nan limen l nan Settings → Mòd launcher (ak PIN).
 */
class LauncherHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (ChannelStore.all.isNotEmpty()) Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        else Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(next)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private fun comp(ctx: Context) = ComponentName(ctx, "com.galaxytvstick.app.LauncherHome")

        fun isEnabled(ctx: Context) = ctx.packageManager.getComponentEnabledSetting(comp(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.packageManager.setComponentEnabledSetting(comp(ctx),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }

        /** Mande box la chwazi ekran akèy la (Android montre lis la; admin nan chwazi app la epi "Always"). */
        fun askDefault(ctx: Context) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val settings = Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(settings) }.isFailure) runCatching { ctx.startActivity(home) }
        }
    }
}
