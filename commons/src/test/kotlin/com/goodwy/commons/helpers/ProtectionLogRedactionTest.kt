package com.goodwy.commons.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionLogRedactionTest {

    @Test
    fun phone_keepsOnlyLastFourDigits() {
        assertEquals("****2855", ProtectionLogRedaction.phone("1915882855"))
    }

    @Test
    fun phone_masksShortNumbersEntirely() {
        // A 4-digit number has no prefix to hide behind, so none of it survives.
        assertEquals("****", ProtectionLogRedaction.phone("8625"))
        assertEquals("****", ProtectionLogRedaction.phone("1"))
    }

    @Test
    fun phone_distinguishesEmptyFromMasked() {
        assertEquals("(empty)", ProtectionLogRedaction.phone(""))
        assertEquals("(empty)", ProtectionLogRedaction.phone("   "))
        assertEquals("(empty)", ProtectionLogRedaction.phone(null))
    }

    @Test
    fun phones_masksEachEntryAndKeepsCountVisible() {
        assertEquals(
            "[****2855, ****7653]",
            ProtectionLogRedaction.phones(arrayOf("1915882855", "1957007653")),
        )
    }

    @Test
    fun phones_nullArrayIsDistinctFromEmptyArray() {
        assertEquals("null", ProtectionLogRedaction.phones(null as Array<String>?))
        assertEquals("[]", ProtectionLogRedaction.phones(emptyArray<String>()))
    }

    @Test
    fun phones_acceptsCollections() {
        assertEquals("[****2855]", ProtectionLogRedaction.phones(listOf("1915882855")))
    }

    @Test
    fun space_reportsKindWithoutTheSlot() {
        // Secure box slots 0 and 7 are ciphers 2 and 9 (SECURE_BOX_CIPHER_OFFSET). Both must read
        // the same: which box the user opened is exactly what must not reach logcat.
        assertEquals("secure_box", ProtectionLogRedaction.space(2))
        assertEquals("secure_box", ProtectionLogRedaction.space(9))
        assertEquals("private_space", ProtectionLogRedaction.space(1))
        assertEquals("normal", ProtectionLogRedaction.space(0))
    }

    @Test
    fun space_acceptsThePinFormOfTheSameValue() {
        // pinForCipher is cipher.toString(), so the two spellings must agree.
        assertEquals("secure_box", ProtectionLogRedaction.space("2"))
        assertEquals("private_space", ProtectionLogRedaction.space("1"))
        assertEquals("normal", ProtectionLogRedaction.space("0"))
        assertEquals("normal", ProtectionLogRedaction.space(" 0 "))
    }

    @Test
    fun space_distinguishesNoSessionFromAnUnparseablePin() {
        assertEquals("none", ProtectionLogRedaction.space(null as String?))
        assertEquals("none", ProtectionLogRedaction.space(""))
        assertEquals("none", ProtectionLogRedaction.space(null as Int?))
        assertEquals("invalid", ProtectionLogRedaction.space("abc"))
        assertEquals("invalid", ProtectionLogRedaction.space(-1))
    }
}
