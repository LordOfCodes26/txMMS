package com.goodwy.commons.helpers

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.goodwy.commons.extensions.getNameLetter
import com.goodwy.commons.extensions.isEmoji
import com.goodwy.commons.extensions.toAvatarColorSeed
import kotlin.math.abs

/**
 * Utility class for generating monogram-related visual elements.
 * Provides deterministic generation of initials and gradients based on names.
 * 
 * This class is thread-safe and can be used across the application.
 */
object MonogramGenerator {

    /**
     * Generates initials from a name string.
     * 
     * Rules:
     * - For single word: returns first letter (e.g., "John" -> "J")
     * - For multiple words: returns first letter of first and last word (e.g., "John Smith" -> "JS")
     * - Handles emojis: if name starts with emoji, returns the emoji
     * - Empty names: returns "A" as fallback
     * 
     * Examples:
     * - "John Smith" -> "JS"
     * - "Mary Jane Watson" -> "MW"
     * - "John" -> "J"
     * - "😀 John" -> "😀"
     * - "" -> "A"
     * 
     * @param name The name to extract initials from
     * @return The initials string (1-2 characters, or emoji)
     */
    fun generateInitials(name: String): String {
        if (name.isEmpty()) {
            return "A"
        }

        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return "A"
        }

        // Check for emoji first (take first 2 characters to handle multi-byte emojis)
        val emoji = trimmedName.take(2)
        if (emoji.isEmoji()) {
            return emoji
        }

        // Split by whitespace and filter out empty strings
        val words = trimmedName.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
            .filter { word -> 
                // Filter out words that are only punctuation/symbols
                word.any { it.isLetterOrDigit() }
            }

        return when {
            words.isEmpty() -> "A"
            words.size == 1 -> {
                // Single word: return first letter
                words[0].getNameLetter()
            }
            else -> {
                // Multiple words: return first letter of first and last word
                val firstInitial = words.first().getNameLetter()
                val lastInitial = words.last().getNameLetter()
                "$firstInitial$lastInitial"
            }
        }
    }

    /**
     * Generates a deterministic gradient drawable from a name hash.
     * 
     * The gradient is deterministic - the same name will always produce the same gradient.
     * Uses the name's hash code to generate consistent colors.
     * 
     * The gradient:
     * - Uses HSV color space for better color distribution
     * - Creates a top-to-bottom gradient with lighter top and darker bottom
     * - Ensures colors are within visible and pleasant ranges
     * - Always produces a valid GradientDrawable
     * 
     * @param name The name to generate gradient from
     * @return A GradientDrawable with top-to-bottom orientation
     */
    fun generateGradient(name: String): GradientDrawable {
        val colors = generateGradientColors(name)
        
        return GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            this.colors = colors.toIntArray()
        }
    }

    /**
     * Generates gradient colors from a name hash.
     * This is a deterministic process - same name always produces same colors.
     * 
     * @param name The name to generate colors from
     * @return List of colors (typically 2 colors for gradient)
     */
    fun generateGradientColors(name: String): List<Int> {
        // Use name hash to generate consistent colors
        // Use absolute value to ensure positive numbers
        val hash = abs(name.toAvatarColorSeed().hashCode())
        
        // Generate base color using HSV for better color distribution
        // Hue: 0-360 (full color spectrum)
        // Saturation: 0.5-0.7 (moderately saturated, not too vibrant)
        // Value/Brightness: 0.6-0.8 (moderately bright)
        val hue = (hash % 360).toFloat()
        val saturation = 0.5f + ((hash % 20) / 100f).coerceIn(0f, 0.2f) // 0.5-0.7
        val brightness = 0.6f + ((hash % 20) / 100f).coerceIn(0f, 0.2f) // 0.6-0.8
        
        val baseColor = Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
        
        // Create lighter and darker variants for gradient
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        
        // Top color: lighter and slightly less saturated
        val topHue = hsv[0]
        val topSaturation = (hsv[1] * 0.7f).coerceIn(0f, 1f)
        val topBrightness = (hsv[2] * 1.15f).coerceAtMost(0.95f)
        val topColor = Color.HSVToColor(floatArrayOf(topHue, topSaturation, topBrightness))
        
        // Bottom color: darker and slightly more saturated
        val bottomHue = hsv[0]
        val bottomSaturation = (hsv[1] * 1.1f).coerceIn(0f, 1f)
        val bottomBrightness = (hsv[2] * 0.85f).coerceAtLeast(0.1f)
        val bottomColor = Color.HSVToColor(floatArrayOf(bottomHue, bottomSaturation, bottomBrightness))
        
        return listOf(topColor, bottomColor)
    }
}
