package com.android.mms.emoji

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.android.mms.R
import com.chutils.emo.views.EmoGridViewPage

/**
 * CH350 compose emoji pane ([ChatPaneEmoji](com.chonha.totaldial.ui.customviews.res.ChatPaneEmoji)), backed by [EmoGridViewPage].
 */
class ChatPaneEmoji(
    context: Context,
    private val targetEditText: EditText,
) : LinearLayout(context) {

    private val emoGrid: EmoGridViewPage = EmoGridViewPage(context)

    init {
        orientation = VERTICAL
        id = R.id.layout_emoji_root
        addView(
            emoGrid,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        emoGrid.initView(targetEditText)
    }

    fun setBackspaceRepeatListener(listener: View.OnTouchListener) {
        emoGrid.findViewById<View>(R.id.emoji_lay_end)?.setOnTouchListener(listener)
    }

    fun showFirstTab() {
        emoGrid.setEmogiTabIndex(0)
    }

    fun canClose(): Boolean = true

    fun deleteAtCaret() {
        emoGrid.deleteEditable(targetEditText)
    }
}
