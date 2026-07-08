package com.fwp.doubaonewline.bridge

import android.content.Context
import android.content.Intent
import com.fwp.doubaonewline.MainActivity
import com.fwp.doubaonewline.v2.V2Activity
import com.fwp.doubaonewline.v3.V3Activity

object VersionRouter {
    fun selectedMode(context: Context): String =
        context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
            .getString(BridgeContract.PREF_MODE, BridgeContract.MODE_V2)
            ?: BridgeContract.MODE_V2

    fun saveSelectedMode(context: Context, mode: String) {
        context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeContract.PREF_ENABLED, true)
            .putString(BridgeContract.PREF_MODE, mode)
            .apply()
        runCatching { AutoStartMonitorService.start(context) }
    }

    fun launchSelectedMode(context: Context) {
        runCatching { AutoStartMonitorService.start(context) }
        launchMode(context, selectedMode(context))
    }

    fun launchMode(context: Context, mode: String) {
        val activityClass = when (mode) {
            BridgeContract.MODE_V1 -> MainActivity::class.java
            BridgeContract.MODE_V3 -> V3Activity::class.java
            else -> V2Activity::class.java
        }
        context.startActivity(
            Intent(context, activityClass).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }
}
