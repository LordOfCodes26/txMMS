package com.android.mms.activities

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.android.mms.R
import com.android.mms.databinding.ActivityManageQuickTextsBinding
import com.android.mms.dialogs.AddQuickTextDialog
import com.android.mms.dialogs.ExportQuickTextsDialog
import com.android.mms.dialogs.ManageQuickTextsAdapter
import com.android.mms.extensions.config
import com.android.mms.extensions.toArrayList
import com.android.mms.helpers.QuickTextsExporter
import com.android.mms.helpers.QuickTextsImporter
import java.io.FileOutputStream
import java.io.OutputStream

class ManageQuickTextsActivity : SimpleActivity(), RefreshRecyclerViewListener {

    private val binding by viewBinding(ActivityManageQuickTextsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateQuickTexts()
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.manageQuickTextsList))
        setupMaterialScrollListener(
            scrollingView = binding.manageQuickTextsList,
            topAppBar = binding.quickTextsAppbar
        )
        updateTextColors(binding.manageQuickTextsWrapper)

        binding.manageQuickTextsPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addOrEditQuickText()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.quickTextsAppbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.quickTextsToolbar.setOnMenuItemClickListener { menuItem ->
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

                else -> false
            }
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
                }
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
                        }
                    )
                }
            }
        }
    }

    private fun tryExportQuickTexts() {
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        ExportQuickTextsDialog(
            activity = this,
            path = config.lastQuickTextExportPath,
            hidePath = true,
            blurTarget = blurTarget
        ) { file ->
            try {
                createDocument.launch(file.name)
            } catch (_: ActivityNotFoundException) {
                toast(
                    com.goodwy.commons.R.string.system_service_disabled,
                    Toast.LENGTH_LONG
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
            val quickTexts = config.quickTexts.sorted().toArrayList()
            runOnUiThread {
                ManageQuickTextsAdapter(
                    activity = this,
                    quickTexts = quickTexts,
                    listener = this,
                    recyclerView = binding.manageQuickTextsList
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
        val blurTarget = findViewById<eightbitlab.com.blurview.BlurTarget>(R.id.mainBlurTarget)
            ?: throw IllegalStateException("mainBlurTarget not found")
        AddQuickTextDialog(this, text, blurTarget = blurTarget) {
            updateQuickTexts()
        }
    }
}

