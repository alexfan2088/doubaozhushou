package com.fwp.doubaonewline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class UsbDetachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DoubaoAccessibilityService.requestHangup()
        runCatching {
            NewlineBridgeService.start(context, BridgeContract.ACTION_USB_DETACHED)
        }
    }
}
