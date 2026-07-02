package com.fwp.doubaonewline.bridge

import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import com.fwp.doubaonewline.v2.V2VoiceForegroundService
import com.fwp.doubaonewline.v3.V3VoiceForegroundService

/**
 * Stops every service owned by the versions that are not being entered.
 * Activities still release their client/recorder synchronously before navigation.
 */
object VersionSessionIsolation {
    fun enterV1(context: Context) {
        V2VoiceForegroundService.stop(context)
        V3VoiceForegroundService.stop(context)
    }

    fun enterV2(context: Context) {
        context.stopService(Intent(context, NewlineBridgeService::class.java))
        V3VoiceForegroundService.stop(context)
        DoubaoAccessibilityService.cancelCallStart()
    }

    fun enterV3(context: Context) {
        context.stopService(Intent(context, NewlineBridgeService::class.java))
        V2VoiceForegroundService.stop(context)
        DoubaoAccessibilityService.cancelCallStart()
    }
}
