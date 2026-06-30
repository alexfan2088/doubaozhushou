package com.fwp.doubaonewline.v2

import android.content.Context
import java.time.LocalDate

data class V2UsageSnapshot(
    val totalTokens: Long,
    val todayTokens: Long,
    val totalDurationMs: Long,
    val todayDurationMs: Long
)

class V2UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION) {
            prefs.edit()
                .clear()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .apply()
        }
    }

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
            .apply {
                if (storedDay != today) putLong(KEY_TODAY_DURATION, 0L)
            }
            .apply()
        return snapshot()
    }

    @Synchronized
    fun addDuration(durationMs: Long): V2UsageSnapshot {
        val added = durationMs.coerceAtLeast(0L)
        val today = LocalDate.now().toString()
        val sameDay = prefs.getString(KEY_DAY, null) == today
        val total = prefs.getLong(KEY_TOTAL_DURATION, 0L) + added
        val todayTotal = (if (sameDay) prefs.getLong(KEY_TODAY_DURATION, 0L) else 0L) + added
        prefs.edit()
            .putLong(KEY_TOTAL_DURATION, total)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY_DURATION, todayTotal)
            .apply {
                if (!sameDay) putLong(KEY_TODAY, 0L)
            }
            .apply()
        return snapshot()
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
            },
            totalDurationMs = prefs.getLong(KEY_TOTAL_DURATION, 0L),
            todayDurationMs = if (prefs.getString(KEY_DAY, null) == today) {
                prefs.getLong(KEY_TODAY_DURATION, 0L)
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
        private const val KEY_TOTAL_DURATION = "total_duration_ms"
        private const val KEY_TODAY_DURATION = "today_duration_ms"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val SCHEMA_VERSION = 2
    }
}
