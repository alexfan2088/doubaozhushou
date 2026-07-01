package com.fwp.doubaonewline.v2

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

data class V2UsageSnapshot(
    val monthTokens: Long,
    val todayTokens: Long,
    val monthDurationMs: Long,
    val todayDurationMs: Long,
    val monthInputAudioTokens: Long,
    val monthInputTextTokens: Long,
    val monthOutputAudioTokens: Long,
    val monthOutputTextTokens: Long,
    val todayInputAudioTokens: Long,
    val todayInputTextTokens: Long,
    val todayOutputAudioTokens: Long,
    val todayOutputTextTokens: Long
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
    fun add(usage: RealtimeVoiceEvent.Usage): V2UsageSnapshot {
        val inputAudio = usage.inputAudioTokens.coerceAtLeast(0)
        val inputText = usage.inputTextTokens.coerceAtLeast(0)
        val outputAudio = usage.outputAudioTokens.coerceAtLeast(0)
        val outputText = usage.outputTextTokens.coerceAtLeast(0)
        val added = inputAudio + inputText + outputAudio + outputText
        val today = LocalDate.now().toString()
        val month = YearMonth.now().toString()
        val storedDay = prefs.getString(KEY_DAY, null)
        val sameDay = storedDay == today
        val sameMonth = prefs.getString(KEY_MONTH, null) == month
        val previousToday = if (sameDay) prefs.getLong(KEY_TODAY, 0L) else 0L
        val monthTotal = (if (sameMonth) prefs.getLong(KEY_MONTH_TOKENS, 0L) else 0L) + added
        val todayTotal = previousToday + added
        prefs.edit()
            .putString(KEY_MONTH, month)
            .putLong(KEY_MONTH_TOKENS, monthTotal)
            .putString(KEY_DAY, today)
            .putLong(KEY_TODAY, todayTotal)
            .putLong(
                KEY_MONTH_INPUT_AUDIO,
                (if (sameMonth) prefs.getLong(KEY_MONTH_INPUT_AUDIO, 0L) else 0L) + inputAudio
            )
            .putLong(
                KEY_MONTH_INPUT_TEXT,
                (if (sameMonth) prefs.getLong(KEY_MONTH_INPUT_TEXT, 0L) else 0L) + inputText
            )
            .putLong(
                KEY_MONTH_OUTPUT_AUDIO,
                (if (sameMonth) prefs.getLong(KEY_MONTH_OUTPUT_AUDIO, 0L) else 0L) + outputAudio
            )
            .putLong(
                KEY_MONTH_OUTPUT_TEXT,
                (if (sameMonth) prefs.getLong(KEY_MONTH_OUTPUT_TEXT, 0L) else 0L) + outputText
            )
            .putLong(
                KEY_TODAY_INPUT_AUDIO,
                (if (sameDay) prefs.getLong(KEY_TODAY_INPUT_AUDIO, 0L) else 0L) + inputAudio
            )
            .putLong(
                KEY_TODAY_INPUT_TEXT,
                (if (sameDay) prefs.getLong(KEY_TODAY_INPUT_TEXT, 0L) else 0L) + inputText
            )
            .putLong(
                KEY_TODAY_OUTPUT_AUDIO,
                (if (sameDay) prefs.getLong(KEY_TODAY_OUTPUT_AUDIO, 0L) else 0L) + outputAudio
            )
            .putLong(
                KEY_TODAY_OUTPUT_TEXT,
                (if (sameDay) prefs.getLong(KEY_TODAY_OUTPUT_TEXT, 0L) else 0L) + outputText
            )
            .apply {
                if (!sameDay) putLong(KEY_TODAY_DURATION, 0L)
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
                if (!sameDay) {
                    putLong(KEY_TODAY, 0L)
                    putLong(KEY_TODAY_INPUT_AUDIO, 0L)
                    putLong(KEY_TODAY_INPUT_TEXT, 0L)
                    putLong(KEY_TODAY_OUTPUT_AUDIO, 0L)
                    putLong(KEY_TODAY_OUTPUT_TEXT, 0L)
                }
                if (!sameMonth) {
                    putLong(KEY_MONTH_TOKENS, 0L)
                    putLong(KEY_MONTH_INPUT_AUDIO, 0L)
                    putLong(KEY_MONTH_INPUT_TEXT, 0L)
                    putLong(KEY_MONTH_OUTPUT_AUDIO, 0L)
                    putLong(KEY_MONTH_OUTPUT_TEXT, 0L)
                }
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
            monthInputAudioTokens = if (sameMonth) prefs.getLong(KEY_MONTH_INPUT_AUDIO, 0L) else 0L,
            monthInputTextTokens = if (sameMonth) prefs.getLong(KEY_MONTH_INPUT_TEXT, 0L) else 0L,
            monthOutputAudioTokens = if (sameMonth) prefs.getLong(KEY_MONTH_OUTPUT_AUDIO, 0L) else 0L,
            monthOutputTextTokens = if (sameMonth) prefs.getLong(KEY_MONTH_OUTPUT_TEXT, 0L) else 0L,
            todayInputAudioTokens = todayValue(KEY_TODAY_INPUT_AUDIO, today),
            todayInputTextTokens = todayValue(KEY_TODAY_INPUT_TEXT, today),
            todayOutputAudioTokens = todayValue(KEY_TODAY_OUTPUT_AUDIO, today),
            todayOutputTextTokens = todayValue(KEY_TODAY_OUTPUT_TEXT, today)
        )
    }

    private fun todayValue(key: String, today: String): Long =
        if (prefs.getString(KEY_DAY, null) == today) prefs.getLong(key, 0L) else 0L

    companion object {
        private const val PREFS = "v2_usage"
        private const val KEY_MONTH = "usage_month"
        private const val KEY_MONTH_TOKENS = "month_tokens"
        private const val KEY_DAY = "usage_day"
        private const val KEY_TODAY = "today_tokens"
        private const val KEY_MONTH_DURATION = "month_duration_ms"
        private const val KEY_TODAY_DURATION = "today_duration_ms"
        private const val KEY_MONTH_INPUT_AUDIO = "month_input_audio_tokens"
        private const val KEY_MONTH_INPUT_TEXT = "month_input_text_tokens"
        private const val KEY_MONTH_OUTPUT_AUDIO = "month_output_audio_tokens"
        private const val KEY_MONTH_OUTPUT_TEXT = "month_output_text_tokens"
        private const val KEY_TODAY_INPUT_AUDIO = "today_input_audio_tokens"
        private const val KEY_TODAY_INPUT_TEXT = "today_input_text_tokens"
        private const val KEY_TODAY_OUTPUT_AUDIO = "today_output_audio_tokens"
        private const val KEY_TODAY_OUTPUT_TEXT = "today_output_text_tokens"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val SCHEMA_VERSION = 4
    }
}
