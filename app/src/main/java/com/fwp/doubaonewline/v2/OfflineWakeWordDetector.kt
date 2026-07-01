package com.fwp.doubaonewline.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class OfflineWakeWordDetector(
    context: Context,
    private val onDetected: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val application = context.applicationContext

    @Volatile private var running = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    @Synchronized
    fun start(device: AudioDeviceInfo, profile: WakeCalibrationProfile): Boolean {
        if (running) return true
        if (!hasPermission()) return false
        running = true
        worker = thread(name = "offline-wake-word", isDaemon = true) {
            runDetector(device, profile)
        }
        return true
    }

    @Synchronized
    fun calibrate(
        device: AudioDeviceInfo,
        onReady: () -> Unit,
        onProgress: (Int) -> Unit,
        onComplete: (WakeCalibrationProfile) -> Unit
    ): Boolean {
        if (running || !hasPermission()) return false
        running = true
        worker = thread(name = "wake-calibration", isDaemon = true) {
            runCalibration(device, onReady, onProgress, onComplete)
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
        worker?.takeIf { it !== Thread.currentThread() }?.let {
            runCatching { it.join(STOP_JOIN_TIMEOUT_MS) }
        }
        worker = null
    }

    private fun runDetector(device: AudioDeviceInfo, profile: WakeCalibrationProfile) {
        var spotter: KeywordSpotter? = null
        var detected = false
        try {
            spotter = createSpotter(profile.keywordThreshold)
            val stream = spotter.createStream()
            val recorder = createExternalRecorder(device)
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var lastLevelLogAt = 0L
            val personalizedSamples = ArrayList<Short>()
            val preRoll = ArrayDeque<ShortArray>()
            var personalizedSpeech = false
            var personalizedSilenceFrames = 0
            var adaptiveNoise = profile.noiseRms.toDouble().coerceAtLeast(30.0)
            while (running) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val rms = rms(buffer, count)
                val gain = adaptiveGain(profile.gain, rms)
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelLogAt >= LEVEL_LOG_INTERVAL_MS) {
                    Log.i(TAG, "External wake audio rms=%.0f gain=%.1f device=%s"
                        .format(rms, gain, device.productName))
                    lastLevelLogAt = now
                }
                if (profile.templates.isNotEmpty()) {
                    val speechGate = maxOf(adaptiveNoise * 1.8, 70.0)
                    if (!personalizedSpeech) {
                        preRoll.addLast(buffer.copyOf(count))
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        if (rms >= speechGate) {
                            personalizedSpeech = true
                            preRoll.forEach {
                                appendSamples(personalizedSamples, it, it.size)
                            }
                            preRoll.clear()
                        } else {
                            adaptiveNoise = adaptiveNoise * 0.99 + rms * 0.01
                        }
                    } else {
                        appendSamples(personalizedSamples, buffer, count)
                        if (rms < speechGate) {
                            personalizedSilenceFrames++
                        } else {
                            personalizedSilenceFrames = 0
                        }
                        if (
                            personalizedSilenceFrames >= END_SILENCE_FRAMES ||
                            personalizedSamples.size >= MAX_UTTERANCE_SAMPLES
                        ) {
                            val candidate = PersonalizedWakeMatcher.extract(
                                personalizedSamples.toShortArray()
                            )
                            val distance = candidate?.let {
                                PersonalizedWakeMatcher.nearest(it, profile.templates)
                            } ?: Float.MAX_VALUE
                            Log.i(
                                TAG,
                                "Personal wake distance=%.3f threshold=%.3f frames=%d"
                                    .format(
                                        distance,
                                        profile.templateThreshold,
                                        candidate?.frames ?: 0
                                    )
                            )
                            if (
                                profile.templateThreshold > 0f &&
                                distance <= profile.templateThreshold
                            ) {
                                detected = true
                                running = false
                            }
                            personalizedSamples.clear()
                            personalizedSpeech = false
                            personalizedSilenceFrames = 0
                        }
                    }
                }
                stream.acceptWaveform(
                    FloatArray(count) {
                        (buffer[it] / 32768f * gain).coerceIn(-1f, 1f)
                    },
                    SAMPLE_RATE
                )
                while (running && spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val result = spotter.getResult(stream).keyword
                    if (result.isNotBlank()) {
                        Log.i(TAG, "KWS candidate detected: $result")
                    }
                    if (result.startsWith(WAKE_WORD)) {
                        detected = true
                        running = false
                        break
                    }
                }
            }
            stream.release()
        } catch (error: Throwable) {
            if (running) onFailure(error)
            Log.e(TAG, "External wake-word detector failed", error)
        } finally {
            releaseRecorder()
            spotter?.release()
            running = false
            worker = null
            if (detected) onDetected(WAKE_WORD)
        }
    }

    private fun runCalibration(
        device: AudioDeviceInfo,
        onReady: () -> Unit,
        onProgress: (Int) -> Unit,
        onComplete: (WakeCalibrationProfile) -> Unit
    ) {
        try {
            val recorder = createExternalRecorder(device)
            val buffer = ShortArray(SAMPLE_RATE / 10)
            val noiseSamples = mutableListOf<Double>()
            val speechSamples = mutableListOf<Double>()
            val templates = mutableListOf<WakeTemplate>()
            val utteranceSamples = ArrayList<Short>()
            val preRoll = ArrayDeque<ShortArray>()
            var noiseRms = 40.0
            var inSpeech = false
            var eventPeak = 0.0
            var silenceFrames = 0
            var acceptSpeechAfterMs = 0L
            val deadline = SystemClock.elapsedRealtime() + CALIBRATION_TIMEOUT_MS

            while (running && templates.size < CALIBRATION_UTTERANCES &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                val count = recorder.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val level = rms(buffer, count)
                if (noiseSamples.size < NOISE_FRAMES) {
                    noiseSamples += level
                    noiseRms = noiseSamples.sorted()[noiseSamples.size / 2]
                    if (noiseSamples.size == NOISE_FRAMES) {
                        acceptSpeechAfterMs =
                            SystemClock.elapsedRealtime() + CALIBRATION_TONE_GUARD_MS
                        onReady()
                    }
                    continue
                }
                if (SystemClock.elapsedRealtime() < acceptSpeechAfterMs) {
                    continue
                }
                val speechGate = maxOf(noiseRms * 3.2, 120.0)
                if (level >= speechGate) {
                    if (!inSpeech) {
                        preRoll.forEach {
                            appendSamples(utteranceSamples, it, it.size)
                        }
                        preRoll.clear()
                    }
                    inSpeech = true
                    silenceFrames = 0
                    eventPeak = maxOf(eventPeak, level)
                } else if (inSpeech) {
                    silenceFrames++
                    if (silenceFrames >= END_SILENCE_FRAMES) {
                        PersonalizedWakeMatcher.extract(
                            utteranceSamples.toShortArray()
                        )?.let { template ->
                            templates += template
                            speechSamples += eventPeak
                            onProgress(templates.size)
                        }
                        inSpeech = false
                        eventPeak = 0.0
                        utteranceSamples.clear()
                    }
                } else {
                    noiseRms = noiseRms * 0.98 + level * 0.02
                    preRoll.addLast(buffer.copyOf(count))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                }
                if (inSpeech) {
                    appendSamples(utteranceSamples, buffer, count)
                }
            }
            check(templates.size == CALIBRATION_UTTERANCES) {
                "标定超时，只采集到 ${templates.size}/$CALIBRATION_UTTERANCES 次"
            }
            val medianSpeech = speechSamples.sorted()[speechSamples.size / 2]
            val gain = (TARGET_SPEECH_RMS / medianSpeech).toFloat().coerceIn(1f, MAX_GAIN)
            val stableTemplates = PersonalizedWakeMatcher.stableTemplates(templates)
            check(stableTemplates.size >= 6) {
                "有效标定样本不足，请保持相同距离和语速重新标定"
            }
            val profile = WakeCalibrationProfile(
                gain = gain,
                noiseRms = noiseRms.toFloat(),
                keywordThreshold = WakeCalibrationStore.DEFAULT_THRESHOLD,
                calibratedAtMs = System.currentTimeMillis(),
                templates = stableTemplates,
                templateThreshold =
                    PersonalizedWakeMatcher.calibrationThreshold(stableTemplates)
            )
            running = false
            onComplete(profile)
        } catch (error: Throwable) {
            if (running) onFailure(error)
            Log.e(TAG, "Wake calibration failed", error)
        } finally {
            releaseRecorder()
            running = false
            worker = null
        }
    }

    private fun createSpotter(threshold: Float): KeywordSpotter =
        KeywordSpotter(
            application.assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$MODEL_DIR/encoder.int8.onnx",
                        decoder = "$MODEL_DIR/decoder.onnx",
                        joiner = "$MODEL_DIR/joiner.int8.onnx"
                    ),
                    tokens = "$MODEL_DIR/tokens.txt",
                    numThreads = 2
                ),
                maxActivePaths = 4,
                keywordsFile = "$MODEL_DIR/keywords.txt",
                keywordsScore = KEYWORD_SCORE,
                keywordsThreshold = threshold,
                numTrailingBlanks = 1
            )
        )

    private fun createExternalRecorder(device: AudioDeviceInfo): AudioRecord {
        check(isExternalInput(device)) { "拒绝使用非外接麦克风：${device.productName}" }
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBytes > 0) { "AudioRecord buffer unavailable: $minBytes" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBytes * 2
        )
        audioRecord = recorder
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "外接麦克风初始化失败" }
        check(recorder.setPreferredDevice(device)) { "系统拒绝绑定外接麦克风" }
        recorder.startRecording()
        SystemClock.sleep(ROUTE_SETTLE_MS)
        val routed = recorder.routedDevice
        check(routed != null && sameDevice(routed, device) && isExternalInput(routed)) {
            "录音没有路由到外接设备，已禁止回退手机麦克风"
        }
        Log.i(TAG, "External input verified id=${routed.id} type=${routed.type} name=${routed.productName}")
        return recorder
    }

    private fun releaseRecorder() {
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun adaptiveGain(configured: Float, rms: Double): Float {
        if (rms <= 1.0) return configured
        val antiClip = (MAX_SAFE_RMS / rms).toFloat().coerceAtMost(configured)
        return antiClip.coerceAtLeast(1f)
    }

    private fun rms(buffer: ShortArray, count: Int): Double =
        sqrt((0 until count).sumOf {
            val value = buffer[it].toDouble()
            value * value
        } / count)

    private fun appendSamples(
        destination: MutableList<Short>,
        source: ShortArray,
        count: Int
    ) {
        val remaining = (MAX_UTTERANCE_SAMPLES - destination.size).coerceAtLeast(0)
        for (index in 0 until minOf(count, remaining)) {
            destination += source[index]
        }
    }

    companion object {
        private const val TAG = "OfflineWakeWord"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "kws-zh-en"
        private const val WAKE_WORD = "豆包豆包"
        private const val KEYWORD_SCORE = 3.0f
        private const val LEVEL_LOG_INTERVAL_MS = 2_000L
        private const val ROUTE_SETTLE_MS = 350L
        private const val STOP_JOIN_TIMEOUT_MS = 1_500L
        private const val CALIBRATION_UTTERANCES = 10
        private const val CALIBRATION_TIMEOUT_MS = 90_000L
        private const val CALIBRATION_TONE_GUARD_MS = 700L
        private const val NOISE_FRAMES = 20
        private const val END_SILENCE_FRAMES = 5
        private const val PRE_ROLL_FRAMES = 2
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 4
        private const val TARGET_SPEECH_RMS = 3_500.0
        private const val MAX_SAFE_RMS = 9_000.0
        private const val MAX_GAIN = 12f

        fun isExternalInput(device: AudioDeviceInfo): Boolean =
            device.isSource && (
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                )

        private fun sameDevice(actual: AudioDeviceInfo, requested: AudioDeviceInfo): Boolean =
            actual.id == requested.id ||
                (
                    actual.type == requested.type &&
                        actual.address == requested.address &&
                        actual.productName.toString() == requested.productName.toString()
                    )
    }
}
