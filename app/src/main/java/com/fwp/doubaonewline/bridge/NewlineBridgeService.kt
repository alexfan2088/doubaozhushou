package com.fwp.doubaonewline.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fwp.doubaonewline.MainActivity
import com.fwp.doubaonewline.R
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService

class NewlineBridgeService : Service() {

    private lateinit var audioMonitor: AudioDeviceMonitor
    private var doubaoStartedForConnection = false
    private var usbAudioWasReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("等待连接 Newline"))

        audioMonitor = AudioDeviceMonitor(this, ::handleAudioSnapshot)
        audioMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BridgeContract.ACTION_USB_DETACHED -> {
                usbAudioWasReady = false
                doubaoStartedForConnection = false
                publish("设备已断开", usbDetails())
            }
            else -> inspectCurrentState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audioMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun inspectCurrentState() {
        val snapshot = audioMonitor.snapshot()
        handleAudioSnapshot(snapshot)
    }

    private fun handleAudioSnapshot(snapshot: AudioDeviceMonitor.Snapshot) {
        val usbManager = getSystemService(UsbManager::class.java)
        val usbDevices = usbManager.deviceList.values
        if (usbDevices.isEmpty()) {
            endDoubaoCallIfNeeded()
            doubaoStartedForConnection = false
            publish("等待连接 Newline", "当前未发现 USB 设备")
            return
        }
        val details = buildString {
            appendLine("USB：")
            usbDevices.forEach {
                appendLine(
                    "${it.productName ?: it.deviceName} " +
                        "VID=${it.vendorId} PID=${it.productId} class=${it.deviceClass}"
                )
            }
            appendLine("USB 音频输入：${snapshot.usbInputs.ifEmpty { listOf("未发现") }.joinToString()}")
            append("USB 音频输出：${snapshot.usbOutputs.ifEmpty { listOf("未发现") }.joinToString()}")
        }

        if (!snapshot.ready) {
            endDoubaoCallIfNeeded()
            doubaoStartedForConnection = false
            publish("已连接，等待音频设备", details)
            return
        }

        usbAudioWasReady = true
        publish("Newline 音频已就绪", details)
        if (!doubaoStartedForConnection) {
            doubaoStartedForConnection = true
            DoubaoAccessibilityService.requestCallStart()
            DoubaoLauncher(this).launch()
                .onSuccess { publish("豆包已启动，可以开始对话", details) }
                .onFailure {
                    doubaoStartedForConnection = false
                    Log.e(TAG, "Unable to launch Doubao", it)
                    publish("豆包启动失败", "$details\n原因：${it.message}")
                }
        }
    }

    private fun endDoubaoCallIfNeeded() {
        if (!usbAudioWasReady) return
        usbAudioWasReady = false
        Log.i(TAG, "USB audio disappeared; call hangup disabled in stable start-only mode")
    }

    private fun publish(status: String, detail: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))

        sendBroadcast(
            Intent(BridgeContract.ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(BridgeContract.EXTRA_STATUS, status)
                .putExtra(BridgeContract.EXTRA_DETAIL, detail)
        )
    }

    private fun usbDetails(): String {
        val devices = getSystemService(UsbManager::class.java).deviceList.values
        return if (devices.isEmpty()) "当前未发现 USB 设备"
        else devices.joinToString("\n") {
            "${it.productName ?: it.deviceName} VID=${it.vendorId} PID=${it.productId}"
        }
    }

    private fun notification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "NewlineBridgeService"
        private const val CHANNEL_ID = "newline_bridge"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, action: String = BridgeContract.ACTION_START) {
            val intent = Intent(context, NewlineBridgeService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
