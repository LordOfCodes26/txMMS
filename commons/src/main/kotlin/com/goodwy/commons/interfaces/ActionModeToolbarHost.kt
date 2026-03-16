package com.goodwy.commons.interfaces

import android.view.View
import com.goodwy.commons.views.CustomActionModeToolbar

/**
 * Optional interface for activities that host a [CustomActionModeToolbar] in their layout.
 * When the activity implements this, list adapters will show/hide the existing toolbar
 * instead of starting the system ActionMode and creating a new one.
 */
interface ActionModeToolbarHost {

    /** Returns the action mode toolbar that is already in the activity layout. */
    fun getActionModeToolbar(): CustomActionModeToolbar

    /** Shows the action mode toolbar (and typically hides the normal toolbar). */
    fun showActionModeToolbar()

    /** Hides the action mode toolbar (and typically shows the normal toolbar again). */
    fun hideActionModeToolbar()

    /** Optional view used as blur target for popup menus (e.g. mainBlurTarget). */
    fun getBlurTargetView(): View? = null
}
