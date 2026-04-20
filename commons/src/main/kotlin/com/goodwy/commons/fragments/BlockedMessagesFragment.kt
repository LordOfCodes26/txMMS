package com.goodwy.commons.fragments

import com.goodwy.commons.R
import android.view.View
import androidx.fragment.app.Fragment
import com.android.common.view.MRippleToolBar
import eightbitlab.com.blurview.BlurTarget

class BlockedMessagesFragment : Fragment(R.layout.fragment_blocked_messages) {
    fun bindRippleToolbarIfNeeded(ripple: MRippleToolBar, blurTarget: BlurTarget) {
        ripple.visibility = View.GONE
    }

    fun tryStartSelectionActionMode(): Boolean = false

    fun finishSelectionActionModeIfActive(): Boolean = false
}
