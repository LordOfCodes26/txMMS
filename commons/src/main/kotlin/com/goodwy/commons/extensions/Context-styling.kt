package com.goodwy.commons.extensions

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import androidx.loader.content.CursorLoader
import com.goodwy.commons.R
import com.goodwy.commons.helpers.*
import com.goodwy.commons.helpers.MyContentProvider.GLOBAL_THEME_AUTO
import com.goodwy.commons.helpers.MyContentProvider.GLOBAL_THEME_SYSTEM
import com.goodwy.commons.models.GlobalConfig
import com.goodwy.commons.models.isGlobalThemingEnabled
import com.goodwy.commons.views.*
import kotlin.math.abs

fun Context.isDynamicTheme() = isSPlus() && baseConfig.isSystemThemeEnabled

fun Context.isSystemInDarkMode() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_YES != 0

fun Context.isAutoTheme() = baseConfig.isAutoThemeEnabled

fun Context.isLightTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_light_background_color, theme)

fun Context.isGrayTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_gray_background_color, theme)

fun Context.isDarkTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_dark_background_color, theme)

fun Context.isBlackTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_black_background_color, theme)

fun Context.getProperTextColor() = when {
    isDynamicTheme() -> resources.getColor(R.color.you_neutral_text_color, theme)
    else -> baseConfig.textColor
}

fun Context.getProperBackgroundColor() = when {
    isDynamicTheme() -> resources.getColor(R.color.you_background_color, theme)
    else -> baseConfig.backgroundColor
}

fun Context.getProperPrimaryColor() = when {
    isDynamicTheme() -> resources.getColor(R.color.you_primary_color, theme)
    else -> baseConfig.primaryColor
}

fun Context.getProperAccentColor() = when {
    !baseConfig.isUsingAccentColor -> getProperPrimaryColor()
//    isDynamicTheme() -> resources.getColor(R.color.you_primary_dark_color, theme)
    else -> baseConfig.accentColor
}

// get the color of the status bar with material activity, if the layout is scrolled down a bit
fun Context.getColoredMaterialStatusBarColor(): Int {
    return when {
        isDynamicTheme() -> resources.getColor(R.color.you_status_bar_color, theme).lightenColor(2)
        else -> getSurfaceColor().lightenColor(2)
    }
}

fun Context.getColoredMaterialSearchBarColor(): Int {
    return when {
        isDynamicTheme() -> {
            resources.getColor(R.color.you_status_bar_color, theme).darkenColor(if (isSystemInDarkMode()) 4 else 2)
        }
        else -> getSurfaceColor().darkenColor(4)
    }
}

fun Context.updateTextColors(viewGroup: ViewGroup) {
    val textColor = getProperTextColor()
    val backgroundColor = getProperBackgroundColor()
    val primaryColor = getProperPrimaryColor()
    val accentColor = getProperAccentColor()
    val textCursorColor = getProperTextCursorColor()

    val cnt = viewGroup.childCount
    (0 until cnt).map { viewGroup.getChildAt(it) }.forEach {
        when (it) {
            is MyTextView -> it.setColors(textColor, primaryColor, backgroundColor)
            is MyAppCompatSpinner -> it.setColors(textColor, primaryColor, backgroundColor)
            is MyCompatRadioButton -> it.setColors(textColor, accentColor, backgroundColor)
            is MyAppCompatCheckbox -> it.setColors(textColor, accentColor, backgroundColor)
            is MyMaterialSwitch -> it.setColors(textColor, accentColor, backgroundColor)
            is MyEditText -> it.setColors(textColor, primaryColor, textCursorColor)
            is MyAutoCompleteTextView -> it.setColors(textColor, primaryColor, textCursorColor)
            is MyFloatingActionButton -> it.setColors(textColor, primaryColor, backgroundColor)
            is MySeekBar -> it.setColors(textColor, primaryColor, backgroundColor.getContrastColor())
            is MyButton -> it.setColors(textColor, primaryColor, backgroundColor)
            is MyTextInputLayout -> it.setColors(textColor, primaryColor, backgroundColor)
            is ViewGroup -> updateTextColors(it)
        }
    }
}

fun Context.getTimePickerDialogTheme() = when {
    isDynamicTheme() -> if (isSystemInDarkMode()) {
        R.style.MyTimePickerMaterialTheme_Dark
    } else {
        R.style.MyDateTimePickerMaterialTheme
    }
    isBlackTheme() -> R.style.MyDialogTheme_Black
    isLightTheme() -> R.style.MyDialogTheme_Light
    isGrayTheme() -> R.style.MyDialogTheme_Gray
    baseConfig.backgroundColor.getContrastColor() == Color.WHITE -> R.style.MyDialogTheme_Dark
    else -> R.style.MyDialogTheme
}

