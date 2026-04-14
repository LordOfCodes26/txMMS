package com.android.mms.activities

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
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
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.android.mms.R
import com.android.mms.databinding.ActivityManageQuickTextsBinding
import com.android.mms.dialogs.AddQuickTextDialog
import com.android.mms.dialogs.ExportQuickTextsDialog
import com.android.mms.dialogs.ManageQuickTextsAdapter
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.clearMySearchMenuSpringSync
import com.android.mms.extensions.config
import com.android.mms.extensions.postSyncMySearchMenuToolbarGeometry
import com.android.mms.extensions.setupMySearchMenuSpringSync
import com.android.mms.extensions.toArrayList
import com.android.mms.helpers.QuickTextsExporter
import com.android.mms.helpers.QuickTextsImporter
import java.io.FileOutputStream
import java.io.OutputStream

class ManageQuickTextsActivity : SimpleActivity(), RefreshRecyclerViewListener {

    private lateinit var binding: ActivityManageQuickTextsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManageQuickTextsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initTheme()
        initMVSideFrames()
        setupEdgeToEdge()
        makeSystemBarsToTransparent()
        setupTopBar()
        setupOptionsMenu()
        updateQuickTexts()
        binding.manageQuickTextsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditQuickText()
            }
        }
        binding.nestScroll.post {
            postSyncMySearchMenuToolbarGeometry(
                binding.root,
                binding.quickTextsAppbar,
                binding.mainBlurTarget,
                binding.mVerticalSideFrameTop,
                binding.manageQuickTextsList,
            )
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

        val useSurfaceColor = isDynamicTheme() && !isSystemInDarkMode()
        val backgroundColor = if (useSurfaceColor) getSurfaceColor() else getProperBackgroundColor()
        binding.rootView.setBackgroundColor(backgroundColor)
        binding.mainBlurTarget.setBackgroundColor(backgroundColor)
        binding.manageQuickTextsList.setBackgroundColor(backgroundColor)
        updateTextColors(binding.rootView)
        setupTopBar()
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

    private fun initMVSideFrames() {
        binding.mVerticalSideFrameTop.bindBlurTarget(binding.mainBlurTarget)
        binding.mVerticalSideFrameBottom.bindBlurTarget(binding.mainBlurTarget)
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
            applyQuickTextsListBottomInset(navHeight, ime.bottom)
            insets
        }
    }

    private fun applyQuickTextsListBottomInset(navHeight: Int, imeBottom: Int) {
        val activityMargin = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        val bottomInset = if (imeBottom > 0) imeBottom else navHeight
        binding.manageQuickTextsList.updatePadding(bottom = bottomInset + activityMargin)
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
            bindBlurTarget(this@ManageQuickTextsActivity, binding.mainBlurTarget)
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
        AddQuickTextDialog(this, text, blurTarget = binding.mainBlurTarget) {
            updateQuickTexts()
        }
    }
}
