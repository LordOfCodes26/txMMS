package com.goodwy.commons.extensions

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.loader.content.CursorLoader
import com.goodwy.commons.R
import com.goodwy.commons.helpers.*
import com.goodwy.commons.helpers.MyContentProvider.GLOBAL_THEME_AUTO
import com.goodwy.commons.helpers.MyContentProvider.GLOBAL_THEME_SYSTEM
import com.goodwy.commons.models.GlobalConfig
import com.goodwy.commons.models.isGlobalThemingEnabled
import com.goodwy.commons.views.*
import kotlin.math.abs

/**
 * Explicit IDs for light/dark avatar rings. Do not use [Resources.getIdentifier] here: it often returns 0
 * for library drawables at runtime, which incorrectly fell back to legacy [R.drawable.contact_avatar_bg_1]
 * (dark-looking browns) even when light mode was requested.
 */
private val CONTACT_AVATAR_BG_LIGHT_RES_IDS = intArrayOf(
    R.drawable.contact_avatar_bg_light_1, R.drawable.contact_avatar_bg_light_2, R.drawable.contact_avatar_bg_light_3,
    R.drawable.contact_avatar_bg_light_4, R.drawable.contact_avatar_bg_light_5, R.drawable.contact_avatar_bg_light_6,
    R.drawable.contact_avatar_bg_light_7, R.drawable.contact_avatar_bg_light_8, R.drawable.contact_avatar_bg_light_9,
    R.drawable.contact_avatar_bg_light_10, R.drawable.contact_avatar_bg_light_11, R.drawable.contact_avatar_bg_light_12,
    R.drawable.contact_avatar_bg_light_13, R.drawable.contact_avatar_bg_light_14, R.drawable.contact_avatar_bg_light_15,
    R.drawable.contact_avatar_bg_light_16, R.drawable.contact_avatar_bg_light_17, R.drawable.contact_avatar_bg_light_18,
    R.drawable.contact_avatar_bg_light_19, R.drawable.contact_avatar_bg_light_20, R.drawable.contact_avatar_bg_light_21,
    R.drawable.contact_avatar_bg_light_22, R.drawable.contact_avatar_bg_light_23, R.drawable.contact_avatar_bg_light_24,
    R.drawable.contact_avatar_bg_light_25, R.drawable.contact_avatar_bg_light_26, R.drawable.contact_avatar_bg_light_27
)

private val CONTACT_AVATAR_BG_DARK_RES_IDS = intArrayOf(
    R.drawable.contact_avatar_bg_dark_1, R.drawable.contact_avatar_bg_dark_2, R.drawable.contact_avatar_bg_dark_3,
    R.drawable.contact_avatar_bg_dark_4, R.drawable.contact_avatar_bg_dark_5, R.drawable.contact_avatar_bg_dark_6,
    R.drawable.contact_avatar_bg_dark_7, R.drawable.contact_avatar_bg_dark_8, R.drawable.contact_avatar_bg_dark_9,
    R.drawable.contact_avatar_bg_dark_10, R.drawable.contact_avatar_bg_dark_11, R.drawable.contact_avatar_bg_dark_12,
    R.drawable.contact_avatar_bg_dark_13, R.drawable.contact_avatar_bg_dark_14, R.drawable.contact_avatar_bg_dark_15,
    R.drawable.contact_avatar_bg_dark_16, R.drawable.contact_avatar_bg_dark_17, R.drawable.contact_avatar_bg_dark_18,
    R.drawable.contact_avatar_bg_dark_19, R.drawable.contact_avatar_bg_dark_20, R.drawable.contact_avatar_bg_dark_21,
    R.drawable.contact_avatar_bg_dark_22, R.drawable.contact_avatar_bg_dark_23, R.drawable.contact_avatar_bg_dark_24,
    R.drawable.contact_avatar_bg_dark_25, R.drawable.contact_avatar_bg_dark_26, R.drawable.contact_avatar_bg_dark_27
)

