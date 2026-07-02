package com.fwp.doubaonewline.v2

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

data class V2UsageSnapshot(
    val monthTokens: Long,
    val todayTokens: Long,
    val monthDurationMs: Long,
    val todayDurationMs: Long,
    val monthBreakdown: V2TokenBreakdown,
    val todayBreakdown: V2TokenBreakdown
)

data class V2TokenBreakdown(
    val inputText: Long = 0L,
    val inputAudio: Long = 0L,
    val outputText: Long = 0L,
    val outputAudio: Long = 0L
) {
    val total: Long
        get() = inputText + inputAudio + outputText + outputAudio
}

class V2UsageTracker(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 0) < SCHEMA_VERSION) {
            prefs.edit()
                .clear()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .commit()
        }
    }

    @Synchronized
    fun add(
        inputTokens: Long,
        outputTokens: Long,
        breakdown: V2TokenBreakdown = V2TokenBreakdown()
    ): V2UsageSnapshot {
        val added = (inputTokens.coerceAtLeast(0) + outputTokens.coerceAtLeast(0))
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val storedDay = prefs.getString(KEY_DAY, null)
        val sameMonth = prefs.getString(KEY_MONTH, null) == month
        val previousToday = if (storedDay == today) prefs.getLong(KEY_TODAY, 0L) else 0L
        val monthTotal = (if (sameMonth) prefs.getLong(KEY_MONTH_TOKENS, 0L) else 0L) + added
        val todayTotal = previousToday + added
        val monthBreakdown = if (sameMonth) readBreakdown(MONTH_PREFIX) else V2TokenBreakdown()
        val todayBreakdown = if (storedDay == today) {
            readBreakdown(TODAY_PREFIX)
        } else {
            V2TokenBreakdown()
        }
        prefs.edit()
            .putString(KEY_MONTH, month)
            .putLong(KEY_MONTH_TOKENS, monthTotal)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, todayTotal)
            .putBreakdown(MONTH_PREFIX, monthBreakdown + breakdown)
            .putBreakdown(TODAY_PREFIX, todayBreakdown + breakdown)
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
            },
            monthBreakdown = if (sameMonth) {
                readBreakdown(MONTH_PREFIX)
            } else {
                V2TokenBreakdown()
            },
            todayBreakdown = if (prefs.getString(KEY_DAY, null) == today) {
                readBreakdown(TODAY_PREFIX)
            } else {
                V2TokenBreakdown()
            }
        )
    }

    private fun readBreakdown(prefix: String) = V2TokenBreakdown(
        inputText = prefs.getLong("${prefix}_input_text", 0L),
        inputAudio = prefs.getLong("${prefix}_input_audio", 0L),
        outputText = prefs.getLong("${prefix}_output_text", 0L),
        outputAudio = prefs.getLong("${prefix}_output_audio", 0L)
    )

    private fun android.content.SharedPreferences.Editor.putBreakdown(
        prefix: String,
        value: V2TokenBreakdown
    ) = apply {
        putLong("${prefix}_input_text", value.inputText)
        putLong("${prefix}_input_audio", value.inputAudio)
        putLong("${prefix}_output_text", value.outputText)
        putLong("${prefix}_output_audio", value.outputAudio)
    }

    private operator fun V2TokenBreakdown.plus(other: V2TokenBreakdown) = V2TokenBreakdown(
        inputText = inputText + other.inputText.coerceAtLeast(0L),
        inputAudio = inputAudio + other.inputAudio.coerceAtLeast(0L),
        outputText = outputText + other.outputText.coerceAtLeast(0L),
        outputAudio = outputAudio + other.outputAudio.coerceAtLeast(0L)
    )

    companion object {
        private const val PREFS = "v2_usage"
        private const val KEY_MONTH = "usage_month"
        private const val KEY_MONTH_TOKENS = "month_tokens"
        private const val KEY_DAY = "usage_day"
        private const val KEY_TODAY = "today_tokens"
        private const val KEY_MONTH_DURATION = "month_duration_ms"
        private const val KEY_TODAY_DURATION = "today_duration_ms"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val MONTH_PREFIX = "month"
        private const val TODAY_PREFIX = "today"
        private const val SCHEMA_VERSION = 5
    }
}
