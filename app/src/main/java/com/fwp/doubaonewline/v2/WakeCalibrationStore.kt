package com.fwp.doubaonewline.v2

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class WakeCalibrationProfile(
    val gain: Float,
    val noiseRms: Float,
    val keywordThreshold: Float,
    val calibratedAtMs: Long,
    val templates: List<WakeTemplate> = emptyList(),
    val templateThreshold: Float = 0f
)

data class WakeTemplate(val frames: Int, val dimensions: Int, val values: FloatArray)

class WakeCalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(deviceKey: String): WakeCalibrationProfile? {
        val prefix = prefix(deviceKey)
        if (!prefs.contains("${prefix}gain")) return null
        val templates = PersonalizedWakeMatcher.stableTemplates(
            decodeTemplates(prefs.getString("${prefix}templates", null))
        )
        val recalculatedThreshold = if (templates.size >= 3) {
            PersonalizedWakeMatcher.calibrationThreshold(templates)
        } else {
            0f
        }
        return WakeCalibrationProfile(
            gain = prefs.getFloat("${prefix}gain", 1f),
            noiseRms = prefs.getFloat("${prefix}noise", 0f),
            keywordThreshold = prefs.getFloat("${prefix}threshold", DEFAULT_THRESHOLD)
                .coerceAtMost(DEFAULT_THRESHOLD),
            calibratedAtMs = prefs.getLong("${prefix}at", 0L),
            templates = templates,
            templateThreshold = recalculatedThreshold
        )
    }

    fun save(deviceKey: String, profile: WakeCalibrationProfile) {
        val prefix = prefix(deviceKey)
        prefs.edit()
            .putFloat("${prefix}gain", profile.gain)
            .putFloat("${prefix}noise", profile.noiseRms)
            .putFloat("${prefix}threshold", profile.keywordThreshold)
            .putLong("${prefix}at", profile.calibratedAtMs)
            .putString("${prefix}templates", encodeTemplates(profile.templates))
            .putFloat("${prefix}template_threshold", profile.templateThreshold)
            .apply()
    }

    fun describe(deviceKey: String): String? = load(deviceKey)?.let {
        "已标定：增益 ${((it.gain * 10).roundToInt() / 10f)}× · " +
            "${it.templates.size}个声纹模板"
    }

    private fun encodeTemplates(templates: List<WakeTemplate>): String? {
        if (templates.isEmpty()) return null
        val bytes = templates.sumOf { 8 + it.values.size * 4 } + 4
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(templates.size)
        templates.forEach {
            buffer.putInt(it.frames)
            buffer.putInt(it.dimensions)
            it.values.forEach(buffer::putFloat)
        }
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    private fun decodeTemplates(encoded: String?): List<WakeTemplate> {
        if (encoded.isNullOrBlank()) return emptyList()
        return runCatching {
            val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
                .order(ByteOrder.LITTLE_ENDIAN)
            List(buffer.int.coerceIn(0, 20)) {
                val frames = buffer.int
                val dimensions = buffer.int
                require(frames in 1..400 && dimensions in 1..32)
                WakeTemplate(
                    frames,
                    dimensions,
                    FloatArray(frames * dimensions) { buffer.float }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun prefix(deviceKey: String): String =
        "device_${deviceKey.hashCode().toUInt().toString(16)}_"

    companion object {
        private const val PREFS = "wake_calibration_v1"
        const val DEFAULT_THRESHOLD = 0.12f
        val DEFAULT_PROFILE = WakeCalibrationProfile(
            gain = 3f,
            noiseRms = 0f,
            keywordThreshold = DEFAULT_THRESHOLD,
            calibratedAtMs = 0L,
            templates = emptyList(),
            templateThreshold = 0f
        )
    }
}
