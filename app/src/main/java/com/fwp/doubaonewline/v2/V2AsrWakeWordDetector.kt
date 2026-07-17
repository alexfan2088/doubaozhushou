package com.fwp.doubaonewline.v2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

data class V2WakeAsrFiles(
    val encoder: File,
    val decoder: File,
    val tokens: File
)

data class V2WakeDetection(
    val recognizedText: String,
    val match: WakeMatchResult
)

class V2AsrWakeWordDetector(
    context: Context,
    private val onDetected: (V2WakeDetection) -> Unit,
    private val onRejected: (V2WakeDetection) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val application = context.applicationContext

    @Volatile private var running = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Synchronized
    fun start(
        device: AudioDeviceInfo,
        files: V2WakeAsrFiles,
        wakeWord: String
    ): Boolean {
        if (running) return true
        if (worker?.isAlive == true) return false
        if (!hasPermission()) return false
        running = true
        worker = thread(name = "v2-asr-wake-word", isDaemon = true) {
            runDetector(device, files, wakeWord)
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        worker?.takeIf { it !== Thread.currentThread() }?.let {
            runCatching { it.join(STOP_JOIN_TIMEOUT_MS) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runDetector(
        device: AudioDeviceInfo,
        files: V2WakeAsrFiles,
        wakeWord: String
    ) {
        try {
            recognizer = createRecognizer(files)
            stream = recognizer?.createStream()
            val input = createRecorder(device)
            val buffer = ShortArray(FRAME_SAMPLES)
            val preRoll = ArrayDeque<ShortArray>()
            val utterance = ArrayList<Short>()
            var inSpeech = false
            var speechFrames = 0
            var silenceFrames = 0
            var noiseRms = 0.008
            var lastLevelLogAt = 0L
            while (running) {
                val count = input.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val rms = rms(buffer, count)
                val now = SystemClock.elapsedRealtime()
                val speechGate = speechGate(noiseRms, device)
                if (now - lastLevelLogAt >= LEVEL_LOG_INTERVAL_MS) {
                    Log.i(TAG, "ASR wake audio rms=%.4f noise=%.4f gate=%.4f hfp=%s device=%s"
                        .format(rms, noiseRms, speechGate, isBluetoothInput(device), device.productName))
                    lastLevelLogAt = now
                }
                if (!inSpeech) {
                    preRoll.addLast(buffer.copyOf(count))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (rms >= speechGate) {
                        inSpeech = true
                        speechFrames = 1
                        silenceFrames = 0
                        preRoll.forEach { appendSamples(utterance, it, it.size) }
                        preRoll.clear()
                        appendSamples(utterance, buffer, count)
                    } else {
                        noiseRms = noiseRms * 0.98 + rms * 0.02
                    }
                } else {
                    appendSamples(utterance, buffer, count)
                    if (rms >= speechGate) {
                        speechFrames++
                        silenceFrames = 0
                    } else {
                        silenceFrames++
                    }
                    if (
                        silenceFrames >= END_SILENCE_FRAMES ||
                        utterance.size >= MAX_UTTERANCE_SAMPLES
                    ) {
                        if (speechFrames >= MIN_SPEECH_FRAMES) {
                            evaluateUtterance(utterance.toShortArray(), wakeWord)
                        }
                        utterance.clear()
                        inSpeech = false
                        speechFrames = 0
                        silenceFrames = 0
                    }
                }
            }
        } catch (error: Throwable) {
            if (running) onFailure(error)
            Log.e(TAG, "ASR wake-word detector failed", error)
        } finally {
            running = false
            worker = null
            release()
        }
    }

    private fun evaluateUtterance(samples: ShortArray, wakeWord: String) {
        val currentRecognizer = recognizer ?: return
        val currentStream = stream ?: return
        try {
            currentStream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, SAMPLE_RATE)
            currentStream.acceptWaveform(FloatArray(TAIL_SAMPLES), SAMPLE_RATE)
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            val text = currentRecognizer.getResult(currentStream).text.trim()
            val match = WakeWordTextMatcher.matches(text, wakeWord)
            val detection = V2WakeDetection(text, match)
            Log.i(TAG, "ASR wake text='$text' normalized='${match.normalizedText}' matched=${match.matched} reason=${match.reason}")
            if (match.matched) {
                running = false
                onDetected(detection)
            } else {
                onRejected(detection)
            }
        } finally {
            runCatching { currentRecognizer.reset(currentStream) }
        }
    }

    private fun createRecognizer(files: V2WakeAsrFiles): OnlineRecognizer =
        OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f),
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath
                    ),
                    tokens = files.tokens.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0f),
                    rule2 = EndpointRule(true, 0.7f, 0f),
                    rule3 = EndpointRule(false, 0f, 3.2f)
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
            )
        )

    @SuppressLint("MissingPermission")
    private fun createRecorder(device: AudioDeviceInfo): AudioRecord {
        check(device.isSource) { "不可用的麦克风：${device.productName}" }
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SAMPLES * 2)
        val input = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2
        )
        recorder = input
        check(input.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
        check(input.setPreferredDevice(device)) { "系统拒绝绑定麦克风" }
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true }
        } else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(input.audioSessionId)?.apply { enabled = true }
        } else null
        input.startRecording()
        SystemClock.sleep(ROUTE_SETTLE_MS)
        val routed = input.routedDevice
        check(routed != null && sameDevice(routed, device)) {
            "录音没有路由到指定麦克风"
        }
        Log.i(TAG, "ASR wake input verified id=${routed.id} type=${routed.type} name=${routed.productName}")
        return input
    }

    private fun release() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun rms(buffer: ShortArray, count: Int): Double {
        var sum = 0.0
        for (index in 0 until count) {
            val value = buffer[index] / 32768.0
            sum += value * value
        }
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun appendSamples(destination: MutableList<Short>, source: ShortArray, count: Int) {
        val remaining = (MAX_UTTERANCE_SAMPLES - destination.size).coerceAtLeast(0)
        for (index in 0 until minOf(count, remaining)) {
            destination += source[index]
        }
    }

    private fun minSpeechRms(device: AudioDeviceInfo): Double =
        if (isBluetoothInput(device)) MIN_BLUETOOTH_SPEECH_RMS else MIN_SPEECH_RMS

    private fun speechGate(noiseRms: Double, device: AudioDeviceInfo): Double {
        val dynamicGate = maxOf(noiseRms * 2.6, minSpeechRms(device))
        return if (isBluetoothInput(device)) {
            dynamicGate.coerceAtMost(MAX_BLUETOOTH_SPEECH_RMS)
        } else {
            dynamicGate
        }
    }

    private fun isBluetoothInput(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    companion object {
        private const val TAG = "V2AsrWakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600
        private const val PRE_ROLL_FRAMES = 3
        private const val MIN_SPEECH_RMS = 0.018
        private const val MIN_BLUETOOTH_SPEECH_RMS = 0.003
        private const val MAX_BLUETOOTH_SPEECH_RMS = 0.006
        private const val MIN_SPEECH_FRAMES = 2
        private const val END_SILENCE_FRAMES = 5
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 3
        private const val TAIL_SAMPLES = SAMPLE_RATE / 2
        private const val LEVEL_LOG_INTERVAL_MS = 2_000L
        private const val ROUTE_SETTLE_MS = 350L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L

        fun installedFiles(context: Context): V2WakeAsrFiles? {
            val root = File(context.applicationContext.filesDir, "v3-models/asr-paraformer")
            val encoder = File(root, "encoder.int8.onnx")
            val decoder = File(root, "decoder.int8.onnx")
            val tokens = File(root, "tokens.txt")
            return V2WakeAsrFiles(encoder, decoder, tokens).takeIf {
                encoder.length() == 165_462_184L &&
                    decoder.length() == 71_664_561L &&
                    tokens.length() == 75_756L
            }
        }

        private fun sameDevice(actual: AudioDeviceInfo, requested: AudioDeviceInfo): Boolean =
            actual.id == requested.id ||
                (
                    actual.type == requested.type &&
                        actual.address == requested.address &&
                        actual.productName.toString() == requested.productName.toString()
                    )
    }
}
