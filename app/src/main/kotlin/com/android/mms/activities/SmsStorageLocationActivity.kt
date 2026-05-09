package com.android.mms.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.android.mms.R
import com.android.mms.databinding.ActivitySmsStorageLocationBinding
import com.android.mms.databinding.ItemSmsStorageLocationSimBinding
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.extensions.subscriptionManagerCompat
import com.android.mms.helpers.SMS_SAVE_LOCATION_PHONE
import com.android.mms.helpers.SMS_SAVE_LOCATION_SIM
import com.android.mms.helpers.resolveSimIconTint
import com.android.mms.models.SIMCard
import com.goodwy.commons.dialogs.RadioGroupDialog
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
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.models.RadioItem
import eightbitlab.com.blurview.BlurTarget

class SmsStorageLocationActivity : SimpleActivity() {

    private lateinit var binding: ActivitySmsStorageLocationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmsStorageLocationBinding.inflate(layoutInflater)
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
                binding.smsStorageLocationAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.storageLocationWrapper,
            )
            setupMySearchMenuSpringSync(binding.smsStorageLocationAppbar, null)
            if (config.changeColourTopBar) {
                scrollingView = binding.nestScroll
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.nestScroll,
                    binding.smsStorageLocationAppbar,
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
        clearMySearchMenuSpringSync(binding.smsStorageLocationAppbar, null)
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
        binding.smsStorageLocationAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        binding.smsStorageLocationAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.smsStorageLocationAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.smsStorageLocationAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.storageLocationWrapper,
            )
        }
    }

    private fun setupTopBar() {
        binding.smsStorageLocationAppbar.applyLargeTitleOnly(getString(R.string.sms_storage_location))
        binding.smsStorageLocationAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@SmsStorageLocationActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor,
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        binding.smsStorageLocationAppbar.searchBeVisibleIf(false)
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
        binding.storageLocationRows.removeAllViews()
        if (simCards.isEmpty()) {
            binding.noSimPlaceholder.beVisible()
            binding.noSimPlaceholderImg.beVisible()
            binding.storageLocationCard.beGone()
            return
        }
        binding.noSimPlaceholder.beGone()
        binding.noSimPlaceholderImg.beGone()
        binding.storageLocationCard.beVisible()

        val textColor = getProperTextColor()
        val cardBgColor = resources.getColor(com.android.common.R.color.tx_cardview_bg, theme)
        binding.storageLocationCard.setCardBackgroundColor(cardBgColor)

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
        binding.storageLocationRows.addView(divider)
    }

    private fun addSimRow(sim: SIMCard, textColor: Int) {
        val rowBinding = ItemSmsStorageLocationSimBinding.inflate(
            layoutInflater, binding.storageLocationRows, false
        )

        val simIconRes = when (sim.id) {
            1 -> com.android.common.R.drawable.ic_cmn_sim1
            2 -> com.android.common.R.drawable.ic_cmn_sim2
            else -> R.drawable.ic_sim_vector
        }
        rowBinding.simIcon.setImageResource(simIconRes)
        rowBinding.simIcon.applyColorFilter(resolveSimIconTint(textColor, sim.subscriptionId, sim.id))

        rowBinding.simLabel.text = sim.label

        if (sim.number.isNotEmpty()) {
            rowBinding.simNumber.text = sim.number
            rowBinding.simNumber.beVisible()
        }

        rowBinding.storageLocationChevron.applyColorFilter(textColor)

        rowBinding.storageLocationRow.background =
            AppCompatResources.getDrawable(this, R.drawable.ripple_all_corners)

        updateRowValue(sim.subscriptionId, rowBinding)

        rowBinding.storageLocationRow.setOnClickListener {
            showLocationPickerDialog(sim, rowBinding)
        }

        binding.storageLocationRows.addView(rowBinding.root)
    }

    private fun updateRowValue(subscriptionId: Int, rowBinding: ItemSmsStorageLocationSimBinding) {
        val location = config.getSmsStorageLocation(subscriptionId)
        rowBinding.storageLocationValue.text = getLocationLabel(location)
    }

    private fun getLocationLabel(location: Int): String = when (location) {
        SMS_SAVE_LOCATION_SIM -> getString(R.string.sms_storage_location_sim)
        else -> getString(R.string.sms_storage_location_phone)
    }

    private fun showLocationPickerDialog(sim: SIMCard, rowBinding: ItemSmsStorageLocationSimBinding) {
        val items = arrayListOf(
            RadioItem(SMS_SAVE_LOCATION_PHONE, getString(R.string.sms_storage_location_phone)),
            RadioItem(SMS_SAVE_LOCATION_SIM, getString(R.string.sms_storage_location_sim)),
        )
        val currentLocation = config.getSmsStorageLocation(sim.subscriptionId)
        val blurTarget = findViewById<BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        RadioGroupDialog(
            this,
            items,
            currentLocation,
            R.string.sms_storage_location,
            blurTarget = blurTarget,
        ) { selected ->
            config.setSmsStorageLocation(sim.subscriptionId, selected as Int)
            updateRowValue(sim.subscriptionId, rowBinding)
        }
    }
}
