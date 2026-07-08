package com.android.mms.emoji

import android.text.Spannable
import android.text.util.Linkify
import android.widget.EditText
import com.chutils.CHGlobal
import com.chutils.emo.views.EmoTextView

/**
 * CH350 emoticon encode/decode helpers (aligned with SMS350 [EmoTextView] / [CHGlobal.convertSpan2Text]).
 */
object Ch350EmojiText {
    fun ensureResources(context: android.content.Context): Boolean =
        Ch350EmojiBootstrap.ensureInitialized(context)

    fun EditText.getCh350EncodedText(): String {
        val editable = text ?: return ""
        if (editable.isEmpty()) return ""
        return CHGlobal.convertSpan2Text(editable, false).trim()
    }

    fun EditText.setCh350ComposeText(rawText: String) {
        if (rawText.isEmpty()) {
            setText("")
            return
        }
        if (!ensureResources(context)) {
            setText(rawText)
            return
        }
        setText(EmoTextView.getEditable(context, rawText, EmoTextView.EMO_IN_SMS_EDIT, true))
    }

    fun EmoTextView.bindCh350MessageBody(rawText: String, linkify: Boolean = true) {
        if (rawText.isEmpty()) {
            text = ""
            return
        }
        if (!ensureResources(context)) {
            text = rawText
            return
        }
        setEmoText(rawText, EmoTextView.EMO_IN_MESSAGE)
        if (linkify) {
            val current = text
            if (current is Spannable) {
                Linkify.addLinks(current, Linkify.ALL)
            }
        }
    }

    fun EmoTextView.bindCh350Snippet(rawText: String) {
        if (rawText.isEmpty()) {
            text = ""
            return
        }
        if (!ensureResources(context)) {
            text = rawText
            return
        }
        setEmoText(rawText, EmoTextView.EMO_IN_MESSAGE)
    }
}