fun Context.getDatePickerDialogTheme() = when {
    isDynamicTheme() -> R.style.MyDateTimePickerMaterialTheme
    isBlackTheme() -> R.style.MyDialogTheme_Black
    isLightTheme() -> R.style.MyDialogTheme_Light
    isGrayTheme() -> R.style.MyDialogTheme_Gray
    baseConfig.backgroundColor.getContrastColor() == Color.WHITE -> R.style.MyDialogTheme_Dark
    else -> R.style.MyDialogTheme
}

fun Context.getPopupMenuTheme(): Int {
    return if (isDynamicTheme()) {
        R.style.AppTheme_YouPopupMenuStyle
    } else if (isLightTheme() || isGrayTheme()) {
        R.style.AppTheme_PopupMenuLightStyle
    } else {
        R.style.AppTheme_PopupMenuDarkStyle
    }
}

fun Context.syncGlobalConfig(callback: (() -> Unit)? = null) {
    if (canAccessGlobalConfig()) {
        withGlobalConfig {
            if (it != null) {
                baseConfig.apply {
                    showCheckmarksOnSwitches = it.showCheckmarksOnSwitches
                    if (it.isGlobalThemingEnabled()) {
                        isGlobalThemeEnabled = true
                        isSystemThemeEnabled = it.themeType == GLOBAL_THEME_SYSTEM
                        isAutoThemeEnabled = it.themeType == GLOBAL_THEME_AUTO
                        textColor = it.textColor
                        backgroundColor = it.backgroundColor
                        primaryColor = it.primaryColor
                        accentColor = it.accentColor

                        if (baseConfig.appIconColor != it.appIconColor) {
                            baseConfig.appIconColor = it.appIconColor
                            checkAppIconColor()
                        }
                    }
                }
            }

            callback?.invoke()
        }
    } else {
        baseConfig.isGlobalThemeEnabled = false
        baseConfig.showCheckmarksOnSwitches = false
        callback?.invoke()
    }
}

fun Context.withGlobalConfig(callback: (globalConfig: GlobalConfig?) -> Unit) {
    if (!isPro()) {
        callback(null)
    } else {
        val cursorLoader = getMyContentProviderCursorLoader()
        ensureBackgroundThread {
            callback(getGlobalConfig(cursorLoader))
        }
    }
}

fun getGlobalConfig(cursorLoader: CursorLoader): GlobalConfig? {
    val cursor = cursorLoader.loadInBackground()
    cursor?.use {
        if (cursor.moveToFirst()) {
            try {
                return GlobalConfig(
                    themeType = cursor.getIntValue(MyContentProvider.COL_THEME_TYPE),
                    textColor = cursor.getIntValue(MyContentProvider.COL_TEXT_COLOR),
                    backgroundColor = cursor.getIntValue(MyContentProvider.COL_BACKGROUND_COLOR),
                    primaryColor = cursor.getIntValue(MyContentProvider.COL_PRIMARY_COLOR),
                    accentColor = cursor.getIntValue(MyContentProvider.COL_ACCENT_COLOR),
                    appIconColor = cursor.getIntValue(MyContentProvider.COL_APP_ICON_COLOR),
                    showCheckmarksOnSwitches = cursor.getIntValue(MyContentProvider.COL_SHOW_CHECKMARKS_ON_SWITCHES) != 0,
                    lastUpdatedTS = cursor.getIntValue(MyContentProvider.COL_LAST_UPDATED_TS)
                )
            } catch (_: Exception) {
            }
        }
    }
    return null
}

fun Context.checkAppIconColor() {
    val appId = baseConfig.appId
    if (appId.isNotEmpty() && baseConfig.lastIconColor != baseConfig.appIconColor) {
        getAppIconColors().forEachIndexed { index, color ->
            toggleAppIconColor(appId, index, color, false)
        }

        getAppIconColors().forEachIndexed { index, color ->
            if (baseConfig.appIconColor == color) {
                toggleAppIconColor(appId, index, color, true)
            }
        }
    }
}

