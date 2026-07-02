package com.fwp.doubaonewline.v3

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class V3AsrEngine(
    private val files: V3ModelManager.InstalledFiles,
    private val listener: Listener
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onVoiceActivity()
        fun onAsrError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(true)
    private var recorder: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var recordingThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching {
            val paraformer = OnlineParaformerModelConfig(
                encoder = files.asrEncoder.absolutePath,
                decoder = files.asrDecoder.absolutePath
            )
            val model = OnlineModelConfig(
                paraformer = paraformer,
                tokens = files.asrTokens.absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val endpoint = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 0.7f, 0f),
                rule3 = EndpointRule(false, 0f, MAX_UTTERANCE_SECONDS)
            )
            recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(SAMPLE_RATE, 80, 0f),
                    modelConfig = model,
                    endpointConfig = endpoint,
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                    maxActivePaths = 4
                )
            )
            stream = recognizer?.createStream()
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 2)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 2
            ).also {
                check(it.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
                echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
                    AcousticEchoCanceler.create(it.audioSessionId)?.apply { enabled = true }
                } else null
                noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
                    NoiseSuppressor.create(it.audioSessionId)?.apply { enabled = true }
                } else null
                it.startRecording()
            }
            recordingThread = thread(name = "v3-local-asr", start = true) { recordLoop() }
        }.onFailure {
            running.set(false)
            release()
            listener.onAsrError(it.message ?: "本地语音识别启动失败")
        }
    }

    fun setProcessingEnabled(enabled: Boolean) {
        processing.set(enabled)
        if (!enabled) resetStream()
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
        recordingThread?.join(1_000)
        recordingThread = null
        release()
    }

    private fun recordLoop() {
        val buffer = ShortArray(FRAME_SAMPLES)
        var voicedFrames = 0
        while (running.get()) {
            val count = recorder?.read(buffer, 0, buffer.size) ?: break
            if (count <= 0 || !processing.get()) continue
            val rms = calculateRms(buffer, count)
            if (rms >= VOICE_RMS_THRESHOLD) voicedFrames++ else voicedFrames = 0
            if (voicedFrames == VOICE_TRIGGER_FRAMES) listener.onVoiceActivity()

            val samples = FloatArray(count) { buffer[it] / 32768f }
            val currentStream = stream ?: continue
            val currentRecognizer = recognizer ?: continue
            currentStream.acceptWaveform(samples, SAMPLE_RATE)
            while (currentRecognizer.isReady(currentStream)) {
                currentRecognizer.decode(currentStream)
            }
            val partial = currentRecognizer.getResult(currentStream).text.trim()
            if (partial.isNotEmpty()) listener.onPartial(partial)
            if (currentRecognizer.isEndpoint(currentStream)) {
                val tail = FloatArray((SAMPLE_RATE * 0.8f).toInt())
                currentStream.acceptWaveform(tail, SAMPLE_RATE)
                while (currentRecognizer.isReady(currentStream)) {
                    currentRecognizer.decode(currentStream)
                }
                val finalText = currentRecognizer.getResult(currentStream).text.trim()
                currentRecognizer.reset(currentStream)
                voicedFrames = 0
                if (finalText.isNotEmpty()) listener.onFinal(finalText)
            }
        }
    }

    private fun resetStream() {
        val currentRecognizer = recognizer ?: return
        val currentStream = stream ?: return
        runCatching { currentRecognizer.reset(currentStream) }
    }

    private fun release() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun calculateRms(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (index in 0 until count) {
            val normalized = samples[index] / 32768.0
            sum += normalized * normalized
        }
        return sqrt(sum / count.coerceAtLeast(1)).toFloat()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1_600
        private const val MAX_UTTERANCE_SECONDS = 15f
        private const val VOICE_RMS_THRESHOLD = 0.04f
        private const val VOICE_TRIGGER_FRAMES = 3
    }
}
