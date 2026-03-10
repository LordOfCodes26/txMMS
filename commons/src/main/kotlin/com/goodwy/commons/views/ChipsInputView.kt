package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.chip.Chip
import android.view.ViewGroup
import com.goodwy.commons.R
import com.goodwy.commons.extensions.adjustAlpha

class ChipsInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val flexContainer: FlexboxLayout
    private val editText: MyEditText
    private val clearButton: ImageView
    private val addressBookButton: ImageView
    private val speechToTextButton: ImageView

    private val chips = mutableListOf<String>()
    private var placeholderHint: String = ""
    private var onTextChangedListener: ((String) -> Unit)? = null
    private var onChipsChangedListener: ((List<String>) -> Unit)? = null
    private var onChipAddedListener: ((String) -> Boolean)? = null

    var hint: String
        get() = editText.hint?.toString() ?: ""
        set(value) {
            placeholderHint = value
            updatePlaceholderVisibility()
        }

    val currentText: String
        get() = editText.text?.toString() ?: ""

    val allChips: List<String>
        get() = chips.toList()

    init {
        val inflater = LayoutInflater.from(context)
        val rootView = inflater.inflate(R.layout.view_chips_input, this, true)

        flexContainer = rootView.findViewById(R.id.chips_flex_container)
        editText = rootView.findViewById(R.id.chips_edit_text)
        clearButton = rootView.findViewById(R.id.chips_clear_button)
        addressBookButton = rootView.findViewById(R.id.chips_address_book_button)
        speechToTextButton = rootView.findViewById(R.id.chips_speech_to_text_button)

        setupEditText()
        setupButtons()
        updateButtonsVisibility("")
        updatePlaceholderVisibility()
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

        // Backspace with empty field removes the last chip (tag-style behavior)
        editText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                val text = editText.text?.toString() ?: ""
                if (text.isEmpty() && chips.isNotEmpty()) {
                    removeChip(chips.last())
                    true
                } else {
                    false
                }
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
                    updatePlaceholderVisibility()
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

        addressBookButton.setOnClickListener {
            // Handled by setAddressBookButtonClickListener
        }

        speechToTextButton.setOnClickListener {
            // Handled by setSpeechToTextButtonClickListener
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
        updatePlaceholderVisibility()
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
            flexContainer.removeViewAt(index)
            onChipsChangedListener?.invoke(chips.toList())
            updatePlaceholderVisibility()
        }
    }

    fun clearChips() {
        chips.clear()
        // Remove only chip views; keep the trailing edit wrapper (last child)
        while (flexContainer.childCount > 1) {
            flexContainer.removeViewAt(0)
        }
        onChipsChangedListener?.invoke(emptyList())
        updatePlaceholderVisibility()
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

    fun setAddressBookButtonClickListener(listener: () -> Unit) {
        addressBookButton.setOnClickListener { listener() }
    }

    fun setColors(textColor: Int, accentColor: Int, backgroundColor: Int) {
        editText.setColors(textColor, accentColor, backgroundColor)
        
        val chipBackgroundColor = accentColor.adjustAlpha(0.2f)
        
        // Update existing chips (flexContainer also has the edit wrapper as last child)
        flexContainer.children.forEach { view ->
            if (view is Chip) {
                view.chipBackgroundColor = ColorStateList.valueOf(chipBackgroundColor)
                view.setTextColor(textColor)
                view.chipIconTint = ColorStateList.valueOf(textColor)
            }
        }

        clearButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
        addressBookButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
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
        val chipBackgroundColor = accentColor.adjustAlpha(0.1f)

        val shapeDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 60f
        }
        chip.background = shapeDrawable
        chip.chipBackgroundColor = ColorStateList.valueOf(chipBackgroundColor)
        chip.chipStrokeWidth = 0f
        chip.setTextColor(textColor)
        chip.chipIconTint = ColorStateList.valueOf(textColor)
        chip.closeIconTint = ColorStateList.valueOf(textColor.adjustAlpha(0.6f))

        chip.setOnCloseIconClickListener {
            removeChip(text)
        }

        val chipMargin = context.resources.getDimensionPixelSize(R.dimen.tiny_margin)
        val params = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = chipMargin
            bottomMargin = chipMargin
        }
        chip.layoutParams = params

        // Insert chip before the trailing edit wrapper (iPhone-style: chips then text input)
        val insertIndex = (flexContainer.childCount - 1).coerceAtLeast(0)
        flexContainer.addView(chip, insertIndex)
    }

    private fun updateButtonsVisibility(text: String) {
        val hasText = text.isNotEmpty()
        clearButton.visibility = if (hasText) View.VISIBLE else View.GONE
        addressBookButton.visibility = if (!hasText) View.VISIBLE else View.GONE
        speechToTextButton.visibility = View.GONE
    }

    private fun updatePlaceholderVisibility() {
        val hasContent = chips.isNotEmpty() || currentText.isNotEmpty()
        editText.hint = if (hasContent) "" else placeholderHint
    }

    fun getEditText(): MyEditText = editText
    fun getClearButton(): ImageView = clearButton
    fun getAddressBookButton(): ImageView = addressBookButton
    fun getSpeechToTextButton(): ImageView = speechToTextButton
}

