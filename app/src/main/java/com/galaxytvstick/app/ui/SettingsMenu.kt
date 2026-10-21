package com.galaxytvstick.app.ui

import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.galaxytvstick.app.R
import com.galaxytvstick.app.data.ChannelStore
import com.galaxytvstick.app.data.Prefs

/**
 * Meni Settings app la (kliyan, plan, ekspirasyon, playlist, lang, kontwòl paran, mizajou…).
 * Pataje ant gid la ak player plen ekran an: konsa bouton ≡ a louvri Settings TOUSWIT sou videyo a,
 * san l pa bezwen chaje tout gid la anvan (se sa ki te fè TV a kanpe kèk segonn).
 */
class SettingsMenu(
    private val a: AppCompatActivity,
    private val prefs: Prefs,
    /** Lis chanèl yo chanje (lòd A→Z, kontwòl paran): ekran an rechaje lis li. */
    private val onListsChanged: () -> Unit
) {
    companion object {
        val LANG_CODES = listOf("ht", "en", "fr", "es")
        val LANG_NAMES = listOf("Kreyòl", "English", "Français", "Español")
        /** Dat ekspirasyon founisè a bay (si panel la pa gen youn pou kliyan an). */
        @Volatile var providerExpiry: String? = null
        /** Plan an selon founisè a (si panel la poko gen youn pou kliyan an). */
        @Volatile var providerPlan: String = ""
    }

    private fun onOff(on: Boolean) = a.getString(if (on) R.string.switch_on else R.string.switch_off)

    private fun info(title: Int, message: String) {
        val d = androidx.appcompat.app.AlertDialog.Builder(a, R.style.Theme_Galaxy_Dialog)
            .setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).create()
        glass(d)
        d.show()
    }


    /** Menm règ ak panel la: esè → Trial, sinon dire kont lan (kreyasyon → ekspirasyon). */
    fun planOf(i: com.galaxytvstick.app.data.XtreamApi.ProviderInfo): String {
        if (i.trial) return "Trial"
        val now = System.currentTimeMillis() / 1000
        val days = when {
            i.exp <= 0 -> return ""
            i.created in 1 until i.exp -> (i.exp - i.created) / 86400.0
            else -> (i.exp - now).coerceAtLeast(0) / 86400.0
        }
        return when {
            days < 8 -> "Trial"
            days <= 49 -> "1 Month"
            days <= 137 -> "3 Months"
            days <= 274 -> "6 Months"
            else -> "12 Months"
        }
    }
    private fun planText(cfg: com.galaxytvstick.app.data.PanelConfig) = cfg.accountPlan.ifBlank { providerPlan }

    /** Lis chwa ak fokis ble byen vizib (lis AlertDialog estanda a pa montre fokis la sou TV). */
    private fun menuDialog(title: Int, items: List<Pair<String, () -> Unit>>) {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(a, R.style.Theme_Galaxy_Dialog)
            .setTitle(title)
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .create()
        for ((label, action) in items) {
            box.addView(TextView(a).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (42 * d).toInt()).apply { bottomMargin = (2 * d).toInt() }
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                text = label
                textSize = 16f
                setSingleLine(true)
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setBackgroundResource(R.drawable.bg_menu_item)
                isFocusable = true
                isClickable = true
                setOnClickListener { dialog.dismiss(); action() }
            })
        }
        glass(dialog)
        dialog.show()
        box.getChildAt(0)?.requestFocus()
    }

    /** Fon yon ti jan transparan: videyo a / gid la parèt dèyè meni an. */
    private fun glass(d: android.app.Dialog) {
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_glass)
        d.window?.setDimAmount(0.2f)
    }

    fun show() {
        val cfg = PanelState.config
        val exp = fmtExpiry(cfg.accountExpiry.ifBlank { providerExpiry.orEmpty() }).ifBlank { "—" }
        val version = com.galaxytvstick.app.BuildConfig.VERSION_NAME
        menuDialog(R.string.home_settings, listOf(
            a.getString(R.string.set_customer, cfg.accountName.ifBlank { "—" }) to {
                info(R.string.set_expiry_title, a.getString(R.string.set_customer, cfg.accountName.ifBlank { "—" }))
            },
            a.getString(R.string.home_plan_fmt, planText(cfg).ifBlank { "—" }) to {
                info(R.string.set_expiry_title, a.getString(R.string.home_plan_fmt, planText(cfg).ifBlank { "—" }))
            },
            a.getString(R.string.set_expiry, exp) to {
                info(R.string.set_expiry_title, listOf(
                    cfg.accountName,
                    if (planText(cfg).isBlank()) "" else a.getString(R.string.home_plan_fmt, planText(cfg)),
                    a.getString(R.string.home_exp_fmt, exp)
                ).filter { it.isNotBlank() }.joinToString("\n"))
            },
            a.getString(R.string.set_playlist, prefs.account?.name?.ifBlank { null } ?: prefs.account?.username ?: "—") to { choosePlaylist() },
            a.getString(R.string.set_device_info) to { info(R.string.set_device_info, a.getString(R.string.set_info_fmt, cfg.accountName, prefs.mac, prefs.deviceKey, version)) },
            a.getString(R.string.set_language, LANG_NAMES[LANG_CODES.indexOf(currentLang())]) to { chooseLanguage() },
            a.getString(R.string.set_sort, a.getString(if (prefs.sortAz) R.string.sort_az else R.string.sort_server)) to {
                prefs.sortAz = !prefs.sortAz
                ChannelStore.sortAz = prefs.sortAz
                onListsChanged()
                show()
            },
            a.getString(R.string.set_captions, captionName(prefs.captions)) to { chooseCaptions() },
            a.getString(if (prefs.parentalOn) R.string.set_parental_on else R.string.set_parental_off) to { parentalControl() },
            a.getString(R.string.set_autostart, onOff(prefs.autoStart)) to { prefs.autoStart = !prefs.autoStart; show() },
            a.getString(R.string.set_last_channel, onOff(prefs.startLastChannel)) to { prefs.startLastChannel = !prefs.startLastChannel; show() },
            a.getString(R.string.set_update, version) to { Updater.checkNow(a, cfg.update) },
            a.getString(R.string.set_about, version) to { info(R.string.set_about_title, a.getString(R.string.set_about_fmt, version, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE) + "\n\n" + a.getString(R.string.disclaimer)) },
            a.getString(R.string.exit_title) to { a.finishAffinity() }
        ))
    }

    /** Playlist yo panel la bay: chwazi youn (app la rechaje chanèl yo). */
    private fun choosePlaylist() {
        val list = PanelState.config.playlists
        val cur = prefs.account?.playlistId
        if (list.size <= 1) {
            info(R.string.set_playlist_title, prefs.account?.name?.ifBlank { null } ?: prefs.account?.username ?: "—")
            return
        }
        menuDialog(R.string.set_playlist_title, list.map { pl ->
            ((if (pl.id == cur) "✓  " else "     ") + pl.name.ifBlank { pl.username }) to {
                if (pl.id != cur) {
                    val account = pl.toAccount(com.galaxytvstick.app.BuildConfig.DEFAULT_SERVER)
                    if (account == null) {
                        android.widget.Toast.makeText(a, a.getString(R.string.error_no_server, pl.name), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        prefs.account = account
                        prefs.lastChannelId = -1
                        a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        a.finish()
                    }
                }
            }
        })
    }

    /** Dat ekspirasyon an fòma Ameriken: 11/10/2026 2:30 PM (12 è). */
    private fun fmtExpiry(raw: String): String {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        val utc = v.endsWith("Z", true)
        val clean = v.removeSuffix("Z").removeSuffix("z").substringBefore('.').replace(Regex("[+-]\\d{2}:?\\d{2}$"), "")
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (p in patterns) {
            val inF = java.text.SimpleDateFormat(p, java.util.Locale.US).apply {
                isLenient = false
                if (utc) timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val d = runCatching { inF.parse(clean) }.getOrNull() ?: continue
            val out = if (p == "yyyy-MM-dd") "MM/dd/yyyy" else "MM/dd/yyyy h:mm a"
            return java.text.SimpleDateFormat(out, java.util.Locale.US).format(d)
        }
        return v
    }

    /** Lang app la kounye a: sa kliyan an chwazi, sinon lang TV a (Kreyòl si pa youn nan 4 yo). */
    private fun currentLang(): String {
        val picked = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags().take(2)
        val sys = java.util.Locale.getDefault().language
        return listOf(picked, sys).firstOrNull { it in LANG_CODES } ?: "ht"
    }

    /** Kliyan an chwazi lang app la; ekran yo rechaje nan nouvo lang nan. */
    private fun captionName(code: String): String = when (code) {
        "off" -> a.getString(R.string.captions_off)
        "any" -> a.getString(R.string.captions_any)
        else -> Captions.NAMES.getOrNull(Captions.CODES.indexOf(code))?.ifBlank { null } ?: code
    }

    /** Soutit: etenn, nenpòt lang, oswa yon lang presi. Aplike touswit sou videyo k ap jwe a. */
    private fun chooseCaptions() {
        val cur = prefs.captions
        menuDialog(R.string.captions_title, Captions.CODES.map { code ->
            ((if (code == cur) "✓  " else "     ") + captionName(code)) to {
                prefs.captions = code
                Captions.onChanged?.invoke()
                show()
            }
        })
    }

    private fun chooseLanguage() {
        val cur = currentLang()
        menuDialog(R.string.language, LANG_CODES.mapIndexed { i, code ->
            ((if (code == cur) "✓  " else "     ") + LANG_NAMES[i]) to {
                if (code != cur) androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(code)
                )
            }
        })
    }

    /** Mande yon PIN 4 chif (klavye Fire TV a). */
    private fun askPin(title: Int, onPin: (String) -> Unit) {
        val input = android.widget.EditText(a).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            hint = "••••"
            gravity = Gravity.CENTER
            textSize = 26f
            setTextColor(ContextCompat.getColor(context, R.color.text))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_dim))
        }
        androidx.appcompat.app.AlertDialog.Builder(a, R.style.Theme_Galaxy_Dialog)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4) onPin(pin) else android.widget.Toast.makeText(a, R.string.parental_pin_4, android.widget.Toast.LENGTH_LONG).show()
            }
            .show()
        input.requestFocus()
    }

    /** Kontwòl paran: kache kategori pou granmoun. Limen l = kreye/konfime PIN; etenn li = antre PIN lan. */
    private fun parentalControl() {
        fun setParental(on: Boolean) {
            prefs.parentalOn = on
            ChannelStore.hideAdult = on
            onListsChanged()
            show()
        }
        if (prefs.parentalOn) {
            askPin(R.string.parental_enter_pin) { pin ->
                if (pin == prefs.parentalPin) setParental(false)
                else android.widget.Toast.makeText(a, R.string.parental_wrong_pin, android.widget.Toast.LENGTH_LONG).show()
            }
        } else if (prefs.parentalPin.isEmpty()) {
            askPin(R.string.parental_new_pin) { pin -> prefs.parentalPin = pin; setParental(true) }
        } else {
            askPin(R.string.parental_enter_pin) { pin ->
                if (pin == prefs.parentalPin) setParental(true)
                else android.widget.Toast.makeText(a, R.string.parental_wrong_pin, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

}
