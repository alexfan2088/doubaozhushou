package com.fwp.doubaonewline.v2

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineGenerator
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * P0 adapter for Volcengine's official end-to-end Android SDK.
 *
 * It deliberately uses the SDK recorder/player so Android's selected
 * communication device remains the single routing authority for USB and
 * Bluetooth. A later phase can switch to RECORDER_TYPE_STREAM if custom PCM
 * routing becomes necessary.
 */
class VolcengineRealtimeVoiceClient(
    context: Context,
    private val welcomeDelayMs: Long = DEFAULT_WELCOME_DELAY_MS
) : RealtimeVoiceClient, SpeechEngine.SpeechListener {
    private val application = context.applicationContext as Application
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: SpeechEngine? = null
    private var listener: RealtimeVoiceListener? = null
    private var pendingWelcomeText = ""
    private var useCustomExternalRecorder = false
    @Volatile private var legacyBluetoothRouting = false
    @Volatile private var customRecorderRunning = false
    @Volatile private var customRecorder: AudioRecord? = null
    @Volatile private var customRecorderThread: Thread? = null

    @Volatile
    override var state: RealtimeVoiceState = RealtimeVoiceState.IDLE
        private set

    override fun setListener(listener: RealtimeVoiceListener?) {
        this.listener = listener
    }

    fun setLegacyBluetoothRouting(enabled: Boolean) {
        legacyBluetoothRouting = enabled
    }

    @Synchronized
    override fun connect(config: RealtimeVoiceConfig): Result<Unit> = runCatching {
        check(state == RealtimeVoiceState.IDLE || state == RealtimeVoiceState.ERROR) {
            "A realtime voice session is already active"
        }
        config.credentials.validate().getOrThrow()
        state = RealtimeVoiceState.CONNECTING

        check(SpeechEngineGenerator.PrepareEnvironment(application, application)) {
            "Speech SDK environment initialization failed"
        }
        val aecModel = AecModelInstaller.install(application).getOrThrow()
        val speechEngine = SpeechEngineGenerator.getInstance().also {
            it.createEngine()
            it.setContext(application)
            it.setListener(this)
        }
        engine = speechEngine
        configure(speechEngine, config, aecModel.absolutePath)

        val initCode = speechEngine.initEngine()
        check(initCode == SpeechEngineDefines.ERR_NO_ERROR) {
            "Speech SDK init failed: $initCode"
        }

        speechEngine.sendDirective(SpeechEngineDefines.DIRECTIVE_SYNC_STOP_ENGINE, "")
        pendingWelcomeText = config.welcomeText.trim()
        val startPayload = JSONObject().apply {
            put("dialog", JSONObject().apply {
                put("bot_name", "豆包")
                put("system_role", config.systemPrompt)
                put("extra", JSONObject().apply {
                    put("input_mod", "keep_alive")
                    put("model", MODEL_VERSION)
                })
            })
        }.toString()
        val startCode = speechEngine.sendDirective(
            SpeechEngineDefines.DIRECTIVE_START_ENGINE,
            startPayload
        )
        check(startCode == SpeechEngineDefines.ERR_NO_ERROR) {
            "Speech SDK start failed: $startCode"
        }
    }.onFailure {
        state = RealtimeVoiceState.ERROR
        releaseEngine()
    }

    private fun configure(
        speechEngine: SpeechEngine,
        config: RealtimeVoiceConfig,
        aecModelPath: String
    ) {
        val credentials = config.credentials
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING,
            SpeechEngineDefines.DIALOG_ENGINE
        )
        // The Dialog SDK maps these options to X-Api-App-ID,
        // X-Api-Access-Key and X-Api-App-Key. The last value is the
        // service-defined fixed Dialog key, not the console Secret Key.
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_APP_ID_STRING,
            credentials.appId
        )
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING,
            DIALOG_APP_KEY
        )
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_APP_TOKEN_STRING,
            credentials.accessToken
        )
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING,
            credentials.resourceId
        )
        speechEngine.setOptionString(SpeechEngineDefines.PARAMS_KEY_UID_STRING, config.userId)
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ADDRESS_STRING,
            DIALOG_ADDRESS
        )
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_URI_STRING,
            DIALOG_URI
        )
        @Suppress("DEPRECATION")
        val bluetoothScoActive = !legacyBluetoothRouting && (
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                audioManager.isBluetoothScoOn
            )
        val communicationType = audioManager.communicationDevice?.type
        useCustomExternalRecorder =
            legacyBluetoothRouting ||
                bluetoothScoActive ||
                communicationType == AudioDeviceInfo.TYPE_USB_DEVICE ||
                communicationType == AudioDeviceInfo.TYPE_USB_HEADSET ||
                communicationType == AudioDeviceInfo.TYPE_USB_ACCESSORY
        speechEngine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_ENABLE_AEC_BOOL,
            !bluetoothScoActive
        )
        if (!bluetoothScoActive) {
            speechEngine.setOptionString(
                SpeechEngineDefines.PARAMS_KEY_AEC_MODEL_PATH_STRING,
                aecModelPath
            )
        }
        speechEngine.setOptionString(
            SpeechEngineDefines.PARAMS_KEY_RECORDER_TYPE_STRING,
            if (useCustomExternalRecorder) {
                SpeechEngineDefines.RECORDER_TYPE_STREAM
            } else {
                SpeechEngineDefines.RECORDER_TYPE_RECORDER
            }
        )
        if (legacyBluetoothRouting) {
            speechEngine.setOptionInt(
                SpeechEngineDefines.PARAMS_KEY_AUDIO_STREAM_TYPE_INT,
                SpeechEngineDefines.AUDIO_STREAM_TYPE_MEDIA
            )
        } else {
            speechEngine.setOptionInt(
                SpeechEngineDefines.PARAMS_KEY_RECORDER_PRESET_INT,
                SpeechEngineDefines.RECORDER_PRESET_VOICE_COMMUNICATION
            )
            speechEngine.setOptionInt(
                SpeechEngineDefines.PARAMS_KEY_AUDIO_STREAM_TYPE_INT,
                SpeechEngineDefines.AUDIO_STREAM_TYPE_VOICE
            )
        }
        speechEngine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_BOOL,
            true
        )
        speechEngine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_RECORDER_AUDIO_CALLBACK_BOOL,
            false
        )
        speechEngine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_PLAYER_AUDIO_CALLBACK_BOOL,
            false
        )
        speechEngine.setOptionBoolean(
            SpeechEngineDefines.PARAMS_KEY_DIALOG_ENABLE_DECODER_AUDIO_CALLBACK_BOOL,
            false
        )
    }

    override fun sendAudio(frame: AudioFrame): Result<Unit> =
        Result.failure(
            UnsupportedOperationException("P0 uses the SDK recorder; custom PCM input is disabled")
        )

    @Synchronized
    override fun interrupt(): Result<Unit> = directive(
        SpeechEngineDefines.DIRECTIVE_EVENT_CLIENT_INTERRUPT,
        "{}"
    )

    @Synchronized
    override fun disconnect(reason: DisconnectReason): Result<Unit> = runCatching {
        state = RealtimeVoiceState.DISCONNECTING
        mainHandler.removeCallbacksAndMessages(null)
        engine?.sendDirective(SpeechEngineDefines.DIRECTIVE_STOP_ENGINE, "")
        releaseEngine()
        state = RealtimeVoiceState.IDLE
    }.onFailure { state = RealtimeVoiceState.ERROR }

    override fun onSpeechMessage(type: Int, data: ByteArray, len: Int) {
        when (type) {
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START -> {
                state = RealtimeVoiceState.LISTENING
                if (useCustomExternalRecorder) startCustomExternalRecorder()
                listener?.onEvent(RealtimeVoiceEvent.Connected)
                scheduleWelcome()
            }
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP -> {
                state = RealtimeVoiceState.IDLE
            }
            SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR -> {
                state = RealtimeVoiceState.ERROR
                listener?.onEvent(
                    RealtimeVoiceEvent.Failure(
                        code = "SPEECH_ENGINE_ERROR",
                        message = data.decodeToString(0, len.coerceAtMost(data.size)),
                        retryable = true
                    )
                )
            }
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_ASR_RESPONSE -> {
                state = RealtimeVoiceState.USER_SPEAKING
                listener?.onEvent(RealtimeVoiceEvent.UserSpeechStarted)
            }
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_ASR_ENDED -> {
                state = RealtimeVoiceState.WAITING_RESPONSE
                listener?.onEvent(RealtimeVoiceEvent.UserSpeechEnded)
            }
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_CHAT_RESPONSE -> {
                state = RealtimeVoiceState.MODEL_SPEAKING
                listener?.onEvent(RealtimeVoiceEvent.ModelResponseStarted)
            }
            SpeechEngineDefines.MESSAGE_TYPE_PLAYER_START_PLAY_AUDIO -> {
                state = RealtimeVoiceState.MODEL_SPEAKING
                listener?.onEvent(RealtimeVoiceEvent.ModelResponseStarted)
            }
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_CHAT_ENDED -> {
                state = RealtimeVoiceState.LISTENING
                listener?.onEvent(RealtimeVoiceEvent.ModelResponseEnded)
            }
            SpeechEngineDefines.MESSAGE_TYPE_PLAYER_FINISH_PLAY_AUDIO -> {
                state = RealtimeVoiceState.LISTENING
                listener?.onEvent(RealtimeVoiceEvent.ModelResponseEnded)
            }
            SpeechEngineDefines.MESSAGE_TYPE_EVENT_USAGE_RESPONSE -> {
                parseUsage(data.decodeToString(0, len.coerceAtMost(data.size)))?.let {
                    listener?.onEvent(it)
                }
            }
        }
    }

    private fun parseUsage(payload: String): RealtimeVoiceEvent.Usage? = runCatching {
        val root = JSONObject(payload)
        val inputAudio = findLong(root, setOf("input_audio_tokens")) +
            findLong(root, setOf("cached_audio_tokens"))
        val inputText = findLong(root, setOf("input_text_tokens")) +
            findLong(root, setOf("cached_text_tokens"))
        val outputAudio = findLong(root, setOf("output_audio_tokens"))
        val outputText = findLong(root, setOf("output_text_tokens"))
        val splitInput = inputAudio + inputText
        val splitOutput = outputAudio + outputText
        val genericInput = findLong(
            root,
            setOf(
                "input_tokens",
                "input_token",
                "inputtokens",
                "speech_input_tokens",
                "text_input_tokens"
            )
        )
        val genericOutput = findLong(
            root,
            setOf(
                "output_tokens",
                "output_token",
                "outputtokens",
                "speech_output_tokens",
                "text_output_tokens"
            )
        )
        val input = if (splitInput > 0L) splitInput else genericInput
        val output = if (splitOutput > 0L) splitOutput else genericOutput
        val total = findLong(root, setOf("total_tokens", "total_token", "totaltokens"))
        when {
            splitInput > 0L || splitOutput > 0L ->
                RealtimeVoiceEvent.Usage(inputAudio, inputText, outputAudio, outputText)
            input > 0L || output > 0L ->
                RealtimeVoiceEvent.Usage(input, 0L, output, 0L)
            total > 0L -> RealtimeVoiceEvent.Usage(total, 0L, 0L, 0L)
            else -> null
        }
    }.getOrNull()

    private fun findLong(value: Any?, names: Set<String>): Long {
        if (value !is JSONObject) return 0L
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = value.opt(key)
            if (key.lowercase() in names) {
                val number = when (child) {
                    is Number -> child.toLong()
                    is String -> child.toLongOrNull()
                    else -> null
                }
                if (number != null) return number
            }
            val nested = findLong(child, names)
            if (nested > 0L) return nested
        }
        return 0L
    }

    private fun scheduleWelcome() {
        val text = pendingWelcomeText
        if (text.isEmpty()) return
        mainHandler.postDelayed({
            if (state != RealtimeVoiceState.IDLE && state != RealtimeVoiceState.ERROR) {
                directive(
                    SpeechEngineDefines.DIRECTIVE_EVENT_SAY_HELLO,
                    JSONObject().put("content", text).toString()
                )
            }
        }, welcomeDelayMs)
    }

    private fun directive(type: Int, payload: String): Result<Unit> = runCatching {
        val speechEngine = checkNotNull(engine) { "Speech engine is not initialized" }
        val code = speechEngine.sendDirective(type, payload)
        check(code == SpeechEngineDefines.ERR_NO_ERROR) {
            "Speech SDK directive $type failed: $code"
        }
    }

    private fun releaseEngine() {
        stopCustomExternalRecorder()
        mainHandler.removeCallbacksAndMessages(null)
        engine?.destroyEngine()
        engine = null
        pendingWelcomeText = ""
    }

    private fun startCustomExternalRecorder() {
        if (customRecorderRunning) return
        val communicationType = audioManager.communicationDevice?.type
        val input = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                when {
                    legacyBluetoothRouting ->
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                    else -> when (communicationType) {
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                    else -> it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                }
            }
            ?: return
        val minimum = AudioRecord.getMinBufferSize(
            BLUETOOTH_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimum <= 0) return
        val recorder = AudioRecord(
            if (legacyBluetoothRouting) {
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            },
            BLUETOOTH_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 2
        )
        if (
            recorder.state != AudioRecord.STATE_INITIALIZED ||
            !recorder.setPreferredDevice(input)
        ) {
            recorder.release()
            return
        }
        customRecorder = recorder
        customRecorderRunning = true
        recorder.startRecording()
        customRecorderThread = thread(
            name = "v2-bluetooth-recorder",
            isDaemon = true
        ) {
            val samples = ShortArray(BLUETOOTH_FRAME_SAMPLES)
            val bytes = ByteArray(BLUETOOTH_FRAME_SAMPLES * 2)
            while (customRecorderRunning) {
                val count = recorder.read(samples, 0, samples.size)
                if (count <= 0) continue
                for (index in 0 until count) {
                    val value = samples[index].toInt()
                    bytes[index * 2] = (value and 0xff).toByte()
                    bytes[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
                }
                engine?.feedAudio(bytes, count * 2)
            }
        }
    }

    private fun stopCustomExternalRecorder() {
        customRecorderRunning = false
        runCatching { customRecorder?.stop() }
        customRecorderThread?.takeIf { it !== Thread.currentThread() }?.let {
            runCatching { it.join(1_000L) }
        }
        customRecorder?.release()
        customRecorder = null
        customRecorderThread = null
    }

    companion object {
        const val DIALOG_ADDRESS = "wss://openspeech.bytedance.com"
        const val DIALOG_URI = "/api/v3/realtime/dialogue"
        const val MODEL_VERSION = "1.2.1.1"
        const val DEFAULT_WELCOME_DELAY_MS = 6_000L
        private const val DIALOG_APP_KEY = "PlgvMymc7f3tQnJ6"
        private const val BLUETOOTH_SAMPLE_RATE = 16_000
        private const val BLUETOOTH_FRAME_SAMPLES = 320
    }
}
