package com.fwp.doubaonewline.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTextMatcherTest {
    @Test
    fun exactWakeWordMatches() {
        assertTrue(WakeWordTextMatcher.matches("豆包豆包", "豆包豆包").matched)
    }

    @Test
    fun pinyinSimilarityAllowsMissingAndRepeatedCharacters() {
        assertTrue(WakeWordTextMatcher.matches("豆包豆", "豆包豆包").matched)
        assertTrue(WakeWordTextMatcher.matches("豆包豆豆", "豆包豆包").matched)
        assertTrue(WakeWordTextMatcher.matches("豆包", "豆包豆包").matched)
    }

    @Test
    fun pinyinSimilarityAllowsToneFreeAndClosePinyin() {
        assertTrue(WakeWordTextMatcher.matches("daobao", "豆包").matched)
        assertTrue(WakeWordTextMatcher.matches("doudiao", "豆包").matched)
        assertTrue(WakeWordTextMatcher.matches("到包", "豆包").matched)
        assertTrue(WakeWordTextMatcher.matches("豆雕", "豆包").matched)
    }

    @Test
    fun pinyinSimilarityAllowsObservedBluetoothAsrErrors() {
        assertTrue(WakeWordTextMatcher.matches("宝宝八八", "doubaodoubao").matched)
        assertTrue(WakeWordTextMatcher.matches("得到吧走", "doubaodoubao").matched)
    }

    @Test
    fun unrelatedTextIsRejected() {
        assertFalse(WakeWordTextMatcher.matches("天气很好", "豆包豆包").matched)
        assertFalse(WakeWordTextMatcher.matches("怎他么怎", "doubaodoubao").matched)
        assertFalse(WakeWordTextMatcher.matches("天气很好", "doubaodoubao").matched)
    }

    @Test
    fun unsupportedWakeWordsAreRejected() {
        assertTrue(WakeWordTextMatcher.isSupportedWakeWord("你好豆包"))
        assertFalse(WakeWordTextMatcher.isSupportedWakeWord("豆"))
        assertFalse(WakeWordTextMatcher.isSupportedWakeWord("hello"))
        assertFalse(WakeWordTextMatcher.isSupportedWakeWord("豆包123"))
    }

    @Test
    fun punctuationAndFillersAreNormalized() {
        assertTrue(WakeWordTextMatcher.matches("请，豆包豆包一下！", "豆包豆包").matched)
    }
}
