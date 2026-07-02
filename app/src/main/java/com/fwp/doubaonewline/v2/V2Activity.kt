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
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var welcomeInput: EditText
    private lateinit var stopButton: Button
    private lateinit var usageTable: LinearLayout
    private lateinit var selectedBluetoothText: TextView
    private lateinit var localWakeCheck: CheckBox
    private lateinit var wakeSensitivityText: TextView
    private lateinit var wakeSensitivitySeek: SeekBar

    private lateinit var client: VolcengineRealtimeVoiceClient
    private lateinit var coordinator: V2SessionCoordinator
    private lateinit var usageTracker: V2UsageTracker
    private lateinit var audioMonitor: AudioDeviceMonitor
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var wakeWordDetector: OfflineWakeWordDetector
    private val handler = Handler(Looper.getMainLooper())
    private var startAfterPermission = false
    private var activeRouteKey: String? = null
    private var sessionTokens = 0L
    private var sessionStartedAtMs: Long? = null
    private var lastDurationCheckpointAtMs: Long? = null
    private var finishedSessionDurationMs = 0L
    private var connectionReceiverRegistered = false
    private var pendingBluetoothSelection = false
    private var userRequestedStop = false
    private var manuallyPaused = false
    private var sessionInputTextTokens = 0L
    private var sessionInputAudioTokens = 0L
    private var sessionOutputTextTokens = 0L
    private var sessionOutputAudioTokens = 0L
    private var activeConnectionDetail = ""
    private var activeWakeInput: AudioDeviceInfo? = null
    private var cloudSessionActive = false

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

    private val reconnectSession: Runnable = Runnable {
        tryReconnectSession()
    }

    private fun tryReconnectSession() {
        if (
            !userRequestedStop &&
            activeRouteKey != null &&
            !manuallyPaused
        ) {
            if (isPhoneCallActive()) {
                statusText.text = "通话中"
                detailText.text = "电话结束后将自动恢复 V2 语音会话。"
                handler.postDelayed(reconnectSession, RECONNECT_DELAY_MS)
            } else {
                if (coordinator.state != RealtimeVoiceState.IDLE) {
                    coordinator.stop(DisconnectReason.SERVICE_ERROR)
                }
                startSession(resetSession = false, announceWelcome = false)
            }
        }
    }

    private val enterLocalWakeStandby: Runnable = Runnable {
        tryEnterLocalWakeStandby()
    }

    private fun tryEnterLocalWakeStandby() {
        if (
            localWakeCheck.isChecked &&
            !manuallyPaused &&
            !userRequestedStop &&
            activeRouteKey != null &&
            cloudSessionActive
        ) {
            if (
                client.state == RealtimeVoiceState.MODEL_SPEAKING ||
                client.state == RealtimeVoiceState.USER_SPEAKING
            ) {
                handler.postDelayed(enterLocalWakeStandby, BUSY_RECHECK_DELAY_MS)
                return
            }
            cloudSessionActive = false
            coordinator.stop(DisconnectReason.IDLE_TIMEOUT)
            statusText.text = "请用豆包豆包唤醒"
            detailText.text = "$activeConnectionDetail\n本地监听中，不消耗云端 Token。"
            handler.postDelayed({ startLocalWakeListening() }, AUDIO_HANDOFF_DELAY_MS)
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
            showError("无法开始", "V2 实时语音需要麦克风权限。")
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
        usageTable = findViewById(R.id.v2UsageTable)
        selectedBluetoothText = findViewById(R.id.v2SelectedBluetoothText)
        localWakeCheck = findViewById(R.id.v2LocalWakeCheck)
        wakeSensitivityText = findViewById(R.id.v2WakeSensitivityText)
        wakeSensitivitySeek = findViewById(R.id.v2WakeSensitivitySeek)

        getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeContract.PREF_ENABLED, false)
            .putString(BridgeContract.PREF_MODE, BridgeContract.MODE_V2)
            .apply()
        stopService(Intent(this, NewlineBridgeService::class.java))
        DoubaoAccessibilityService.cancelCallStart()
        client = VolcengineRealtimeVoiceClient(this)
        client.setListener(this)
        coordinator = V2SessionCoordinator(LocalTestCredentialProvider, client)
        usageTracker = V2UsageTracker(this)
        audioRouteManager = AudioRouteManager(this)
        wakeWordDetector = OfflineWakeWordDetector(
            this,
            onDetected = {
                runOnUiThread {
                    if (
                        localWakeCheck.isChecked &&
                        !manuallyPaused &&
                        activeRouteKey != null
                    ) {
                        playWakeConfirmationTone()
                        statusText.text = "已听到豆包豆包"
                        detailText.text = "正在连接云端语音模型…"
                        handler.postDelayed(
                            {
                                startSession(
                                    resetSession = false,
                                    announceWelcome = true
                                )
                            },
                            WAKE_CONNECT_DELAY_MS
                        )
                    }
                }
            },
            onFailure = {
                runOnUiThread {
                    showError("本地唤醒失败", it.message ?: it.javaClass.simpleName)
                }
            }
        )
        audioMonitor = AudioDeviceMonitor(this) { inspectAudioRoute() }
        renderUsage(usageTracker.snapshot())
        renderSessionTokens()
        updateSelectedBluetoothText()
        val voicePrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        localWakeCheck.isChecked = voicePrefs
            .getBoolean(KEY_LOCAL_WAKE_ENABLED, false)
        val initialSensitivity = voicePrefs
            .getInt(KEY_WAKE_SENSITIVITY, DEFAULT_WAKE_SENSITIVITY)
            .coerceIn(MIN_WAKE_SENSITIVITY, MAX_WAKE_SENSITIVITY)
        wakeSensitivitySeek.progress = initialSensitivity
        renderWakeSensitivity(initialSensitivity)
        wakeSensitivitySeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val sensitivity = progress.coerceIn(
                        MIN_WAKE_SENSITIVITY,
                        MAX_WAKE_SENSITIVITY
                    )
                    renderWakeSensitivity(sensitivity)
                    if (fromUser) {
                        voicePrefs.edit()
                            .putInt(KEY_WAKE_SENSITIVITY, sensitivity)
                            .apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (
                        localWakeCheck.isChecked &&
                        !cloudSessionActive &&
                        !manuallyPaused &&
                        activeRouteKey != null
                    ) {
                        wakeWordDetector.stop()
                        handler.postDelayed(
                            { startLocalWakeListening() },
                            AUDIO_HANDOFF_DELAY_MS
                        )
                    }
                }
            }
        )
        localWakeCheck.setOnCheckedChangeListener { _, checked ->
            voicePrefs.edit()
                .putBoolean(KEY_LOCAL_WAKE_ENABLED, checked)
                .apply()
            handler.removeCallbacks(enterLocalWakeStandby)
            if (checked) {
                if (cloudSessionActive) scheduleLocalWakeStandby()
            } else {
                wakeWordDetector.stop()
                if (
                    !manuallyPaused &&
                    activeRouteKey != null &&
                    !cloudSessionActive
                ) {
                    startSession(resetSession = false, announceWelcome = false)
                }
            }
        }

        findViewById<Button>(R.id.v2SelectBluetoothButton).setOnClickListener {
            chooseBluetoothDevice()
        }
        stopButton.setOnClickListener {
            if (manuallyPaused) resumeSession() else pauseSession()
        }
        findViewById<Button>(R.id.switchToV1Button).setOnClickListener {
            stopSession()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
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
        handler.removeCallbacks(enterLocalWakeStandby)
        wakeWordDetector.stop()
        userRequestedStop = false
        V2VoiceForegroundService.start(this)
        if (resetSession) {
            sessionTokens = 0L
            sessionInputTextTokens = 0L
            sessionInputAudioTokens = 0L
            sessionOutputTextTokens = 0L
            sessionOutputAudioTokens = 0L
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
                systemPrompt = "你是一个自然、简洁、有帮助的中文语音助手。",
                welcomeText = if (announceWelcome) welcomeInput.text.toString() else "",
                inputFormat = PCM_16K_MONO,
                outputFormat = PCM_24K_MONO
            )
        }.onFailure {
            cloudSessionActive = false
            finishSessionDuration()
            coordinator.stop(DisconnectReason.SERVICE_ERROR)
            scheduleReconnect(it.message ?: it.javaClass.simpleName)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (userRequestedStop || manuallyPaused || activeRouteKey == null) {
            showError("V2 连接失败", reason)
            return
        }
        statusText.text = if (isPhoneCallActive()) "通话中" else "V2 连接失败，正在重试"
        detailText.text = if (isPhoneCallActive()) {
            "电话结束后将自动恢复 V2 语音会话。"
        } else {
            "$reason\n将在 ${RECONNECT_DELAY_MS / 1_000} 秒后自动重试。"
        }
        stopButton.text = "暂停语音会话"
        stopButton.isEnabled = true
        handler.removeCallbacks(reconnectSession)
        handler.postDelayed(reconnectSession, RECONNECT_DELAY_MS)
    }

    private fun isPhoneCallActive(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun scheduleLocalWakeStandby() {
        handler.removeCallbacks(enterLocalWakeStandby)
        if (localWakeCheck.isChecked && cloudSessionActive) {
            handler.postDelayed(enterLocalWakeStandby, LOCAL_WAKE_IDLE_TIMEOUT_MS)
        }
    }

    private fun startLocalWakeListening() {
        val input = activeWakeInput ?: run {
            showError("本地唤醒不可用", "没有找到可用麦克风。")
            return
        }
        if (!wakeWordDetector.start(input, currentWakeProfile())) {
            showError("本地唤醒不可用", "缺少麦克风权限。")
        }
    }

    private fun currentWakeProfile(): WakeCalibrationProfile {
        val sensitivity = wakeSensitivitySeek.progress.coerceIn(
            MIN_WAKE_SENSITIVITY,
            MAX_WAKE_SENSITIVITY
        )
        return WakeCalibrationStore.DEFAULT_PROFILE.copy(
            keywordThreshold = when (sensitivity) {
                1 -> 0.28f
                2 -> 0.20f
                3 -> 0.12f
                4 -> 0.08f
                else -> 0.045f
            }
        )
    }

    private fun renderWakeSensitivity(sensitivity: Int) {
        val description = when (sensitivity) {
            1 -> "保守 · 最低误唤醒"
            2 -> "较低 · 偏重准确性"
            3 -> "均衡（推荐）"
            4 -> "较高 · 更容易唤醒"
            else -> "高灵敏 · 误唤醒较多"
        }
        wakeSensitivityText.text = "唤醒灵敏度：$sensitivity · $description"
    }

    private fun playWakeConfirmationTone() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        handler.postDelayed({ tone.release() }, 300L)
    }

    private fun selectWakeInput(
        kind: AudioRouteManager.Kind,
        externalRouteAccepted: Boolean
    ): AudioDeviceInfo? {
        val inputs = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (externalRouteAccepted) {
            val external = inputs.firstOrNull {
                when (kind) {
                    AudioRouteManager.Kind.USB ->
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    AudioRouteManager.Kind.BLUETOOTH ->
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    AudioRouteManager.Kind.NONE -> false
                }
            }
            if (external != null) return external
        }
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
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
        val selectedText = "  当前选择"
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
            "当前选择：$it"
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
        handler.removeCallbacks(enterLocalWakeStandby)
        wakeWordDetector.stop()
        cloudSessionActive = false
        finishSessionDuration()
        coordinator.stop(DisconnectReason.USER_REQUEST)
        V2VoiceForegroundService.stop(this)
        statusText.text = "语音会话已暂停"
        detailText.text = "点击“恢复语音会话”后继续工作。"
        stopButton.text = "恢复语音会话"
        stopButton.isEnabled = activeRouteKey != null
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

    private fun stopSession() {
        manuallyPaused = false
        userRequestedStop = true
        handler.removeCallbacks(reconnectSession)
        handler.removeCallbacks(enterLocalWakeStandby)
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
        activeWakeInput = selectWakeInput(selection.kind, selection.routeAccepted)
        activeConnectionDetail = when (selection.kind) {
            AudioRouteManager.Kind.USB ->
                if (selection.routeAccepted) {
                    "V2 已通过数据线连接，使用 Type-C 设备拾音。"
                } else {
                    "V2 已通过数据线连接，外设无可用拾音，使用手机麦克风。"
                }
            AudioRouteManager.Kind.BLUETOOTH -> {
                val name = audioRouteManager.selectedBluetoothName()
                    ?.substringBefore("（") ?: selection.label.substringBefore("（")
                if (selection.routeAccepted) {
                    "V2 已蓝牙连接 $name，使用蓝牙 HFP 麦克风。"
                } else {
                    "V2 已蓝牙连接 $name，无 HFP 拾音，使用手机麦克风。"
                }
            }
            AudioRouteManager.Kind.NONE -> ""
        }
        val routeKey = when (selection.kind) {
            AudioRouteManager.Kind.USB ->
                if (snapshot.ready) "usb" else null
            AudioRouteManager.Kind.BLUETOOTH ->
                if (audioRouteManager.selectedBluetoothConnected()) {
                    "bluetooth:${audioRouteManager.selectedBluetoothKey().orEmpty()}"
                } else {
                    null
                }
            AudioRouteManager.Kind.NONE -> null
        }

        if (routeKey == null) {
            handler.removeCallbacks(reconnectSession)
            handler.removeCallbacks(enterLocalWakeStandby)
            wakeWordDetector.stop()
            cloudSessionActive = false
            V2VoiceForegroundService.stop(this)
            val hadRoute = activeRouteKey != null
            activeRouteKey = null
            if (hadRoute && coordinator.state != RealtimeVoiceState.IDLE) {
                finishSessionDuration()
                coordinator.stop(DisconnectReason.AUDIO_DEVICE_LOST)
            }
            showWaitingForDevice()
            return
        }

        if (routeKey != activeRouteKey) {
            handler.removeCallbacks(enterLocalWakeStandby)
            wakeWordDetector.stop()
            cloudSessionActive = false
            if (coordinator.state != RealtimeVoiceState.IDLE) {
                finishSessionDuration()
                coordinator.stop(DisconnectReason.AUDIO_DEVICE_LOST)
            }
            activeRouteKey = routeKey
            statusText.text = "检测到${selection.label}"
            detailText.text = activeConnectionDetail
            if (manuallyPaused) {
                statusText.text = "语音会话已暂停"
                stopButton.text = "恢复语音会话"
                stopButton.isEnabled = true
            } else {
                ensurePermissionAndStart()
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
                    showActiveUi("正在聆听", activeConnectionDetail)
                    scheduleLocalWakeStandby()
                }
                RealtimeVoiceEvent.UserSpeechStarted -> {
                    showActiveUi("正在听你说话", "$activeConnectionDetail\n检测到用户语音。")
                }
                RealtimeVoiceEvent.UserSpeechEnded -> {
                    showActiveUi("正在思考", "$activeConnectionDetail\n等待模型回答。")
                }
                RealtimeVoiceEvent.ModelResponseStarted -> {
                    handler.removeCallbacks(enterLocalWakeStandby)
                    showActiveUi("豆包正在回答", "$activeConnectionDetail\n直接开口即可打断。")
                }
                RealtimeVoiceEvent.ModelResponseEnded -> {
                    showActiveUi("正在聆听", activeConnectionDetail)
                    scheduleLocalWakeStandby()
                }
                is RealtimeVoiceEvent.Failure -> {
                    cloudSessionActive = false
                    handler.removeCallbacks(enterLocalWakeStandby)
                    coordinator.stop(DisconnectReason.SERVICE_ERROR)
                    if (!userRequestedStop && activeRouteKey != null) {
                        scheduleReconnect("${event.code}: ${event.message}")
                    } else {
                        finishSessionDuration()
                        V2VoiceForegroundService.stop(this)
                        showError("V2 服务错误", "${event.code}: ${event.message}")
                    }
                }
                is RealtimeVoiceEvent.ModelAudio -> Unit
                is RealtimeVoiceEvent.Usage -> {
                    val added = event.inputUnits.coerceAtLeast(0) +
                        event.outputUnits.coerceAtLeast(0)
                    sessionTokens += added
                    sessionInputTextTokens += event.inputTextUnits
                    sessionInputAudioTokens += event.inputAudioUnits
                    sessionOutputTextTokens += event.outputTextUnits
                    sessionOutputAudioTokens += event.outputAudioUnits
                    val breakdown = V2TokenBreakdown(
                        inputText = event.inputTextUnits,
                        inputAudio = event.inputAudioUnits,
                        outputText = event.outputTextUnits,
                        outputAudio = event.outputAudioUnits
                    )
                    renderUsage(
                        usageTracker.add(
                            event.inputUnits,
                            event.outputUnits,
                            breakdown
                        )
                    )
                }
            }
        }
    }

    private fun setConnectingUi() {
        statusText.text = "正在连接 V2"
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

    private fun showIdleUi(detail: String) {
        statusText.text = "尚未连接"
        detailText.text = detail
        stopButton.text = "暂停语音会话"
        stopButton.isEnabled = false
    }

    private fun showError(status: String, detail: String) {
        statusText.text = status
        detailText.text = detail
        stopButton.isEnabled = coordinator.state != RealtimeVoiceState.IDLE
    }

    private fun showWaitingForDevice() {
        statusText.text = "等待设备连接"
        detailText.text = "连接 Type-C 或已选择的蓝牙通话设备后，将自动开始 V2 实时语音。"
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
        usageTable.removeAllViews()
        addUsageHeader()
        addUsagePeriod("本月", snapshot.monthBreakdown, snapshot.monthTokens)
        addUsagePeriod("今日", snapshot.todayBreakdown, snapshot.todayTokens)
        addUsagePeriod(
            "本轮",
            V2TokenBreakdown(
                sessionInputTextTokens,
                sessionInputAudioTokens,
                sessionOutputTextTokens,
                sessionOutputAudioTokens
            ),
            sessionTokens
        )
    }

    private fun renderSessionTokens() {
        renderUsage(usageTracker.snapshot())
    }

    private fun addUsageHeader() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(25)
            )
        }
        listOf("", "", "文本入", "语音入", "文本出", "语音出", "合计").forEach { value ->
            row.addView(createUsageCell(value, bold = true))
        }
        usageTable.addView(row)
    }

    private fun addUsagePeriod(
        period: String,
        breakdown: V2TokenBreakdown,
        totalTokens: Long
    ) {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        }
        group.addView(
            createUsageCell(
                period,
                color = ContextCompat.getColor(this, R.color.primary),
                bold = true
            )
        )
        val values = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 6f)
        }
        values.addView(
            createUsageSubRow(
                listOf(
                    "用量",
                    compactNumber(breakdown.inputText),
                    compactNumber(breakdown.inputAudio),
                    compactNumber(breakdown.outputText),
                    compactNumber(breakdown.outputAudio),
                    compactNumber(totalTokens)
                )
            )
        )
        values.addView(
            createUsageSubRow(
                listOf(
                    "价格",
                    formatCost(breakdown.inputText, INPUT_TEXT_PRICE),
                    formatCost(breakdown.inputAudio, INPUT_AUDIO_PRICE),
                    formatCost(breakdown.outputText, OUTPUT_TEXT_PRICE),
                    formatCost(breakdown.outputAudio, OUTPUT_AUDIO_PRICE),
                    "¥%.3f".format(totalCost(breakdown))
                ),
                priceRow = true
            )
        )
        group.addView(values)
        usageTable.addView(group)
    }

    private fun createUsageSubRow(
        values: List<String>,
        priceRow: Boolean = false
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        values.forEachIndexed { index, value ->
            addView(
                createUsageCell(
                    value,
                    color = if (priceRow && index > 0) {
                        ContextCompat.getColor(this@V2Activity, R.color.primary)
                    } else {
                        ContextCompat.getColor(this@V2Activity, R.color.text)
                    },
                    bold = priceRow && index > 0
                )
            )
        }
    }

    private fun createUsageCell(
        value: String,
        color: Int = ContextCompat.getColor(this, R.color.text),
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setBackgroundResource(R.drawable.usage_table_cell_background)
        setPadding(1, 0, 1, 0)
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
    }

    private fun compactNumber(value: Long): String =
        NumberFormat.getIntegerInstance().format(value.coerceAtLeast(0L))

    private fun formatCost(tokens: Long, unitPrice: Double): String =
        "¥%.3f".format(tokens.coerceAtLeast(0L) * unitPrice)

    private fun totalCost(value: V2TokenBreakdown): Double =
        value.inputText * INPUT_TEXT_PRICE +
            value.inputAudio * INPUT_AUDIO_PRICE +
            value.outputText * OUTPUT_TEXT_PRICE +
            value.outputAudio * OUTPUT_AUDIO_PRICE

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        private const val PREFS = "v2_voice"
        private const val KEY_USER_ID = "user_id"
        private const val ROUTE_CHECK_INTERVAL_MS = 2_000L
        private const val DURATION_UI_INTERVAL_MS = 1_000L
        private const val DURATION_CHECKPOINT_INTERVAL_MS = 5_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val LOCAL_WAKE_IDLE_TIMEOUT_MS = 30_000L
        private const val AUDIO_HANDOFF_DELAY_MS = 600L
        private const val WAKE_CONNECT_DELAY_MS = 350L
        private const val BUSY_RECHECK_DELAY_MS = 1_000L
        private const val KEY_LOCAL_WAKE_ENABLED = "local_wake_enabled"
        private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        private const val DEFAULT_WAKE_SENSITIVITY = 3
        private const val MIN_WAKE_SENSITIVITY = 1
        private const val MAX_WAKE_SENSITIVITY = 5
        private const val INPUT_TEXT_PRICE = 0.000010
        private const val INPUT_AUDIO_PRICE = 0.000080
        private const val OUTPUT_TEXT_PRICE = 0.000080
        private const val OUTPUT_AUDIO_PRICE = 0.000300
        private val PCM_16K_MONO = AudioFormatSpec(16_000, 1, 16, "pcm")
        private val PCM_24K_MONO = AudioFormatSpec(24_000, 1, 16, "pcm")

        private fun formatMinutes(durationMs: Long): String =
            "%.1f".format(durationMs / 60_000.0)
    }
}
