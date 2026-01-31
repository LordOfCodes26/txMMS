package com.goodwy.commons.dialogs

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.getDialogTheme

/**
 * Custom dialog builder that wraps AlertDialog.Builder to provide a consistent interface
 * regardless of theme settings. This replaces MaterialAlertDialogBuilder and AlertDialog.Builder
 * usage throughout the codebase.
 */
class CustomDialogBuilder(
    private val activity: Activity,
    private var themeResId: Int = 0
) {
    private var builder: AlertDialog.Builder? = null
    private var currentThemeResId: Int = 0

    private fun getBuilder(): AlertDialog.Builder {
        val theme = if (themeResId != 0) {
            themeResId
        } else {
            activity.getDialogTheme()
        }
        
        // Recreate builder if theme changed or builder doesn't exist
        if (builder == null || currentThemeResId != theme) {
            builder = AlertDialog.Builder(activity, theme)
            currentThemeResId = theme
        }
        
        return builder!!
    }

    fun setTitle(title: CharSequence?): CustomDialogBuilder {
        getBuilder().setTitle(title)
        return this
    }

    fun setTitle(titleId: Int): CustomDialogBuilder {
        getBuilder().setTitle(titleId)
        return this
    }

    fun setMessage(message: CharSequence?): CustomDialogBuilder {
        getBuilder().setMessage(message)
        return this
    }

    fun setMessage(messageId: Int): CustomDialogBuilder {
        getBuilder().setMessage(messageId)
        return this
    }

    fun setView(view: View?): CustomDialogBuilder {
        getBuilder().setView(view)
        return this
    }

    fun setView(layoutResId: Int): CustomDialogBuilder {
        getBuilder().setView(layoutResId)
        return this
    }

    fun setPositiveButton(
        textId: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setPositiveButton(textId, listener)
        return this
    }

    fun setPositiveButton(
        text: CharSequence?,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setPositiveButton(text, listener)
        return this
    }

    fun setNegativeButton(
        textId: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setNegativeButton(textId, listener)
        return this
    }

    fun setNegativeButton(
        text: CharSequence?,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setNegativeButton(text, listener)
        return this
    }

    fun setNeutralButton(
        textId: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setNeutralButton(textId, listener)
        return this
    }

    fun setNeutralButton(
        text: CharSequence?,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setNeutralButton(text, listener)
        return this
    }

    fun setItems(
        itemsId: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setItems(itemsId, listener)
        return this
    }

    fun setItems(
        items: Array<CharSequence>?,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setItems(items, listener)
        return this
    }

    fun setSingleChoiceItems(
        itemsId: Int,
        checkedItem: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setSingleChoiceItems(itemsId, checkedItem, listener)
        return this
    }

    fun setSingleChoiceItems(
        items: Array<CharSequence>?,
        checkedItem: Int,
        listener: DialogInterface.OnClickListener?
    ): CustomDialogBuilder {
        getBuilder().setSingleChoiceItems(items, checkedItem, listener)
        return this
    }

    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): CustomDialogBuilder {
        getBuilder().setOnCancelListener(listener)
        return this
    }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): CustomDialogBuilder {
        getBuilder().setOnDismissListener(listener)
        return this
    }

    fun setCancelable(cancelable: Boolean): CustomDialogBuilder {
        getBuilder().setCancelable(cancelable)
        return this
    }

    /**
     * Sets the dialog theme/style. The theme will be applied when the builder is created.
     * If not set, the default theme based on the app's current theme will be used.
     * 
     * **Important:** This method should be called before any other builder methods
     * (setTitle, setMessage, setView, etc.) to ensure the theme is applied correctly.
     * If called after other methods, the builder will be recreated and previous
     * configuration will be lost.
     * 
     * @param themeResId The resource ID of the theme style to use (e.g., R.style.MyDialogTheme)
     */
    fun setTheme(themeResId: Int): CustomDialogBuilder {
        this.themeResId = themeResId
        // Force recreation of builder on next access if theme changed
        if (currentThemeResId != themeResId && builder != null) {
            builder = null
        }
        return this
    }

    fun create(): AlertDialog {
        return getBuilder().create()
    }

    /**
     * Creates and shows the dialog. Returns the AlertDialog instance.
     * The dialog will only be shown if the activity is not finishing or destroyed.
     */
    fun show(): AlertDialog {
        val dialog = getBuilder().create()
        // Check if activity is still valid before showing
        if (!activity.isFinishing) {
            // Check if activity is destroyed (available on ComponentActivity/AppCompatActivity)
            val isDestroyed = (activity as? ComponentActivity)?.isDestroyed ?: false
            if (!isDestroyed) {
                dialog.show()
            }
        }
        return dialog
    }
}
