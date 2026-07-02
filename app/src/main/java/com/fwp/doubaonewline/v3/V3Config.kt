package com.fwp.doubaonewline.v3

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.fwp.doubaonewline.v2.TtsEngineMode

enum class V3Model(
    val label: String,
    val parameters: String,
    val downloadBytes: Long,
    val minimumFreeBytes: Long,
    val threads: Int
) {
    DEEPSEEK_1_5B(
        label = "DeepSeek 1.5B（速度优先）",
        parameters = "1.5B",
        downloadBytes = 1_117_320_480L,
        minimumFreeBytes = 4L * 1024 * 1024 * 1024,
        threads = 4
    ),
    DEEPSEEK_7B(
        label = "DeepSeek 7B（质量优先）",
        parameters = "7B",
        downloadBytes = 4_683_073_184L,
        minimumFreeBytes = 10L * 1024 * 1024 * 1024,
        threads = 6
    )
}

data class V3Config(
    val selectedModel: V3Model = V3Model.DEEPSEEK_1_5B,
    val allowBargeIn: Boolean = true,
    val speakerId: Int = 0,
    val ttsGain: Int = 20,
    val ttsEngineMode: TtsEngineMode = TtsEngineMode.LOCAL,
    val maxResponseSentences: Int = 2,
    val contextRounds: Int = 4
)

data class V3DeviceRecommendation(
    val recommendedModel: V3Model,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val freeStorageBytes: Long,
    val reason: String
)

object V3RecommendationPolicy {
    const val MIN_7B_TOTAL_MEMORY = 12L * 1024 * 1024 * 1024
    const val MIN_7B_AVAILABLE_MEMORY = 6L * 1024 * 1024 * 1024

    fun recommend(totalMemory: Long, availableMemory: Long, freeStorage: Long): V3Model =
        if (
            totalMemory >= MIN_7B_TOTAL_MEMORY &&
            availableMemory >= MIN_7B_AVAILABLE_MEMORY &&
            freeStorage >= V3Model.DEEPSEEK_7B.minimumFreeBytes
        ) {
            V3Model.DEEPSEEK_7B
        } else {
            V3Model.DEEPSEEK_1_5B
        }
}

class V3Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load() = V3Config(
        selectedModel = runCatching {
            V3Model.valueOf(
                prefs.getString(KEY_MODEL, V3Model.DEEPSEEK_1_5B.name).orEmpty()
            )
        }.getOrDefault(V3Model.DEEPSEEK_1_5B),
        allowBargeIn = prefs.getBoolean(KEY_BARGE_IN, true),
        speakerId = prefs.getInt(KEY_SPEAKER, 0).coerceIn(0, 4),
        ttsGain = prefs.getInt(KEY_GAIN, 20).coerceIn(1, 30),
        ttsEngineMode = runCatching {
            TtsEngineMode.valueOf(
                prefs.getString(KEY_TTS_ENGINE, TtsEngineMode.LOCAL.name).orEmpty()
            )
        }.getOrDefault(TtsEngineMode.LOCAL),
        maxResponseSentences = prefs.getInt(KEY_SENTENCES, 2).coerceIn(1, 3),
        contextRounds = prefs.getInt(KEY_CONTEXT_ROUNDS, 4).coerceIn(1, 8)
    )

    fun save(config: V3Config) {
        prefs.edit()
            .putString(KEY_MODEL, config.selectedModel.name)
            .putBoolean(KEY_BARGE_IN, config.allowBargeIn)
            .putInt(KEY_SPEAKER, config.speakerId.coerceIn(0, 4))
            .putInt(KEY_GAIN, config.ttsGain.coerceIn(1, 30))
            .putString(KEY_TTS_ENGINE, config.ttsEngineMode.name)
            .putInt(KEY_SENTENCES, config.maxResponseSentences.coerceIn(1, 3))
            .putInt(KEY_CONTEXT_ROUNDS, config.contextRounds.coerceIn(1, 8))
            .apply()
    }

    fun recommendation(): V3DeviceRecommendation {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val freeStorage = StatFs(context.filesDir.absolutePath).availableBytes
        val recommended =
            V3RecommendationPolicy.recommend(memory.totalMem, memory.availMem, freeStorage)
        val reason = if (recommended == V3Model.DEEPSEEK_7B) {
            "内存和存储满足 7B 建议条件，推荐质量优先。"
        } else {
            "当前可用内存或存储不足 7B 建议条件，推荐 1.5B 保证流畅。"
        }
        return V3DeviceRecommendation(
            recommendedModel = recommended,
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            freeStorageBytes = freeStorage,
            reason = reason
        )
    }

    companion object {
        private const val PREFS = "v3_settings"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_BARGE_IN = "allow_barge_in"
        private const val KEY_SPEAKER = "speaker_id"
        private const val KEY_GAIN = "tts_gain"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_SENTENCES = "max_response_sentences"
        private const val KEY_CONTEXT_ROUNDS = "context_rounds"
    }
}
