package com.fwp.doubaonewline.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.MainActivity
import com.fwp.doubaonewline.R
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import java.util.Locale

class NewlineBridgeService : Service(), TextToSpeech.OnInitListener {

    private lateinit var audioMonitor: AudioDeviceMonitor
    private lateinit var audioRouteManager: AudioRouteManager
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var launchedRouteKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var connectionReceiverRegistered = false
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Connection event: ${intent?.action}")
            scheduleInspections()
        }
    }
    private val periodicInspection = object : Runnable {
        override fun run() {
            inspectCurrentState()
            handler.postDelayed(this, INSPECTION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("等待连接"))

        audioRouteManager = AudioRouteManager(this)
        textToSpeech = TextToSpeech(this, this)
        audioMonitor = AudioDeviceMonitor(this, ::handleAudioSnapshot)
        registerConnectionReceiver()
        audioMonitor.start()
        handler.post(periodicInspection)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            BridgeContract.ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            BridgeContract.ACTION_USB_DETACHED -> {
                inspectCurrentState()
            }
            else -> inspectCurrentState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        DoubaoAccessibilityService.cancelCallStart()
        if (connectionReceiverRegistered) {
            unregisterReceiver(connectionReceiver)
            connectionReceiverRegistered = false
        }
        audioMonitor.stop()
        audioRouteManager.clear()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        val tts = textToSpeech ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech initialization failed: $status")
            return
        }
        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        textToSpeechReady =
            languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        if (!textToSpeechReady) {
            Log.w(TAG, "Simplified Chinese text-to-speech is unavailable")
        }
    }

    private fun inspectCurrentState() {
        val snapshot = audioMonitor.snapshot()
        handleAudioSnapshot(snapshot)
    }

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
            connectionReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        connectionReceiverRegistered = true
    }

    private fun scheduleInspections() {
        handler.post { inspectCurrentState() }
        handler.postDelayed({ inspectCurrentState() }, 1_000L)
        handler.postDelayed({ inspectCurrentState() }, 3_000L)
    }

    private fun handleAudioSnapshot(snapshot: AudioDeviceMonitor.Snapshot) {
        val selection = audioRouteManager.select(snapshot)
        val selectedBluetoothConnected = audioRouteManager.selectedBluetoothConnected()
        val routeKey = when (selection.kind) {
            AudioRouteManager.Kind.USB -> "usb"
            AudioRouteManager.Kind.BLUETOOTH -> {
                if (selectedBluetoothConnected) {
                    "bluetooth:${audioRouteManager.selectedBluetoothKey().orEmpty()}"
                } else {
                    null
                }
            }
            AudioRouteManager.Kind.NONE -> null
        }
        if (routeKey == null) {
            launchedRouteKey = null
            DoubaoAccessibilityService.cancelCallStart()
            publish(
                "等待设备连接",
                "连接 Type-C 或当前选择的蓝牙设备后，将自动启动 V1 豆包。"
            )
            return
        }

        val connectionStatus = when (selection.kind) {
            AudioRouteManager.Kind.USB -> "V1 已通过数据线连接"
            AudioRouteManager.Kind.BLUETOOTH -> {
                val name = audioRouteManager.selectedBluetoothName()
                    ?.substringBefore("（") ?: selection.label.substringBefore("（")
                "V1 已蓝牙连接 $name"
            }
            AudioRouteManager.Kind.NONE -> "等待连接"
        }
        val pickupDetail = when (selection.kind) {
            AudioRouteManager.Kind.USB ->
                if (selection.routeAccepted) {
                    "当前优先使用 Type-C 设备拾音。"
                } else {
                    "Type-C 设备无可用拾音，使用手机麦克风。"
                }
            AudioRouteManager.Kind.BLUETOOTH ->
                if (selection.routeAccepted) {
                    "当前优先使用蓝牙 HFP 麦克风。"
                } else {
                    "蓝牙设备无 HFP 拾音，使用手机麦克风。"
                }
            AudioRouteManager.Kind.NONE -> ""
        }
        val connectionDetails = pickupDetail
        publish(connectionStatus, connectionDetails)
        if (launchedRouteKey != routeKey) {
            launchedRouteKey = routeKey
            DoubaoAccessibilityService.requestCallStart()
            DoubaoLauncher(this).launch()
                .onSuccess {
                    publish(connectionStatus, connectionDetails)
                    scheduleReadyGreeting(routeKey)
                }
                .onFailure {
                    launchedRouteKey = null
                    DoubaoAccessibilityService.cancelCallStart()
                    Log.e(TAG, "Unable to launch Doubao", it)
                    publish("豆包启动失败", "$connectionDetails\n原因：${it.message}")
                }
        }
    }

    private fun scheduleReadyGreeting(routeKey: String) {
        handler.postDelayed(
            {
                if (launchedRouteKey != routeKey) return@postDelayed
                if (!textToSpeechReady) {
                    Log.w(TAG, "Ready greeting skipped because text-to-speech is not ready")
                    return@postDelayed
                }
                val greeting = BridgeContract.normalizeReadyGreeting(
                    getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
                        .getString(BridgeContract.PREF_READY_GREETING, null)
                )
                textToSpeech?.speak(
                    greeting,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    READY_GREETING_ID
                )
            },
            READY_GREETING_DELAY_MS
        )
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
        private const val INSPECTION_INTERVAL_MS = 2_000L
        private const val READY_GREETING_DELAY_MS = 6_000L
        private const val READY_GREETING_ID = "doubao_ready_greeting"

        fun start(context: Context, action: String = BridgeContract.ACTION_START) {
            val intent = Intent(context, NewlineBridgeService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stopSafely(context: Context) {
            val intent = Intent(context, NewlineBridgeService::class.java)
                .setAction(BridgeContract.ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(intent) }
        }
    }
}
