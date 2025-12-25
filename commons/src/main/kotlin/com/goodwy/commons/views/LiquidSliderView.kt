package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.components.LiquidSlider

class LiquidSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val composeView = ComposeView(context)

    private var rangeStart = 0f
    private var rangeEnd = 1f
    private var visibilityThreshold = 1f
    private var initialValue = 0f
    private var onValueChange: (Float) -> Unit = {}

    init {
        addView(
            composeView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        render()
    }

    fun configure(
        rangeStart: Float,
        rangeEnd: Float,
        initial: Float,
        visibilityThreshold: Float = 1f,
        onValueChange: (Float) -> Unit
    ) {
        this.rangeStart = rangeStart
        this.rangeEnd = rangeEnd
        this.visibilityThreshold = visibilityThreshold
        this.initialValue = initial.coerceIn(rangeStart, rangeEnd)
        this.onValueChange = onValueChange
        render()
    }

    private fun render() {
        composeView.setContent {
            SliderContent()
        }
    }

    @Composable
    private fun SliderContent() {
        val start = rangeStart
        val end = rangeEnd
        var current by remember(start, end, initialValue) {
            mutableFloatStateOf(initialValue.coerceIn(start, end))
        }

        val isLight = !isSystemInDarkTheme()
        val accentColor =
            if (isLight) Color(0xFF0088FF) else Color(0xFF0091FF)
        val backdropColor = Color(context.getProperBackgroundColor()).copy(alpha = 0.9f)

        val backdrop = rememberCanvasBackdrop {
            drawRect(backdropColor.copy(alpha = 0.25f))
        }

        LiquidSlider(
            value = { current },
            onValueChange = {
                val clamped = it.coerceIn(start, end)
                current = clamped
                onValueChange(clamped)
            },
            valueRange = start..end,
            visibilityThreshold = visibilityThreshold,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 15.dp)
        )
    }
}

