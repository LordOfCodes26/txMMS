package com.goodwy.commons.extensions

import com.android.common.view.MVSideFrame
import eightbitlab.com.blurview.BlurTarget

/**
 * Binds [blurTarget] for screens with scrolling lists under the glass strip.
 *
 * [MVSideFrame.bindBlurTarget] always attaches a BlurTarget [android.view.ViewTreeObserver.OnPreDrawListener]
 * that marks the frame dirty and [android.view.View.postInvalidateOnAnimation] on every pre-draw.
 * While a RecyclerView scrolls inside the target, that re-runs the blur every frame and janks the list.
 *
 * [MVSideFrame.setAutoUpdate] alone does not stop that listener. This helper disables auto-update and
 * detaches the listener so the strip keeps a cached blur until [MVSideFrame.update] is called
 * (e.g. app-bar offset or scroll idle).
 */
fun MVSideFrame.bindBlurTargetFrozen(blurTarget: BlurTarget) {
    bindBlurTarget(blurTarget)
    setAutoUpdate(false)
    detachBlurTargetPreDrawListenerCompat()
    update()
}

private fun MVSideFrame.detachBlurTargetPreDrawListenerCompat() {
    runCatching {
        val method = MVSideFrame::class.java.getDeclaredMethod("detachBlurTargetPreDrawListener")
        method.isAccessible = true
        method.invoke(this)
    }
}
