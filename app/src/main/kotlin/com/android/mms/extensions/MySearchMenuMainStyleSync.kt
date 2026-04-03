package com.android.mms.extensions

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.mms.R
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.commons.views.MySearchMenu
import kotlin.math.max
import kotlin.math.min

private const val MAIN_MENU_OVERSCROLL_FACTOR = 0.35f

fun syncBlurTargetTopMarginForMenu(blurTarget: View, menuHeight: Int) {
    if (menuHeight < 0) return
    val targetTopMargin = -menuHeight
    blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        if (topMargin != targetTopMargin) {
            topMargin = targetTopMargin
        }
    }
}

fun syncTopSideFrameHeightForMenu(sideFrame: View, menuHeight: Int) {
    if (menuHeight < 0) return
    sideFrame.updateLayoutParams<ViewGroup.LayoutParams> {
        val h = menuHeight / 2
        if (height != h) {
            height = h
        }
    }
}

/** Same geometry as [com.android.mms.activities.MainActivity.getRecentsListTopInsetPx]. */
fun getMySearchMenuListTopInsetPx(menu: MySearchMenu, list: View): Int {
    var base = menu.height.takeIf { it > 0 }
        ?: menu.measuredHeight.takeIf { it > 0 }
        ?: 0
    if (
        list.visibility == View.VISIBLE &&
        menu.visibility == View.VISIBLE &&
        menu.isLaidOut &&
        list.isLaidOut &&
        menu.height > 0
    ) {
        val mLoc = IntArray(2)
        val lLoc = IntArray(2)
        menu.getLocationOnScreen(mLoc)
        list.getLocationOnScreen(lLoc)
        val inset = (mLoc[1] + menu.height) - lLoc[1]
        // Only trust geometry when it matches ~one app bar height. Stale coordinates after
        // resume (before coordinator + blur margin settle) often produce values up to ~3× height.
        val slack = (48 * list.resources.displayMetrics.density).toInt()
        if (inset > 0 && inset <= menu.height + slack) {
            base = inset
        }
    }
    if (menu.requireCustomToolbar().isSearchExpanded) {
        val minSearchListTop = list.resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
        return max(base, minSearchListTop)
    }
    return base
}

fun applyMySearchMenuListTopPadding(menu: MySearchMenu, list: View) {
    // Skip until the app bar has a real height; otherwise measuredHeight / stale
    // getLocationOnScreen after resume can apply a huge top pad (e.g. returning from another activity).
    if (!menu.isAttachedToWindow || !menu.isLaidOut || menu.height <= 0) return
    val inset = getMySearchMenuListTopInsetPx(menu, list)
    if (inset <= 0) return
    val density = list.resources.displayMetrics.density
    val slack = (48 * density).toInt()
    val cap = if (menu.requireCustomToolbar().isSearchExpanded) {
        max(
            list.resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top),
            menu.height + slack,
        )
    } else {
        menu.height + slack
    }
    list.updatePadding(top = min(inset, cap))
}

/**
 * After coordinator layout, measure full [MySearchMenu] height and sync blur negative margin,
 * optional top [MVSideFrame] height, and list top padding (txDial / MainActivity pattern).
 */
fun postSyncMySearchMenuToolbarGeometry(
    @Suppress("UNUSED_PARAMETER") root: View,
    menu: MySearchMenu,
    blurTarget: View,
    topSideFrame: View?,
    paddedList: View?,
    /** When false, keeps [blurTarget] top margin at 0 (negative margin can break BlurView sampling on some screens). */
    applyNegativeBlurTargetTopMargin: Boolean = true,
) {
    fun applyListTop() {
        val l = paddedList ?: return
        applyMySearchMenuListTopPadding(menu, l)
    }
    menu.post {
        menu.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        menu.post {
            val h = menu.height.takeIf { it > 0 }
                ?: menu.measuredHeight.takeIf { it > 0 }
                ?: return@post
            topSideFrame?.let { syncTopSideFrameHeightForMenu(it, h) }
            if (applyNegativeBlurTargetTopMargin) {
                syncBlurTargetTopMarginForMenu(blurTarget, h)
            } else {
                blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (topMargin != 0) topMargin = 0
                }
            }
            applyListTop()
        }
    }
    // Avoid applyListTop() here: it can run before blur negative margin is synced and yields
    // bogus screen geometry (extra top padding after returning from another activity).
}

fun setupMySearchMenuSpringSync(menu: MySearchMenu, recycler: MyRecyclerView?) {
    recycler?.onOverscrollTranslationChanged = { translationY ->
        menu.translationY = translationY * MAIN_MENU_OVERSCROLL_FACTOR
    }
}

fun clearMySearchMenuSpringSync(menu: MySearchMenu, recycler: MyRecyclerView?) {
    recycler?.onOverscrollTranslationChanged = null
    menu.translationY = 0f
}
