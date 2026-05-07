package com.android.mms.extensions

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.mms.R
import com.google.android.material.appbar.AppBarLayout
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

/**
 * Sets the top blur side-frame height to collapsed menu height plus an optional feather so blur extends
 * slightly below transparent app bar chrome (scroll content is not legible under the title row).
 *
 * @param extraBlurBelowCollapsedPx when null, uses `R.dimen.tx_my_search_menu_top_blur_feather`; use `0` to disable.
 */
fun syncTopSideFrameHeightForMenu(
    sideFrame: View,
    menu: MySearchMenu,
    menuHeight: Int,
    extraBlurBelowCollapsedPx: Int? = null,
) {
    if (menuHeight < 0) return
    val collapsedMenuHeight = menu.getCollapsedHeightPx().takeIf { it > 0 } ?: menuHeight
    val feather = extraBlurBelowCollapsedPx
        ?: sideFrame.resources.getDimensionPixelSize(R.dimen.tx_my_search_menu_top_blur_feather)
    val sideFrameHeight = collapsedMenuHeight + max(0, feather)
    sideFrame.updateLayoutParams<ViewGroup.LayoutParams> {
        if (height != sideFrameHeight) {
            height = sideFrameHeight
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
        val slack = (48 * list.resources.displayMetrics.density).toInt()
        val minSearchListTop = list.resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
        // Normal: ~one collapsed toolbar. Search: locked bar is shorter than visible search chrome;
        // allow geometry up to minSearch + slack (still rejects stale half-screen from resume).
        val maxTrustInset = if (menu.requireCustomToolbar().isSearchExpanded) {
            max(menu.height + slack, minSearchListTop + slack)
        } else {
            menu.height + slack
        }
        if (inset > 0 && inset <= maxTrustInset) {
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
    val inset = getMySearchMenuListTopInsetPx(menu, list) - 40
    if (inset <= 0) return
    val density = list.resources.displayMetrics.density
    val slack = (48 * density).toInt()
    val minSearchListTop = list.resources.getDimensionPixelSize(R.dimen.nest_bouncy_content_padding_top)
    val cap = if (menu.requireCustomToolbar().isSearchExpanded) {
        max(menu.height + slack, minSearchListTop + slack)
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
            topSideFrame?.let { syncTopSideFrameHeightForMenu(it, menu, h) }
            if (applyNegativeBlurTargetTopMargin) {
                syncBlurTargetTopMarginForMenu(blurTarget, h)
            } else {
                blurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    if (topMargin != 0) topMargin = 0
                }
            }
            blurTarget.invalidate()
            applyListTop()
        }
    }
    // Avoid applyListTop() here: it can run before blur negative margin is synced and yields
    // bogus screen geometry (extra top padding after returning from another activity).
}

/**
 * Bouncy overscroll moves [menu] slightly; optionally keeps [translationSyncView] at the same
 * vertical shift as the app bar ([AppBarLayout] offset + menu overscroll [translationY]).
 */
fun setupMySearchMenuSpringSync(
    menu: MySearchMenu,
    recycler: MyRecyclerView?,
    translationSyncView: View? = null,
): AppBarLayout.OnOffsetChangedListener? {
    var appBarVerticalOffset = 0
    val offsetListener = if (translationSyncView != null) {
        AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            translationSyncView.translationY = verticalOffset.toFloat() + menu.translationY
        }.also { menu.addOnOffsetChangedListener(it) }
    } else {
        null
    }
    recycler?.onOverscrollTranslationChanged = { translationY ->
        val t = translationY * MAIN_MENU_OVERSCROLL_FACTOR
        menu.translationY = t
        translationSyncView?.translationY = appBarVerticalOffset.toFloat() + t
    }
    return offsetListener
}

fun clearMySearchMenuSpringSync(
    menu: MySearchMenu,
    recycler: MyRecyclerView?,
    filterBarAppBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null,
    translationSyncView: View? = null,
) {
    filterBarAppBarOffsetListener?.let { menu.removeOnOffsetChangedListener(it) }
    recycler?.onOverscrollTranslationChanged = null
    menu.translationY = 0f
    translationSyncView?.translationY = 0f
}
