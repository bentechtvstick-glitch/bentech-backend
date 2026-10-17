package com.galaxytvstick.app.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Lis pou remòt TV: lè w desann (oswa monte) vit, pwochen liy lan ka poko desine.
 * Android al chèche yon lòt bagay pou fokis la (yon lòt kolòn, bouton anlè…): se sa ki fè liy lan "sove".
 * Isit la, si pwochen liy lan poko la, nou fè lis la woule, nou kenbe fokis la kote l ye,
 * epi depi liy lan desine nou mete fokis la sou li. Nan dènye liy lan, fokis la rete nan lis la.
 */
private class TvFocus(private val lm: RecyclerView.LayoutManager, private val vertical: () -> Boolean, private val step: () -> Int) {
    private var pending = false

    fun intercept(focused: View, direction: Int): View? {
        val forward = if (vertical()) View.FOCUS_DOWN else View.FOCUS_RIGHT
        val back = if (vertical()) View.FOCUS_UP else View.FOCUS_LEFT
        if (direction != forward && direction != back) return null
        val item = lm.findContainingItemView(focused) ?: return null
        val pos = lm.getPosition(item)
        val count = lm.itemCount
        if (pos == RecyclerView.NO_POSITION || count == 0) return null
        if (pending) return focused // nou deja ap tann liy lan: pa kite fokis la ale lòt kote
        val s = step()
        var next = if (direction == forward) pos + s else pos - s
        if (direction == forward && next >= count) {
            // Griy: dènye ranje a ka pa konplè → ale sou dènye eleman an si l nan yon ranje pi ba
            next = if (s > 1 && (count - 1) / s > pos / s) count - 1 else return focused
        }
        if (next < 0) return null // anlè lis la: kite fokis la monte (rechèch, bouton…)
        if (lm.findViewByPosition(next) != null) return null // liy lan la deja: Android jere l nòmalman
        pending = true
        lm.scrollToPosition(next)
        focused.post {
            focused.post {
                pending = false
                val target = lm.findViewByPosition(next)
                if (target != null) {
                    // Chèche nan direksyon an ankò kounye a liy lan desine (kenbe menm kolòn nan, sitou nan gid TV a)
                    val f = focused.takeIf { it.isAttachedToWindow }?.focusSearch(direction)
                    if (f != null && lm.findContainingItemView(f) != null) f.requestFocus() else target.requestFocus()
                }
            }
        }
        return focused
    }
}

class TvLinearLayoutManager(ctx: Context, orientation: Int = RecyclerView.VERTICAL) : LinearLayoutManager(ctx, orientation, false) {
    private val tv = TvFocus(this, { this.orientation == RecyclerView.VERTICAL }, { 1 })
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? =
        tv.intercept(focused, direction) ?: super.onInterceptFocusSearch(focused, direction)
}

class TvGridLayoutManager(ctx: Context, span: Int) : GridLayoutManager(ctx, span) {
    private val tv = TvFocus(this, { true }, { spanCount })
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? =
        tv.intercept(focused, direction) ?: super.onInterceptFocusSearch(focused, direction)
}
