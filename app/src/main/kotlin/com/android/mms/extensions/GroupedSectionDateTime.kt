package com.android.mms.extensions

import android.content.Context
import com.android.mms.R
import com.android.mms.models.ConversationListItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val ONE_MIN_MS = 60_000L
private const val SIXTY_MIN_MS = 60 * ONE_MIN_MS

/**
 * Formats last-message / call time for list rows under Today, Yesterday, or Previous headers.
 *
 * - **Today:** Delta &lt; 1 min → "Now"; 1–60 min → "1 min ago" / "N mins ago"; else → `HH:mm`
 * - **Yesterday:** `HH:mm`
 * - **Previous:** same calendar year as now → `MM.dd HH:mm`; else → `yyyy.MM.dd HH:mm`
 */
fun formatGroupedSectionDateTime(
    context: Context,
    lastMessageMillis: Long,
    sectionDayCode: String?,
): String {
    if (sectionDayCode == null) {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(lastMessageMillis)
    }
    val now = System.currentTimeMillis()
    val delta = now - lastMessageMillis
    val msgCal = Calendar.getInstance().apply { timeInMillis = lastMessageMillis }
    val curCal = Calendar.getInstance()

    return when (sectionDayCode) {
        ConversationListItem.SECTION_TODAY -> {
            when {
                delta < ONE_MIN_MS -> context.getString(R.string.grouped_list_now)
                delta <= SIXTY_MIN_MS -> {
                    val mins = (delta / ONE_MIN_MS).toInt().coerceIn(1, 60)
                    context.resources.getQuantityString(R.plurals.grouped_list_minutes_ago, mins, mins)
                }
                else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(lastMessageMillis)
            }
        }
        ConversationListItem.SECTION_YESTERDAY -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(lastMessageMillis)
        }
        ConversationListItem.SECTION_BEFORE -> {
            val y = msgCal.get(Calendar.YEAR)
            val curY = curCal.get(Calendar.YEAR)
            if (y == curY) {
                SimpleDateFormat("MM.dd HH:mm", Locale.getDefault()).format(lastMessageMillis)
            } else {
                SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(lastMessageMillis)
            }
        }
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(lastMessageMillis)
    }
}

/** Compacts relative time phrases for Korean locale (same idea as conversation list). */
fun normalizeGroupedListRelativeTextForKorean(context: Context, text: String): String {
    val language = context.resources.configuration.locales[0]?.language ?: Locale.getDefault().language
    if (language != Locale.KOREAN.language) return text

    val koreanCompact = text
        .replace("분 전", "분전")
        .replace("시간 전", "시간전")
        .replace("일 전", "일전")
        .replace("주 전", "주전")
        .replace("개월 전", "개월전")
        .replace("년 전", "년전")

    return koreanCompact
        .replace(Regex("""(\d+)\s*min\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
        .replace(Regex("""(\d+)\s*mins\.?\s*ago""", RegexOption.IGNORE_CASE), "$1분전")
        .replace(Regex("""(\d+)\s*hr\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
        .replace(Regex("""(\d+)\s*hrs\.?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
        .replace(Regex("""(\d+)\s*hour[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1시간전")
        .replace(Regex("""(\d+)\s*day[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1일전")
        .replace(Regex("""(\d+)\s*week[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1주전")
        .replace(Regex("""(\d+)\s*month[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1개월전")
        .replace(Regex("""(\d+)\s*year[s]?\s*ago""", RegexOption.IGNORE_CASE), "$1년전")
}

/**
 * Milliseconds until the displayed Today-section label may change for this message/call time.
 * Schedules the next UI refresh so "1 min ago" becomes "2 min ago", "Now" becomes "1 min ago", etc.
 */
fun nextGroupedTodayLabelRefreshDelayMillis(lastMessageMillis: Long): Long {
    val now = System.currentTimeMillis()
    val delta = now - lastMessageMillis
    return when {
        delta < ONE_MIN_MS -> (ONE_MIN_MS - delta).coerceAtLeast(1L)
        delta <= SIXTY_MIN_MS -> {
            val m = (delta / ONE_MIN_MS).toInt()
            val nextBoundary = (m + 1) * ONE_MIN_MS
            (nextBoundary - delta).coerceAtLeast(1L)
        }
        else -> {
            val mod = now % ONE_MIN_MS
            (if (mod == 0L) ONE_MIN_MS else ONE_MIN_MS - mod).coerceAtLeast(1L)
        }
    }
}
