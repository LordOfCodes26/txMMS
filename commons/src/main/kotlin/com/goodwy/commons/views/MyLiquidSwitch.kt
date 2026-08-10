package com.goodwy.commons.views

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.android.common.view.MSwitch
import com.qmdeve.liquidglass.view.LiquidGlassSwitch

/**
 * Kotlin-friendly wrapper laying out a label [TextView] + txCommon [MSwitch] (LiquidGlassSwitch
 * with Dialer/CardView-friendly height clamping and pre-toggle click dispatch) in a row.
 *
 * [MSwitch] / [LiquidGlassSwitch] always centers its fixed-size track pill within whatever width
 * it is given (it isn't a label-aware [android.widget.CompoundButton] row like stock
 * [android.widget.Switch]), so the label can't be rendered via its own `android:text`; instead
 * this wrapper positions a separate start-aligned label next to a compact, wrap-content-sized
 * switch pinned to the end — mirroring the old Compose `Row` (label with weight 1f + toggle).
 *
 * The wrapper (rather than subclassing [MSwitch] directly) also keeps the listener API as a
 * simple `(Boolean) -> Unit`; [LiquidGlassSwitch] exposes two overloaded
 * `setOnCheckedChangeListener` methods (for [android.widget.CompoundButton.OnCheckedChangeListener]
 * and its own `OnCheckedChangeListener`) that would make a single-arg Kotlin lambda ambiguous.
 *
 * [MSwitch] draws an opaque [switchBackgroundColor] rectangle behind the track (larger than the
 * pill). That color must match the surface behind the switch (typically the parent [CardView]);
 * otherwise a white box shows around the green track. This wrapper syncs from the nearest
 * [CardView] after attach, matching txCommon SampleActivity / `layout_pager_control.xml`.
 */
class MyLiquidSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val labelView: TextView = TextView(context, attrs)

    // Pass attrs through so LiquidGlassSwitch-specific XML attributes (trackOnColor,
    // trackOffColor, thumbColor, switchBackgroundColor, touchable) and base View attributes
    // (clickable, focusable, etc.) declared on the <MyLiquidSwitch> tag still apply to the real
    // switch. android:text is harmless here too: MSwitch always centers its track pill
    // within whatever width it's given rather than reserving space for a CompoundButton label, so
    // it never actually renders text (hence labelView above).
    val liquidSwitch: MSwitch = MSwitch(context, attrs)

    private var listener: ((Boolean) -> Unit)? = null
    private var switchBackgroundExplicitlySet = false

    init {
        clipChildren = false
        clipToPadding = false

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, intArrayOf(com.android.common.R.attr.switchBackgroundColor))
            try {
                switchBackgroundExplicitlySet = a.hasValue(0)
            } finally {
                a.recycle()
            }
        }
        // Default to settings card surface so the glass backdrop is not white-on-gray during toggle.
        if (!switchBackgroundExplicitlySet) {
            liquidSwitch.setSwitchBackgroundColor(
                context.getColor(com.android.common.R.color.tx_cardview_bg)
            )
        }

        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        labelView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dpToPx(8f)
        }
        rowLayout.addView(labelView)

        // Match txCommon sample host size (60x30dp) so CardView AT_MOST measure stays stable.
        liquidSwitch.layoutParams = LinearLayout.LayoutParams(dpToPx(60f), dpToPx(30f))
        liquidSwitch.setOnCheckedChangeListener(object : LiquidGlassSwitch.OnCheckedChangeListener {
            override fun onCheckedChanged(view: LiquidGlassSwitch, isChecked: Boolean) {
                listener?.invoke(isChecked)
            }
        })
        rowLayout.addView(liquidSwitch)

        addView(rowLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        updateLabelVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Post so Activity theme code (e.g. setCardBackgroundColor) can run first in onCreate.
        if (!switchBackgroundExplicitlySet) {
            post { syncSwitchBackgroundFromAncestor() }
        }
    }

    var isChecked: Boolean
        get() = liquidSwitch.isChecked
        set(value) {
            liquidSwitch.isChecked = value
        }

    fun toggle() {
        liquidSwitch.setCheckedWithAnim(!liquidSwitch.isChecked)
    }

    fun setOnCheckedChangeListener(l: (Boolean) -> Unit) {
        listener = l
    }

    fun setLabelText(text: String) {
        labelView.text = text
        updateLabelVisibility()
    }

    /** Matches the opaque glass backdrop to the surface behind this switch (see class KDoc). */
    fun setSwitchBackgroundColor(color: Int) {
        switchBackgroundExplicitlySet = true
        liquidSwitch.setSwitchBackgroundColor(color)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        liquidSwitch.isEnabled = enabled
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        liquidSwitch.isClickable = clickable
    }

    private fun syncSwitchBackgroundFromAncestor() {
        if (switchBackgroundExplicitlySet) return
        var current: View? = this
        while (current != null) {
            when (current) {
                is CardView -> {
                    liquidSwitch.setSwitchBackgroundColor(current.cardBackgroundColor.defaultColor)
                    return
                }
                else -> {
                    val bg = current.background
                    if (bg is ColorDrawable && current !== this) {
                        liquidSwitch.setSwitchBackgroundColor(bg.color)
                        return
                    }
                }
            }
            current = current.parent as? View
        }
    }

    private fun updateLabelVisibility() {
        labelView.visibility = if (labelView.text.isNullOrEmpty()) GONE else VISIBLE
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
