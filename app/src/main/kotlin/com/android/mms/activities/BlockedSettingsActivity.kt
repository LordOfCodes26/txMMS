package com.android.mms.activities

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.android.mms.databinding.ActivityBlockedSettingsBinding
import com.android.mms.extensions.config
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.extensions.viewBinding

class BlockedSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBlockedSettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paintSettingsWindowBeforeContentView()
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupSettingsTopAppBar()
        setupNestBouncyScroll()
        applySettingsWindowBackgroundsAndTopChrome()
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

    private fun mainContentBackgroundColor(): Int {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        return if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
    }

    private fun paintSettingsWindowBeforeContentView() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
    }

    private fun applySettingsWindowBackgroundsAndTopChrome() {
        val backgroundColor = mainContentBackgroundColor()
        window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        window.decorView.setBackgroundColor(backgroundColor)
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.settingsNestedScrollview.setBackgroundColor(Color.TRANSPARENT)
        binding.settingsHolder.setBackgroundColor(backgroundColor)
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

        applySettingsWindowBackgroundsAndTopChrome()
        setupSettingsTopAppBar()
        scrollingView = binding.settingsNestedScrollview
        setupShowNotification()
        updateTextColors(binding.rootView)
        binding.settingsNestedScrollview.post {
            refreshSideFrameBlurAndInsets()
        }
    }

    private fun setupShowNotification() = binding.apply {
        settingsShowNotification.isChecked = config.showBlockedNotifications
        settingsShowNotification.setOnCheckedChangeListener { isChecked ->
            config.showBlockedNotifications = isChecked
        }
        settingsShowNotificationHolder.setOnClickListener {
            settingsShowNotification.toggle()
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
