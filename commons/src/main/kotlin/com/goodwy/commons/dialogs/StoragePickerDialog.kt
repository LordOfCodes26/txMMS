package com.goodwy.commons.dialogs

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.databinding.DialogRadioGroupBinding
import com.goodwy.commons.databinding.RadioButtonBinding
import com.goodwy.commons.extensions.*
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * A dialog for choosing between internal, root, SD card (optional) storage
 *
 * @param activity has to be activity to avoid some Theme.AppCompat issues
 * @param currPath current path to decide which storage should be preselected
 * @param pickSingleOption if only one option like "Internal" is available, select it automatically
 * @param callback an anonymous function
 *
 */
class StoragePickerDialog(
    val activity: BaseSimpleActivity, val currPath: String, val showRoot: Boolean, pickSingleOption: Boolean,
    private val blurTarget: BlurTarget, val callback: (pickedPath: String) -> Unit
) {
    private val ID_INTERNAL = 1
    private val ID_SD = 2
    private val ID_OTG = 3
    private val ID_ROOT = 4

    private lateinit var radioGroup: RadioGroup
    private var dialog: AlertDialog? = null
    private var defaultSelectedId = 0
    private val availableStorages = ArrayList<String>()

    init {
        availableStorages.add(activity.internalStoragePath)
        when {
            activity.hasExternalSDCard() -> availableStorages.add(activity.sdCardPath)
            activity.hasOTGConnected() -> availableStorages.add("otg")
            showRoot -> availableStorages.add("root")
        }

        if (pickSingleOption && availableStorages.size == 1) {
            callback(availableStorages.first())
        } else {
            initDialog()
        }
    }

    private fun initDialog() {
        val inflater = LayoutInflater.from(activity)
        val resources = activity.resources
        val layoutParams = RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val view = DialogRadioGroupBinding.inflate(inflater, null, false)
        
        // Setup BlurView with the provided BlurTarget
        val blurView = view.root.findViewById<BlurView>(R.id.blurView)
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView?.setOverlayColor(0xa3ffffff.toInt())
        blurView?.setupWith(blurTarget)
            ?.setFrameClearDrawable(windowBackground)
            ?.setBlurRadius(8f)
            ?.setBlurAutoUpdate(true)
        
        radioGroup = view.dialogRadioGroup
        val basePath = currPath.getBasePath(activity)

        val internalButton = RadioButtonBinding.inflate(inflater, null, false).root
        internalButton.apply {
            id = ID_INTERNAL
            text = resources.getString(R.string.internal)
            isChecked = basePath == context.internalStoragePath
            setOnClickListener { internalPicked() }
            if (isChecked) {
                defaultSelectedId = id
            }
        }
        radioGroup.addView(internalButton, layoutParams)

        if (activity.hasExternalSDCard()) {
            val sdButton = RadioButtonBinding.inflate(inflater, null, false).root
            sdButton.apply {
                id = ID_SD
                text = resources.getString(R.string.sd_card)
                isChecked = basePath == context.sdCardPath
                setOnClickListener { sdPicked() }
                if (isChecked) {
                    defaultSelectedId = id
                }
            }
            radioGroup.addView(sdButton, layoutParams)
        }

        if (activity.hasOTGConnected()) {
            val otgButton = RadioButtonBinding.inflate(inflater, null, false).root
            otgButton.apply {
                id = ID_OTG
                text = resources.getString(R.string.usb)
                isChecked = basePath == context.otgPath
                setOnClickListener { otgPicked() }
                if (isChecked) {
                    defaultSelectedId = id
                }
            }
            radioGroup.addView(otgButton, layoutParams)
        }

        // allow for example excluding the root folder at the gallery
        if (showRoot) {
            val rootButton = RadioButtonBinding.inflate(inflater, null, false).root
            rootButton.apply {
                id = ID_ROOT
                text = resources.getString(R.string.root)
                isChecked = basePath == "/"
                setOnClickListener { rootPicked() }
                if (isChecked) {
                    defaultSelectedId = id
                }
            }
            radioGroup.addView(rootButton, layoutParams)
        }

        // Setup title inside BlurView
        val titleTextView = view.root.findViewById<com.goodwy.commons.views.MyTextView>(R.id.dialog_title)
        titleTextView?.apply {
            beVisible()
            text = activity.getString(R.string.select_storage)
        }
        
        activity.getAlertDialogBuilder().apply {
            // Pass empty titleText to prevent setupDialogStuff from adding title outside BlurView
            activity.setupDialogStuff(view.root, this, titleText = "") { alertDialog ->
                dialog = alertDialog
            }
        }
    }

    private fun internalPicked() {
        dialog?.dismiss()
        callback(activity.internalStoragePath)
    }

    private fun sdPicked() {
        dialog?.dismiss()
        callback(activity.sdCardPath)
    }

    private fun otgPicked() {
        activity.handleOTGPermission {
            if (it) {
                callback(activity.otgPath)
                dialog?.dismiss()
            } else {
                radioGroup.check(defaultSelectedId)
            }
        }
    }

    private fun rootPicked() {
        dialog?.dismiss()
        callback("/")
    }
}