fun Context.toggleAppIconColor(appId: String, colorIndex: Int, color: Int, enable: Boolean) {
    val className = "${appId.removeSuffix(".debug")}.activities.SplashActivity${appIconColorStrings[colorIndex]}"
    val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    try {
        packageManager.setComponentEnabledSetting(ComponentName(appId, className), state, PackageManager.DONT_KILL_APP)
        if (enable) {
            baseConfig.lastIconColor = color
        }
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Context.getAppIconColors() = resources.getIntArray(R.array.md_app_icon_colors).toCollection(ArrayList())

fun Context.getSurfaceColor(): Int {
    val baseColor = baseConfig.backgroundColor
    val bottomColor = when {
        isDynamicTheme() -> resources.getColor(R.color.you_surface_color, theme)
        isLightTheme() -> resources.getColor(R.color.bottom_tabs_light_background, theme)
        isGrayTheme() -> resources.getColor(R.color.bottom_tabs_gray_background, theme)
        isBlackTheme() -> resources.getColor(R.color.bottom_tabs_black_background, theme)
        isDarkTheme() -> resources.getColor(R.color.bottom_tabs_dark_background, theme)
        else -> baseColor.lightenColor(8)
    }
    return bottomColor
}

fun Context.getDialogBackgroundColor(): Int {
    return when {
        isDynamicTheme() -> resources.getColor(R.color.you_dialog_background_color, theme)
        else -> baseConfig.backgroundColor
    }
}

fun Context.getProperTextCursorColor() = when {
    isDynamicTheme() -> resources.getColor(R.color.you_primary_color, theme)
    else -> baseConfig.textCursorColor
}

/**
 * Gets the avatar color for a contact name using the same logic as avatar generation.
 * This respects the user's contact color list preference.
 */
fun Context.getAvatarColorForName(name: String): Int {
    if (!baseConfig.useColoredContacts) {
        return getProperBackgroundColor()
    }
    val letterBackgroundColors = getLetterBackgroundColors()
    return letterBackgroundColors[abs(name.hashCode()) % letterBackgroundColors.size].toInt()
}

/**
 * Creates a gradient drawable for activity background based on avatar color.
 * The gradient is similar to iOS 26 contacts background style with a glow effect.
 * 
 * @param avatarColor The base avatar color to create gradient from
 * @param blendWithSurface If true, blends the gradient with surface color for light themes (default: true)
 * @param glowIntensity The intensity of the glow effect (0.0 to 1.0, default: 0.4)
 * @return A LayerDrawable with linear gradient base and radial glow overlay
 */
fun Context.createAvatarGradientDrawable(
    avatarColor: Int, 
    blendWithSurface: Boolean = true,
    glowIntensity: Float = 0.4f
): android.graphics.drawable.Drawable {
    val (topColor, bottomColor) = avatarColor.createGradientColors()
    
    val finalTopColor: Int
    val finalBottomColor: Int
    
    if (blendWithSurface && (isLightTheme() || isGrayTheme()) && !isDynamicTheme()) {
        // For light theme, blend with surface color for subtlety
        val surfaceColor = getSurfaceColor()
        finalTopColor = topColor.blendColors(surfaceColor, 0.3f)
        finalBottomColor = bottomColor.blendColors(surfaceColor, 0.3f)
    } else {
        finalTopColor = topColor
        finalBottomColor = bottomColor
    }
    
    // Base linear gradient
    val baseGradient = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(finalTopColor, finalBottomColor)
    )
    
    // Create glow colors - brighter version of the avatar color with multiple stops for smooth glow
    val hsv = FloatArray(3)
    Color.colorToHSV(avatarColor, hsv)
    
    // Create multiple glow color stops for smooth radial gradient
    val glowHsv1 = hsv.clone()
    glowHsv1[2] = (glowHsv1[2] * 1.25f).coerceAtMost(0.95f) // Brightest at center
    val glowColor1 = Color.HSVToColor((255 * glowIntensity * 0.8f).toInt(), glowHsv1)
    
    val glowHsv2 = hsv.clone()
    glowHsv2[2] = (glowHsv2[2] * 1.15f).coerceAtMost(0.9f)
    val glowColor2 = Color.HSVToColor((255 * glowIntensity * 0.5f).toInt(), glowHsv2)
    
    val glowHsv3 = hsv.clone()
    glowHsv3[2] = (glowHsv3[2] * 1.05f).coerceAtMost(0.85f)
    val glowColor3 = Color.HSVToColor((255 * glowIntensity * 0.2f).toInt(), glowHsv3)
    
    // Radial gradient for glow effect - centered at top
    val glowGradient = android.graphics.drawable.GradientDrawable().apply {
        gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
        // Multiple color stops for smooth glow: bright center fading to transparent
        colors = intArrayOf(glowColor1, glowColor2, glowColor3, Color.TRANSPARENT)
        // Set gradient center to top center (0.5, 0.0 means center horizontally, top vertically)
        setGradientCenter(0.5f, 0.0f)
        // Set radius to create a large glow effect (in pixels, will scale with screen)
        gradientRadius = 600f
    }
    
    // Use LayerDrawable to combine base gradient with glow
    return android.graphics.drawable.LayerDrawable(arrayOf(baseGradient, glowGradient)).apply {
        // Set glow layer to be larger and positioned at top center
        setLayerGravity(1, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        setLayerSize(1, 2400, 1200) // Make glow layer larger than screen
        setLayerInset(1, -600, -400, -600, 0) // Position glow at top, extend beyond bounds for smooth fade
    }
}

fun Context.getProperBlurOverlayColor(): Int {
    val isDark = when {
        isDynamicTheme() -> isSystemInDarkMode()
        isAutoTheme() -> isSystemInDarkMode()
        isDarkTheme() || isBlackTheme() -> true
        isLightTheme() || isGrayTheme() -> false
        else -> baseConfig.backgroundColor.getContrastColor() == Color.WHITE
    }
    return if (isDark) {
        0xa3000000.toInt() // Black overlay with alpha for dark mode
    } else {
        0xa3ffffff.toInt() // White overlay with alpha for light mode
    }
}