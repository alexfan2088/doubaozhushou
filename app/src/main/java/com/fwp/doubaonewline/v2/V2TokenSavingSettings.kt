package com.fwp.doubaonewline.v2

import android.content.Context

enum class ContextRetentionMode {
    RESET_AFTER_LIMIT,
    KEEP
}

data class V2TokenSavingConfig(
    val localWelcomeEnabled: Boolean = true,
    val localWelcomeText: String = DEFAULT_WELCOME_TEXT,
    val offlineTtsSpeakerId: Int = 0,
    val offlineTtsGain: Int = DEFAULT_TTS_GAIN,
    val maxResponseSentences: Int = 2,
    val idleTimeoutSeconds: Int = 15,
    val wakeCooldownSeconds: Int = 3,
    val contextRetentionMode: ContextRetentionMode = ContextRetentionMode.RESET_AFTER_LIMIT,
    val maxContextRounds: Int = 10,
    val maxResponseSeconds: Int = 20
) {
    fun systemPrompt(): String {
        val base = "你是一个自然、简洁、有帮助的中文语音助手。先给结论，不重复用户的问题。"
        return if (maxResponseSentences == UNLIMITED) {
            base
        } else {
            "$base 除非用户明确要求详细说明，否则每次回答不超过${maxResponseSentences}句话。"
        }
    }

    companion object {
        const val DEFAULT_WELCOME_TEXT = "豆包已经准备好了"
        const val MIN_TTS_GAIN = 1
        const val DEFAULT_TTS_GAIN = 20
        const val MAX_TTS_GAIN = 30
        const val UNLIMITED = 0
        val SENTENCE_OPTIONS = listOf(1, 2, 3, UNLIMITED)
        val IDLE_TIMEOUT_OPTIONS = listOf(10, 15, 30, 60)
        val WAKE_COOLDOWN_OPTIONS = listOf(1, 3, 5, 10)
        val CONTEXT_ROUND_OPTIONS = listOf(5, 8, 10, 15, 20)
        val RESPONSE_TIMEOUT_OPTIONS = listOf(10, 20, 30, 60, UNLIMITED)
    }
}

class V2TokenSavingSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): V2TokenSavingConfig = V2TokenSavingConfig(
        localWelcomeEnabled = prefs.getBoolean(KEY_LOCAL_WELCOME_ENABLED, true),
        localWelcomeText = prefs.getString(
            KEY_LOCAL_WELCOME_TEXT,
            V2TokenSavingConfig.DEFAULT_WELCOME_TEXT
        ).orEmpty().ifBlank { V2TokenSavingConfig.DEFAULT_WELCOME_TEXT },
        offlineTtsSpeakerId = prefs.getInt(KEY_OFFLINE_TTS_SPEAKER_ID, 0).coerceIn(0, 4),
        offlineTtsGain = prefs.getInt(
            KEY_OFFLINE_TTS_GAIN,
            V2TokenSavingConfig.DEFAULT_TTS_GAIN
        ).coerceIn(V2TokenSavingConfig.MIN_TTS_GAIN, V2TokenSavingConfig.MAX_TTS_GAIN),
        maxResponseSentences = allowed(
            prefs.getInt(KEY_MAX_RESPONSE_SENTENCES, 2),
            V2TokenSavingConfig.SENTENCE_OPTIONS,
            2
        ),
        idleTimeoutSeconds = allowed(
            prefs.getInt(KEY_IDLE_TIMEOUT_SECONDS, 15),
            V2TokenSavingConfig.IDLE_TIMEOUT_OPTIONS,
            15
        ),
        wakeCooldownSeconds = allowed(
            prefs.getInt(KEY_WAKE_COOLDOWN_SECONDS, 3),
            V2TokenSavingConfig.WAKE_COOLDOWN_OPTIONS,
            3
        ),
        contextRetentionMode = runCatching {
            ContextRetentionMode.valueOf(
                prefs.getString(
                    KEY_CONTEXT_RETENTION_MODE,
                    ContextRetentionMode.RESET_AFTER_LIMIT.name
                ).orEmpty()
            )
        }.getOrDefault(ContextRetentionMode.RESET_AFTER_LIMIT),
        maxContextRounds = allowed(
            prefs.getInt(KEY_MAX_CONTEXT_ROUNDS, 10),
            V2TokenSavingConfig.CONTEXT_ROUND_OPTIONS,
            10
        ),
        maxResponseSeconds = allowed(
            prefs.getInt(KEY_MAX_RESPONSE_SECONDS, 20),
            V2TokenSavingConfig.RESPONSE_TIMEOUT_OPTIONS,
            20
        )
    )

    fun save(config: V2TokenSavingConfig) {
        prefs.edit()
            .putBoolean(KEY_LOCAL_WELCOME_ENABLED, config.localWelcomeEnabled)
            .putString(
                KEY_LOCAL_WELCOME_TEXT,
                config.localWelcomeText.ifBlank { V2TokenSavingConfig.DEFAULT_WELCOME_TEXT }
            )
            .putInt(KEY_OFFLINE_TTS_SPEAKER_ID, config.offlineTtsSpeakerId.coerceIn(0, 4))
            .putInt(
                KEY_OFFLINE_TTS_GAIN,
                config.offlineTtsGain.coerceIn(
                    V2TokenSavingConfig.MIN_TTS_GAIN,
                    V2TokenSavingConfig.MAX_TTS_GAIN
                )
            )
            .putInt(KEY_MAX_RESPONSE_SENTENCES, config.maxResponseSentences)
            .putInt(KEY_IDLE_TIMEOUT_SECONDS, config.idleTimeoutSeconds)
            .putInt(KEY_WAKE_COOLDOWN_SECONDS, config.wakeCooldownSeconds)
            .putString(KEY_CONTEXT_RETENTION_MODE, config.contextRetentionMode.name)
            .putInt(KEY_MAX_CONTEXT_ROUNDS, config.maxContextRounds)
            .putInt(KEY_MAX_RESPONSE_SECONDS, config.maxResponseSeconds)
            .apply()
    }

    private fun allowed(value: Int, options: List<Int>, fallback: Int): Int =
        value.takeIf(options::contains) ?: fallback

    companion object {
        private const val PREFS = "v2_token_saving_settings"
        private const val KEY_LOCAL_WELCOME_ENABLED = "local_welcome_enabled"
        private const val KEY_LOCAL_WELCOME_TEXT = "local_welcome_text"
        private const val KEY_OFFLINE_TTS_SPEAKER_ID = "offline_tts_speaker_id"
        private const val KEY_OFFLINE_TTS_GAIN = "offline_tts_gain"
        private const val KEY_MAX_RESPONSE_SENTENCES = "max_response_sentences"
        private const val KEY_IDLE_TIMEOUT_SECONDS = "idle_timeout_seconds"
        private const val KEY_WAKE_COOLDOWN_SECONDS = "wake_cooldown_seconds"
        private const val KEY_CONTEXT_RETENTION_MODE = "context_retention_mode"
        private const val KEY_MAX_CONTEXT_ROUNDS = "max_context_rounds"
        private const val KEY_MAX_RESPONSE_SECONDS = "max_response_seconds"
    }
}