private fun contactAvatarBgResId(drawableIndex: Int, isDarkMode: Boolean): Int {
    val idx = ((drawableIndex % 27) + 27) % 27
    return if (isDarkMode) CONTACT_AVATAR_BG_DARK_RES_IDS[idx] else CONTACT_AVATAR_BG_LIGHT_RES_IDS[idx]
}

/** Same ordering as [CONTACT_AVATAR_BG_*] / contact_background: index 0 → color_1, …, index 26 → color_27 */
private val CONTACT_CARD_OVERLAY_COLOR_RES_IDS = intArrayOf(
    R.color.contact_card_base_color_1, R.color.contact_card_base_color_2, R.color.contact_card_base_color_3,
    R.color.contact_card_base_color_4, R.color.contact_card_base_color_5, R.color.contact_card_base_color_6,
    R.color.contact_card_base_color_7, R.color.contact_card_base_color_8, R.color.contact_card_base_color_9,
    R.color.contact_card_base_color_10, R.color.contact_card_base_color_11, R.color.contact_card_base_color_12,
    R.color.contact_card_base_color_13, R.color.contact_card_base_color_14, R.color.contact_card_base_color_15,
    R.color.contact_card_base_color_16, R.color.contact_card_base_color_17, R.color.contact_card_base_color_18,
    R.color.contact_card_base_color_19, R.color.contact_card_base_color_20, R.color.contact_card_base_color_21,
    R.color.contact_card_base_color_22, R.color.contact_card_base_color_23, R.color.contact_card_base_color_24,
    R.color.contact_card_base_color_25, R.color.contact_card_base_color_26, R.color.contact_card_base_color_27
)

fun Context.isDynamicTheme() = isSPlus() && baseConfig.isSystemThemeEnabled

fun Context.isSystemInDarkMode() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Effective light/dark for UI: respects Settings display mode (follow system / light / dark). */
fun Context.isNightDisplay(): Boolean = when (baseConfig.appNightMode) {
    AppCompatDelegate.MODE_NIGHT_YES -> true
    AppCompatDelegate.MODE_NIGHT_NO -> false
    else -> isSystemInDarkMode()
}

fun Context.isAutoTheme() = baseConfig.isAutoThemeEnabled

fun Context.isLightTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_light_background_color, theme)

fun Context.isGrayTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_gray_background_color, theme)

fun Context.isDarkTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_dark_background_color, theme)

fun Context.isBlackTheme() = baseConfig.backgroundColor == resources.getColor(R.color.theme_black_background_color, theme)

fun Context.getSoldTextColor() = when {
    isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_main_letter, theme)
    else -> baseConfig.textColor
}

fun Context.getProperTextColor() = when {
    isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_main_letter, theme)
    else -> baseConfig.textColor
}
fun Context.getGrayTextColor() = when {
    isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_gray, theme)
    else -> baseConfig.textColor
}

fun Context.getProperBackgroundColor() = when {
    isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_common_bg, theme)
    else -> baseConfig.backgroundColor
}

fun Context.getProperPrimaryColor() = when {
    isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_main_blue, theme)
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
            resources.getColor(R.color.you_status_bar_color, theme).darkenColor(if (isNightDisplay()) 4 else 2)
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
            is MyAppCompatSpinner -> it.setColors(textColor, primaryColor, backgroundColor)
//            is MyCompatRadioButton -> it.setColors(textColor, accentColor, backgroundColor)
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
    isDynamicTheme() -> if (isNightDisplay()) {
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
    isDynamicTheme() -> com.android.common.R.style.MDialogStyleMinWidth
    isBlackTheme() -> R.style.MyDialogTheme_Black
    isLightTheme() -> R.style.MyDialogTheme_Light
    isGrayTheme() -> R.style.MyDialogTheme_Gray
    baseConfig.backgroundColor.getContrastColor() == Color.WHITE -> R.style.MyDialogTheme_Dark
    else -> R.style.MyDialogTheme
}

