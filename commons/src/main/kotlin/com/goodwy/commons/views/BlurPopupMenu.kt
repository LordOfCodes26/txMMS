package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import androidx.appcompat.view.menu.MenuBuilder
import com.android.common.view.MPopup
import com.goodwy.commons.R
import eightbitlab.com.blurview.BlurTarget

/**
 * Kotlin wrapper around txCommon's [MPopup] that keeps the existing BlurPopupMenu API.
 *
 * Toolbar-style placement: [MPopup] lays out at default END, then [applyMpopupAnchorAdjustments] applies
 * inset/pull-up **on the first [ViewTreeObserver.OnPreDrawListener]**, while the popup content stays
 * **invisible** until then—so you should not see a one-frame “stuck right then jump”.
 *
 * @param horizontalEndInsetPx LTR: shift popup left from the screen edge by this many px.
 *        [USE_DEFAULT_END_INSET] uses [R.dimen.activity_margin] for end-aligned, non-touch anchors.
 */
class BlurPopupMenu(
    private val context: Context,
    private val anchor: View,
    private val gravity: Int = Gravity.NO_GRAVITY,
    private val touchX: Float = -1f,
    touchY: Float = -1f,
    xThreshold: Float = 0.5f,
    yThreshold: Float = 0.5f,
    private val horizontalEndInsetPx: Int = USE_DEFAULT_END_INSET,
) {
    companion object {
        const val USE_DEFAULT_END_INSET = Int.MIN_VALUE
    }

    val menu: Menu = MenuBuilder(context)
    private val menuInflater = MenuInflater(context)
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onDismissListener: PopupWindow.OnDismissListener? = null
    private var blurTarget: BlurTarget? = null

    private val popupDelegate = MPopup(
        context,
        anchor,
        gravity,
        touchX,
        touchY,
        xThreshold.coerceIn(0f, 1f),
        yThreshold.coerceIn(0f, 1f),
    )

    fun inflate(menuRes: Int) {
        menuInflater.inflate(menuRes, menu)
    }

    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
        popupDelegate.setOnMenuItemClickListener(listener)
    }

    fun setOnDismissListener(listener: PopupWindow.OnDismissListener?) {
        onDismissListener = listener
    }

    fun setBlurTarget(blurTarget: BlurTarget?) {
        this.blurTarget = blurTarget
        blurTarget?.let { popupDelegate.setBlurTarget(it) }
    }

    fun show() {
        removeAllMenuIcons(menu)
        syncDelegateMenu()
        popupDelegate.setOnMenuItemClickListener(onMenuItemClickListener)

        val resolvedBlurTarget = blurTarget ?: (context as? Activity)?.findViewById<BlurTarget>(R.id.mainBlurTarget)
        if (resolvedBlurTarget != null) {
            popupDelegate.setBlurTarget(resolvedBlurTarget)
        }

        val endInset = resolveHorizontalEndInsetPx()
        val pullUp = resolveVerticalPullUpPx()
        val activity = context as? Activity
        val wantToolbarOffset =
            resolvedBlurTarget != null &&
                activity != null &&
                (endInset > 0 || pullUp > 0)

        clearMpopupAnchorOffset(popupDelegate)

        popupDelegate.show()
        bridgeDismissListener()

        if (wantToolbarOffset && activity != null) {
            applyMpopupAnchorAdjustments(popupDelegate, activity, endInset, pullUp)
        }
    }

    private fun resolveHorizontalEndInsetPx(): Int {
        if (horizontalEndInsetPx >= 0) {
            return horizontalEndInsetPx
        }
        if (touchX >= 0f) {
            return 0
        }
        val endAligned = gravity == Gravity.END ||
            gravity == Gravity.RIGHT ||
            gravity == Gravity.NO_GRAVITY
        if (!endAligned) {
            return 0
        }
        return runCatching {
            context.resources.getDimensionPixelSize(R.dimen.activity_margin)
        }.getOrDefault(0)
    }

    /** Pull popup up for toolbar-anchored menus (no touch X); removes gap under MActionBar. */
    private fun resolveVerticalPullUpPx(): Int {
        if (touchX >= 0f) {
            return 0
        }
        val endAligned = gravity == Gravity.END ||
            gravity == Gravity.RIGHT ||
            gravity == Gravity.NO_GRAVITY
        if (!endAligned) {
            return 0
        }
        return runCatching {
            context.resources.getDimensionPixelSize(R.dimen.mactionbar_popup_vertical_overlap)
        }.getOrDefault(0)
    }

    fun dismiss() {
        popupDelegate.dismiss()
    }

    fun isShowing(): Boolean = popupDelegate.isShowing

    fun updateMenuItemTitle(itemId: Int, newTitle: CharSequence): Boolean {
        val item = menu.findItem(itemId) ?: return false
        item.title = newTitle
        syncDelegateMenu()
        return popupDelegate.updateMenuItemTitle(itemId, newTitle)
    }

    fun setMenuItemVisible(itemId: Int, visible: Boolean): Boolean {
        val item = menu.findItem(itemId) ?: return false
        item.isVisible = visible
        syncDelegateMenu()
        return popupDelegate.setMenuItemVisible(itemId, visible)
    }

    private fun syncDelegateMenu() {
        assignMenuToMpopup(popupDelegate, menu)
    }

    private fun removeAllMenuIcons(targetMenu: Menu) {
        for (index in 0 until targetMenu.size()) {
            val item = targetMenu.getItem(index)
            item.icon = null
            val subMenu = item.subMenu
            if (subMenu != null) {
                removeAllMenuIcons(subMenu)
            }
        }
    }

    private fun bridgeDismissListener() {
        if (onDismissListener == null) {
            return
        }

        getMpopupPopupWindow(popupDelegate)?.setOnDismissListener {
            onDismissListener?.onDismiss()
        }
    }
}

