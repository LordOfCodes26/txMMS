package com.android.mms.dialogs

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
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
import com.goodwy.commons.extensions.fadeIn
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupMDialogStuff
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.SHORT_ANIMATION_DURATION
import com.goodwy.commons.helpers.ensureBackgroundThread
import eightbitlab.com.blurview.BlurTarget
import kotlin.getValue

enum class DialpadSimListDialogPosition {
    BOTTOM,   // Bottom of screen (default)
    CENTER,   // Center of screen
    TOP,      // Top of screen
}

enum class SelectSimDialogAnchorPlacement {
    TOP_RIGHT_OF_ANCHOR,
    BOTTOM_RIGHT_OF_ANCHOR
}

@SuppressLint("SetTextI18n")
class SelectSIMDialog(
    private val activity: SimpleActivity,
    blurTarget: BlurTarget?,
    private val anchorView: View? = null,
    private val anchorPlacement: SelectSimDialogAnchorPlacement = SelectSimDialogAnchorPlacement.TOP_RIGHT_OF_ANCHOR,
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
                    val simIconColor = activity.resolveSimIconTint(
                        textColor,
                        simAccount.subscriptionId,
                        simAccount.id
                    )
                    simSelectSlotIcon.applyColorFilter(simIconColor)

                    root.setOnClickListener {
                        selectSim(simAccount, index)
                    }
                }
                binding.selectSimRows.addView(rowBinding.root)
            }
            binding.blurView.beVisible()
            if (blurTarget != null && blurTarget.isShown) {
                binding.blurView.setupWith(blurTarget)
                    ?.setBlurRadius(36f)
                    ?.setBlurAutoUpdate(true)
            }

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
                styleId = R.style.MDialogSimSelect,
                beforeShow = { mDialog ->
                    dialog = mDialog
                    configureDialogWindow(mDialog)
                    binding.selectSimHolder.alpha = 0f
                    applyDialogPosition(mDialog)
                }
            ) { mDialog ->
                dialog = mDialog
                binding.selectSimHolder.fadeIn(SHORT_ANIMATION_DURATION)
            }
        }
    }

    private fun configureDialogWindow(mDialog: MDialog) {
        mDialog.window?.let { window ->
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

    }

    private fun applyDialogPosition(mDialog: MDialog) {
        val window = mDialog.window ?: return
        val params = window.attributes ?: return
        val anchor = anchorView
//        val anchorBounds = anchor?.let {resolveAnchorBounds( it )}
        if (anchor != null && anchor.isAttachedToWindow) {
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val w = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_width)
            val h = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_height)
            val overlap = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_anchor_overlap)
            val gapAbove = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_above_anchor_margin)
            val gapBelow = activity.resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.select_sim_dialog_above_anchor_margin)
            val screenW = activity.resources.displayMetrics.widthPixels
            val screenH = activity.resources.displayMetrics.heightPixels
            params.gravity = Gravity.TOP or Gravity.START
            val anchorRight = loc[0] + anchor.width
            params.x = (anchorRight - w + overlap).coerceIn(0, maxOf(0, screenW - w))
            params.y = when(anchorPlacement) {
                SelectSimDialogAnchorPlacement.TOP_RIGHT_OF_ANCHOR -> (loc[1] - h - gapAbove).coerceAtLeast(0)
                SelectSimDialogAnchorPlacement.BOTTOM_RIGHT_OF_ANCHOR -> (loc[1] + anchor.height).coerceAtMost(maxOf(0, screenH - h))
            }
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
        }
        window.attributes = params
    }

    private fun resolveAnchorBounds(anchor: View): Rect? {
        if (!anchor.isAttachedToWindow) return null
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        if (anchor.width > 0 && anchor.height > 0) {
            return Rect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height)
        }
        val visible = Rect()
        if (anchor.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0) {
            return visible
        }
        return null
    }

    private fun selectSim(simAccount: SIMCard, index: Int) {
        selectionConfirmed = true
        callback(simAccount, index)
        dialog?.dismiss()
    }
}
