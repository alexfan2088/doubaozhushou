package com.fwp.doubaonewline.v2

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

object PersonalizedWakeMatcher {
    private const val FRAME_SIZE = 400
    private const val FRAME_STEP = 160
    private const val DIMENSIONS = 12
    private const val SAMPLE_RATE = 16_000.0
    private const val MIN_FREQUENCY = 180.0
    private const val MAX_FREQUENCY = 3_600.0

    fun extract(samples: ShortArray): WakeTemplate? {
        if (samples.size < 4_000) return null
        val frames = 1 + (samples.size - FRAME_SIZE) / FRAME_STEP
        if (frames !in 8..400) return null
        val values = FloatArray(frames * DIMENSIONS)
        for (frame in 0 until frames) {
            val offset = frame * FRAME_STEP
            val energies = FloatArray(DIMENSIONS)
            for (band in 0 until DIMENSIONS) {
                val fraction = band.toDouble() / (DIMENSIONS - 1)
                val frequency = MIN_FREQUENCY *
                    Math.pow(MAX_FREQUENCY / MIN_FREQUENCY, fraction)
                energies[band] = ln(
                    goertzel(samples, offset, FRAME_SIZE, frequency).coerceAtLeast(1.0)
                ).toFloat()
            }
            val mean = energies.average().toFloat()
            var norm = 0.0
            for (band in energies.indices) {
                energies[band] -= mean
                norm += energies[band] * energies[band]
            }
            val scale = sqrt(norm).toFloat().coerceAtLeast(0.001f)
            for (band in energies.indices) {
                values[frame * DIMENSIONS + band] = energies[band] / scale
            }
        }
        return WakeTemplate(frames, DIMENSIONS, values)
    }

    fun distance(first: WakeTemplate, second: WakeTemplate): Float {
        if (first.dimensions != second.dimensions) return Float.MAX_VALUE
        val width = second.frames + 1
        var previous = FloatArray(width) { Float.POSITIVE_INFINITY }
        previous[0] = 0f
        for (i in 1..first.frames) {
            val current = FloatArray(width) { Float.POSITIVE_INFINITY }
            for (j in 1..second.frames) {
                val frameDistance = cosineDistance(first, i - 1, second, j - 1)
                current[j] = frameDistance + minOf(
                    previous[j],
                    current[j - 1],
                    previous[j - 1]
                )
            }
            previous = current
        }
        val pathLength = maxOf(first.frames, second.frames).toFloat()
        val durationRatio = maxOf(first.frames, second.frames).toFloat() /
            minOf(first.frames, second.frames).toFloat()
        return previous[second.frames] / pathLength + (durationRatio - 1f) * 0.12f
    }

    fun nearest(candidate: WakeTemplate, templates: List<WakeTemplate>): Float =
        templates.minOfOrNull { distance(candidate, it) } ?: Float.MAX_VALUE

    fun calibrationThreshold(templates: List<WakeTemplate>): Float {
        if (templates.size < 3) return 0f
        val nearest = templates.mapIndexed { index, template ->
            nearest(template, templates.filterIndexed { other, _ -> other != index })
        }.sorted()
        val representative = nearest[(nearest.size * 8 / 10).coerceAtMost(nearest.lastIndex)]
        return (representative * 1.35f + 0.03f).coerceIn(0.16f, 0.42f)
    }

    private fun cosineDistance(
        first: WakeTemplate,
        firstFrame: Int,
        second: WakeTemplate,
        secondFrame: Int
    ): Float {
        var dot = 0f
        for (dimension in 0 until first.dimensions) {
            dot += first.values[firstFrame * first.dimensions + dimension] *
                second.values[secondFrame * second.dimensions + dimension]
        }
        return (1f - dot.coerceIn(-1f, 1f)).coerceAtLeast(0f)
    }

    private fun goertzel(
        samples: ShortArray,
        offset: Int,
        count: Int,
        frequency: Double
    ): Double {
        val omega = 2.0 * PI * frequency / SAMPLE_RATE
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previous2 = 0.0
        for (index in 0 until count) {
            val window = 0.54 - 0.46 * cos(2.0 * PI * index / (count - 1))
            val current = samples[offset + index] * window +
                coefficient * previous - previous2
            previous2 = previous
            previous = current
        }
        return previous2 * previous2 + previous * previous -
            coefficient * previous * previous2
    }
}
