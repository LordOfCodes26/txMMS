package com.goodwy.commons.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.goodwy.commons.extensions.getSurfaceColor
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton

@SuppressLint("ResourceType")
class MyLiquidButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val composeView = ComposeView(context)
    private var clickListener: (() -> Unit)? = null

    private val iconResState = mutableStateOf<Int?>(null)
    private val iconTintState = mutableStateOf<Color?>(null)
    private val backgroundColorState = mutableStateOf<Color?>(null)
    private val surfaceColorState = mutableStateOf<Color?>(null)
    private val paddingState = mutableStateOf(0.dp)
    private val paddingStartState = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    private val paddingEndState = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    private val paddingTopState = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
    private val paddingBottomState = mutableStateOf<androidx.compose.ui.unit.Dp?>(null)

    init {
        // Allow the button to scale beyond its bounds without clipping
        clipChildren = false
        clipToPadding = false

        // Read padding from XML attributes
        attrs?.let {
            context.withStyledAttributes(
                it,
                intArrayOf(
                    android.R.attr.padding,
                    android.R.attr.paddingStart,
                    android.R.attr.paddingEnd,
                    android.R.attr.paddingTop,
                    android.R.attr.paddingBottom
                )
            ) {
                val padding = getDimensionPixelSize(0, 0)
                if (padding > 0) {
                    paddingState.value = padding.toFloat().dp
                }
                
                val paddingStart = getDimensionPixelSize(1, 0)
                if (paddingStart > 0) {
                    paddingStartState.value = paddingStart.toFloat().dp
                }
                
                val paddingEnd = getDimensionPixelSize(2, 0)
                if (paddingEnd > 0) {
                    paddingEndState.value = paddingEnd.toFloat().dp
                }
                
                val paddingTop = getDimensionPixelSize(3, 0)
                if (paddingTop > 0) {
                    paddingTopState.value = paddingTop.toFloat().dp
                }
                
                val paddingBottom = getDimensionPixelSize(4, 0)
                if (paddingBottom > 0) {
                    paddingBottomState.value = paddingBottom.toFloat().dp
                }
            }
        }

        addView(
            composeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )

        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )

        setupContent()
    }

    private fun setupContent() {
        composeView.setContent {
            val lifecycleOwner = findViewTreeLifecycleOwner()
            val isLightTheme = !isSystemInDarkTheme()

            val contentColor = if (isLightTheme) Color.Black else Color.White
            val iconColorFilter = ColorFilter.tint(contentColor)
            if (lifecycleOwner != null) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MaterialTheme {
                        val isLight = !isSystemInDarkTheme()
                        val defaultBackgroundColor = backgroundColorState.value
                            ?: Color(context.getSurfaceColor())

                        val backdrop = rememberCanvasBackdrop {
                            drawRect(Color(context.getSurfaceColor()).copy(alpha = 0.9f))
                        }

                        val iconRes = iconResState.value
                        val iconTint = iconTintState.value
                        val surfaceColor = surfaceColorState.value
                        val padding = paddingState.value
                        val paddingStart = paddingStartState.value
                        val paddingEnd = paddingEndState.value
                        val paddingTop = paddingTopState.value
                        val paddingBottom = paddingBottomState.value

                        // Build padding modifier for ComposeView
                        val composeViewPaddingModifier = when {
                            paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null -> {
                                Modifier.padding(
                                    start = paddingStart ?: 10.dp,
                                    end = paddingEnd ?: 10.dp,
                                    top = paddingTop ?: 10.dp,
                                    bottom = paddingBottom ?: 10.dp
                                )
                            }
                            padding > 0.dp -> Modifier.padding(padding)
                            else -> Modifier
                        }

                        Box( modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                            if (iconRes != null) {
                                LiquidButton(
                                    onClick = { clickListener?.invoke() },
                                    backdrop = backdrop,
                                    modifier = Modifier.size(56.dp),
                                    tint = iconTint ?: Color.Unspecified,
                                    surfaceColor = Color(context.getSurfaceColor()).copy(alpha = 0.4f),
                                ) {
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = contentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun setIcon(@androidx.annotation.DrawableRes iconRes: Int) {
        iconResState.value = iconRes
    }

    fun setIconTint(color: Color) {
        iconTintState.value = color
    }

    fun setIconTint(colorInt: Int) {
        iconTintState.value = Color(colorInt)
    }

    fun setBackgroundColor(color: Color) {
        backgroundColorState.value = color
    }

    override fun setBackgroundColor(colorInt: Int) {
        backgroundColorState.value = Color(colorInt)
    }

    fun setSurfaceColor(color: Color) {
        surfaceColorState.value = color
    }

    fun setSurfaceColor(colorInt: Int) {
        surfaceColorState.value = Color(colorInt)
    }

    fun setOnClickListener(listener: () -> Unit) {
        clickListener = listener
    }

    override fun setOnClickListener(listener: OnClickListener?) {
        clickListener = if (listener != null) { { listener.onClick(this@MyLiquidButton) } } else null
    }

    fun setPadding(padding: androidx.compose.ui.unit.Dp) {
        paddingState.value = padding
        // Clear individual padding values when setting uniform padding
        paddingStartState.value = null
        paddingEndState.value = null
        paddingTopState.value = null
        paddingBottomState.value = null
    }

    fun setPadding(paddingPx: Int) {
        paddingState.value = paddingPx.toFloat().dp
        // Clear individual padding values when setting uniform padding
        paddingStartState.value = null
        paddingEndState.value = null
        paddingTopState.value = null
        paddingBottomState.value = null
    }

    fun setPadding(
        start: androidx.compose.ui.unit.Dp? = null,
        end: androidx.compose.ui.unit.Dp? = null,
        top: androidx.compose.ui.unit.Dp? = null,
        bottom: androidx.compose.ui.unit.Dp? = null
    ) {
        paddingStartState.value = start
        paddingEndState.value = end
        paddingTopState.value = top
        paddingBottomState.value = bottom
        // Clear uniform padding when setting individual padding values
        if (start != null || end != null || top != null || bottom != null) {
            paddingState.value = 0.dp
        }
    }

    fun setPaddingPx(
        startPx: Int? = null,
        endPx: Int? = null,
        topPx: Int? = null,
        bottomPx: Int? = null
    ) {
        paddingStartState.value = startPx?.toFloat()?.dp
        paddingEndState.value = endPx?.toFloat()?.dp
        paddingTopState.value = topPx?.toFloat()?.dp
        paddingBottomState.value = bottomPx?.toFloat()?.dp
        // Clear uniform padding when setting individual padding values
        if (startPx != null || endPx != null || topPx != null || bottomPx != null) {
            paddingState.value = 0.dp
        }
    }
}

