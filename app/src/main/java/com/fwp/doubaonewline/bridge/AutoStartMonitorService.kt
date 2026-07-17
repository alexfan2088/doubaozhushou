package com.fwp.doubaonewline.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.AppLauncherActivity
import com.fwp.doubaonewline.R

class AutoStartMonitorService : Service() {
    private lateinit var audioMonitor: AudioDeviceMonitor
    private lateinit var audioRouteManager: AudioRouteManager
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var lastLaunchedRouteKey: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Connection broadcast received: ${intent?.action}")
            scheduleInspections()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        audioRouteManager = AudioRouteManager(this)
        audioMonitor = AudioDeviceMonitor(this) { scheduleInspections() }
        registerConnectionReceiver()
        audioMonitor.start()
        scheduleInspections()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        scheduleInspections()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (::audioMonitor.isInitialized) audioMonitor.stop()
        if (::audioRouteManager.isInitialized) audioRouteManager.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerConnectionReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun scheduleInspections() {
        handler.post { inspectAndLaunchIfReady() }
        handler.postDelayed({ inspectAndLaunchIfReady() }, 1_000L)
        handler.postDelayed({ inspectAndLaunchIfReady() }, 3_000L)
    }

    private fun inspectAndLaunchIfReady() {
        val prefs = getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(BridgeContract.PREF_ENABLED, false)) return

        val mode = VersionRouter.selectedMode(this)
        if (mode == BridgeContract.MODE_V1) {
            runCatching { NewlineBridgeService.start(this) }
                .onFailure { Log.w(TAG, "Unable to start V1 bridge", it) }
            return
        }

        val snapshot = audioMonitor.snapshot()
        val selection = audioRouteManager.select(snapshot)
        val routeKey = when (selection.kind) {
            AudioRouteManager.Kind.USB -> if (snapshot.ready) BridgeContract.ROUTE_USB else null
            AudioRouteManager.Kind.BLUETOOTH ->
                if (audioRouteManager.selectedBluetoothConnected()) {
                    "${BridgeContract.ROUTE_BLUETOOTH}:${audioRouteManager.selectedBluetoothKey().orEmpty()}"
                } else {
                    null
                }
            AudioRouteManager.Kind.NONE -> null
        }
        Log.i(TAG, "Inspection mode=$mode route=$routeKey selection=${selection.kind}")
        if (routeKey == null || routeKey == lastLaunchedRouteKey) return

        lastLaunchedRouteKey = routeKey
        VersionRouter.launchMode(this, mode)
        Log.i(TAG, "Requested launch for mode=$mode route=$routeKey")
    }

    private fun notification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AppLauncherActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb)
            .setContentTitle("豆包助手自动启动")
            .setContentText("正在监听已选择的蓝牙和 Type-C 设备")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动启动监控",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AutoStartMonitorService"
        private const val CHANNEL_ID = "auto_start_monitor"
        private const val NOTIFICATION_ID = 4004

        fun start(context: Context, trigger: Boolean = false) {
            val action = if (trigger) {
                BridgeContract.ACTION_MONITOR_TRIGGER
            } else {
                BridgeContract.ACTION_MONITOR_START
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutoStartMonitorService::class.java).setAction(action)
            )
        }
    }
}