fun Context.getDialogTheme() = when {
    isDynamicTheme() -> com.android.common.R.style.MDialogStyleMinWidth
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
        if (!hasAppIconAliases(appId)) {
            // No alias-based icon variants in this app/flavor, keep state in sync and skip toggling.
            baseConfig.lastIconColor = baseConfig.appIconColor
            return
        }

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

fun Context.hasAppIconAliases(appId: String): Boolean {
    val className = "${appId.removeSuffix(".debug")}.activities.MainActivity${appIconColorStrings.first()}"
    return try {
        packageManager.getActivityInfo(ComponentName(appId, className), 0)
        true
    } catch (_: Exception) {
        false
    }
}

fun Context.toggleAppIconColor(appId: String, colorIndex: Int, color: Int, enable: Boolean) {
    val className = "${appId.removeSuffix(".debug")}.activities.MainActivity${appIconColorStrings[colorIndex]}"
    val state = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    try {
        packageManager.setComponentEnabledSetting(ComponentName(appId, className), state, PackageManager.DONT_KILL_APP)
        if (enable) {
            baseConfig.lastIconColor = color
        }
    } catch (e: Exception) {
        // Some apps/flavors do not define launcher aliases for icon variants.
        // Treat missing components as a no-op instead of surfacing a startup error toast.
        if (enable) {
            baseConfig.lastIconColor = color
        }
    }
}

fun Context.getAppIconColors() = resources.getIntArray(R.array.md_app_icon_colors).toCollection(ArrayList())

fun Context.getSurfaceColor(): Int {
    val baseColor = baseConfig.backgroundColor
    val bottomColor = when {
        isDynamicTheme() -> resources.getColor(com.android.common.R.color.tx_common_bg, theme)
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
    isDynamicTheme() -> resources.getColor(R.color.default_primary_color, theme)
    else -> baseConfig.textCursorColor
}

/** Cursor color for dedicated search fields (toolbar search, pill search, SearchView [search_src_text]). */
fun Context.getSearchFieldCursorColor(): Int =
    resources.getColor(com.android.common.R.color.tx_main_blue, theme)

/**
 * Gets the avatar color for a contact name using the same logic as avatar generation.
 * This respects the user's contact color list preference.
 */
fun Context.getAvatarColorForName(name: String): Int {
    if (!baseConfig.useColoredContacts) {
        return getProperBackgroundColor()
    }
    val letterBackgroundColors = getLetterBackgroundColors()
    return letterBackgroundColors[abs(name.toAvatarColorSeed().hashCode()) % letterBackgroundColors.size].toInt()
}

fun Context.getAvatarColorIndexForName(name: String): Int {
    if (!baseConfig.useColoredContacts) {
        return -1
    }
    val letterBackgroundColors = getLetterBackgroundColors()

    return abs(name.toAvatarColorSeed().hashCode()) % letterBackgroundColors.size
}

/**
 * Drawable index (0–26) for avatar ring backgrounds from [name]'s hash.
 * Phone-like [name] values are normalized first so formatting does not change the index.
 *
 * @return Index in 0..26 if colored contacts are enabled, -1 otherwise
 */
fun Context.getAvatarDrawableIndexForName(name: String): Int {
    if (!baseConfig.useColoredContacts) {
        return -1
    }
    return abs(name.toAvatarColorSeed().hashCode()) % 27
}

/**
 * Solid overlay tint for contact CardViews, keyed the same way as [getAvatarDrawableIndexForName]
 * (same seed and modulo 27 as contact_background / avatar rings).
 *
 * @return Theme-resolved color from [contact_card_base_color_1]…[contact_card_base_color_27], or
 * [getProperBackgroundColor] when colored contacts are disabled.
 */
fun Context.getContactCardOverlayColorForName(name: String): Int {
    val index = getAvatarDrawableIndexForName(name)
    return if (index < 0) {
        getProperBackgroundColor()
    } else {
        resources.getColor(CONTACT_CARD_OVERLAY_COLOR_RES_IDS[index], theme)
    }
}

/**
 * Resolved overlay color for a contact-background drawable index in `0..26` (same as [createContactGradientDrawable]).
 */
fun Context.getContactCardOverlayColorForDrawableIndex(drawableIndex: Int): Int {
    // drawableIndex 0 -> contact_card_base_color_1, drawableIndex 1 -> contact_card_base_color_2, etc.
    val resourceNumber = ((drawableIndex % 27) + 27) % 27
    return resources.getColor(CONTACT_CARD_OVERLAY_COLOR_RES_IDS[resourceNumber], theme)
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
@SuppressLint("UseCompatLoadingForDrawables")
fun Context.createAvatarGradientDrawable(
    drawableIndex: Int,
    isDarkMode: Boolean = isNightDisplay(),
    blendWithSurface: Boolean = true,
    glowIntensity: Float = 0.4f
): Drawable {
//    val (topColor, bottomColor) = avatarColor.createGradientColors()

    Log.d("CHero-createAvatarDrawable", "idx=$drawableIndex dark=$isDarkMode")

    val resId = contactAvatarBgResId(drawableIndex, isDarkMode)
    return getDrawable(resId) ?: getDrawable(R.drawable.contact_avatar_bg_1)!!
}
fun Context.createContactGradientDrawable(
    drawableIndex: Int,
    blendWithSurface: Boolean = true,
    glowIntensity: Float = 0.4f
): Drawable {
//    val (topColor, bottomColor) = avatarColor.createGradientColors()

    Log.d("CHero-createAvatarDrawable", drawableIndex.toString())

    // Convert drawableIndex to resource number (1-27)
    // drawableIndex 0 -> contact_avatar_bg_1, drawableIndex 1 -> contact_avatar_bg_2, etc.
    val resourceNumber = (drawableIndex % 27) + 1
    val resourceName = "contact_background_$resourceNumber"

    val resourceId = resources.getIdentifier(resourceName, "drawable", packageName)

    return if (resourceId != 0) {
        getDrawable(resourceId)!!
    } else {
        // Fallback to contact_avatar_bg_1 if resource not found
        getDrawable(R.drawable.contact_background_1)!!
    }
}

/** Same corner radius as contact detail card drawables when [tx_cardview_corner_radius] exists in the app theme. */
fun Context.contactDetailCardCornerRadiusPx(): Float {
    val txId = resources.getIdentifier("tx_cardview_corner_radius", "dimen", packageName)
    if (txId != 0) {
        return resources.getDimension(txId)
    }
    val fallbackId = resources.getIdentifier("contact_detail_card_corner_radius", "dimen", packageName)
    return if (fallbackId != 0) {
        resources.getDimension(fallbackId)
    } else {
        resources.getDimension(R.dimen.normal_margin)
    }
}

/**
 * Rounded rectangle with alpha matching app [contact_detail_card_bg] color, filled with [baseColor].
 */
fun Context.createContactDetailCardGradientDrawable(baseColor: Int): GradientDrawable {
    val colorId = resources.getIdentifier("contact_detail_card_bg", "color", packageName)
    val cardAlpha = if (colorId != 0) {
        Color.alpha(ContextCompat.getColor(this, colorId))
    } else {
        0x30
    }
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = contactDetailCardCornerRadiusPx()
        setColor(ColorUtils.setAlphaComponent(baseColor, 0xFF))
    }
}

fun applyContactDetailCardBackgrounds(views: Iterable<View>, base: Drawable) {
    views.forEach { view ->
        val target = contactDetailCardBackgroundTarget(view)
        target.background = base.constantState?.newDrawable()?.mutate() ?: base
    }
}

/** When the card root is a [CardView], tint the inner surface (first child). */
private fun contactDetailCardBackgroundTarget(view: View): View {
    if (view is CardView && view.childCount > 0) {
        return view.getChildAt(0)
    }
    return view
}

/** Default [contact_detail_card_bg] from the host app (used when resetting cards away from gradient tint). */
fun Context.getContactDetailCardBgDrawable(): Drawable? {
    val id = resources.getIdentifier("contact_detail_card_bg", "drawable", packageName)
    return if (id != 0) ContextCompat.getDrawable(this, id) else null
}

//fun Context.getAvatarDrawablw(
//    avatarColor: Int,
//): Drawable {
//    var avatarDrawable: Drawable
//    avatarDrawable =
//}

fun Context.getProperBlurOverlayColor(): Int {
    val isDark = when {
        isDynamicTheme() -> isNightDisplay()
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
