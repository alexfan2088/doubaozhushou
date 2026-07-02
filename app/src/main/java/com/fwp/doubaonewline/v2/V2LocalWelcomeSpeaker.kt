package com.fwp.doubaonewline.v2

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class V2LocalWelcomeSpeaker(context: Context) {
    private val application = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val cacheDirectory = File(application.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val sherpaExecutor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var ready = false
    private var completion: ((Boolean) -> Unit)? = null
    private var synthesisId: String? = null
    private var synthesisSource: File? = null
    private var synthesisTarget: File? = null
    private var synthesisGain = V2TokenSavingConfig.DEFAULT_TTS_GAIN.toFloat()
    private var generation = 0L

    init {
        tts = TextToSpeech(application) { status ->
            ready = status == TextToSpeech.SUCCESS && configureLanguage()
        }.also { engine ->
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != synthesisId) return
                        val source = synthesisSource
                        val target = synthesisTarget
                        val playable = if (
                            source != null &&
                            target != null &&
                            amplifyPcmWav(source, target, synthesisGain)
                        ) {
                            target
                        } else {
                            source?.takeIf(File::isFile)?.also {
                                if (target != null) it.copyTo(target, overwrite = true)
                            }?.let { target ?: it }
                        }
                        handler.post {
                            if (utteranceId == synthesisId && playable?.isFile == true) {
                                playFile(playable)
                            } else {
                                finish(false)
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == synthesisId) finish(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == synthesisId) finish(false)
                    }
                }
            )
        }
    }

    fun speak(
        text: String,
        speakerId: Int,
        gain: Float,
        onComplete: (Boolean) -> Unit
    ) {
        stop()
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            handler.post { onComplete(false) }
            return
        }
        completion = onComplete
        val currentGeneration = ++generation
        val safeGain = gain.coerceIn(
            V2TokenSavingConfig.MIN_TTS_GAIN.toFloat(),
            V2TokenSavingConfig.MAX_TTS_GAIN.toFloat()
        )
        val target = cachedWelcomeFile(
            normalizedText,
            "sherpa-${speakerId.coerceIn(0, 4)}-gain-$safeGain"
        )
        if (target.isFile && target.length() > WAV_HEADER_SIZE) {
            playFile(target)
            return
        }
        synthesizeWithSherpa(
            normalizedText,
            speakerId.coerceIn(0, 4),
            safeGain,
            target,
            currentGeneration
        )
    }

    private fun synthesizeWithSystem(text: String, target: File, gain: Float) {
        if (!ready) {
            finish(false)
            return
        }

        val source = File.createTempFile("welcome-source-", ".wav", cacheDirectory)
        val id = "v2-welcome-synthesis-${UUID.randomUUID()}"
        synthesisId = id
        synthesisSource = source
        synthesisTarget = target
        synthesisGain = gain
        val result = tts?.synthesizeToFile(text, Bundle(), source, id)
            ?: TextToSpeech.ERROR
        if (result == TextToSpeech.ERROR) {
            finish(false)
        } else {
            handler.postDelayed({ finish(false) }, MAX_SYNTHESIS_DURATION_MS)
        }
    }

    private fun synthesizeWithSherpa(
        text: String,
        speakerId: Int,
        gain: Float,
        target: File,
        currentGeneration: Long
    ) {
        handler.postDelayed({ finish(false) }, MAX_SHERPA_SYNTHESIS_DURATION_MS)
        sherpaExecutor.execute {
            val source = File.createTempFile("welcome-sherpa-", ".wav", cacheDirectory)
            val success = runCatching {
                val modelDirectory = ensureBundledModelFiles()
                val vits = OfflineTtsVitsModelConfig(
                    model = File(modelDirectory, "model.onnx").absolutePath,
                    lexicon = File(modelDirectory, "lexicon.txt").absolutePath,
                    tokens = File(modelDirectory, "tokens.txt").absolutePath,
                    dictDir = File(modelDirectory, "dict").absolutePath,
                    lengthScale = 1.0f
                )
                val model = OfflineTtsModelConfig(
                    vits = vits,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
                val config = OfflineTtsConfig(
                    model = model,
                    ruleFsts = listOf("date.fst", "number.fst", "phone.fst")
                        .joinToString(",") { File(modelDirectory, it).absolutePath },
                    maxNumSentences = 1
                )
                val offlineTts = OfflineTts(config = config)
                try {
                    val audio = offlineTts.generate(text, speakerId, 1.0f)
                    check(audio.samples.isNotEmpty())
                    check(audio.save(source.absolutePath))
                } finally {
                    offlineTts.release()
                }
                check(amplifyPcmWav(source, target, gain))
            }.isSuccess
            source.delete()
            handler.post {
                if (generation != currentGeneration || completion == null) {
                    return@post
                }
                if (success && target.isFile) {
                    playFile(target)
                } else {
                    val systemTarget = cachedWelcomeFile(text, "system-gain-$gain")
                    if (systemTarget.isFile) {
                        playFile(systemTarget)
                    } else {
                        synthesizeWithSystem(text, systemTarget, gain)
                    }
                }
            }
        }
    }

    private fun ensureBundledModelFiles(): File {
        val destination = File(application.filesDir, MODEL_INSTALL_ROOT)
        val installedModel = File(destination, "model.onnx")
        if (
            installedModel.length() == MODEL_SIZE_BYTES &&
            File(destination, "lexicon.txt").isFile &&
            File(destination, "tokens.txt").isFile &&
            File(destination, "dict").isDirectory
        ) {
            return destination
        }

        val temporary = File(application.filesDir, "$MODEL_INSTALL_ROOT.tmp")
        temporary.deleteRecursively()
        temporary.mkdirs()
        listOf("lexicon.txt", "tokens.txt", "date.fst", "number.fst", "phone.fst")
            .forEach { copyAsset("$MODEL_ASSET_ROOT/$it", File(temporary, it)) }
        copyAssetDirectory("$MODEL_ASSET_ROOT/dict", File(temporary, "dict"))
        FileOutputStream(File(temporary, "model.onnx")).use { output ->
            MODEL_PARTS.forEach { part ->
                application.assets.open("$MODEL_ASSET_ROOT/$part").use { input ->
                    input.copyTo(output, MODEL_COPY_BUFFER_SIZE)
                }
            }
        }
        check(File(temporary, "model.onnx").length() == MODEL_SIZE_BYTES) {
            "内置离线语音模型不完整"
        }
        destination.deleteRecursively()
        check(temporary.renameTo(destination)) { "无法安装内置离线语音模型" }
        return destination
    }

    private fun copyAsset(assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        application.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output, MODEL_COPY_BUFFER_SIZE)
            }
        }
    }

    private fun copyAssetDirectory(assetPath: String, destination: File) {
        destination.mkdirs()
        val children = application.assets.list(assetPath).orEmpty()
        children.forEach { child ->
            val childAsset = "$assetPath/$child"
            val childDestination = File(destination, child)
            if (application.assets.list(childAsset).orEmpty().isNotEmpty()) {
                copyAssetDirectory(childAsset, childDestination)
            } else {
                copyAsset(childAsset, childDestination)
            }
        }
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        completion = null
        synthesisId = null
        synthesisSource?.delete()
        synthesisSource = null
        synthesisTarget = null
        runCatching { tts?.stop() }
        releasePlayer()
    }

    fun shutdown() {
        stop()
        sherpaExecutor.shutdownNow()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun configureLanguage(): Boolean {
        val engine = tts ?: return false
        val result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        return result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun playFile(file: File) {
        handler.removeCallbacksAndMessages(null)
        synthesisSource?.takeIf { it != file }?.delete()
        synthesisSource = null
        synthesisTarget = null
        synthesisId = null
        releasePlayer()
        runCatching {
            MediaPlayer().also { mediaPlayer ->
                player = mediaPlayer
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mediaPlayer.setDataSource(file.absolutePath)
                mediaPlayer.setOnCompletionListener { finish(true) }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    finish(false)
                    true
                }
                mediaPlayer.prepare()
                mediaPlayer.start()
                handler.postDelayed({ finish(false) }, MAX_PLAYBACK_DURATION_MS)
            }
        }.onFailure {
            finish(false)
        }
    }

    private fun finish(success: Boolean) {
        handler.post {
            handler.removeCallbacksAndMessages(null)
            synthesisSource?.delete()
            synthesisSource = null
            synthesisTarget = null
            synthesisId = null
            releasePlayer()
            completion?.also {
                completion = null
                it(success)
            }
        }
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun cachedWelcomeFile(text: String, engine: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$CACHE_VERSION:$engine:$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDirectory, "$digest.wav")
    }

    private fun amplifyPcmWav(source: File, target: File, gain: Float): Boolean =
        runCatching {
            val bytes = source.readBytes()
            require(bytes.size > WAV_HEADER_SIZE)
            require(bytes.ascii(0, 4) == "RIFF")
            require(bytes.ascii(8, 12) == "WAVE")

            var offset = 12
            var audioFormat = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0
            while (offset + 8 <= bytes.size) {
                val chunkId = bytes.ascii(offset, offset + 4)
                val chunkLength = bytes.littleEndianInt(offset + 4)
                val contentOffset = offset + 8
                if (contentOffset + chunkLength > bytes.size) break
                when (chunkId) {
                    "fmt " -> {
                        audioFormat = bytes.littleEndianShort(contentOffset)
                        bitsPerSample = bytes.littleEndianShort(contentOffset + 14)
                    }
                    "data" -> {
                        dataOffset = contentOffset
                        dataLength = chunkLength
                        break
                    }
                }
                offset = contentOffset + chunkLength + (chunkLength and 1)
            }
            require(audioFormat == WAVE_FORMAT_PCM)
            require(bitsPerSample == 16)
            require(dataOffset >= 0 && dataLength > 0)

            val end = (dataOffset + dataLength).coerceAtMost(bytes.size)
            var sampleOffset = dataOffset
            while (sampleOffset + 1 < end) {
                val sample = (
                    (bytes[sampleOffset].toInt() and 0xff) or
                        (bytes[sampleOffset + 1].toInt() shl 8)
                    ).toShort().toInt()
                val amplified = (sample * gain).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[sampleOffset] = (amplified and 0xff).toByte()
                bytes[sampleOffset + 1] = ((amplified shr 8) and 0xff).toByte()
                sampleOffset += 2
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            true
        }.getOrDefault(false)

    private fun ByteArray.ascii(start: Int, end: Int): String =
        String(this, start, end - start, Charsets.US_ASCII)

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val CACHE_DIRECTORY = "v2-local-welcome"
        private const val MODEL_ASSET_ROOT = "sherpa-tts-zh-ll"
        private const val MODEL_INSTALL_ROOT = "sherpa-tts-zh-ll-bundled-v1"
        private const val MODEL_SIZE_BYTES = 121_100_803L
        private val MODEL_PARTS = listOf(
            "model.onnx.part-00",
            "model.onnx.part-01",
            "model.onnx.part-02"
        )
        private const val MODEL_COPY_BUFFER_SIZE = 128 * 1024
        private const val CACHE_VERSION = 5
        private const val WAV_HEADER_SIZE = 44
        private const val WAVE_FORMAT_PCM = 1
        private const val MAX_SYNTHESIS_DURATION_MS = 8_000L
        private const val MAX_SHERPA_SYNTHESIS_DURATION_MS = 30_000L
        private const val MAX_PLAYBACK_DURATION_MS = 6_000L
    }
}
