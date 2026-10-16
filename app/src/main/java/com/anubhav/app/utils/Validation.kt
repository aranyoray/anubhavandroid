package com.anubhav.app.utils

/**
 * Shared patient-input rules so every booking form agrees on what a plausible
 * value is. Age is capped at a human maximum, so a slip like "200" or a stray
 * extra digit is rejected instead of saved to a bill.
 */
object AgeRules {
    const val MIN = 0
    const val MAX = 120

    /** Returns the age in years when it is a whole number in [MIN, MAX], else null. */
    fun parse(raw: String?): Int? {
        val n = raw?.trim()?.toIntOrNull() ?: return null
        return if (n in MIN..MAX) n else null
    }

    fun isValid(raw: String?): Boolean = parse(raw) != null
}
