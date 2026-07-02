package com.fwp.doubaonewline.v3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V3ConfigTest {
    @Test
    fun defaultUsesFastModelAndAllowsBargeIn() {
        val config = V3Config()
        assertEquals(V3Model.DEEPSEEK_1_5B, config.selectedModel)
        assertTrue(config.allowBargeIn)
        assertEquals(20, config.ttsGain)
    }

    @Test
    fun recommends7BOnlyWhenAllResourceThresholdsPass() {
        val gib = 1024L * 1024 * 1024
        assertEquals(
            V3Model.DEEPSEEK_7B,
            V3RecommendationPolicy.recommend(12 * gib, 6 * gib, 10 * gib)
        )
        assertEquals(
            V3Model.DEEPSEEK_1_5B,
            V3RecommendationPolicy.recommend(12 * gib, 5 * gib, 20 * gib)
        )
        assertEquals(
            V3Model.DEEPSEEK_1_5B,
            V3RecommendationPolicy.recommend(16 * gib, 8 * gib, 9 * gib)
        )
    }
}
