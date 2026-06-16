package com.android.mms.activities

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.mms.R
import com.android.mms.extensions.config
import com.android.mms.extensions.setConversationPinScope
import com.goodwy.commons.extensions.getProperBackgroundColor
import com.goodwy.commons.extensions.isVisible
import com.goodwy.commons.extensions.toast

/**
 * PIN-scoped conversation list: [EXTRA_CIPHER_NUMBER] 1 = private space, greater than 1 = secure box.
 * Two-finger swipe down opens [com.android.common.dialogs.MSecretBoxDialog] to switch into secure box (cipher > 1).
 */
class SecureMainActivity : MainActivity() {

    companion object {
        const val EXTRA_CIPHER_NUMBER = "cipher_number"
        /** Set when [MainActivity.launchPrivateSpace] starts this screen; allows [finish] to reveal Main without restarting it. */
        const val EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY = "launched_from_main_activity"
        const val EXTRA_PRIVATE_ENTRY_AUTH_FIRST = "PRIVATE_ENTRY_AUTH_FIRST"
        private const val DEFAULT_CIPHER_NUMBER = 1
        private const val REQUEST_PRIVATE_ENTRY_AUTH = 1139
        private const val STATE_SECURE_MAIN_INIT = "SECURE_MAIN_INIT"
        private const val PRIVATE_SPACE_AUTH_ACTIVITY = "com.yft.settings.privacy.PrivateSpaceAuthActivity"
    }

    private var launchedFromMainActivity = false
    private var cipherNumber = DEFAULT_CIPHER_NUMBER
    private var secureMainContentInitialized = false
    private var deferInitialMessageLoad = false

    override fun shouldDeferInitialMessageLoad(): Boolean = deferInitialMessageLoad

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedFromMainActivity = intent.getBooleanExtra(EXTRA_LAUNCHED_FROM_MAIN_ACTIVITY, false)
        cipherNumber = intent.getIntExtra(EXTRA_CIPHER_NUMBER, DEFAULT_CIPHER_NUMBER).coerceAtLeast(1)

        val mainRestored = savedInstanceState?.getBoolean(STATE_SECURE_MAIN_INIT, false) == true
        val authFirst = intent.getBooleanExtra(EXTRA_PRIVATE_ENTRY_AUTH_FIRST, false)
            && cipherNumber == DEFAULT_CIPHER_NUMBER
            && !mainRestored
        deferInitialMessageLoad = authFirst

        if (authFirst) {
            super.onCreate(savedInstanceState)
            val holder = FrameLayout(this)
            holder.setBackgroundColor(getProperBackgroundColor())
            setContentView(holder)
            authPrivateSpace()
            return
        }
        initSecureMainContent()
        super.onCreate(savedInstanceState)
    }

    private fun authPrivateSpace() {
        val intent = Intent().setClassName("com.android.settings", PRIVATE_SPACE_AUTH_ACTIVITY)
        startActivityForResult(intent, REQUEST_PRIVATE_ENTRY_AUTH)
    }

    private fun initSecureMainContent() {
        if (!setConversationPinScope(cipherNumber)) {
            toast(com.goodwy.commons.R.string.unknown_error_occurred)
            finish()
            return
        }
        secureMainContentInitialized = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SECURE_MAIN_INIT, secureMainContentInitialized)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_PRIVATE_ENTRY_AUTH) {
            if (resultCode == RESULT_OK) {
                completePrivateEntryAuth()
            } else {
                finish()
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    /** Restores the conversation list UI after [PrivateSpaceAuthActivity] (placeholder replaced [binding.root]). */
    private fun completePrivateEntryAuth() {
        deferInitialMessageLoad = false
        initSecureMainContent()
        setContentView(binding.root)
        refreshMenuItems()
        visibilityConversationAddBtn()
        loadInitialMessagesIfEnabled()
    }

    override fun handleTwoFingerSwipeDown() {
        launchSecretBoxForUnlock()
    }

    override fun onSecretBoxCipherApplied(cipher: Int) {
        cipherNumber = cipher.coerceAtLeast(DEFAULT_CIPHER_NUMBER)
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

    private fun visibilityConversationAddBtn() {
        binding.conversationsFab.isVisible = false
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
