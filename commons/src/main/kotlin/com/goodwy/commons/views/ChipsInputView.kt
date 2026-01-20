package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.children
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.goodwy.commons.R
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getContrastColor

class ChipsInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val chipGroup: ChipGroup
    private val editText: MyEditText
    private val clearButton: ImageView
    private val speechToTextButton: ImageView

    private val chips = mutableListOf<String>()
    private var onTextChangedListener: ((String) -> Unit)? = null
    private var onChipsChangedListener: ((List<String>) -> Unit)? = null
    private var onChipAddedListener: ((String) -> Boolean)? = null

    var hint: String
        get() = editText.hint?.toString() ?: ""
        set(value) {
            editText.hint = value
        }

    val currentText: String
        get() = editText.text?.toString() ?: ""

    val allChips: List<String>
        get() = chips.toList()

    init {
        val inflater = LayoutInflater.from(context)
        val rootView = inflater.inflate(R.layout.view_chips_input, this, true)

        chipGroup = rootView.findViewById(R.id.chips_group)
        editText = rootView.findViewById(R.id.chips_edit_text)
        clearButton = rootView.findViewById(R.id.chips_clear_button)
        speechToTextButton = rootView.findViewById(R.id.chips_speech_to_text_button)

        setupEditText()
        setupButtons()
        updateButtonsVisibility("")
    }

    private fun setupEditText() {
        editText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                addChipFromText()
                true
            } else {
                false
            }
        }

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                
                // Check for comma or semicolon to add chip
                if (text.endsWith(",") || text.endsWith(";")) {
                    val trimmed = text.dropLast(1).trim()
                    if (trimmed.isNotEmpty()) {
                        editText.setText("")
                        addChip(trimmed)
                    }
                } else {
                    onTextChangedListener?.invoke(text)
                    updateButtonsVisibility(text)
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupButtons() {
        clearButton.setOnClickListener {
            editText.setText("")
            editText.requestFocus()
        }

        speechToTextButton.setOnClickListener {
            // This will be handled by the activity
        }
    }

    fun addChip(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        
        // Allow duplicates but check if already exists
        if (chips.contains(trimmed)) {
            return false
        }

        chips.add(trimmed)
        createChipView(trimmed)
        
        // Call onChipAddedListener for validation
        onChipAddedListener?.invoke(trimmed)
        
        onChipsChangedListener?.invoke(chips.toList())
        updateButtonsVisibility(editText.text?.toString() ?: "")
        return true
    }

    private fun addChipFromText() {
        val text = editText.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) {
            if (addChip(text)) {
                editText.setText("")
            }
        }
    }

    fun removeChip(text: String) {
        val index = chips.indexOf(text)
        if (index >= 0) {
            chips.removeAt(index)
            chipGroup.removeViewAt(index)
            onChipsChangedListener?.invoke(chips.toList())
        }
    }

    fun clearChips() {
        chips.clear()
        chipGroup.removeAllViews()
        onChipsChangedListener?.invoke(emptyList())
    }

    fun clearText() {
        editText.setText("")
    }

    fun setText(text: String) {
        editText.setText(text)
    }

    fun requestEditTextFocus() {
        editText.requestFocus()
    }

    fun setOnTextChangedListener(listener: (String) -> Unit) {
        onTextChangedListener = listener
    }

    fun setOnChipsChangedListener(listener: (List<String>) -> Unit) {
        onChipsChangedListener = listener
    }

    fun setOnChipAddedListener(listener: (String) -> Boolean) {
        onChipAddedListener = listener
    }

    fun setSpeechToTextButtonVisible(visible: Boolean) {
        speechToTextButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setSpeechToTextButtonClickListener(listener: () -> Unit) {
        speechToTextButton.setOnClickListener { listener() }
    }

    fun setColors(textColor: Int, accentColor: Int, backgroundColor: Int) {
        editText.setColors(textColor, accentColor, backgroundColor)
        
        val contrastColor = accentColor.getContrastColor()
        val chipBackgroundColor = accentColor.adjustAlpha(0.2f)
        
        // Update existing chips
        chipGroup.children.forEach { view ->
            if (view is Chip) {
                view.chipBackgroundColor = ColorStateList.valueOf(chipBackgroundColor)
                view.setTextColor(textColor)
                view.chipIconTint = ColorStateList.valueOf(textColor)
            }
        }

        clearButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
        speechToTextButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
    }

    private fun createChipView(text: String) {
        val chip = Chip(context)
        chip.text = text
        chip.isCloseIconVisible = true
        chip.isClickable = false
        chip.isCheckable = false
        
        // Set colors if they've been set
        val textColor = editText.currentTextColor
        val accentColor = context.getColor(com.goodwy.commons.R.color.color_primary)
        val chipBackgroundColor = accentColor.adjustAlpha(0.2f)
        
        chip.chipBackgroundColor = ColorStateList.valueOf(chipBackgroundColor)
        chip.setTextColor(textColor)
        chip.chipIconTint = ColorStateList.valueOf(textColor)
        chip.closeIconTint = ColorStateList.valueOf(textColor.adjustAlpha(0.6f))

        chip.setOnCloseIconClickListener {
            removeChip(text)
        }

        chipGroup.addView(chip)
    }

    private fun updateButtonsVisibility(text: String) {
        val hasText = text.isNotEmpty()
        clearButton.visibility = if (hasText) View.VISIBLE else View.GONE
        speechToTextButton.visibility = if (!hasText) View.VISIBLE else View.GONE
    }

    fun getEditText(): MyEditText = editText
    fun getClearButton(): ImageView = clearButton
    fun getSpeechToTextButton(): ImageView = speechToTextButton
}

