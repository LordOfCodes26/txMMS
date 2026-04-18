package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.android.common.view.MDialog
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.DialogSelectSimBinding
import com.android.mms.databinding.ItemSimFeeDialogBinding
import com.android.mms.extensions.getAvailableSIMCardLabels
import com.android.mms.helpers.FeeInfoUtils
import com.android.mms.models.SIMAccount
import com.android.mms.R
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupMDialogStuff
import com.goodwy.commons.extensions.viewBinding
import eightbitlab.com.blurview.BlurTarget
import kotlin.getValue

enum class DialpadSimListDialogPosition {
    BOTTOM,   // Bottom of screen (default)
    CENTER,   // Center of screen
    TOP,      // Top of screen
}

@SuppressLint("SetTextI18n")
class SelectSIMDialog(
    private val activity: SimpleActivity,
    blurTarget: BlurTarget,
    private val anchorView: View? = null,
    private val position: DialpadSimListDialogPosition = DialpadSimListDialogPosition.BOTTOM,
    private val verticalOffsetPx: Int = 250,
    private val onDismiss: () -> Unit = {},
    private val callback: (simAccount: SIMAccount, index: Int) -> Unit,
) {
    private var dialog: MDialog? = null
    private var selectionConfirmed = false
    private val binding by activity.viewBinding(DialogSelectSimBinding::inflate)

    init {
        binding.selectSimHolder.beVisible()
        binding.selectSimRadioHolder.beGone()
        val simAccounts = activity.getAvailableSIMCardLabels().sortedBy { it.id }
        if (simAccounts.isNotEmpty()) {
            simAccounts.forEachIndexed { index, simAccount ->
                val rowBinding = ItemSimFeeDialogBinding.inflate(LayoutInflater.from(activity), null, false).apply {
                    simItemName.text = simAccount.label
                    simItemName.setTextColor(activity.getProperTextColor())
                    simItemFeeInfo.text = "sms: 12"
                    simItemFeeInfo.setTextColor(activity.getProperTextColor())

                    val iconRes = when (simAccount.label) {
                        activity.getString(R.string.koryo_label) -> R.drawable.ic_koryo
                        activity.getString(R.string.kangsong_label) -> R.drawable.ic_kangsong
                        else -> R.drawable.ic_sim_vector
                    }
                    val drawable = ResourcesCompat.getDrawable(activity.resources, iconRes, activity.theme)
                    simItemIcon.setImageDrawable(drawable)
                    root.setOnClickListener {
                        selectSim(simAccount, index)
                        dialog?.dismiss()
                    }
                }
                if (index == 0) binding.sim1Holder.addView(rowBinding.root)
                else binding.sim2Holder.addView(rowBinding.root)
            }
            binding.blurView.setupWith(blurTarget)
                ?.setBlurRadius(36f)
                ?.setBlurAutoUpdate(true)

            activity.setupMDialogStuff(
                view = binding.root,
                blurView = binding.blurView,
                blurTarget = blurTarget,
                titleText = "",
                cancelListener = {
                    if (!selectionConfirmed) {
                        onDismiss()
                    }
                },
                styleId = R.style.MDialogSimSelect
            ) { mDialog ->
                dialog = mDialog
                dialog?.window?.setDimAmount(0.1f)
                applyDialogPosition()
            }
        }
    }

    private fun applyDialogPosition() {
        val window = dialog?.window ?: return
        val params = window.attributes ?: return
        when (position) {
            DialpadSimListDialogPosition.BOTTOM -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = verticalOffsetPx
            }
            DialpadSimListDialogPosition.CENTER -> {
                params.gravity = Gravity.CENTER
                params.x = 0
                params.y = 0
            }
            DialpadSimListDialogPosition.TOP -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = verticalOffsetPx
            }
        }
        window.attributes = params
    }

    private fun selectSim(simAccount: SIMAccount, index: Int) {
        selectionConfirmed = true
        callback(simAccount, index)
        dialog?.dismiss()
    }
}
