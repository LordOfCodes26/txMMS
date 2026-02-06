package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.android.common.view.MSearchView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.goodwy.commons.R

/**
 * Reusable app bar with collapsing toolbar, title, and CustomToolbar (search/menu).
 * Use [setupOffsetListener] to scale title on scroll; use [setOnSearchStateListener] for search UI callbacks.
 */
class BlurAppBarLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppBarLayout(context, attrs, defStyleAttr) {

    interface OnSearchStateListener {
        fun onState(state: Int)
        fun onSearchTextChanged(s: String?)
    }

    val titleView: TextView? by lazy { findViewById(R.id.tv_title) }
    val toolbar: CustomToolbar? by lazy { findViewById(R.id.toolbar) as? CustomToolbar }
    val collapsingToolbarLayout: CollapsingToolbarLayout? by lazy { findViewById(R.id.collapsing_toolbar) }

    private var offsetListener: ((verticalOffset: Int, height: Int) -> Unit)? = null

    init {
        elevation = 0f
        ViewCompat.setElevation(this, 0f)
        stateListAnimator = null
        isLiftOnScroll = false
        isLifted = false

        LayoutInflater.from(context).inflate(R.layout.blur_app_bar_layout, this, true)

        if (attrs != null) {
            context.theme.obtainStyledAttributes(attrs, R.styleable.BlurAppBarLayout, defStyleAttr, 0).use { a ->
                val bgResId = a.getResourceId(R.styleable.BlurAppBarLayout_appBarBackground, 0)
                if (bgResId != 0) {
                    setBackgroundResource(bgResId)
                }
            }
        }

        addOnOffsetChangedListener { _, verticalOffset ->
            offsetListener?.invoke(verticalOffset, height)
        }

        // CollapsingToolbarLayout only sets minimum height when it finds a recognized Toolbar
        // (androidx.appcompat.widget.Toolbar). CustomToolbar is not recognized, so set min height
        // so the layout does not collapse below the pinned CustomToolbar (layout_collapseMode="pin").
        post {
            val collapsing = collapsingToolbarLayout ?: return@post
            val tb = toolbar ?: return@post
            val lp = tb.layoutParams as? ViewGroup.MarginLayoutParams
            val topMargin = lp?.topMargin ?: 0
            val bottomMargin = lp?.bottomMargin ?: 0
            val toolbarHeight = tb.height.takeIf { it > 0 } ?: tb.measuredHeight
            val minHeight = (toolbarHeight + topMargin + bottomMargin).coerceAtLeast(0)
            if (minHeight > 0) {
                collapsing.minimumHeight = minHeight
            } else {
                // Before first layout: run again after layout so toolbar has height
                tb.post {
                    val margins = (tb.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0
                    val h = tb.height + margins
                    if (h > 0) collapsing.minimumHeight = h
                }
            }
        }
    }

    /** Call from activity to scale title based on scroll offset (e.g. titleView.scaleX/Y). */
    fun setupOffsetListener(callback: (verticalOffset: Int, appBarHeight: Int) -> Unit) {
        offsetListener = callback
    }

    fun setTitle(title: CharSequence?) {
        titleView?.text = title
    }

    fun setTitle(titleResId: Int) {
        titleView?.setText(titleResId)
    }

    /** Registers with the CustomToolbar and updates expand/collapse and visibility; then notifies [listener]. */
    fun setOnSearchStateListener(listener: OnSearchStateListener?) {
        toolbar?.let { tb ->
            tb.setOnSearchExpandListener {
                setExpanded(false)
                titleView?.visibility = View.GONE
                listener?.onState(MSearchView.SEARCH_START)
            }
            tb.setOnSearchBackClickListener {
                setExpanded(true)
                titleView?.visibility = View.VISIBLE
                listener?.onState(MSearchView.SEARCH_END)
            }
            tb.setOnSearchTextChangedListener { text ->
                listener?.onSearchTextChanged(text)
            }
        }
    }

    /** Call when user taps search button to show search and start search mode. */
    fun startSearch() {
        toolbar?.expandSearch()
        titleView?.visibility = View.GONE
    }
}
