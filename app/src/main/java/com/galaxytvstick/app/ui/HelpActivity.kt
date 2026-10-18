package com.galaxytvstick.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.galaxytvstick.app.R

/** Manyèl itilizatè a: kijan pou itilize app la ak remòt la (nan lang TV a). */
class HelpActivity : AppCompatActivity() {

    private val sections = listOf(
        R.string.help_1_t to R.string.help_1_b, R.string.help_2_t to R.string.help_2_b,
        R.string.help_3_t to R.string.help_3_b, R.string.help_4_t to R.string.help_4_b,
        R.string.help_5_t to R.string.help_5_b, R.string.help_6_t to R.string.help_6_b,
        R.string.help_7_t to R.string.help_7_b, R.string.help_8_t to R.string.help_8_b,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(28), dp(48), dp(20)) }
        root.addView(TextView(this).apply {
            text = getString(R.string.help_title); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.help_sub); setTextColor(0xFF9AA3B5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(4), 0, dp(14))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        for ((t, bdy) in sections) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                setPadding(dp(20), dp(14), dp(20), dp(16))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xFF1E2A4A.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(3), 0xFF6EA2FF.toInt()) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(0xFF151C2E.toInt()); cornerRadius = dp(14).toFloat() })
                }
                addView(TextView(context).apply { text = getString(t); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = android.graphics.Typeface.DEFAULT_BOLD })
                addView(TextView(context).apply { text = getString(bdy); setTextColor(0xFFD7DCE8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setLineSpacing(0f, 1.25f); setPadding(0, dp(6), 0, 0) })
            }
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            if (first == null) first = card
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = getString(R.string.help_back); setTextColor(0xFF9AA3B5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        root.setBackgroundColor(0xFF0B1020.toInt())
        setContentView(root)
        first?.post { first?.requestFocus() }
    }
}
