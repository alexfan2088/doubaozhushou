package com.fwp.doubaonewline.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fwp.doubaonewline.bridge.AutoStartMonitorService
import com.fwp.doubaonewline.bridge.BridgeContract

class ExternalTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        val prefs = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(BridgeContract.PREF_ENABLED, false)
        Log.i(TAG, "Received $action enabled=$enabled")
        if (!enabled) return

        runCatching { AutoStartMonitorService.start(context, trigger = true) }
            .onFailure { Log.w(TAG, "Unable to start monitor from $action", it) }
    }

    companion object {
        private const val TAG = "ExternalTriggerReceiver"
    }
}
