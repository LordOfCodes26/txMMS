package com.goodwy.commons.providercache.filter

import org.junit.Assert.assertEquals
import org.junit.Test

class T9MapperTest {

    @Test
    fun john_mapsTo5646() {
        assertEquals("5646", T9Mapper.toT9Digits("john"))
    }

    @Test
    fun alice_mapsTo25423() {
        assertEquals("25423", T9Mapper.toT9Digits("alice"))
    }

    @Test
    fun mixedNamesWithSpacesAndHyphens() {
        assertEquals("564676484", T9Mapper.toT9Digits("John Smith"))
        assertEquals("56467285", T9Mapper.toT9Digits("John-Paul"))
        assertEquals("25423", T9Mapper.toT9Digits("A. Lice"))
    }

    @Test
    fun phoneDigitsExtractIgnoresFormatting() {
        assertEquals("15551234567", T9Mapper.extractDigits("+1 (555) 123-4567"))
        assertEquals("5551234567", T9Mapper.extractDigits("555 123 4567"))
        assertEquals("123456", T9Mapper.extractDigits("(12) 34-56"))
    }

    @Test
    fun queryDigitsMatchNameT9AndPhoneDigits() {
        val nameKey = T9Mapper.toT9Digits("john")
        val phoneDigits = T9Mapper.extractDigits("+1 (555) 564-6000")
        assertEquals("5646", nameKey)
        assertEquals(true, phoneDigits.contains("5646"))
    }
}
