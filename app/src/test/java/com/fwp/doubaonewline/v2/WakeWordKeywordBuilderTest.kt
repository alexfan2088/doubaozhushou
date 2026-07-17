package com.fwp.doubaonewline.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordKeywordBuilderTest {
    @Test
    fun defaultWakeWordBuildsSherpaKeywordLine() {
        assertEquals(
            "d òu b āo d òu b āo :5.0 @豆包豆包",
            WakeWordKeywordBuilder.buildKeywordsLine("豆包豆包")
        )
    }

    @Test
    fun customWakeWordBuildsSherpaKeywordLine() {
        assertEquals(
            "n ǐ h ǎo d òu b āo :5.0 @你好豆包",
            WakeWordKeywordBuilder.buildKeywordsLine("你好豆包")
        )
    }

    @Test
    fun wakeWordValidationOnlyAllowsTwoToEightChineseCharacters() {
        assertTrue(WakeWordKeywordBuilder.isSupportedWakeWord("你好豆包"))
        assertFalse(WakeWordKeywordBuilder.isSupportedWakeWord("豆"))
        assertFalse(WakeWordKeywordBuilder.isSupportedWakeWord("hello"))
        assertFalse(WakeWordKeywordBuilder.isSupportedWakeWord("豆包123"))
    }
}
