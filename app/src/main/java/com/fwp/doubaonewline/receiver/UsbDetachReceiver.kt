package com.fwp.doubaonewline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class UsbDetachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val mode = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
            .getString(BridgeContract.PREF_MODE, BridgeContract.MODE_V2)
        if (mode != BridgeContract.MODE_V1) return
        runCatching {
            NewlineBridgeService.start(context, BridgeContract.ACTION_USB_DETACHED)
        }
    }
}
