package com.goodwy.commons.securebox

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.goodwy.commons.securebox.SecureBoxHelper.Companion.unlockSecureBox
import java.util.concurrent.Executor

/**
 * Helper class for biometric authentication to unlock Secure Box
 */
class BiometricUnlockHelper(private val activity: FragmentActivity) {

    private val executor: Executor = ContextCompat.getMainExecutor(activity)
    private val biometricPrompt: BiometricPrompt

    init {
        biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    unlockSecureBox()
                    onUnlockSuccess?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onUnlockError?.invoke(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onUnlockFailed?.invoke()
                }
            }
        )
    }

    var onUnlockSuccess: (() -> Unit)? = null
    var onUnlockError: ((Int, String) -> Unit)? = null
    var onUnlockFailed: (() -> Unit)? = null

    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Show biometric prompt to unlock secure box
     */
    fun showBiometricPrompt(title: String = "Unlock Secure Box", subtitle: String = "Use your fingerprint or face to unlock") {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        /**
         * Check if biometric is available on device
         */
        fun isBiometricAvailable(context: Context): Boolean {
            val biometricManager = BiometricManager.from(context)
            return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
                BiometricManager.BIOMETRIC_SUCCESS -> true
                else -> false
            }
        }
    }
}


