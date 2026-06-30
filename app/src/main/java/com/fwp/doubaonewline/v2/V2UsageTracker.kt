package com.fwp.doubaonewline.v2

import android.content.Context
import java.time.LocalDate

data class V2UsageSnapshot(
    val totalTokens: Long,
    val todayTokens: Long
)

class V2UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(inputTokens: Long, outputTokens: Long): V2UsageSnapshot {
        val added = (inputTokens.coerceAtLeast(0) + outputTokens.coerceAtLeast(0))
        val today = LocalDate.now().toString()
        val storedDay = prefs.getString(KEY_DAY, null)
        val previousToday = if (storedDay == today) prefs.getLong(KEY_TODAY, 0L) else 0L
        val total = prefs.getLong(KEY_TOTAL, 0L) + added
        val todayTotal = previousToday + added
        prefs.edit()
            .putLong(KEY_TOTAL, total)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, todayTotal)
            .apply()
        return V2UsageSnapshot(total, todayTotal)
    }

    @Synchronized
    fun snapshot(): V2UsageSnapshot {
        val today = LocalDate.now().toString()
        return V2UsageSnapshot(
            totalTokens = prefs.getLong(KEY_TOTAL, 0L),
            todayTokens = if (prefs.getString(KEY_DAY, null) == today) {
                prefs.getLong(KEY_TODAY, 0L)
            } else {
                0L
            }
        )
    }

    companion object {
        private const val PREFS = "v2_usage"
        private const val KEY_TOTAL = "total_tokens"
        private const val KEY_DAY = "usage_day"
        private const val KEY_TODAY = "today_tokens"
    }
}
