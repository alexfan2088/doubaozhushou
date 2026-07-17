package com.fwp.doubaonewline.v2

object WakeKeywordPolicy {
    const val EXACT_KEYWORD = "豆包豆包"

    /**
     * Sensitivity 1-2 use thresholds above 0.12 and only accept the configured keyword.
     * Sensitivity 3-5 retain intentionally permissive same-prefix candidates.
     */
    fun accepts(
        result: String,
        threshold: Float,
        wakeWord: String = EXACT_KEYWORD
    ): Boolean {
        val expected = wakeWord.ifBlank { EXACT_KEYWORD }
        if (result == expected) return true
        return threshold <= 0.12f && result.startsWith(expected)
    }
}
