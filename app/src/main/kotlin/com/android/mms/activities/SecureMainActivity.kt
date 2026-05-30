package com.android.mms.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import com.android.mms.R
import com.android.mms.extensions.config
import com.android.mms.extensions.setConversationPinScope
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.toast

/**
 * PIN-scoped conversation list: [EXTRA_CIPHER_NUMBER] 1 = private space, greater than 1 = secure box.
 * Two-finger swipe down opens the secret-box app to switch into secure box (cipher > 1).
 */
class SecureMainActivity : MainActivity() {

    companion object {
        const val EXTRA_CIPHER_NUMBER = "cipher_number"
        /** Set when [MainActivity.launchPrivateSpace] starts this screen; allows [finish] to reveal Main without restarting it. */
        const val EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY = "launched_from_main_activity"
        private const val DEFAULT_CIPHER_NUMBER = 1
    }

    private var launchedFromMainActivity = false

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedFromMainActivity = intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
        val cipherNumber = intent.getIntExtra(EXTRA_CIPHER_NUMBER, DEFAULT_CIPHER_NUMBER).coerceAtLeast(1)
        if (!setConversationPinScope(cipherNumber)) {
            super.onCreate(savedInstanceState)
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        setupSecureLockPlaceholder()
    }

    private fun setupSecureLockPlaceholder() {
        binding.noConversationsPlaceholder.setImageDrawable(
            AppCompatResources.getDrawable(this, com.android.common.R.drawable.ic_cmn_lock_fill),
        )
        binding.noConversationsPlaceholder2.beGone()
    }

    @SuppressLint("ResourceAsColor")
    override fun showOrHidePlaceholder(show: Boolean) {
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder2.beGone()
    }

    override fun handleTwoFingerSwipeDown() {
        launchSecretBoxForUnlock()
    }

    override fun mainOverflowMenuRes(): Int = R.menu.menu_main_secure

    override fun refreshMenuItems() {
        val title = when {
            config.selectedConversationPin == 1 -> getString(R.string.private_space)
            config.selectedConversationPin > 1 -> getString(R.string.secure_box)
            else -> getString(R.string.private_space)
        }
        binding.mainAppbar.setTitle(title)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
    }

    override fun closeSecureBox() {
        openMainInNormalMode()
    }

    /**
     * Leave PIN scope and return to the normal list.
     * When opened from [MainActivity] and it is still in the task, [finish] reveals it without
     * [Intent.FLAG_ACTIVITY_CLEAR_TOP] (avoids recreating Main and reloading conversations).
     */
    private fun openMainInNormalMode() {
        if (isFinishing) return
        setConversationPinScope(0)
        if (launchedFromMainActivity && !isTaskRoot) {
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            setConversationPinScope(0)
        }
        super.onDestroy()
    }
}
