package com.goodwy.commons.helpers

/**
 * Redaction for protection-flow logging.
 *
 * Contact protection exists to conceal specific contacts and numbers. Logcat is readable over adb
 * and, on some builds, by any app holding `READ_LOGS` — so writing those records there defeats the
 * feature for exactly the users who asked for it. These logs are also not stripped in release:
 * there is no `-assumenosideeffects` rule for `android.util.Log`, and minified builds have been
 * observed emitting them.
 *
 * Masking matches the convention already used elsewhere in the codebase (`****` plus the last four
 * digits) so protection logs read the same as `RecentAvatarIdentity.maskedSeed` and
 * `CacheDebugCommands.maskPhone`.
 *
 * Raw contact ids are deliberately *not* redacted: they are the handle that makes these logs worth
 * keeping, and they identify nobody on their own.
 */
object ProtectionLogRedaction {

    /** `"15551234"` -> `"****1234"`. Blank stays distinguishable from a short number. */
    fun phone(value: String?): String = when {
        value.isNullOrBlank() -> "(empty)"
        value.length <= 4 -> "****"
        else -> "****${value.takeLast(4)}"
    }

    /** Masks each entry, keeping the count visible: `[****1234, ****9876]`. */
    fun phones(values: Array<String>?): String =
        values?.joinToString(prefix = "[", postfix = "]") { phone(it) } ?: "null"

    /** @see phones */
    fun phones(values: Collection<String>?): String =
        values?.joinToString(prefix = "[", postfix = "]") { phone(it) } ?: "null"

    /**
     * Protection PIN / cipher -> space kind: `"none"`, `"normal"`, `"private_space"`, `"secure_box"`.
     *
     * The PIN is not an independent secret. `SecureModeFilterSupport.pinForCipher` is
     * `cipher.toString()`, and the cipher is the Secret-box slot the user types on the dialpad
     * (`#NNNN#`) offset by two — so `pin=3`, `cipher=3` and `code=0001` are one secret written three
     * ways, and masking only the field literally spelled `pin` would be theatre. Typing that slot is
     * how a user reaches their hidden contacts and calls; publishing it to logcat tells a reader
     * which boxes exist and how to open them.
     *
     * The space kind is what the diagnostics actually use — "were we in a box at all, and which
     * kind" — and it does not identify *which* box.
     */
    fun space(pin: String?): String = when {
        pin.isNullOrBlank() -> "none"
        // Blank means no session; unparseable means a PIN that is not a cipher at all. Keeping them
        // apart is the whole diagnostic value left in this field.
        else -> pin.trim().toIntOrNull()?.let { space(it) } ?: "invalid"
    }

    /** @see space */
    fun space(cipher: Int?): String = when {
        cipher == null -> "none"
        cipher == NORMAL_CIPHER -> "normal"
        cipher == PRIVATE_SPACE_CIPHER -> "private_space"
        cipher > PRIVATE_SPACE_CIPHER -> "secure_box"
        else -> "invalid"
    }

    // Duplicated from SecureModeFilterSupport / DialerContactsTabSupport: commons cannot depend on
    // the app module. The scheme is documented in SecureModeFilterSupport's KDoc.
    private const val NORMAL_CIPHER = 0
    private const val PRIVATE_SPACE_CIPHER = 1
}
