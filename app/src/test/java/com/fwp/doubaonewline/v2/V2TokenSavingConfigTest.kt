package com.fwp.doubaonewline.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TokenSavingConfigTest {
    @Test
    fun defaultsEnableAllRecommendedTokenSavingLimits() {
        val config = V2TokenSavingConfig()

        assertEquals(20, config.offlineTtsGain)
        assertEquals(TtsEngineMode.SYSTEM, config.ttsEngineMode)
        assertTrue(config.localWelcomeEnabled)
        assertEquals(0, config.offlineTtsSpeakerId)
        assertEquals(2, config.maxResponseSentences)
        assertEquals(15, config.idleTimeoutSeconds)
        assertEquals(3, config.wakeCooldownSeconds)
        assertEquals(ContextRetentionMode.RESET_AFTER_LIMIT, config.contextRetentionMode)
        assertEquals(10, config.maxContextRounds)
        assertEquals(20, config.maxResponseSeconds)
    }

    @Test
    fun limitedPromptRequestsConciseAnswers() {
        val prompt = V2TokenSavingConfig(maxResponseSentences = 2).systemPrompt()

        assertTrue(prompt.contains("不超过2句话"))
        assertTrue(prompt.contains("不重复用户的问题"))
    }

    @Test
    fun unlimitedPromptDoesNotAddSentenceLimit() {
        val prompt = V2TokenSavingConfig(
            maxResponseSentences = V2TokenSavingConfig.UNLIMITED
        ).systemPrompt()

        assertFalse(prompt.contains("不超过"))
    }

    @Test
    fun unoptimizedPresetRemovesAllConfiguredLimits() {
        val config = V2TokenSavingConfig.unoptimized()

        assertFalse(config.localWelcomeEnabled)
        assertEquals(V2TokenSavingConfig.UNLIMITED, config.maxResponseSentences)
        assertEquals(ContextRetentionMode.KEEP, config.contextRetentionMode)
        assertEquals(V2TokenSavingConfig.UNLIMITED, config.maxResponseSeconds)
        assertEquals(60, config.idleTimeoutSeconds)
        assertEquals(1, config.wakeCooldownSeconds)
    }
}
