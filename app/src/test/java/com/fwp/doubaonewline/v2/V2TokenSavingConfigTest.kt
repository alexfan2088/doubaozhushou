package com.fwp.doubaonewline.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2TokenSavingConfigTest {
    @Test
    fun defaultsEnableAllRecommendedTokenSavingLimits() {
        val config = V2TokenSavingConfig()
        assertEquals(20, config.offlineTtsGain)

        assertTrue(config.localWelcomeEnabled)
        assertTrue(config.offlineTtsSpeakerId == 0)
        assertTrue(config.maxResponseSentences == 2)
        assertTrue(config.idleTimeoutSeconds == 15)
        assertTrue(config.wakeCooldownSeconds == 3)
        assertTrue(config.contextRetentionMode == ContextRetentionMode.RESET_AFTER_LIMIT)
        assertTrue(config.maxContextRounds == 10)
        assertTrue(config.maxResponseSeconds == 20)
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
}
