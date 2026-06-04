package com.android.mms.activities

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.common.dialogs.MRenameDialog
import com.android.common.view.MActionBar
import com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getTempFile
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getSurfaceColor
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.isDynamicTheme
import com.goodwy.commons.extensions.isSystemInDarkMode
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.underlineText
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.helpers.ExportResult
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.interfaces.ActionModeToolbarHost
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.goodwy.commons.views.CustomActionModeToolbar
import com.goodwy.commons.views.MyRecyclerView
import com.android.mms.R
import com.android.mms.databinding.ActivityManageQuickTextsBinding
import com.android.mms.dialogs.ExportQuickTextsDialog
import com.android.mms.dialogs.ManageQuickTextsAdapter
import com.android.mms.extensions.config
import com.android.mms.extensions.setRippleTabEnabledWidthAlpha
import com.android.mms.extensions.toArrayList
import com.android.mms.helpers.QuickTextsExporter
import com.android.mms.helpers.QuickTextsImporter
import com.goodwy.commons.extensions.showKeyboard
import java.io.FileOutputStream
import java.io.OutputStream

class ManageQuickTextsActivity : SimpleActivity(), RefreshRecyclerViewListener, ActionModeToolbarHost {

    private lateinit var binding: ActivityManageQuickTextsBinding
    private var quickTextsAppBarVerticalOffset = 0

    /** Saved [ScrollingViewBehavior] while action mode clears app-bar coupling from [mainBlurTarget]. */
    private var blurTargetScrollingBehavior: CoordinatorLayout.Behavior<View>? = null

