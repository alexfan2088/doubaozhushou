package com.fwp.doubaonewline.v2

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.BuildConfig
import com.fwp.doubaonewline.MainActivity
import com.fwp.doubaonewline.R
import com.fwp.doubaonewline.automation.DoubaoAccessibilityService
import com.fwp.doubaonewline.bridge.AudioDeviceMonitor
import com.fwp.doubaonewline.bridge.AudioRouteManager
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.NewlineBridgeService
import java.text.NumberFormat
import java.util.UUID

class V2Activity : AppCompatActivity(), RealtimeVoiceListener {
    private val wakeWordMode: Boolean
        get() = intent?.getBooleanExtra(EXTRA_WAKE_WORD_MODE, false) == true

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var welcomeInput: EditText
    private lateinit var stopButton: Button
    private lateinit var totalTokensText: TextView
    private lateinit var todayTokensText: TextView
    private lateinit var sessionTokensText: TextView
    private lateinit var estimatedCostText: TextView
    private lateinit var selectedBluetoothText: TextView
    private lateinit var calibrateWakeButton: Button
    private lateinit var calibrationText: TextView

    private lateinit var client: VolcengineRealtimeVoiceClient
    private lateinit var coordinator: V2SessionCoordinator
    private lateinit var usageTracker: V2UsageTracker
    private lateinit var wakeWordDetector: OfflineWakeWordDetector
    private lateinit var audioMonitor: AudioDeviceMonitor
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var wakeCalibrationStore: WakeCalibrationStore
    private val handler = Handler(Looper.getMainLooper())
    private var startAfterPermission = false
    private var activeRouteKey: String? = null
    private var activeInputDevice: AudioDeviceInfo? = null
    private var activeWakeDeviceKey: String? = null
    private var activeRouteKind = AudioRouteManager.Kind.NONE
    private var activeRouteLabel = ""
    private var sessionTokens = 0L
    private var sessionInputAudioTokens = 0L
    private var sessionInputTextTokens = 0L
    private var sessionOutputAudioTokens = 0L
    private var sessionOutputTextTokens = 0L
    private var sessionStartedAtMs: Long? = null
    private var lastDurationCheckpointAtMs: Long? = null
    private var finishedSessionDurationMs = 0L
    private var connectionReceiverRegistered = false
    private var pendingBluetoothSelection = false
    private var userRequestedStop = false
    private var cloudSessionActive = false
    private var manuallyPaused = false
    private var calibratingWake = false

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleRouteChecks()
        }
    }

    private val periodicRouteCheck = object : Runnable {
        override fun run() {
            inspectAudioRoute()
            handler.postDelayed(this, ROUTE_CHECK_INTERVAL_MS)
        }
    }

    private val durationUiTick = object : Runnable {
        override fun run() {
            checkpointDuration()
            renderUsage(usageTracker.snapshot())
            renderSessionTokens()
            handler.postDelayed(this, DURATION_UI_INTERVAL_MS)
        }
    }

    private val reconnectSession = Runnable {
        if (
            !userRequestedStop &&
            activeRouteKey != null &&
            coordinator.state == RealtimeVoiceState.IDLE
        ) {
            startSession(resetSession = false, announceWelcome = false)
        }
    }

    private val enterLocalStandby = Runnable {
        if (
            !userRequestedStop &&
            activeRouteKey != null &&
            cloudSessionActive &&
            coordinator.state != RealtimeVoiceState.MODEL_SPEAKING &&
            coordinator.state != RealtimeVoiceState.USER_SPEAKING
        ) {
            cloudSessionActive = false
            coordinator.stop(DisconnectReason.IDLE_TIMEOUT)
            showActiveUi("等待“豆包豆包”唤醒", "云端已断开，本机离线监听，不消耗云端 Token。")
            handler.postDelayed({ startWakeWordStandby() }, AUDIO_HANDOFF_DELAY_MS)
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && startAfterPermission) {
            startAfterPermission = false
            startSession()
        } else {
            startAfterPermission = false
            showError("无法开始", "$versionLabel 实时语音需要麦克风权限。")
        }
    }

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingBluetoothSelection) {
            chooseBluetoothDevice()
        } else if (!granted) {
            showError("需要附近设备权限", "没有该权限无法选择蓝牙通话设备。")
        }
        pendingBluetoothSelection = false
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingBluetoothSelection) {
            chooseBluetoothDevice()
        }
        pendingBluetoothSelection = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v2)

        statusText = findViewById(R.id.v2StatusText)
        detailText = findViewById(R.id.v2DetailText)
        welcomeInput = findViewById(R.id.v2WelcomeInput)
        stopButton = findViewById(R.id.v2StopButton)
        totalTokensText = findViewById(R.id.v2TotalTokensText)
        todayTokensText = findViewById(R.id.v2TodayTokensText)
        sessionTokensText = findViewById(R.id.v2SessionTokensText)
        estimatedCostText = findViewById(R.id.v2EstimatedCostText)
        selectedBluetoothText = findViewById(R.id.v2SelectedBluetoothText)
        calibrateWakeButton = findViewById(R.id.v2CalibrateWakeButton)
        calibrationText = findViewById(R.id.v2CalibrationText)
        configureVersionUi()

        getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeContract.PREF_ENABLED, false)
            .putString(
                BridgeContract.PREF_MODE,
                if (wakeWordMode) BridgeContract.MODE_V3 else BridgeContract.MODE_V2
            )
            .apply()
        stopService(Intent(this, NewlineBridgeService::class.java))
        DoubaoAccessibilityService.cancelCallStart()
        client = VolcengineRealtimeVoiceClient(this)
        client.setListener(this)
        coordinator = V2SessionCoordinator(LocalTestCredentialProvider, client)
        usageTracker = V2UsageTracker(this)
        wakeWordDetector = OfflineWakeWordDetector(
            this,
            onDetected = { keyword ->
                runOnUiThread { handleWakeWord(keyword) }
            },
            onFailure = { error ->
                runOnUiThread {
                    if (calibratingWake) {
                        calibratingWake = false
                        userRequestedStop = false
                    }
                    showError("本地唤醒失败", error.message ?: error.javaClass.simpleName)
                }
            }
        )
        wakeCalibrationStore = WakeCalibrationStore(this)
        audioRouteManager = AudioRouteManager(this)
        audioMonitor = AudioDeviceMonitor(this) { inspectAudioRoute() }
        renderUsage(usageTracker.snapshot())
        renderSessionTokens()
        updateSelectedBluetoothText()

        findViewById<Button>(R.id.v2SelectBluetoothButton).setOnClickListener {
            chooseBluetoothDevice()
        }
        calibrateWakeButton.setOnClickListener { startWakeCalibration() }
        stopButton.setOnClickListener {
            if (manuallyPaused) resumeSession() else pauseSession()
        }
        findViewById<Button>(R.id.switchToV1Button).setOnClickListener {
            endSession()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.switchRealtimeModeButton).setOnClickListener {
            switchRealtimeMode()
        }
        registerConnectionReceiver()
        audioMonitor.start()
        handler.post(periodicRouteCheck)
        handler.post(durationUiTick)
    }

    private fun ensurePermissionAndStart() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startSession()
        } else {
            startAfterPermission = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSession(
        resetSession: Boolean = true,
        announceWelcome: Boolean = true
    ) {
        if (activeRouteKey == null) return
        handler.removeCallbacks(enterLocalStandby)
        wakeWordDetector.stop()
        userRequestedStop = false
        V2VoiceForegroundService.start(this)
        if (resetSession) {
            sessionTokens = 0L
            sessionInputAudioTokens = 0L
            sessionInputTextTokens = 0L
            sessionOutputAudioTokens = 0L
            sessionOutputTextTokens = 0L
            val startedAt = SystemClock.elapsedRealtime()
            sessionStartedAtMs = startedAt
            lastDurationCheckpointAtMs = startedAt
            finishedSessionDurationMs = 0L
        }
        renderSessionTokens()
        setConnectingUi()
        coordinator.start { credentials ->
            RealtimeVoiceConfig(
                credentials = credentials,
                userId = getOrCreateUserId(),
                systemPrompt =
                    "中文语音助手。简单问题简答；复杂问题只给必要步骤；" +
                        "不寒暄、不重复、不做无意义总结；用户要求时再详述。",
                welcomeText = if (announceWelcome) welcomeInput.text.toString() else "",
                inputFormat = PCM_16K_MONO,
                outputFormat = PCM_24K_MONO
            )
        }.onFailure {
            cloudSessionActive = false
            showError("$versionLabel 连接失败", it.message ?: it.javaClass.simpleName)
            if (wakeWordMode) {
                handler.postDelayed({ startWakeWordStandby() }, AUDIO_HANDOFF_DELAY_MS)
            } else if (!userRequestedStop && activeRouteKey != null) {
                handler.postDelayed(reconnectSession, RECONNECT_DELAY_MS)
            }
        }
    }

    private fun scheduleLocalStandby() {
        if (!wakeWordMode) return
        handler.removeCallbacks(enterLocalStandby)
        if (!userRequestedStop && cloudSessionActive) {
            handler.postDelayed(enterLocalStandby, CLOUD_IDLE_TIMEOUT_MS)
        }
    }

    private fun startWakeWordStandby() {
        if (!wakeWordMode) return
        val input = activeInputDevice
        val deviceKey = activeWakeDeviceKey
        if (
            userRequestedStop || activeRouteKey == null || cloudSessionActive ||
            input == null || deviceKey == null
        ) return
        val profile = wakeCalibrationStore.load(deviceKey)
            ?: WakeCalibrationStore.DEFAULT_PROFILE
        if (!wakeWordDetector.start(input, profile)) {
            showError("本地唤醒不可用", "缺少麦克风权限，无法监听“豆包豆包”。")
        }
    }

    private fun startWakeCalibration() {
        val input = activeInputDevice
        val deviceKey = activeWakeDeviceKey
        if (input == null || deviceKey == null) {
            showWaitingForDevice()
            return
        }
        calibratingWake = true
        userRequestedStop = true
        handler.removeCallbacks(reconnectSession)
        handler.removeCallbacks(enterLocalStandby)
        wakeWordDetector.stop()
        cloudSessionActive = false
        coordinator.stop(DisconnectReason.USER_REQUEST)
        showActiveUi(
            "准备唤醒标定",
            "请先保持安静2秒，然后在平时距离用正常音量说10次“豆包豆包”，每次间隔1秒。"
        )
        calibrateWakeButton.isEnabled = false
        handler.postDelayed({
            val started = wakeWordDetector.calibrate(
                input,
                onReady = {
                    runOnUiThread {
                        playCalibrationTone()
                        statusText.text = "正在标定：0/10"
                        detailText.text = "听到提示音后，请说第1次“豆包豆包”。"
                    }
                },
                onProgress = { count ->
                    runOnUiThread {
                        statusText.text = "正在标定：$count/10"
                        detailText.text = "继续用正常音量说“豆包豆包”，每次间隔1秒。"
                    }
                },
                onComplete = { profile ->
                    runOnUiThread {
                        wakeCalibrationStore.save(deviceKey, profile)
                        calibratingWake = false
                        userRequestedStop = false
                        calibrateWakeButton.isEnabled = true
                        updateCalibrationText()
                        showActiveUi(
                            "唤醒标定完成",
                            "正在通过外接麦克风等待“豆包豆包”，不会使用手机麦克风。"
                        )
                        handler.postDelayed(
                            { startWakeWordStandby() },
                            AUDIO_HANDOFF_DELAY_MS
                        )
                    }
                }
            )
            if (!started) {
                calibratingWake = false
                userRequestedStop = false
                calibrateWakeButton.isEnabled = true
                showError("无法开始标定", "外接麦克风不可用或录音权限未开启。")
            }
        }, AUDIO_HANDOFF_DELAY_MS)
    }

    private fun updateCalibrationText() {
        val key = activeWakeDeviceKey
        calibrationText.text = when {
            key == null -> "连接外接麦克风后可标定"
            else -> wakeCalibrationStore.describe(key) ?: "当前设备尚未标定，将使用通用参数"
        }
        calibrateWakeButton.isEnabled = key != null && !calibratingWake
    }

    private fun playCalibrationTone() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 220)
        handler.postDelayed({ tone.release() }, 350L)
    }

    private fun handleWakeWord(keyword: String) {
        if (!wakeWordMode || userRequestedStop || activeRouteKey == null || cloudSessionActive) return
        showActiveUi("已听到“$keyword”", "正在重新连接豆包，请听到提示后说话。")
        playWakeTone()
        handler.postDelayed(
            { startSession(resetSession = false, announceWelcome = false) },
            WAKE_CONNECT_DELAY_MS
        )
    }

    private fun playWakeTone() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        handler.postDelayed({ tone.release() }, 300L)
    }

    private fun chooseBluetoothDevice() {
        if (!hasBluetoothPermission()) {
            pendingBluetoothSelection = true
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (!isBluetoothEnabled()) {
            pendingBluetoothSelection = true
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        pendingBluetoothSelection = false

        val candidates = audioRouteManager.bluetoothCandidates()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有可用的蓝牙设备")
                .setMessage("请先在系统蓝牙设置中配对蓝牙通话设备。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val selectedKey = audioRouteManager.selectedBluetoothKey()
        val ordered = candidates.sortedByDescending { it.key == selectedKey }
        val labels = ordered.map {
            if (it.key == selectedKey) selectedBluetoothListLabel(it.displayLabel) else it.displayLabel
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择蓝牙通话设备")
            .setItems(labels) { _, index ->
                audioRouteManager.saveBluetoothCandidate(ordered[index])
                updateSelectedBluetoothText()
                activeRouteKey = null
                scheduleRouteChecks()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedBluetoothListLabel(deviceLabel: String): CharSequence {
        val selectedText = "  已选择"
        return SpannableString(deviceLabel + selectedText).apply {
            setSpan(
                ForegroundColorSpan(Color.rgb(21, 101, 192)),
                0,
                deviceLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                deviceLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(Color.rgb(211, 47, 47)),
                deviceLabel.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                deviceLabel.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updateSelectedBluetoothText() {
        selectedBluetoothText.text = audioRouteManager.selectedBluetoothName()?.let {
            "已选择：$it"
        } ?: "尚未选择蓝牙设备"
    }

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean =
        getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    private fun pauseSession() {
        manuallyPaused = true
        userRequestedStop = true
        handler.removeCallbacks(reconnectSession)
        handler.removeCallbacks(enterLocalStandby)
        wakeWordDetector.stop()
        cloudSessionActive = false
        coordinator.stop(DisconnectReason.USER_REQUEST)
        V2VoiceForegroundService.stop(this)
        showPausedUi()
    }

    private fun resumeSession() {
        if (activeRouteKey == null) {
            showWaitingForDevice()
            return
        }
        manuallyPaused = false
        userRequestedStop = false
        startSession(resetSession = false, announceWelcome = false)
    }

    private fun switchRealtimeMode() {
        val targetWakeWordMode = !wakeWordMode
        endSession()
        startActivity(createIntent(this, targetWakeWordMode))
        finish()
    }

    private fun configureVersionUi() {
        val title = findViewById<TextView>(R.id.v2TitleText)
        val subtitle = findViewById<TextView>(R.id.v2SubtitleText)
        val statusLabel = findViewById<TextView>(R.id.v2StatusLabelText)
        val switchButton = findViewById<Button>(R.id.switchRealtimeModeButton)
        title.text = if (wakeWordMode) "豆包助手 V3" else "豆包助手 V2"
        subtitle.text = if (wakeWordMode) {
            "本地“豆包豆包”唤醒 · 端到端实时语音"
        } else {
            "无需唤醒词 · 直接使用端到端实时语音"
        }
        statusLabel.text = if (wakeWordMode) "V3 会话状态" else "V2 会话状态"
        switchButton.text = if (wakeWordMode) "V2 大模型版" else "V3 唤醒词版"
        calibrateWakeButton.visibility = if (wakeWordMode) View.VISIBLE else View.GONE
        calibrationText.visibility = if (wakeWordMode) View.VISIBLE else View.GONE
    }

    private val versionLabel: String
        get() = if (wakeWordMode) "V3" else "V2"

    private fun endSession() {
        manuallyPaused = false
        userRequestedStop = true
        handler.removeCallbacks(reconnectSession)
        handler.removeCallbacks(enterLocalStandby)
        wakeWordDetector.stop()
        cloudSessionActive = false
        finishSessionDuration()
        coordinator.stop(DisconnectReason.USER_REQUEST)
        V2VoiceForegroundService.stop(this)
    }

    private fun inspectAudioRoute() {
        if (!::audioMonitor.isInitialized || !::audioRouteManager.isInitialized) return
        val snapshot = audioMonitor.snapshot()
        val selection = audioRouteManager.select(snapshot)
        val routeKey = when (selection.kind) {
            AudioRouteManager.Kind.USB ->
                if (snapshot.ready && selection.routeAccepted && selection.inputDevice != null) {
                    "usb:${selection.deviceKey.orEmpty()}"
                } else {
                    null
                }
            AudioRouteManager.Kind.BLUETOOTH ->
                if (
                    selection.routeAccepted &&
                    selection.inputDevice != null
                ) {
                    "bluetooth:${selection.deviceKey.orEmpty()}"
                } else {
                    null
                }
            AudioRouteManager.Kind.NONE -> null
        }

        if (routeKey == null) {
            handler.removeCallbacks(reconnectSession)
            handler.removeCallbacks(enterLocalStandby)
            wakeWordDetector.stop()
            cloudSessionActive = false
            V2VoiceForegroundService.stop(this)
            val hadRoute = activeRouteKey != null
            activeRouteKey = null
            activeInputDevice = null
            activeWakeDeviceKey = null
            activeRouteKind = AudioRouteManager.Kind.NONE
            activeRouteLabel = ""
            updateCalibrationText()
            if (hadRoute && coordinator.state != RealtimeVoiceState.IDLE) {
                finishSessionDuration()
                coordinator.stop(DisconnectReason.AUDIO_DEVICE_LOST)
            }
            showWaitingForDevice()
            return
        }

        if (routeKey != activeRouteKey) {
            wakeWordDetector.stop()
            handler.removeCallbacks(enterLocalStandby)
            cloudSessionActive = false
            if (coordinator.state != RealtimeVoiceState.IDLE) {
                finishSessionDuration()
                coordinator.stop(DisconnectReason.AUDIO_DEVICE_LOST)
            }
            activeRouteKey = routeKey
            activeInputDevice = selection.inputDevice
            activeWakeDeviceKey = selection.deviceKey
            activeRouteKind = selection.kind
            activeRouteLabel = selection.label.substringBefore("（")
            updateCalibrationText()
            statusText.text = "检测到${selection.label}"
            if (manuallyPaused) {
                showPausedUi()
            } else {
                detailText.text = "正在自动启动 $versionLabel 实时语音…"
                if (selection.kind == AudioRouteManager.Kind.BLUETOOTH) {
                    handler.postDelayed(
                        {
                            if (
                                activeRouteKey == routeKey &&
                                !manuallyPaused &&
                                coordinator.state == RealtimeVoiceState.IDLE
                            ) {
                                ensurePermissionAndStart()
                            }
                        },
                        BLUETOOTH_ROUTE_SETTLE_MS
                    )
                } else {
                    ensurePermissionAndStart()
                }
            }
        }
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

    private fun scheduleRouteChecks() {
        handler.post { inspectAudioRoute() }
        handler.postDelayed({ inspectAudioRoute() }, 1_000L)
        handler.postDelayed({ inspectAudioRoute() }, 3_000L)
    }

    override fun onEvent(event: RealtimeVoiceEvent) {
        runOnUiThread {
            when (event) {
                RealtimeVoiceEvent.Connected -> {
                    cloudSessionActive = true
                    if (sessionStartedAtMs == null) {
                        val now = SystemClock.elapsedRealtime()
                        sessionStartedAtMs = now
                        lastDurationCheckpointAtMs = now
                    }
                    val connectionDetail = when (activeRouteKind) {
                        AudioRouteManager.Kind.BLUETOOTH ->
                            "$versionLabel 已蓝牙连接 $activeRouteLabel，可以直接说话。"
                        AudioRouteManager.Kind.USB ->
                            "$versionLabel 已通过数据线连接，可以直接说话。"
                        AudioRouteManager.Kind.NONE ->
                            "$versionLabel 已连接，可以直接说话。"
                    }
                    showActiveUi("正在聆听", connectionDetail)
                    scheduleLocalStandby()
                }
                RealtimeVoiceEvent.UserSpeechStarted -> {
                    handler.removeCallbacks(enterLocalStandby)
                    showActiveUi("正在听你说话", "检测到用户语音。")
                }
                RealtimeVoiceEvent.UserSpeechEnded -> {
                    handler.removeCallbacks(enterLocalStandby)
                    showActiveUi("正在思考", "语音输入结束，等待模型回答。")
                }
                RealtimeVoiceEvent.ModelResponseStarted -> {
                    handler.removeCallbacks(enterLocalStandby)
                    showActiveUi("豆包正在回答", "直接开口即可打断当前回答。")
                }
                RealtimeVoiceEvent.ModelResponseEnded -> {
                    showActiveUi("正在聆听", "回答结束，可以继续说话。")
                    scheduleLocalStandby()
                }
                is RealtimeVoiceEvent.Failure -> {
                    cloudSessionActive = false
                    handler.removeCallbacks(enterLocalStandby)
                    coordinator.stop(DisconnectReason.SERVICE_ERROR)
                    if (event.retryable && !userRequestedStop && activeRouteKey != null) {
                        statusText.text = "连接中断，正在恢复"
                        detailText.text = "${event.code}: ${event.message}"
                        handler.removeCallbacks(reconnectSession)
                        handler.postDelayed(reconnectSession, RECONNECT_DELAY_MS)
                    } else {
                        finishSessionDuration()
                        V2VoiceForegroundService.stop(this)
                        showError("$versionLabel 服务错误", "${event.code}: ${event.message}")
                    }
                }
                is RealtimeVoiceEvent.ModelAudio -> Unit
                is RealtimeVoiceEvent.Usage -> {
                    val added = event.totalUnits.coerceAtLeast(0)
                    sessionTokens += added
                    sessionInputAudioTokens += event.inputAudioTokens.coerceAtLeast(0)
                    sessionInputTextTokens += event.inputTextTokens.coerceAtLeast(0)
                    sessionOutputAudioTokens += event.outputAudioTokens.coerceAtLeast(0)
                    sessionOutputTextTokens += event.outputTextTokens.coerceAtLeast(0)
                    renderSessionTokens()
                    renderUsage(usageTracker.add(event))
                }
            }
        }
    }

    private fun setConnectingUi() {
        statusText.text = "正在连接 $versionLabel"
        detailText.text = "正在初始化端到端实时语音 SDK…"
        stopButton.text = "暂停语音会话"
        stopButton.isEnabled = true
    }

    private fun showActiveUi(status: String, detail: String) {
        statusText.text = status
        detailText.text = detail
        stopButton.text = "暂停语音会话"
        stopButton.isEnabled = true
    }

    private fun showPausedUi() {
        statusText.text = "语音会话已暂停"
        detailText.text = "点击“恢复语音会话”后继续工作。"
        stopButton.text = "恢复语音会话"
        stopButton.isEnabled = activeRouteKey != null
    }

    private fun showError(status: String, detail: String) {
        statusText.text = status
        detailText.text = detail
        stopButton.isEnabled = coordinator.state != RealtimeVoiceState.IDLE
    }

    private fun showWaitingForDevice() {
        statusText.text = "等待设备连接"
        detailText.text =
            "连接 Type-C 或已选择的蓝牙通话设备后，将自动开始 $versionLabel 实时语音。"
        stopButton.text = if (manuallyPaused) "恢复语音会话" else "暂停语音会话"
        stopButton.isEnabled = false
    }

    private fun getOrCreateUserId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getString(KEY_USER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_USER_ID, it).apply()
        }
    }

    private fun renderUsage(snapshot: V2UsageSnapshot) {
        val formatter = NumberFormat.getIntegerInstance()
        val activeDuration = uncheckpointedDurationMs()
        totalTokensText.text =
            "本月累计：${formatter.format(snapshot.monthTokens)} Token · " +
                "${formatMinutes(snapshot.monthDurationMs + activeDuration)} 分钟"
        todayTokensText.text =
            "今日累计：${formatter.format(snapshot.todayTokens)} Token · " +
                "${formatMinutes(snapshot.todayDurationMs + activeDuration)} 分钟"
        val price = BuildConfig.V2_PRICE_PER_MILLION_TOKENS
        estimatedCostText.text = if (price > 0.0) {
            val totalCost = snapshot.monthTokens * price / 1_000_000.0
            val todayCost = snapshot.todayTokens * price / 1_000_000.0
            "估算金额：本月 ¥%.4f · 今日 ¥%.4f".format(totalCost, todayCost)
        } else {
            "估算金额：未配置每百万 Token 单价"
        }
    }

    private fun renderSessionTokens() {
        val formatter = NumberFormat.getIntegerInstance()
        sessionTokensText.text = buildString {
            append("本轮对话：${formatter.format(sessionTokens)} Token · ")
            append("${formatMinutes(currentSessionDurationMs())} 分钟\n")
            append(
                "入音 ${formatter.format(sessionInputAudioTokens)} · " +
                    "入文 ${formatter.format(sessionInputTextTokens)} · " +
                    "出音 ${formatter.format(sessionOutputAudioTokens)} · " +
                    "出文 ${formatter.format(sessionOutputTextTokens)}"
            )
        }
    }

    private fun currentSessionDurationMs(): Long =
        sessionStartedAtMs?.let { startedAt ->
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        } ?: finishedSessionDurationMs

    private fun uncheckpointedDurationMs(): Long =
        lastDurationCheckpointAtMs?.let { checkpoint ->
            (SystemClock.elapsedRealtime() - checkpoint).coerceAtLeast(0L)
        } ?: 0L

    private fun checkpointDuration(force: Boolean = false) {
        val checkpoint = lastDurationCheckpointAtMs ?: return
        val now = SystemClock.elapsedRealtime()
        val delta = (now - checkpoint).coerceAtLeast(0L)
        if (!force && delta < DURATION_CHECKPOINT_INTERVAL_MS) return
        if (delta > 0L) {
            usageTracker.addDuration(delta)
        }
        lastDurationCheckpointAtMs = now
    }

    private fun finishSessionDuration() {
        val duration = currentSessionDurationMs()
        checkpointDuration(force = true)
        finishedSessionDurationMs = duration
        sessionStartedAtMs = null
        lastDurationCheckpointAtMs = null
        renderUsage(usageTracker.snapshot())
        renderSessionTokens()
    }

    override fun onDestroy() {
        userRequestedStop = true
        handler.removeCallbacksAndMessages(null)
        if (::wakeWordDetector.isInitialized) {
            wakeWordDetector.stop()
        }
        V2VoiceForegroundService.stop(this)
        if (connectionReceiverRegistered) {
            unregisterReceiver(connectionReceiver)
            connectionReceiverRegistered = false
        }
        if (::audioMonitor.isInitialized) {
            audioMonitor.stop()
        }
        if (::audioRouteManager.isInitialized) {
            audioRouteManager.clear()
        }
        if (::coordinator.isInitialized) {
            finishSessionDuration()
            coordinator.stop(DisconnectReason.APP_SHUTDOWN)
        }
        if (::client.isInitialized) {
            client.setListener(null)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_WAKE_WORD_MODE = "wake_word_mode"
        private const val PREFS = "v2_voice"
        private const val KEY_USER_ID = "user_id"
        private const val ROUTE_CHECK_INTERVAL_MS = 2_000L
        private const val DURATION_UI_INTERVAL_MS = 1_000L
        private const val DURATION_CHECKPOINT_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val CLOUD_IDLE_TIMEOUT_MS = 30_000L
        private const val AUDIO_HANDOFF_DELAY_MS = 600L
        private const val BLUETOOTH_ROUTE_SETTLE_MS = 2_000L
        private const val WAKE_CONNECT_DELAY_MS = 350L
        private val PCM_16K_MONO = AudioFormatSpec(16_000, 1, 16, "pcm")
        private val PCM_24K_MONO = AudioFormatSpec(24_000, 1, 16, "pcm")

        fun createIntent(context: Context, wakeWordMode: Boolean): Intent =
            Intent(context, V2Activity::class.java)
                .putExtra(EXTRA_WAKE_WORD_MODE, wakeWordMode)

        private fun formatMinutes(durationMs: Long): String =
            "%.1f".format(durationMs / 60_000.0)
    }
}
