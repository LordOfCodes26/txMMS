package com.goodwy.commons.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import com.google.android.flexbox.FlexboxLayout
import com.goodwy.commons.R
import com.goodwy.commons.extensions.adjustAlpha
import com.goodwy.commons.extensions.getTextSizeSmall

class ChipsInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var contentFrame: View? = null
    private val flexContainer: FlexboxLayout
    private val chipsScrollView: MaxHeightScrollView?
    private val editText: MyEditText
    private val clearButton: ImageView
    private val addressBookButton: ImageView
    private val speechToTextButton: ImageView

    private val chips = mutableListOf<String>()
    private var placeholderHint: String = ""
    private var onTextChangedListener: ((String) -> Unit)? = null
    private var onChipsChangedListener: ((List<String>) -> Unit)? = null
    private var onChipAddedListener: ((String) -> Boolean)? = null

    private var smallFontSize = context.getTextSizeSmall()
    /** Marks flex children that are our compact chip rows (not Material Chip — full control over height). */
    private val chipRowMarker = Any()

    private val outlineRadiusPx: Float
        get() = 24f * resources.displayMetrics.density

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
        var elevationFromAttrs = 0f
        context.theme.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.elevation), defStyleAttr, 0).use { a ->
            if (a.hasValue(0)) {
                elevationFromAttrs = a.getDimension(0, 0f)
            }
        }

        val inflater = LayoutInflater.from(context)
        val rootView = inflater.inflate(R.layout.view_chips_input, this, true)
        val frame = getChildAt(0)
        contentFrame = frame

        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, outlineRadiusPx)
                }
            }
        }
        if (elevationFromAttrs > 0f) {
            frame.elevation = elevationFromAttrs
            frame.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.elevation > 0f && v.width > 0 && v.height > 0) {
                v.invalidateOutline()
            }
        }
        super.setElevation(0f)

        flexContainer = rootView.findViewById(R.id.chips_flex_container)
        chipsScrollView = flexContainer.parent as? MaxHeightScrollView
        editText = rootView.findViewById(R.id.chips_edit_text)
        val chipsEditWrapper = rootView.findViewById<View>(R.id.chips_edit_wrapper)
        clearButton = rootView.findViewById(R.id.chips_clear_button)
        addressBookButton = rootView.findViewById(R.id.chips_address_book_button)
        speechToTextButton = rootView.findViewById(R.id.chips_speech_to_text_button)

        setupEditText()
        setupButtons()
        flexContainer.isClickable = true
        flexContainer.setOnClickListener { editText.requestFocus() }
        chipsEditWrapper.isClickable = true
        chipsEditWrapper.setOnClickListener { editText.requestFocus() }
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
        addressBookButton.setOnClickListener {}
        speechToTextButton.setOnClickListener {}
    }

    fun addChip(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (chips.contains(trimmed)) return false

        chips.add(trimmed)
        createChipView(trimmed)
        scrollToBottom()
        onChipAddedListener?.invoke(trimmed)
        onChipsChangedListener?.invoke(chips.toList())
        updateButtonsVisibility(editText.text?.toString() ?: "")
        updatePlaceholderVisibility()
        return true
    }

    private fun addChipFromText() {
        val text = editText.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty() && addChip(text)) {
            editText.setText("")
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
        val closeTint = textColor.adjustAlpha(0.6f)

        flexContainer.children.forEach { view ->
            if (view.tag === chipRowMarker && view is LinearLayout) {
                (view.background as? GradientDrawable)?.setColor(chipBackgroundColor)
                (view.getChildAt(0) as? TextView)?.setTextColor(textColor)
                (view.getChildAt(1) as? ImageView)?.imageTintList = ColorStateList.valueOf(closeTint)
            }
        }

        clearButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
        addressBookButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
        speechToTextButton.imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
    }

    private fun createChipView(text: String) {
        val res = context.resources
        val textColor = editText.currentTextColor

        val rowHeight = res.getDimensionPixelSize(R.dimen.chips_input_chip_row_height)
        val padH = res.getDimensionPixelSize(R.dimen.chips_input_chip_pad_h)
        val iconSize = res.getDimensionPixelSize(R.dimen.chips_input_chip_icon_size)
        val row = LinearLayout(context).apply {
            tag = chipRowMarker
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = rowHeight / 2f
                setColor(textColor.adjustAlpha(0.2f))
            }
            setPadding(padH, 0, padH, 0)
        }

        val tv = TextView(context).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, smallFontSize)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_clear_round)
            imageTintList = ColorStateList.valueOf(textColor.adjustAlpha(0.4f))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams((iconSize*1.5).toInt(), (iconSize*1.5).toInt()).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.tiny_margin)
                gravity = Gravity.CENTER_VERTICAL
            }
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                setBackgroundResource(out.resourceId)
            }
            setOnClickListener { removeChip(text) }
            contentDescription = context.getString(R.string.delete)
        }

        row.addView(tv)
        row.addView(close)

        val chipMarginH = res.getDimensionPixelSize(R.dimen.tiny_margin)
        row.layoutParams = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            rowHeight
        ).apply {
            marginEnd = chipMarginH
            bottomMargin = chipMarginH
            topMargin = chipMarginH
        }

        val insertIndex = (flexContainer.childCount - 1).coerceAtLeast(0)
        flexContainer.addView(row, insertIndex)
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

    private fun scrollToBottom() {
        chipsScrollView?.post { chipsScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun getElevation(): Float = contentFrame?.elevation ?: super.getElevation()

    override fun setElevation(elevation: Float) {
        val frame = contentFrame
        if (frame != null) {
            super.setElevation(0f)
            frame.elevation = elevation
            frame.setLayerType(if (elevation > 0f) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
        } else {
            super.setElevation(elevation)
        }
    }

    fun getEditText(): MyEditText = editText
    fun getClearButton(): ImageView = clearButton
    fun getAddressBookButton(): ImageView = addressBookButton
    fun getSpeechToTextButton(): ImageView = speechToTextButton
}
