package com.goodwy.commons.views

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs

@Composable
fun MyLiquidBottomTabs(
    tabs: List<Pair<Painter, String>>,
    selectedTabIndex: MutableState<Int>,
    onTabSelected: (Int) -> Unit,
    backgroundColor: Int,
    modifier: Modifier = Modifier
) {
    val isLightTheme = !isSystemInDarkTheme()
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val iconColorFilter = ColorFilter.tint(contentColor)
    val backdropColor = Color(backgroundColor).copy(alpha = 0.9f)
    val backdrop = rememberCanvasBackdrop {
        drawRect(backdropColor)
    }
    val blurBackdrop = rememberCanvasBackdrop {
        drawRect(backdropColor.copy(alpha = 0.3f))
    }

    Box(modifier = modifier) {
        // Original LiquidBottomTabs (on top)
        LiquidBottomTabs(
            selectedTabIndex = { selectedTabIndex.value },
            onTabSelected = {
                selectedTabIndex.value = it
                onTabSelected(it)
            },
            backdrop = backdrop,
            tabsCount = tabs.size,
            backgroundColor = backdropColor,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 30.dp)
        ) {
            tabs.forEachIndexed { index, (icon, label) ->
                LiquidBottomTab({ selectedTabIndex.value = index }) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .paint(icon, colorFilter = iconColorFilter)
                    )
                    BasicText(text = label, style = TextStyle(contentColor, 12.sp))
                }
            }
        }
    }
}