    private var topOffsetsAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageQuickTextsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupQuickTextsTopAppBar()
        setupQuickTextsSpringSync()
        applyQuickTextsWindowSurfacesAndChrome()
        updateQuickTexts()
        binding.manageQuickTextsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditQuickText()
            }
        }
        scrollingView = binding.manageQuickTextsList
        binding.quickTextsAppbar.addOnOffsetChangedListener { _, verticalOffset ->
            quickTextsAppBarVerticalOffset = verticalOffset
            binding.mVerticalSideFrameTop.update()
        }
        binding.manageQuickTextsList.post {
            binding.quickTextsAppbar.dismissCollapse()
            quickTextsAppBarVerticalOffset = 0
            applyTransparentMAppBarChrome()
            syncBlurTargetTopMarginForAppBar()
            requestTopInsetSync()
            refreshSideFrameBlurAndInsets()
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

        applyQuickTextsWindowSurfacesAndChrome()
        updateTextColors(binding.rootView)
        setupQuickTextsTopAppBar()
        binding.quickTextsAppbar.translationY = 0f
        applyTransparentMAppBarChrome()
        syncBlurTargetTopMarginForAppBar()
        requestTopInsetSync()
        refreshSideFrameBlurAndInsets()
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.quickTextsAppbar.getBackArrow()?.bindBlurTarget(
                this@ManageQuickTextsActivity,
                binding.mainBlurTarget,
            )
            binding.quickTextsAppbar.getActionBarView()?.bindBlurTarget(
                this@ManageQuickTextsActivity,
                binding.mainBlurTarget,
            )
            binding.quickTextsActionModeToolbar.bindBlurTarget(
                this@ManageQuickTextsActivity,
                binding.mainBlurTarget,
            )
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
        }
    }

    /** Selection-mode back / select-all pills — same [mainBlurTarget] as the quick-texts toolbar. */
    private fun refreshActionModeToolbarBlur() {
        val adapter = binding.manageQuickTextsList.adapter as? ManageQuickTextsAdapter ?: return
        if (!adapter.isActionModeActive()) return
        binding.mainBlurTarget.invalidate()
        binding.quickTextsActionModeToolbar.bindBlurTarget(this, binding.mainBlurTarget)
    }

    private fun applyQuickTextsWindowSurfacesAndChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.manageQuickTextsList.setBackgroundColor(backgroundColor)
        scrollingView = binding.manageQuickTextsList
        applyTransparentMAppBarChrome()
    }

    override fun onDestroy() {
        topOffsetsAnimator?.cancel()
        topOffsetsAnimator = null
        clearQuickTextsSpringSync()
        super.onDestroy()
    }

    private fun initTheme() {
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun makeSystemBarsToTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val navHeight = nav.bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val dp5 = (5 * resources.displayMetrics.density).toInt()
            binding.mVerticalSideFrameBottom.layoutParams =
                binding.mVerticalSideFrameBottom.layoutParams.apply { height = navHeight + dp5 }
            val barLp = binding.actionModeRippleToolbar.layoutParams as ViewGroup.MarginLayoutParams
            val activityMargin = (0 * resources.displayMetrics.density).toInt()
            if (ime.bottom > 0) {
                barLp.bottomMargin = ime.bottom + activityMargin
            } else {
                barLp.bottomMargin = navHeight + activityMargin
            }
            binding.actionModeRippleToolbar.layoutParams = barLp

            insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            syncManageQuickTextsListBottomPadding()
            insets
        }
    }

    private fun syncManageQuickTextsListBottomPadding() {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val rootInsets = ViewCompat.getRootWindowInsets(binding.root)
        val nav = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val ime = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val bottomInset = maxOf(nav, ime)

        val rippleExtra = if (binding.actionModeRippleToolbar.visibility == View.VISIBLE) {
            val ripple = binding.actionModeRippleToolbar
            val h = ripple.height.takeIf { it > 0 } ?: ripple.measuredHeight.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.action_mode_bottom_inset_fallback)
            val margin = (25 * resources.displayMetrics.density).toInt()
            h + margin
        } else {
            0
        }

        binding.manageQuickTextsList.updatePadding(bottom = bottomInset + activityMargin + rippleExtra)
    }

    override fun getActionModeToolbar(): CustomActionModeToolbar =
        binding.quickTextsActionModeToolbar

    override fun showActionModeToolbar() {
        binding.quickTextsAppbar.visibility = View.GONE
        binding.quickTextsActionModeToolbar.visibility = View.VISIBLE
        syncBlurTargetScrollingBehaviorForActionMode()
        syncBlurTargetTopMarginForAppBar()
        syncQuickTextsListTopPadding()
        binding.root.post {
            applyActionModeRippleToolbarForQuickTexts()
            refreshActionModeToolbarBlur()
            binding.root.post { syncManageQuickTextsListBottomPadding() }
        }
    }

    override fun hideActionModeToolbar() {
        binding.quickTextsActionModeToolbar.visibility = View.GONE
        binding.quickTextsAppbar.visibility = View.VISIBLE
        binding.actionModeRippleToolbar.visibility = View.GONE
        binding.quickTextsAppbar.dismissCollapse()
        quickTextsAppBarVerticalOffset = 0
        syncBlurTargetScrollingBehaviorForActionMode()
        syncBlurTargetTopMarginForAppBar()
        syncQuickTextsListTopPadding()
        syncManageQuickTextsListBottomPadding()
        (binding.mainBlurTarget.parent as? View)?.requestLayout()
        binding.root.post {
            applyTransparentMAppBarChrome()
            binding.mVerticalSideFrameTop.update()
            requestTopInsetSync()
        }
    }

    /** Toolbar visibility only — adapter selection can still be active when [hideActionModeToolbar] runs (MainActivity pattern). */
    private fun isQuickTextsActionModeToolbarVisible(): Boolean =
        binding.quickTextsActionModeToolbar.visibility == View.VISIBLE

    /** Fixed list top inset (MainActivity [conversationsListTopPaddingPx]); CoordinatorLayout drives collapse. */
    private fun quickTextsListTopPaddingPx(): Int {
        val dimen = if (isQuickTextsActionModeToolbarVisible()) {
            R.dimen.conversations_list_top_padding_action_mode
        } else {
            R.dimen.conversations_list_top_padding
        }
        return resources.getDimensionPixelSize(dimen)
    }

    private fun syncQuickTextsListTopPadding() {
        applyFinalQuickTextsListTopPadding()
    }

    private fun applyFinalQuickTextsListTopPadding() {
        val topPad = quickTextsListTopPaddingPx()
        val list = binding.manageQuickTextsList
        list.updatePadding(top = topPad)
        list.translationY = 0f
        binding.manageQuickTextsPlaceholder.updatePadding(top = topPad)
    }

    private fun animateTopOffsets(duration: Long) {
        val list = binding.manageQuickTextsList
        val targetTopPadding = quickTextsListTopPaddingPx()

        topOffsetsAnimator?.cancel()

        if (duration <= 0L) {
            if (list.paddingTop != targetTopPadding || list.translationY != 0f) {
                list.updatePadding(top = targetTopPadding)
                list.translationY = 0f
            }
            binding.manageQuickTextsPlaceholder.updatePadding(top = targetTopPadding)
            topOffsetsAnimator = null
            return
        }

        val currentTopPadding = list.paddingTop
        val currentTranslationY = list.translationY
        if (currentTopPadding != targetTopPadding) {
            list.updatePadding(top = targetTopPadding)
            list.translationY = currentTranslationY + (currentTopPadding - targetTopPadding).toFloat()
        }

        val startTranslationY = list.translationY
        if (kotlin.math.abs(startTranslationY) < 0.5f) {
            list.translationY = 0f
            applyFinalQuickTextsListTopPadding()
            return
        }

        topOffsetsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                list.translationY = lerpFloat(startTranslationY, 0f, fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    list.translationY = 0f
                    applyFinalQuickTextsListTopPadding()
                    topOffsetsAnimator = null
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    list.translationY = 0f
                    applyFinalQuickTextsListTopPadding()
                    topOffsetsAnimator = null
                }
            })
            start()
        }
    }

    private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun requestTopInsetSync(animate: Boolean = false, duration: Long = TOP_INSET_SYNC_DURATION_MS) {
        val insetDuration = if (animate) duration else 0L
        runTopInsetSyncOnce(duration = insetDuration)
    }

    private fun runTopInsetSyncOnce(duration: Long) {
        binding.root.post {
            fun applyInsets() {
                animateTopOffsets(duration)
            }
            applyInsets()
            val menu = binding.quickTextsAppbar
            if (!menu.isLaidOut || menu.height <= 0) {
                menu.post { applyInsets() }
            }
        }
    }

    /**
     * [ScrollingViewBehavior] pins [mainBlurTarget] below the app bar even when the bar is [View.GONE].
     * Drop the behavior during action mode (MainActivity pattern).
     */
    private fun syncBlurTargetScrollingBehaviorForActionMode() {
        val inActionMode = isQuickTextsActionModeToolbarVisible()
        val lp = binding.mainBlurTarget.layoutParams as? CoordinatorLayout.LayoutParams ?: return
        if (inActionMode) {
            if (blurTargetScrollingBehavior == null) {
                blurTargetScrollingBehavior = lp.behavior
            }
            if (lp.behavior != null) {
                lp.behavior = null
                binding.mainBlurTarget.layoutParams = lp
            }
        } else if (lp.behavior == null) {
            lp.behavior = blurTargetScrollingBehavior ?: ScrollingViewBehavior()
            blurTargetScrollingBehavior = lp.behavior
            binding.mainBlurTarget.layoutParams = lp
        }
    }

    /** Fixed blur stack offset while the app bar is shown (MainActivity [syncBlurTargetTopMarginForAppBar]). */
    private fun syncBlurTargetTopMarginForAppBar() {
        val targetTopMargin = when {
            isQuickTextsActionModeToolbarVisible() -> 0
            binding.quickTextsAppbar.visibility == View.VISIBLE ->
                resources.getDimensionPixelSize(R.dimen.main_app_bar_blur_offset)
            else -> 0
        }
        binding.mainBlurTarget.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (topMargin != targetTopMargin) {
                topMargin = targetTopMargin
            }
        }
    }

    override fun getBlurTargetView() = binding.mainBlurTarget

    /**
     * Bottom [com.android.common.view.MRippleToolBar] for quick text selection ([MainActivity] pattern).
     */
    private fun applyActionModeRippleToolbarForQuickTexts() {
        val blurTarget = binding.mainBlurTarget
        val adapter = binding.manageQuickTextsList.adapter as? ManageQuickTextsAdapter ?: return
        if (!adapter.isActionModeActive()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        val (items, _) = adapter.buildQuickTextsRippleToolbar()
        if (items.isEmpty()) {
            binding.actionModeRippleToolbar.visibility = View.GONE
            return
        }
        binding.actionModeRippleToolbar.setTabs(this, items, blurTarget)
        binding.actionModeRippleToolbar.setOnClickedListener { index ->
            adapter.dispatchRippleToolbarAction(index)
        }
        binding.actionModeRippleToolbar.visibility = View.VISIBLE
        val hasSelection = adapter.hasRippleToolbarSelection()
        for (i in 0 until items.size) {
            binding.actionModeRippleToolbar.setRippleTabEnabledWidthAlpha(i, hasSelection)
        }
    }

    fun refreshActionModeRippleToolbarIfNeeded() {
        if (isDestroyed || isFinishing) return
        applyActionModeRippleToolbarForQuickTexts()
        binding.root.post { syncManageQuickTextsListBottomPadding() }
    }

    /** Glass top chrome: keep [MAppBarLayout] transparent so [MVSideFrame] blur shows through (txCommon). */
    private fun applyTransparentMAppBarChrome() {
        binding.quickTextsAppbar.apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            setLiftOnScrollColor(null)
        }
        binding.quickTextsActionModeToolbar.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun setupQuickTextsTopAppBar() {
        binding.quickTextsAppbar.setTitle(getString(R.string.manage_quick_texts))

        binding.quickTextsAppbar.getBackArrow()?.apply {
            bindBlurTarget(this@ManageQuickTextsActivity, binding.mainBlurTarget)
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

        binding.quickTextsAppbar.getSearchView()?.visibility = View.GONE
        binding.quickTextsAppbar.getActionBarView()?.let(::setupQuickTextsActionBarMenu)
        applyTransparentMAppBarChrome()
    }

    private fun setupQuickTextsActionBarMenu(actionBar: MActionBar) {
        actionBar.bindBlurTarget(this, binding.mainBlurTarget)
        actionBar.setPosition("right")
        actionBar.inflateMenu(R.menu.menu_add_quick_text)
        actionBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_quick_text -> {
                    addOrEditQuickText()
                    true
                }

                R.id.export_quick_texts -> {
                    tryExportQuickTexts()
                    true
                }

                R.id.import_quick_texts -> {
                    tryImportQuickTexts()
                    true
                }

                R.id.select_quick_text -> {
                    val adapter = binding.manageQuickTextsList.adapter as? ManageQuickTextsAdapter
                    if (adapter != null && adapter.itemCount > 0) {
                        adapter.startActMode()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setupQuickTextsSpringSync() {
        (binding.manageQuickTextsList as? MyRecyclerView)?.onOverscrollTranslationChanged = { translationY ->
            binding.quickTextsAppbar.translationY = translationY * NEST_BOUNCY_OVERSCROLL_FACTOR
        }
    }

    private fun clearQuickTextsSpringSync() {
        (binding.manageQuickTextsList as? MyRecyclerView)?.onOverscrollTranslationChanged = null
        binding.quickTextsAppbar.translationY = 0f
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            try {
                val outputStream = uri?.let { contentResolver.openOutputStream(it) }
                if (outputStream != null) {
                    exportQuickTextsTo(outputStream)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            try {
                if (uri != null) {
                    tryImportQuickTextsFromFile(uri)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private fun tryImportQuickTexts() {
        val mimeType = "text/plain"
        try {
            getContent.launch(mimeType)
        } catch (_: ActivityNotFoundException) {
            toast(com.goodwy.commons.R.string.system_service_disabled, Toast.LENGTH_LONG)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun tryImportQuickTextsFromFile(uri: Uri) {
        when (uri.scheme) {
            "file" -> importQuickTexts(uri.path!!)
            "content" -> {
                val tempFile = getTempFile("quick", "quick_texts.txt")
                if (tempFile == null) {
                    toast(com.goodwy.commons.R.string.unknown_error_occurred)
                    return
                }

                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val out = FileOutputStream(tempFile)
                    inputStream!!.copyTo(out)
                    importQuickTexts(tempFile.absolutePath)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }

            else -> toast(com.goodwy.commons.R.string.invalid_file_format)
        }
    }

    private fun importQuickTexts(path: String) {
        ensureBackgroundThread {
            val result = QuickTextsImporter(this).importQuickTexts(path)
            toast(
                when (result) {
                    QuickTextsImporter.ImportResult.IMPORT_OK -> com.goodwy.commons.R.string.importing_successful
                    QuickTextsImporter.ImportResult.IMPORT_FAIL -> com.goodwy.commons.R.string.no_items_found
                },
            )
            updateQuickTexts()
        }
    }

    private fun exportQuickTextsTo(outputStream: OutputStream?) {
        ensureBackgroundThread {
            val quickTexts = config.quickTexts.toArrayList()
            if (quickTexts.isEmpty()) {
                toast(com.goodwy.commons.R.string.no_entries_for_exporting)
            } else {
                QuickTextsExporter.exportQuickTexts(quickTexts, outputStream) {
                    toast(
                        when (it) {
                            ExportResult.EXPORT_OK -> com.goodwy.commons.R.string.exporting_successful
                            else -> com.goodwy.commons.R.string.exporting_failed
                        },
                    )
                }
            }
        }
    }

    private fun tryExportQuickTexts() {
        ExportQuickTextsDialog(
            activity = this,
            path = config.lastQuickTextExportPath,
            hidePath = true,
            blurTarget = binding.mainBlurTarget,
        ) { file ->
            try {
                createDocument.launch(file.name)
            } catch (_: ActivityNotFoundException) {
                toast(
                    com.goodwy.commons.R.string.system_service_disabled,
                    Toast.LENGTH_LONG,
                )
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    override fun refreshItems() {
        updateQuickTexts()
    }

    private fun updateQuickTexts() {
        ensureBackgroundThread {
            val quickTexts = config.quickTexts.toArrayList()
            runOnUiThread {
                ManageQuickTextsAdapter(
                    activity = this,
                    quickTexts = quickTexts,
                    listener = this,
                    recyclerView = binding.manageQuickTextsList,
                ) {
                    addOrEditQuickText(it as String)
                }.apply {
                    binding.manageQuickTextsList.adapter = this
                }

                binding.manageQuickTextsPlaceholder.beVisibleIf(quickTexts.isEmpty())
                binding.manageQuickTextsPlaceholder2.beVisibleIf(quickTexts.isEmpty())
            }
        }
    }

    private fun addOrEditQuickText(text: String? = null) {
        val dialog = MRenameDialog(this)
        dialog.bindBlurTarget(binding.mainBlurTarget)
        dialog.setTitle(getString(if (text == null) R.string.add_a_quick_text else R.string.quick_text))
        dialog.setHintText(getString(R.string.type_a_message))
        dialog.setContentText(text.orEmpty())
        dialog.setOnRenameListener { newQuickText ->
            if (text != null && newQuickText != text) {
                config.removeQuickText(text)
            }
            if (newQuickText.isNotEmpty()) {
                config.addQuickText(newQuickText)
            }
            updateQuickTexts()
        }
        dialog.show()
        dialog.window?.decorView?.findViewById<EditText>(com.android.common.R.id.input_text)?.let { et ->
            et.post { showKeyboard(et) }
        }
    }

    companion object {
        private const val NEST_BOUNCY_OVERSCROLL_FACTOR = 0.35f
        private const val TOP_INSET_SYNC_DURATION_MS = 480L
    }
}
