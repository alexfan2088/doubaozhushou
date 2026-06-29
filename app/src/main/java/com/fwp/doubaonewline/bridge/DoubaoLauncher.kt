package com.fwp.doubaonewline.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class DoubaoLauncher(private val context: Context) {

    fun launch(): Result<Unit> = runCatching {
        val prefs = context.getSharedPreferences(BridgeContract.PREFS, Context.MODE_PRIVATE)
        val configuredUri = prefs.getString(BridgeContract.PREF_DOUBAO_URI, null)

        val intent = if (!configuredUri.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(configuredUri)).apply {
                setPackage(BridgeContract.DOUBAO_PACKAGE)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(BridgeContract.DOUBAO_PACKAGE)
                ?: error("未安装豆包，包名：${BridgeContract.DOUBAO_PACKAGE}")
        }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        Log.i(TAG, "Launching Doubao; configuredUri=${!configuredUri.isNullOrBlank()}")
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "DoubaoLauncher"
    }
}
