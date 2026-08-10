package com.goodwy.commons.helpers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard: call_log unlock must be paired with call_log lock.
 * (No Robolectric/mock ContentResolver in this module's unit-test classpath.)
 */
class CallLogProtectionHelperLockOwnershipTest {

    @Test
    fun lock_alsoCallsCallLogAuthorityLock() {
        val source = File(
            "src/main/kotlin/com/goodwy/commons/helpers/CallLogProtectionHelper.kt",
        ).takeIf { it.isFile }
            ?: File(
                "../commons/src/main/kotlin/com/goodwy/commons/helpers/CallLogProtectionHelper.kt",
            )
        assertTrue("CallLogProtectionHelper.kt not found at ${source.absolutePath}", source.isFile)
        val text = source.readText()
        assertTrue(
            "unlockAllWithPin must unlock CallLog.Calls.CONTENT_URI",
            text.contains("CallLog.Calls.CONTENT_URI") &&
                text.contains("\"unlock_all_with_pin\""),
        )
        assertTrue(
            "lock() must also lock CallLog.Calls.CONTENT_URI",
            Regex(
                """fun\s+lock\s*\([^)]*\)\s*\{[\s\S]*?CallLog\.Calls\.CONTENT_URI[\s\S]*?"lock"""",
            ).containsMatchIn(text),
        )
    }
}
