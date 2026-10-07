package com.galaxytvstick.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.galaxytvstick.app.data.Prefs
import com.galaxytvstick.app.ui.LoginActivity

/** Reglaj "Louvri app la lè aparèy la limen": lanse app la apre Fire Stick / Android TV fin demare. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Prefs(context).autoStart) return
        // Kèk vèsyon Fire OS resan bloke app ki louvri pou kont yo; nan ka sa a pa gen anyen ki rive.
        runCatching { context.startActivity(Intent(context, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
