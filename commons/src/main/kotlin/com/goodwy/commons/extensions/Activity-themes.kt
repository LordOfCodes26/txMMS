package com.goodwy.commons.extensions

import android.app.Activity
import com.goodwy.commons.R

fun Activity.getThemeId() = when {
    isDynamicTheme() ->
        if (isNightDisplay()) R.style.AppTheme_Base_System else R.style.AppTheme_Base_System_Light
    else ->
        if (isNightDisplay()) R.style.AppTheme_Blue_600_core else R.style.AppTheme_Blue_600_core_Light
}
