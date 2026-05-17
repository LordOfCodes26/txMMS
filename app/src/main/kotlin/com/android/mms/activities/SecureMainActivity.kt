package com.android.mms.activities

import android.os.Bundle
import com.android.mms.R
import com.android.mms.extensions.applyLargeTitleOnly
import com.android.mms.extensions.config
import com.android.mms.extensions.setConversationPinScope
import com.goodwy.commons.extensions.toast

/**
 * PIN-scoped conversation list: [EXTRA_CIPHER_NUMBER] 1 = private space, greater than 1 = secure box.
 * Two-finger swipe down opens the secret-box app to switch into secure box (cipher > 1).
 */
class SecureMainActivity : MainActivity() {

    companion object {
        const val EXTRA_CIPHER_NUMBER = "cipher_number"
        private const val DEFAULT_CIPHER_NUMBER = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val cipherNumber = intent.getIntExtra(EXTRA_CIPHER_NUMBER, DEFAULT_CIPHER_NUMBER).coerceAtLeast(1)
        if (!setConversationPinScope(cipherNumber)) {
            super.onCreate(savedInstanceState)
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun handleTwoFingerSwipeDown() {
        launchSecretBoxForUnlock()
    }

    override fun refreshMenuItemsAndTitle() {
        val title = when {
            config.selectedConversationPin == 1 -> getString(R.string.private_space)
            config.selectedConversationPin > 1 -> getString(R.string.secure_box)
            else -> getString(R.string.private_space)
        }
        binding.mainMenu.applyLargeTitleOnly(title)
        binding.mainMenu.requireCustomToolbar().invalidateMenu()
    }

    override fun closeSecureBox() {
        setConversationPinScope(0)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            setConversationPinScope(0)
        }
        super.onDestroy()
    }
}
