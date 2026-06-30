package com.fwp.doubaonewline.v2

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

data class V2UsageSnapshot(
    val monthTokens: Long,
    val todayTokens: Long,
    val monthDurationMs: Long,
    val todayDurationMs: Long
)

class V2UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION) {
            prefs.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .commit()
        }
    }

    @Synchronized
    fun add(inputTokens: Long, outputTokens: Long): V2UsageSnapshot {
        val added = (inputTokens.coerceAtLeast(0) + outputTokens.coerceAtLeast(0))
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val storedDay = prefs.getString(KEY_DAY, null)
        val sameMonth = prefs.getString(KEY_MONTH, null) == month
        val previousToday = if (storedDay == today) prefs.getLong(KEY_TODAY, 0L) else 0L
        val monthTotal = (if (sameMonth) prefs.getLong(KEY_MONTH_TOKENS, 0L) else 0L) + added
        val todayTotal = previousToday + added
        prefs.edit()
            .putString(KEY_MONTH, month)
            .putLong(KEY_MONTH_TOKENS, monthTotal)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, todayTotal)
            .apply {
                if (storedDay != today) putLong(KEY_TODAY_DURATION, 0L)
                if (!sameMonth) putLong(KEY_MONTH_DURATION, 0L)
            }
            .commit()
        return snapshot()
    }

    @Synchronized
    fun addDuration(durationMs: Long): V2UsageSnapshot {
        val added = durationMs.coerceAtLeast(0L)
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val sameDay = prefs.getString(KEY_DAY, null) == today
        val sameMonth = prefs.getString(KEY_MONTH, null) == month
        val monthTotal =
            (if (sameMonth) prefs.getLong(KEY_MONTH_DURATION, 0L) else 0L) + added
        val todayTotal = (if (sameDay) prefs.getLong(KEY_TODAY_DURATION, 0L) else 0L) + added
        prefs.edit()
            .putString(KEY_MONTH, month)
            .putLong(KEY_MONTH_DURATION, monthTotal)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY_DURATION, todayTotal)
            .apply {
                if (!sameDay) putLong(KEY_TODAY, 0L)
                if (!sameMonth) putLong(KEY_MONTH_TOKENS, 0L)
            }
            .commit()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): V2UsageSnapshot {
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val sameMonth = prefs.getString(KEY_MONTH, null) == month
        return V2UsageSnapshot(
            monthTokens = if (sameMonth) prefs.getLong(KEY_MONTH_TOKENS, 0L) else 0L,
            todayTokens = if (prefs.getString(KEY_DAY, null) == today) {
                prefs.getLong(KEY_TODAY, 0L)
            } else {
                0L
            },
            monthDurationMs =
                if (sameMonth) prefs.getLong(KEY_MONTH_DURATION, 0L) else 0L,
            todayDurationMs = if (prefs.getString(KEY_DAY, null) == today) {
                prefs.getLong(KEY_TODAY_DURATION, 0L)
            } else {
                0L
            }
        )
    }

    companion object {
        private const val PREFS = "v2_usage"
        private const val KEY_MONTH = "usage_month"
        private const val KEY_MONTH_TOKENS = "month_tokens"
        private const val KEY_DAY = "usage_day"
        private const val KEY_TODAY = "today_tokens"
        private const val KEY_MONTH_DURATION = "month_duration_ms"
        private const val KEY_TODAY_DURATION = "today_duration_ms"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val SCHEMA_VERSION = 3
    }
}
