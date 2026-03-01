package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.PopupWindow
import androidx.appcompat.view.menu.MenuBuilder
import com.android.common.view.MPopup
import com.goodwy.commons.R
import eightbitlab.com.blurview.BlurTarget

/**
 * Kotlin wrapper around txCommon's MPopup that keeps the existing BlurPopupMenu API.
 */
class BlurPopupMenu(
    private val context: Context,
    anchor: View,
    gravity: Int = Gravity.NO_GRAVITY,
    touchX: Float = -1f,
    touchY: Float = -1f,
    xThreshold: Float = 0.5f,
    yThreshold: Float = 0.5f
) {
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
        yThreshold.coerceIn(0f, 1f)
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
        syncDelegateMenu()
        popupDelegate.setOnMenuItemClickListener(onMenuItemClickListener)

        val resolvedBlurTarget = blurTarget ?: (context as? Activity)?.findViewById<BlurTarget>(R.id.mainBlurTarget)
        if (resolvedBlurTarget != null) {
            popupDelegate.setBlurTarget(resolvedBlurTarget)
        }

        popupDelegate.show()
        bridgeDismissListener()
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
        runCatching {
            val field = MPopup::class.java.getDeclaredField("menu")
            field.isAccessible = true
            field.set(popupDelegate, menu)
        }
    }

    private fun bridgeDismissListener() {
        if (onDismissListener == null) {
            return
        }

        runCatching {
            val field = MPopup::class.java.getDeclaredField("popupWindow")
            field.isAccessible = true
            val popupWindow = field.get(popupDelegate) as? PopupWindow
            popupWindow?.setOnDismissListener {
                onDismissListener?.onDismiss()
            }
        }
    }
}
