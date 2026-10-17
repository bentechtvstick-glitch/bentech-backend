package com.galaxytvstick.app.ui

import android.text.TextUtils
import android.view.View
import android.widget.TextView

/**
 * Non ki twò long ("…"): lè w kanpe sou li ak remòt la, tèks la defile pou montre rès la,
 * epi lè w kite l, li tounen nòmal ak "…" ankò.
 */
object Marquee {
    fun set(tv: TextView, on: Boolean) {
        if (on) {
            tv.setHorizontallyScrolling(true)
            tv.marqueeRepeatLimit = -1
            tv.isHorizontalFadingEdgeEnabled = true
            tv.ellipsize = TextUtils.TruncateAt.MARQUEE
            tv.isSelected = true
        } else {
            tv.isSelected = false
            tv.isHorizontalFadingEdgeEnabled = false
            tv.ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** Rele nan onBind: mete lis tèks yo ki dwe defile lè [root] gen fokis la. */
    fun bind(root: View, vararg tvs: TextView, extra: ((View, Boolean) -> Unit)? = null) {
        val has = root.hasFocus()
        tvs.forEach { set(it, has) }
        root.setOnFocusChangeListener { v, f ->
            tvs.forEach { set(it, f) }
            extra?.invoke(v, f)
        }
    }
}