/**
 * Pushes [menu] into [MPopup]'s internal field. Tries the `menu` field first, then any field
 * whose type implements [Menu] (helps if the AAR was shrunk/renamed).
 */
internal fun assignMenuToMpopup(popup: MPopup, menu: Menu): Boolean {
    val okByName = runCatching {
        val field = MPopup::class.java.getDeclaredField("menu")
        field.isAccessible = true
        field.set(popup, menu)
        true
    }.getOrDefault(false)
    if (okByName) {
        return true
    }
    var clazz: Class<*>? = MPopup::class.java
    while (clazz != null) {
        for (field in clazz.declaredFields) {
            if (Menu::class.java.isAssignableFrom(field.type)) {
                return runCatching {
                    field.isAccessible = true
                    field.set(popup, menu)
                    true
                }.getOrDefault(false)
            }
        }
        clazz = clazz.superclass
    }
    return false
}

/**
 * Clears [MPopup] anchor offset so the next [MPopup.show] uses touch/gravity positioning again.
 * Required because [BlurPopupMenu] reuses one [MPopup] instance.
 */
internal fun clearMpopupAnchorOffset(popup: MPopup) {
    runCatching {
        val min = Integer.MIN_VALUE
        val fx = MPopup::class.java.getDeclaredField("anchorOffsetX")
        fx.isAccessible = true
        fx.setInt(popup, min)
        val fy = MPopup::class.java.getDeclaredField("anchorOffsetY")
        fy.isAccessible = true
        fy.setInt(popup, min)
    }
}

/**
 * Applies horizontal end inset and/or vertical pull-up after [MPopup.show], **before the first draw**:
 * hides content, runs [PopupWindow.update], then shows. Avoids a visible frame at default END + post jump.
 */
internal fun applyMpopupAnchorAdjustments(
    popup: MPopup,
    activity: Activity,
    horizontalEndInsetPx: Int = 0,
    verticalPullUpPx: Int = 0,
) {
    if (horizontalEndInsetPx <= 0 && verticalPullUpPx <= 0) {
        return
    }
    runCatching {
        val pw = getMpopupPopupWindow(popup) ?: return
        val content = pw.contentView ?: return
        suppressMpopupWindowMotion(pw)
        content.alpha = 0f

        fun applyFinalPositionAndReveal() {
            if (!pw.isShowing) {
                content.alpha = 1f
                return
            }
            val loc = IntArray(2)
            content.getLocationOnScreen(loc)
            val root = activity.window.decorView.rootView
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            var xRoot = loc[0] - rootLoc[0]
            var yRoot = loc[1] - rootLoc[1]
            val isRtl = content.layoutDirection == View.LAYOUT_DIRECTION_RTL
            if (horizontalEndInsetPx > 0) {
                xRoot += if (isRtl) horizontalEndInsetPx else -horizontalEndInsetPx
            }
            if (verticalPullUpPx > 0) {
                yRoot -= verticalPullUpPx
            }
            pw.update(xRoot, yRoot, -1, -1)
            content.alpha = 1f
        }

        val vto = content.viewTreeObserver
        if (vto.isAlive) {
            vto.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        val observer = content.viewTreeObserver
                        if (observer.isAlive) {
                            observer.removeOnPreDrawListener(this)
                        }
                        applyFinalPositionAndReveal()
                        return true
                    }
                },
            )
        } else {
            content.post { applyFinalPositionAndReveal() }
        }
    }
}

internal fun getMpopupPopupWindow(popup: MPopup): PopupWindow? =
    runCatching {
        val field = MPopup::class.java.getDeclaredField("popupWindow")
        field.isAccessible = true
        field.get(popup) as? PopupWindow
    }.getOrNull()

internal fun suppressMpopupWindowMotion(pw: PopupWindow) {
    runCatching {
        @Suppress("DEPRECATION")
        pw.setAnimationStyle(0)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching {
            pw.enterTransition = null
            pw.exitTransition = null
        }
    }
}
