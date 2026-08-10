package com.android.mms.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.android.mms.databinding.ActivityBlockedSettingsBinding
import com.android.mms.extensions.config
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.views.MyLiquidSwitch

class BlockedSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBlockedSettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupSettingsTopAppBar()
        setupNestBouncyScroll()
        // Screen background comes from layout @color/tx_card_bg (same as SettingsActivity / txDial).
        applySettingsTopChrome()
        scrollingView = binding.settingsNestedScrollview
        binding.settingsMenu.addOnOffsetChangedListener { _, _ ->
            binding.mVerticalSideFrameTop.update()
        }
        binding.settingsNestedScrollview.post {
            binding.settingsMenu.dismissCollapse()
            applyTransparentMAppBarChrome()
            refreshSideFrameBlurAndInsets()
        }
    }

    /** Transparent top chrome only — activity surface is layout `tx_card_bg`, cards use `tx_cardview_bg`. */
    private fun applySettingsTopChrome() {
        binding.settingsNestedScrollview.setBackgroundColor(Color.TRANSPARENT)
        scrollingView = binding.settingsNestedScrollview
        applyTransparentMAppBarChrome()
    }

    private fun setupSettingsTopAppBar() {
        binding.settingsMenu.setTitle(getString(com.goodwy.commons.R.string.settings))

        binding.settingsMenu.getBackArrow()?.apply {
            bindBlurTarget(this@BlockedSettingsActivity, binding.mainBlurTarget)
            setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == com.android.common.R.id.back_arrow) {
                    hideKeyboard()
                    finish()
                    true
                } else {
                    false
                }
            }
        }

        binding.settingsMenu.getSearchView()?.visibility = View.GONE
        binding.settingsMenu.getActionBarView()?.visibility = View.GONE
        applyTransparentMAppBarChrome()
    }

    private fun applyTransparentMAppBarChrome() {
        binding.settingsMenu.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
    }

    private fun setupNestBouncyScroll() {
        val scroll = binding.settingsNestedScrollview
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
        scroll.setOnOverScrollListener { _, overScrolledDistance ->
            binding.settingsMenu.translationY = overScrolledDistance * NEST_BOUNCY_OVERSCROLL_FACTOR
        }
    }

    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.settingsMenu.getBackArrow()?.bindBlurTarget(this@BlockedSettingsActivity, binding.mainBlurTarget)
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
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

        applySettingsTopChrome()
        setupSettingsTopAppBar()
        scrollingView = binding.settingsNestedScrollview
        setupShowNotification()
        applySettingsCardBackgrounds()
        updateTextColors(binding.rootView)
        binding.settingsNestedScrollview.post {
            refreshSideFrameBlurAndInsets()
        }
    }

    private fun applySettingsCardBackgrounds() {
        val cardBgColor = resources.getColor(com.android.common.R.color.tx_cardview_bg)
        binding.settingsNotificationsHolder.apply {
            setCardBackgroundColor(cardBgColor)
            // MSwitch draws an opaque backdrop behind the track; keep it matching the card
            // so a white rectangle does not show on the row (txDial / txCommon pattern).
            for (i in 0 until childCount) {
                applySwitchBackground(getChildAt(i), cardBgColor)
            }
        }
    }

    private fun applySwitchBackground(view: View, color: Int) {
        when (view) {
            is MyLiquidSwitch -> view.setSwitchBackgroundColor(color)
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applySwitchBackground(view.getChildAt(i), color)
                }
            }
        }
    }

    private fun setupShowNotification() = binding.apply {
        setupSwitchSetting(
            settingsShowNotificationHolder,
            settingsShowNotification,
            config.showBlockedNumbers
        ) { isChecked ->
            config.showBlockedNumbers = isChecked
        }
    }

    private inline fun setupSwitchSetting(
        holder: View,
        switch: MyLiquidSwitch,
        checked: Boolean,
        crossinline onChecked: (Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener { }
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { isChecked ->
            onChecked(isChecked)
        }
        holder.setOnClickListener {
            switch.toggle()
        }
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            insets
        }
    }

    companion object {
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
    }
}
