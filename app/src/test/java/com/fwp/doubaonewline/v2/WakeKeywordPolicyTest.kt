package com.fwp.doubaonewline.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeKeywordPolicyTest {
    @Test
    fun lowSensitivityOnlyAcceptsExactKeyword() {
        assertTrue(WakeKeywordPolicy.accepts("豆包豆包", 0.20f))
        assertFalse(WakeKeywordPolicy.accepts("豆包豆包A", 0.20f))
        assertFalse(WakeKeywordPolicy.accepts("豆包豆包D", 0.28f))
    }

    @Test
    fun balancedAndHighSensitivityRetainPermissiveKeywords() {
        assertTrue(WakeKeywordPolicy.accepts("豆包豆包A", 0.12f))
        assertTrue(WakeKeywordPolicy.accepts("豆包豆包E", 0.045f))
    }
}
