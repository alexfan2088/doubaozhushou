package com.fwp.doubaonewline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(BridgeContract.PREF_ENABLED, false)
        val mode = prefs.getString(BridgeContract.PREF_MODE, BridgeContract.MODE_V2)
        if (enabled && mode == BridgeContract.MODE_V1) {
            runCatching { NewlineBridgeService.start(context) }
        }
    }
}
