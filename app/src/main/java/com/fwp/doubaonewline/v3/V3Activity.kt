package com.fwp.doubaonewline.v3

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.MainActivity
import com.fwp.doubaonewline.R
import com.fwp.doubaonewline.bridge.AudioDeviceMonitor
import com.fwp.doubaonewline.bridge.AudioRouteManager
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.VersionSessionIsolation
import com.fwp.doubaonewline.v2.V2Activity
import com.fwp.doubaonewline.v2.TtsEngineMode

class V3Activity : AppCompatActivity(), V3AsrEngine.Listener, V3DeepSeekEngine.Listener {
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var modelText: TextView
    private lateinit var routeText: TextView
    private lateinit var selectedBluetoothText: TextView
    private lateinit var pauseButton: Button
    private lateinit var settings: V3Settings
    private lateinit var modelManager: V3ModelManager
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var audioMonitor: AudioDeviceMonitor
    private lateinit var deepSeek: V3DeepSeekEngine
    private lateinit var tts: V3TtsPlayer
    private var config = V3Config()
    private var asr: V3AsrEngine? = null
    private var loadedModel: V3Model? = null
    private var modelLoading = false
    private var paused = false
    private var speaking = false
    private var thinking = false
    private var currentSpokenText = ""
    private var pendingBluetoothSelection = false
    private var callPaused = false
    private var terminating = false
    private var activeRouteKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val callCheck = object : Runnable {
        override fun run() {
            inspectAudioRoute()
            val inCall = isPhoneCallActive()
            if (inCall && !callPaused) {
                callPaused = true
                stopEngines(unloadModel = false)
                statusText.text = "通话中"
                detailText.text = "电话结束后将自动恢复 V3 本地语音。"
            } else if (!inCall && callPaused) {
                callPaused = false
                startIfReady()
            }
            handler.postDelayed(this, 2_000)
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startIfReady() else showError("V3 需要麦克风权限")
    }

    private val bluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingBluetoothSelection) chooseBluetoothDevice()
        pendingBluetoothSelection = false
    }

    private val enableBluetooth = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingBluetoothSelection) chooseBluetoothDevice()
        pendingBluetoothSelection = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v3)
        VersionSessionIsolation.enterV3(this)
        statusText = findViewById(R.id.v3StatusText)
        detailText = findViewById(R.id.v3DetailText)
        modelText = findViewById(R.id.v3ModelText)
        routeText = findViewById(R.id.v3RouteText)
        selectedBluetoothText = findViewById(R.id.v3SelectedBluetoothText)
        pauseButton = findViewById(R.id.v3PauseButton)
        settings = V3Settings(this)
        config = settings.load()
        modelManager = V3ModelManager(this)
        audioRouteManager = AudioRouteManager(this)
        audioMonitor = AudioDeviceMonitor(this) { inspectAudioRoute() }
        deepSeek = V3DeepSeekEngine(this)
        tts = V3TtsPlayer(this)
        setupTtsEngineSelection()

        getSharedPreferences(BridgeContract.PREFS, MODE_PRIVATE)
            .edit().putString(BridgeContract.PREF_MODE, BridgeContract.MODE_V3).apply()

        findViewById<Button>(R.id.v3SettingsButton).setOnClickListener {
            stopEngines(unloadModel = false)
            startActivity(Intent(this, V3SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.v3SelectBluetoothButton).setOnClickListener {
            chooseBluetoothDevice()
        }
        pauseButton.setOnClickListener {
            if (paused) resumeSession() else pauseSession()
        }
        findViewById<Button>(R.id.v3SwitchToV1Button).setOnClickListener {
            shutdownSession()
            VersionSessionIsolation.enterV1(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.v3SwitchToV2Button).setOnClickListener {
            shutdownSession()
            VersionSessionIsolation.enterV2(this)
            startActivity(Intent(this, V2Activity::class.java))
            finish()
        }
        renderModelSummary()
        updateSelectedBluetoothText()
        handler.post(callCheck)
    }

    override fun onStart() {
        super.onStart()
        audioMonitor.start()
    }

    override fun onResume() {
        super.onResume()
        val previous = config
        config = settings.load()
        renderModelSummary()
        inspectAudioRoute()
        updateSelectedBluetoothText()
        if (previous.selectedModel != config.selectedModel && loadedModel != null) {
            stopEngines(unloadModel = true)
        }
        startIfReady()
    }

    override fun onStop() {
        audioMonitor.stop()
        super.onStop()
    }

    private fun startIfReady() {
        if (terminating || paused || callPaused || modelLoading) return
        val files = modelManager.installed(config.selectedModel)
        if (files == null) {
            statusText.text = "本地模型未安装"
            detailText.text = "点击右上角“设置”，下载 ${config.selectedModel.parameters} 模型。"
            pauseButton.isEnabled = false
            return
        }
        if (loadedModel != config.selectedModel) {
            modelLoading = true
            statusText.text = "正在加载 DeepSeek ${config.selectedModel.parameters}"
            detailText.text = "模型加载与音频连接分开处理，请稍候。"
            renderModelSummary()
            deepSeek.load(
                files.llm,
                config.selectedModel.threads,
                config.maxResponseSentences,
                this
            )
            return
        }
        if (activeRouteKey == null) {
            statusText.text = "等待设备连接"
            detailText.text = "连接 Type-C 或当前选择的蓝牙设备后自动启动 V3 会话。"
            pauseButton.isEnabled = false
            renderModelSummary()
            return
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (asr == null) {
            asr = V3AsrEngine(files, this).also { it.start() }
            V3VoiceForegroundService.start(this)
        }
        pauseButton.isEnabled = true
        if (!thinking && !speaking) showListening()
    }

    override fun onModelReady() {
        if (terminating || isFinishing || isDestroyed) {
            return
        }
        modelLoading = false
        loadedModel = config.selectedModel
        renderModelSummary()
        startIfReady()
    }

    override fun onPartial(text: String) {
        runOnUiThread {
            if (paused) return@runOnUiThread
            if (speaking) {
                if (!config.allowBargeIn || isLikelyPlaybackEcho(text)) return@runOnUiThread
                interruptForUserSpeech()
            }
            statusText.text = "正在识别"
        }
    }

    override fun onFinal(text: String) {
        runOnUiThread {
            if (paused || (speaking && !config.allowBargeIn)) return@runOnUiThread
            if (speaking && isLikelyPlaybackEcho(text)) return@runOnUiThread
            if ((speaking || thinking) && config.allowBargeIn) interruptForUserSpeech()
            thinking = true
            statusText.text = "DeepSeek 正在思考"
            detailText.text = "本地推理中，不消耗云端 Token。"
            deepSeek.generate(text, config.contextRounds, this)
        }
    }

    override fun onVoiceActivity() {
        runOnUiThread {
            if ((speaking || thinking) && config.allowBargeIn && !paused) {
                interruptForUserSpeech()
            }
        }
    }

    override fun onPartialAnswer(text: String) {
        // Intermediate text is intentionally hidden; V3 speaks only the final answer.
    }

    override fun onFinalAnswer(text: String) {
        if (terminating) return
        thinking = false
        if (paused) return
        speaking = true
        currentSpokenText = text
        statusText.text = "正在播放本地回答"
        detailText.text = if (config.allowBargeIn) {
            "可直接说话打断当前回答。"
        } else {
            "播放期间暂停聆听。"
        }
        asr?.setProcessingEnabled(config.allowBargeIn)
        tts.speak(text, config.speakerId, config.ttsGain, config.ttsEngineMode) {
            runOnUiThread {
                speaking = false
                currentSpokenText = ""
                asr?.setProcessingEnabled(true)
                showListening()
            }
        }
    }

    override fun onError(message: String) {
        if (terminating) return
        modelLoading = false
        thinking = false
        showError(message)
    }

    override fun onAsrError(message: String) {
        runOnUiThread { showError(message) }
    }

    private fun interruptForUserSpeech() {
        if (!speaking && !thinking) return
        tts.stop()
        deepSeek.cancelGeneration()
        speaking = false
        thinking = false
        currentSpokenText = ""
        asr?.setProcessingEnabled(true)
        statusText.text = "已打断，正在聆听"
        detailText.text = "请继续说。"
    }

    private fun isLikelyPlaybackEcho(text: String): Boolean {
        val heard = normalize(text)
        val spoken = normalize(currentSpokenText)
        if (heard.length < 2 || spoken.isEmpty()) return false
        return spoken.contains(heard) || heard.contains(spoken.take(heard.length.coerceAtMost(spoken.length)))
    }

    private fun normalize(value: String) =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun pauseSession() {
        paused = true
        stopEngines(unloadModel = false)
        statusText.text = "V3 语音会话已暂停"
        detailText.text = "点击“恢复语音会话”后继续。"
        pauseButton.text = "恢复语音会话"
        pauseButton.isEnabled = true
    }

    private fun resumeSession() {
        paused = false
        pauseButton.text = "暂停语音会话"
        startIfReady()
    }

    private fun stopEngines(unloadModel: Boolean) {
        tts.stop()
        deepSeek.cancelGeneration()
        asr?.stop()
        asr = null
        speaking = false
        thinking = false
        V3VoiceForegroundService.stop(this)
        if (unloadModel) {
            deepSeek.unload()
            loadedModel = null
            renderModelSummary()
        }
    }

    private fun shutdownSession() {
        terminating = true
        stopEngines(unloadModel = false)
        audioRouteManager.clear()
    }

    private fun inspectAudioRoute() {
        if (!::audioMonitor.isInitialized) return
        val snapshot = audioMonitor.snapshot()
        val selection = audioRouteManager.select(snapshot)
        val routeKey = when (selection.kind) {
            AudioRouteManager.Kind.USB -> if (snapshot.ready) "usb" else null
            AudioRouteManager.Kind.BLUETOOTH ->
                if (audioRouteManager.selectedBluetoothConnected()) {
                    "bluetooth:${audioRouteManager.selectedBluetoothKey().orEmpty()}"
                } else {
                    null
                }
            AudioRouteManager.Kind.NONE -> null
        }
        routeText.text = when (selection.kind) {
            AudioRouteManager.Kind.USB -> if (selection.routeAccepted) {
                "V3 已通过数据线连接，使用 Type-C 设备拾音。"
            } else {
                "Type-C 外设无可用拾音，使用手机麦克风。"
            }
            AudioRouteManager.Kind.BLUETOOTH -> if (selection.routeAccepted) {
                "V3 已蓝牙连接 ${selection.label.substringBefore("（")}，使用 HFP 麦克风。"
            } else {
                "所选蓝牙设备无 HFP 拾音，使用手机麦克风。"
            }
            AudioRouteManager.Kind.NONE ->
                "当前选择的蓝牙设备未连接，且未检测到 Type-C 音频设备。"
        }
        if (routeKey != activeRouteKey) {
            activeRouteKey = routeKey
            if (routeKey == null) {
                stopEngines(unloadModel = false)
                if (!modelLoading) {
                    statusText.text = "等待设备连接"
                    detailText.text =
                        "连接 Type-C 或当前选择的蓝牙设备后自动启动 V3 会话。"
                }
            } else {
                startIfReady()
            }
        }
    }

    private fun isPhoneCallActive(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    private fun chooseBluetoothDevice() {
        if (
            android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingBluetoothSelection = true
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        if (getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true) {
            pendingBluetoothSelection = true
            enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val candidates = audioRouteManager.bluetoothCandidates()
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有可用的蓝牙设备")
                .setMessage("请先在系统蓝牙设置中配对设备。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val selectedKey = audioRouteManager.selectedBluetoothKey()
        val ordered = candidates.sortedByDescending { it.key == selectedKey }
        val labels = ordered.map {
            if (it.key == selectedKey) selectedLabel(it.displayLabel) else it.displayLabel
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择 V3 蓝牙设备")
            .setItems(labels) { _, index ->
                audioRouteManager.saveBluetoothCandidate(ordered[index])
                stopEngines(unloadModel = false)
                updateSelectedBluetoothText()
                inspectAudioRoute()
                startIfReady()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedLabel(label: String): CharSequence {
        val suffix = "  当前选择"
        return SpannableString(label + suffix).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(Color.rgb(21, 101, 192)),
                0,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(Color.rgb(211, 47, 47)),
                label.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updateSelectedBluetoothText() {
        val name = audioRouteManager.selectedBluetoothName()
        selectedBluetoothText.text = if (name == null) {
            "尚未选择蓝牙设备"
        } else {
            selectedLabel(name)
        }
    }

    private fun setupTtsEngineSelection() {
        val group = findViewById<RadioGroup>(R.id.v3TtsEngineGroup)
        group.check(
            if (config.ttsEngineMode == TtsEngineMode.SYSTEM) {
                R.id.v3SystemTtsRadio
            } else {
                R.id.v3LocalTtsRadio
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            config = config.copy(
                ttsEngineMode = if (checkedId == R.id.v3SystemTtsRadio) {
                    TtsEngineMode.SYSTEM
                } else {
                    TtsEngineMode.LOCAL
                }
            )
            settings.save(config)
        }
    }

    private fun renderModelSummary() {
        val recommendation = settings.recommendation()
        val installed = modelManager.isInstalled(config.selectedModel)
        val runtime = when {
            modelLoading -> "正在加载"
            loadedModel == config.selectedModel -> "已加载，可直接复用"
            else -> "未加载"
        }
        modelText.text =
            "当前模型：DeepSeek ${config.selectedModel.parameters}\n" +
            "模型文件：${if (installed) "已安装" else "未安装"} · 运行状态：$runtime\n" +
            "系统推荐：${recommendation.recommendedModel.parameters} · " +
            if (config.allowBargeIn) "允许语音打断" else "半双工"
    }

    private fun showListening() {
        statusText.text = "正在聆听"
        detailText.text = if (config.allowBargeIn) {
            "本地连续对话已就绪，回答播放时也可以直接打断。"
        } else {
            "本地连续对话已就绪，回答播放期间暂停聆听。"
        }
    }

    private fun showError(message: String) {
        statusText.text = "V3 本地会话失败"
        detailText.text = message
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        shutdownSession()
        deepSeek.shutdown(keepModelLoaded = true)
        tts.shutdown()
        modelManager.close()
        super.onDestroy()
    }
}
