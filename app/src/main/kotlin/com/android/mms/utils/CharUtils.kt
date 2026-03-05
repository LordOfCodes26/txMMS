package com.android.mms.utils

import android.text.TextUtils

object CharUtils {
    const val defaultSection = "#"

    private val sectionIndexItemsOrdered = arrayOf(
        "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ",
        "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "ㅇ", defaultSection
    )

    private val sectionIndexItems = arrayOf(
        "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ",
        "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ", "ㅇ", defaultSection
    )

    fun getSectionIndexItems(): Array<String> {
        return sectionIndexItems
    }

    fun getSectionIndexItemsOrdered(): Array<String> {
        return sectionIndexItemsOrdered
    }

    fun getFirstConsonant(name: String?): String {
        if (name == null || name.isEmpty()) return defaultSection

        for (i in sectionIndexItems.indices) {
            if (TextUtils.equals(sectionIndexItems[i], name)) {
                return name
            }
        }

        val srcCh = name[0]
        val srcChCode = srcCh.code
        val consonantCount = 19
        val vowelCount = 21
        val bachimCount = 28

        val destIndex = (srcChCode - 0xac00) / (vowelCount * bachimCount)
        if (srcChCode < 0xac00 || destIndex < 0 || destIndex >= consonantCount) {
            return defaultSection
        }
        return sectionIndexItems[destIndex]
    }

    val KOREAN_JAUMS = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    fun toJaumUnichars(word: String): String {
        val jamoString = StringBuilder()
        for (i in word.indices) {
            val character = word[i]

            if (isKoreanCharacter(character)) {
                val jaum = KOREAN_JAUMS[(character.code - 0xAC00) / 0x24C]
                jamoString.append(jaum)
            } else if (isKoreanJamo(character)) {
                jamoString.append(character)
            } else {
                jamoString.append(character)
            }
        }

        return jamoString.toString()
    }

    fun toJaumMoumUnichars(word: String): String {
        val jamoString = StringBuilder()
        for (i in word.indices) {
            val character = word[i]

            if (isKoreanCharacter(character)) {
                val jaumMoum = (0xAC00 + (character.code - 0xAC00) / 28 * 28).toChar()
                jamoString.append(jaumMoum)
            } else {
                jamoString.append(character)
            }
        }

        return jamoString.toString()
    }

    fun isKoreanJamo(character: Char): Boolean {
        return character.code in 0x3131..0x3163
    }

    fun isKoreanCharacter(character: Char): Boolean {
        return character.code in 0xAC00..0xD7A3
    }
}
