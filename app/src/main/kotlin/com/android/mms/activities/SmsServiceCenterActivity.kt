package com.android.mms.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.android.common.dialogs.MRenameDialog
import com.android.mms.R
import com.android.mms.databinding.ActivitySmsServiceCenterBinding
import com.android.mms.databinding.ItemSmsServiceCenterSimBinding
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.helpers.resolveSimIconTint
import com.android.mms.models.SIMCard
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.showKeyboard
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors

class SmsServiceCenterActivity : SimpleActivity() {

    private lateinit var binding: ActivitySmsServiceCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsServiceCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupTopBar()
        applyWindowSurfaces()
        loadSimRows()
        binding.nestScroll.post {
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.smsServiceCenterAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.simServiceCenterWrapper,
            )
            setupMySearchMenuSpringSync(binding.smsServiceCenterAppbar, null)
            if (config.changeColourTopBar) {
                scrollingView = binding.nestScroll
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.nestScroll,
                    binding.smsServiceCenterAppbar,
                    useSurfaceColor,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isSystemInDarkMode()) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                )
        }
        applyWindowSurfaces()
        updateTextColors(binding.rootView)
        setupTopBar()
        refreshSideFrameBlurAndInsets()
    }

    override fun onDestroy() {
        clearMySearchMenuSpringSync(binding.smsServiceCenterAppbar, null)
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets -> insets }
    }

    private fun applyWindowSurfaces() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        scrollingView = binding.nestScroll
        binding.smsServiceCenterAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        binding.smsServiceCenterAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.smsServiceCenterAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.smsServiceCenterAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.simServiceCenterWrapper,
            )
        }
    }

    private fun setupTopBar() {
        binding.smsServiceCenterAppbar.applyLargeTitleOnly(getString(R.string.sms_service_center))
        binding.smsServiceCenterAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@SmsServiceCenterActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor,
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        binding.smsServiceCenterAppbar.searchBeVisibleIf(false)
    }

    @SuppressLint("MissingPermission")
    private fun getAllSimCards(): List<SIMCard> {
        val availableSIMs = subscriptionManagerCompat().activeSubscriptionInfoList
            ?: return emptyList()
        return availableSIMs.mapIndexed { index, subscriptionInfo ->
            var label = subscriptionInfo.displayName?.toString()
                ?: getString(com.goodwy.commons.R.string.contact_list_sim_slot, index + 1)
            when (subscriptionInfo.mnc) {
                5 -> label = getString(R.string.koryo_label)
                6 -> label = getString(R.string.kangsong_label)
                3 -> label = getString(R.string.mirae_label)
            }
            SIMCard(
                id = index + 1,
                subscriptionId = subscriptionInfo.subscriptionId,
                label = label,
                mnc = subscriptionInfo.mnc,
                number = subscriptionInfo.number?.trim().orEmpty(),
            )
        }
    }

    private fun loadSimRows() {
        val simCards = getAllSimCards()
        binding.simServiceCenterRows.removeAllViews()
        if (simCards.isEmpty()) {
            binding.noSimPlaceholder.beVisible()
            binding.simServiceCenterCard.beGone()
            return
        }
        binding.noSimPlaceholder.beGone()
        binding.simServiceCenterCard.beVisible()

        val textColor = getProperTextColor()
        val cardBgColor = resources.getColor(com.android.common.R.color.tx_cardview_bg, theme)
        binding.simServiceCenterCard.setCardBackgroundColor(cardBgColor)

        simCards.forEachIndexed { index, sim ->
            if (index > 0) addDivider()
            addSimRow(sim, textColor)
        }
    }

    private fun addDivider() {
        val divider = View(this).apply {
            val startMargin = resources.getDimensionPixelSize(com.android.common.R.dimen.tx_cardview_padding_left)
            val endMargin = resources.getDimensionPixelSize(com.android.common.R.dimen.tx_cardview_divider_margin_right)
            val thickness = resources.getDimensionPixelSize(com.android.common.R.dimen.tx_cardview_divider_thickness)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, thickness
            ).apply {
                marginStart = startMargin
                marginEnd = endMargin
            }
            setBackgroundColor(resources.getColor(com.android.common.R.color.tx_cardview_divider, theme))
        }
        binding.simServiceCenterRows.addView(divider)
    }

    private fun addSimRow(sim: SIMCard, textColor: Int) {
        val rowBinding = ItemSmsServiceCenterSimBinding.inflate(
            layoutInflater, binding.simServiceCenterRows, false
        )

        // SIM icon
        val simIconRes = when (sim.id) {
            1 -> com.android.common.R.drawable.ic_cmn_sim1
            2 -> com.android.common.R.drawable.ic_cmn_sim2
            else -> R.drawable.ic_sim_vector
        }
        rowBinding.simIcon.setImageResource(simIconRes)
        rowBinding.simIcon.applyColorFilter(resolveSimIconTint(textColor, sim.subscriptionId, sim.id))

        // Label
        rowBinding.simLabel.text = sim.label

        // Phone number subtitle
        if (sim.number.isNotEmpty()) {
            rowBinding.simNumber.text = sim.number
            rowBinding.simNumber.beVisible()
        }

        // Chevron tint
        rowBinding.simServiceCenterChevron.applyColorFilter(textColor)

        // Ripple background
        rowBinding.simServiceCenterRow.background =
            AppCompatResources.getDrawable(this, R.drawable.ripple_all_corners)

        // Load SMSC in background
        loadSmscForRow(sim, rowBinding)

        // Click to edit
        rowBinding.simServiceCenterRow.setOnClickListener {
            editSmscForSim(sim, rowBinding)
        }

        binding.simServiceCenterRows.addView(rowBinding.root)
    }

    @SuppressLint("MissingPermission")
    private fun loadSmscForRow(sim: SIMCard, rowBinding: ItemSmsServiceCenterSimBinding) {
        Thread {
            val address = readSmscAddress(sim.subscriptionId)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    rowBinding.simSmscValue.text = address
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun editSmscForSim(sim: SIMCard, rowBinding: ItemSmsServiceCenterSimBinding) {
        Thread {
            val current = readSmscAddress(sim.subscriptionId)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val dialog = MRenameDialog(this@SmsServiceCenterActivity)
                dialog.bindBlurTarget(binding.mainBlurTarget)
                dialog.setTitle(getString(R.string.sms_service_center))
                dialog.setContentText(current)
                dialog.setOnRenameListener { newAddress ->
                    if (!isValidSmscAddress(newAddress)) {
                        toast(R.string.invalid_smsc_number)
                        return@setOnRenameListener
                    }
                    Thread {
                        try {
                            writeSmscAddress(sim.subscriptionId, newAddress)
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing) {
                                    rowBinding.simSmscValue.text = newAddress
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { showErrorToast(e) }
                        }
                    }.start()
                }
                dialog.show()
                dialog.window?.decorView?.findViewById<EditText>(com.android.common.R.id.input_text)
                    ?.let { et ->
                        et.inputType = InputType.TYPE_CLASS_PHONE
                        et.post { showKeyboard(et) }
                    }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun readSmscAddress(subscriptionId: Int): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId).getSmscAddress() ?: ""
        } else ""
    } catch (e: Exception) { "" }

    @SuppressLint("MissingPermission")
    private fun writeSmscAddress(subscriptionId: Int, address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId).setSmscAddress(address)
        }
    }

    private fun isValidSmscAddress(address: String): Boolean {
        if (address.isEmpty()) return true
        return if (address.startsWith("+")) address.drop(1).all { it.isDigit() }
               else address.all { it.isDigit() }
    }
}
