package com.fwp.doubaonewline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.NewlineBridgeService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val enabled = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
            .getBoolean(BridgeContract.PREF_ENABLED, false)
        if (enabled) {
            runCatching { NewlineBridgeService.start(context) }
        }
    }
}
