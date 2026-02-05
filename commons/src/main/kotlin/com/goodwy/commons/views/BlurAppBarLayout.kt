package com.goodwy.commons.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.android.common.view.MImageButton
import com.android.common.view.MSearchView
import com.google.android.material.appbar.AppBarLayout
import com.goodwy.commons.R

/**
 * Reusable app bar with collapsing toolbar, title, search button and MSearchView.
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
    val searchBtn: MImageButton? by lazy { findViewById(R.id.iv_search_btn) }
    val searchView: MSearchView? by lazy { findViewById(R.id.m_search_view) }
    val searchContainer: FrameLayout? by lazy { findViewById(R.id.fl_search_container) }

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

    /** Registers with the internal MSearchView and updates expand/collapse and visibility; then notifies [listener]. */
    fun setOnSearchStateListener(listener: OnSearchStateListener?) {
        searchView?.setOnStateListener(object : MSearchView.OnSearchStateListener {
            override fun onState(state: Int) {
                when (state) {
                    MSearchView.SEARCH_START -> {
                        setExpanded(false)
                        searchContainer?.visibility = View.VISIBLE
                        searchBtn?.visibility = View.GONE
                        titleView?.visibility = View.GONE
                    }
                    MSearchView.SEARCH_END -> {
                        setExpanded(true)
                        titleView?.visibility = View.VISIBLE
                        searchBtn?.visibility = View.VISIBLE
                        searchContainer?.visibility = View.INVISIBLE
                    }
                }
                listener?.onState(state)
            }
            override fun onSearchTextChanged(s: String?) {
                listener?.onSearchTextChanged(s)
            }
        })
    }

    /** Call when user taps search button to show search and start search mode. */
    fun startSearch() {
        searchContainer?.visibility = View.VISIBLE
        searchView?.startSearch()
    }
}
