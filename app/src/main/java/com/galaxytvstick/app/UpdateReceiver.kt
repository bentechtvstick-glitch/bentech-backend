package com.galaxytvstick.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.galaxytvstick.app.ui.LoginActivity

/**
 * Mizajou app la:
 *  - Rezilta enstalasyon an (PackageInstaller): si sistèm nan mande konfimasyon, montre l touswit;
 *    sou Android 12+ li pa menm mande (mizajou an silans).
 *  - Apre mizajou a (MY_PACKAGE_REPLACED): relouvri app la poukont li, kliyan an pa bezwen chèche l.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Relouvri app la sèlman si kliyan an te peze "Mete ajou" (pa pou yon mizajou an silans an aryè plan)
            val sp = context.getSharedPreferences("galaxy_update", Context.MODE_PRIVATE)
            if (sp.getBoolean("relaunch", false)) {
                sp.edit().putBoolean("relaunch", false).apply()
                runCatching { context.startActivity(Intent(context, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                com.galaxytvstick.app.ui.Updater.setState("confirm")
                runCatching { context.startActivity(confirm) }.onFailure { com.galaxytvstick.app.ui.Updater.setState("failed: confirm blocked") }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> { // kliyan an peze "Cancel"
                com.galaxytvstick.app.ui.Updater.setState("cancelled")
                context.getSharedPreferences("galaxy_update", Context.MODE_PRIVATE).edit().putBoolean("relaunch", false).apply()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                com.galaxytvstick.app.ui.Updater.setState("failed: $msg")
                Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
