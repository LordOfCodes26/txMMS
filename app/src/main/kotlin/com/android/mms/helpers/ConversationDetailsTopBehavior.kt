package com.android.mms.helpers

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.behaviorule.arturdumchev.library.*
import com.android.mms.R
import com.android.mms.databinding.ActivityConversationDetailsBinding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import eightbitlab.com.blurview.BlurTarget

class ConversationDetailsTopBehavior(
    context: Context?,
    attrs: AttributeSet?
) : BehaviorByRules(context, attrs) {
    private lateinit var binding: ActivityConversationDetailsBinding

    override fun calcAppbarHeight(child: View): Int = with(child) {
        return height
    }

    private fun View.findRootBlurTarget(): View {
        var rootView: View = this
        while (rootView.parent != null && rootView.parent is View) {
            rootView = rootView.parent as View
            // The binding expects the BlurTarget root (mainBlurTarget), not the CoordinatorLayout
            if (rootView.id == R.id.mainBlurTarget || rootView is BlurTarget) {
                break
            }
        }
        return rootView
    }

    private fun View.ensureBindingInitialized() {
        if (!::binding.isInitialized) {
            val rootView = findRootBlurTarget()
            binding = ActivityConversationDetailsBinding.bind(rootView)
        }
    }

    override fun View.provideAppbar(): AppBarLayout {
        ensureBindingInitialized()
        return binding.conversationDetailsAppbar
    }
    
    override fun View.provideCollapsingToolbar(): CollapsingToolbarLayout {
        ensureBindingInitialized()
        return binding.collapsingToolbar
    }
    override fun canUpdateHeight(progress: Float): Boolean = progress >= GONE_VIEW_THRESHOLD

    override fun View.setUpViews(): List<RuledView> {
        ensureBindingInitialized()
        val height = height
        return listOf(
            RuledView(
                binding.topConversationDetails.root,
                BRuleYOffset(
                    min = -(height/4).toFloat(),
                    max = pixels(R.dimen.zero),
                    interpolator = LinearInterpolator()
                )
            ),
            RuledView(
                binding.topConversationDetails.conversationDetailsImage,
                BRuleXOffset(
                    min = 0f, max = pixels(R.dimen.image_right_margin),
                    interpolator = ReverseInterpolator(LinearInterpolator())
                ),
                BRuleYOffset(
                    min = pixels(R.dimen.zero), max = pixels(R.dimen.image_top_margin),
                    interpolator = ReverseInterpolator(LinearInterpolator())
                ),
                BRuleScale(min = 0.5f, max = 1f)
            ),
            RuledView(
                binding.topConversationDetails.conversationDetailsName,
                BRuleXOffset(
                    min = 0f, max = pixels(R.dimen.details_name_right_margin),
                    interpolator = ReverseInterpolator(LinearInterpolator())
                ),
                BRuleYOffset(
                    min = -pixels(R.dimen.details_name_top_margin), max = pixels(R.dimen.zero),
                    interpolator = LinearInterpolator()
                ),
                BRuleScale(min = 0.8f, max = 1f)
            )
        )
    }

//    private fun actionBarSize(context: Context?): Float {
//        val styledAttributes = context!!.theme?.obtainStyledAttributes(IntArray(1) { android.R.attr.actionBarSize })
//        val actionBarSize = styledAttributes?.getDimension(0, 0F)
//        styledAttributes?.recycle()
//        return actionBarSize ?: context.pixels(R.dimen.toolbar_height)
//    }
//
//    private fun getScreenWidth(): Int {
//        return Resources.getSystem().displayMetrics.widthPixels
//    }

    companion object {
        const val GONE_VIEW_THRESHOLD = 0.8f
    }
}
