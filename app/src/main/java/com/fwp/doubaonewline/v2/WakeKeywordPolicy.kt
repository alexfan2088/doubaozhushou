package com.fwp.doubaonewline.v2

object WakeKeywordPolicy {
    const val EXACT_KEYWORD = "豆包豆包"

    /**
     * Sensitivity 1-2 use thresholds above 0.12 and only accept the exact keyword.
     * Sensitivity 3-5 retain the intentionally permissive alternative phrases.
     */
    fun accepts(result: String, threshold: Float): Boolean {
        if (result == EXACT_KEYWORD) return true
        return threshold <= 0.12f && result.startsWith(EXACT_KEYWORD)
    }
}
