package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.graphics.alpha
import com.android.common.view.MDialog
import com.android.mms.activities.SimpleActivity
import com.android.mms.databinding.DialogSelectSimBinding
import com.android.mms.extensions.getAvailableSIMCardLabels
import com.android.mms.helpers.FeeInfoUtils
import com.android.mms.R
import com.android.mms.databinding.ItemSelectSimPopupRowBinding
import com.android.mms.helpers.resolveSimIconTint
import com.android.mms.models.SIMCard
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupMDialogStuff
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.ensureBackgroundThread
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
    private val callback: (simAccount: SIMCard, index: Int) -> Unit,
) {
    private var dialog: MDialog? = null
    private var selectionConfirmed = false
    private val binding by activity.viewBinding(DialogSelectSimBinding::inflate)

    init {
        binding.selectSimHolder.beVisible()
        val simAccounts = activity.getAvailableSIMCardLabels().sortedBy { it.id }
        if (simAccounts.isNotEmpty()) {
            binding.selectSimRows.removeAllViews()
            simAccounts.forEachIndexed { index, simAccount ->
                if (index > 0) {
                    val gap = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_row_gap)
                    val divider = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                            topMargin = gap
                            bottomMargin = gap
                        }
                        setBackgroundResource(com.goodwy.commons.R.drawable.divider_settings)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    binding.selectSimRows.addView(divider)
                }
                val rowBinding = ItemSelectSimPopupRowBinding.inflate(
                    LayoutInflater.from(activity),
                    binding.selectSimRows,
                    false
                ).apply {
                    val simId = simAccount.id
                    val simRes = when (simId) {
                        1 -> com.android.common.R.drawable.ic_cmn_sim1
                        2 -> com.android.common.R.drawable.ic_cmn_sim2
                        else -> R.drawable.ic_sim_vector
                    }
                    simSelectSlotIcon.setImageResource(simRes)
                    val slotId = FeeInfoUtils.getSimSlotIndexForSubscriptionId(activity, simAccount.subscriptionId)
                    ensureBackgroundThread {
                        val smsCount = slotId?.let { FeeInfoUtils.getAvailableSmsCountForSlot(activity, it) }
                        val feeCash = slotId?.let { FeeInfoUtils.getAvailableFeeCashForSlot(activity, it) }
                        val txtSmsCount = activity.getString(R.string.remained_sms_count, smsCount)
                        val txtCash = activity.getString(R.string.remained_cash) + feeCash
                        activity.runOnUiThread {
                            if (smsCount !== null) {
                                simSelectLabel.text = "$txtSmsCount, $txtCash"
                            } else {
                                simSelectLabel.text = activity.getString(R.string.select_sim_1)
                            }
                        }
                    }
                    val textColor = activity.getProperTextColor()
                    val simIconColor = activity.resolveSimIconTint(textColor, simAccount.subscriptionId,simAccount.id)
                    simSelectSlotIcon.applyColorFilter(simIconColor)

                    root.setOnClickListener {
                        selectSim(simAccount, index)
                    }
                }
                binding.selectSimRows.addView(rowBinding.root)
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
                dialog?.window?.let { window ->
                    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                    )
                    window.setDimAmount(0.1f)
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    val w = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_width)
                    val h = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_height)
                    window.setLayout(w, h)
                }
//                val anchor = anchorView
//                if (anchor != null) {
//                    anchor.post { applyDialogPosition() }
//                } else {
//                    applyDialogPosition()
//                }
                    applyDialogPosition()
            }
        }
    }

    private fun applyDialogPosition() {
        val window = dialog?.window ?: return
        val params = window.attributes ?: return
        val anchor = anchorView
        if (anchor != null && anchor.isAttachedToWindow) {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val w = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_width)
            val h = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_height)
            val overlap = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_anchor_overlap)
            val gapAbove = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_above_anchor_margin)
            val screenW = activity.resources.displayMetrics.widthPixels
            params.gravity = Gravity.TOP or Gravity.START
            val rawX = loc[0] - w + overlap
            params.x = rawX.coerceIn(0, maxOf(0, screenW - w))
            params.y = (loc[1] - h - gapAbove).coerceAtLeast(0)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        } else {
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
    }

    private fun selectSim(simAccount: SIMCard, index: Int) {
        selectionConfirmed = true
        callback(simAccount, index)
        dialog?.dismiss()
    }
}
