package com.android.mms.activities

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.android.common.dialogs.MRenameDialog
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getColoredDrawableWithColor
import com.goodwy.commons.extensions.getTempFile
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
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
import com.android.mms.R
import com.android.mms.databinding.ActivityManageQuickTextsBinding
import com.android.mms.dialogs.ExportQuickTextsDialog
import com.android.mms.dialogs.ManageQuickTextsAdapter
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setRippleTabEnabledWidthAlpha
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.extensions.toArrayList
import com.android.mms.helpers.QuickTextsExporter
import com.android.mms.helpers.QuickTextsImporter
import com.goodwy.commons.extensions.showKeyboard
import java.io.FileOutputStream
import java.io.OutputStream
class ManageQuickTextsActivity : SimpleActivity(), RefreshRecyclerViewListener, ActionModeToolbarHost {

    private lateinit var binding: ActivityManageQuickTextsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageQuickTextsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initTheme()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupTopBar()
        setupOptionsMenu()
        applyQuickTextsWindowSurfacesAndChrome()
        updateQuickTexts()
        binding.manageQuickTextsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditQuickText()
            }
        }
        binding.nestScroll.post {
            setupMySearchMenuSpringSync(binding.quickTextsAppbar, binding.manageQuickTextsList)
            if (config.changeColourTopBar) {
                scrollingView = binding.manageQuickTextsList
                val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
                setupSearchMenuScrollListener(
                    binding.manageQuickTextsList,
                    binding.quickTextsAppbar,
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

        applyQuickTextsWindowSurfacesAndChrome()
        updateTextColors(binding.rootView)
        setupTopBar()
        refreshSideFrameBlurAndInsets()
    }

    /** BlurView + MVSideFrame can stop updating after another activity was shown; re-apply insets and re-bind. */
    private fun refreshSideFrameBlurAndInsets() {
        binding.root.post {
            ViewCompat.requestApplyInsets(binding.root)
            binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
            binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
            binding.quickTextsAppbar.requireCustomToolbar().bindBlurTarget(
                this@ManageQuickTextsActivity,
                binding.mainBlurTarget,
            )
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.quickTextsAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.manageQuickTextsList,
            )
        }
    }

    private fun applyQuickTextsWindowSurfacesAndChrome() {
        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.root.setBackgroundColor(backgroundColor)
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.manageQuickTextsList.setBackgroundColor(backgroundColor)
        scrollingView = binding.manageQuickTextsList
        binding.quickTextsAppbar.updateColors(
            getStartRequiredStatusBarColor(),
            scrollingView?.computeVerticalScrollOffset() ?: 0,
        )
        setQuickTextsTransparentAppBarBackground()
    }

    override fun onDestroy() {
        clearMySearchMenuSpringSync(binding.quickTextsAppbar, binding.manageQuickTextsList)
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
            val bottomInset = maxOf(navHeight, ime.bottom)
            val rippleLp = binding.actionModeRippleToolbar.layoutParams as? ViewGroup.MarginLayoutParams
            if (rippleLp != null) {
                val rippleBase = resources.getDimensionPixelSize(R.dimen.ripple_bottom)
                rippleLp.bottomMargin = rippleBase + bottomInset
                binding.actionModeRippleToolbar.layoutParams = rippleLp
            }
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
        binding.quickTextsAppbar.getActionModeToolbar()

    override fun showActionModeToolbar() {
        binding.quickTextsAppbar.showActionModeToolbar()
        binding.root.post {
            applyActionModeRippleToolbarForQuickTexts()
            binding.root.post { syncManageQuickTextsListBottomPadding() }
        }
    }

    override fun hideActionModeToolbar() {
        binding.quickTextsAppbar.hideActionModeToolbar()
        binding.actionModeRippleToolbar.visibility = View.GONE
        syncManageQuickTextsListBottomPadding()
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

    private fun setQuickTextsTransparentAppBarBackground() {
        binding.quickTextsAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.quickTextsAppbar.binding.searchBarContainer.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun setupTopBar() {
        binding.quickTextsAppbar.applyLargeTitleOnly(getString(R.string.manage_quick_texts))
        binding.quickTextsAppbar.requireCustomToolbar().apply {
            val textColor = getProperTextColor()
            navigationIcon = resources.getColoredDrawableWithColor(
                this@ManageQuickTextsActivity,
                com.android.common.R.drawable.ic_cmn_arrow_left_fill,
                textColor,
            )
            setNavigationContentDescription(com.goodwy.commons.R.string.back)
            setNavigationOnClickListener {
                hideKeyboard()
                finish()
            }
        }
        binding.quickTextsAppbar.searchBeVisibleIf(false)
    }

    private fun setupOptionsMenu() {
        binding.quickTextsAppbar.requireCustomToolbar().apply {
            menu.clear()
            inflateMenu(R.menu.menu_add_quick_text)
            updateMenuItemColors(menu)
            setOnMenuItemClickListener { menuItem ->
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
            invalidateMenu()
        }
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
}
