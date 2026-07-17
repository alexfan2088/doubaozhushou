package com.fwp.doubaonewline.v2

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V2WakeAsrModelManager(context: Context) {
    sealed interface State {
        data class Downloading(val label: String, val percent: Int) : State
        data class Verifying(val label: String) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private data class RemoteFile(
        val label: String,
        val target: File,
        val size: Long,
        val sha256: String,
        val urls: List<String>
    )

    private val application = context.applicationContext
    private val root = File(application.filesDir, "v3-models/asr-paraformer").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun installedFiles(): V2WakeAsrFiles? = V2AsrWakeWordDetector.installedFiles(application)

    fun isInstalled(): Boolean = installedFiles() != null

    fun download(onState: (State) -> Unit) {
        if (isInstalled()) {
            onState(State.Ready)
            return
        }
        executor.execute {
            runCatching {
                val files = remoteFiles()
                val total = files.sumOf { if (it.target.length() == it.size) 0L else it.size }
                    .coerceAtLeast(1L)
                var completed = 0L
                files.forEach { remote ->
                    if (
                        remote.target.length() == remote.size &&
                        sha256(remote.target).equals(remote.sha256, true)
                    ) {
                        return@forEach
                    }
                    downloadFile(remote) { fileBytes ->
                        val percent = ((completed + fileBytes) * 100L / total)
                            .toInt().coerceIn(0, 99)
                        onState(State.Downloading(remote.label, percent))
                    }
                    onState(State.Verifying(remote.label))
                    check(remote.target.length() == remote.size) {
                        "${remote.label} 文件大小不正确"
                    }
                    check(sha256(remote.target).equals(remote.sha256, true)) {
                        "${remote.label} SHA-256 校验失败"
                    }
                    completed += remote.size
                }
                checkNotNull(installedFiles()) { "本地语音识别模型文件不完整" }
            }.onSuccess {
                onState(State.Ready)
            }.onFailure {
                onState(State.Failed(it.message ?: it.javaClass.simpleName))
            }
        }
    }

    fun close() {
        client.dispatcher.cancelAll()
        executor.shutdownNow()
    }

    private fun remoteFiles(): List<RemoteFile> {
        val base = "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
        fun urls(name: String) = listOf(
            "https://hf-mirror.com/$base/resolve/main/$name",
            "https://huggingface.co/$base/resolve/main/$name"
        )
        return listOf(
            RemoteFile(
                "中文识别编码器",
                File(root, "encoder.int8.onnx"),
                ASR_ENCODER_SIZE,
                "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
                urls("encoder.int8.onnx")
            ),
            RemoteFile(
                "中文识别解码器",
                File(root, "decoder.int8.onnx"),
                ASR_DECODER_SIZE,
                "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
                urls("decoder.int8.onnx")
            ),
            RemoteFile(
                "中文识别词表",
                File(root, "tokens.txt"),
                ASR_TOKENS_SIZE,
                "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
                urls("tokens.txt")
            )
        )
    }

    private fun downloadFile(remote: RemoteFile, progress: (Long) -> Unit) {
        val part = File(remote.target.absolutePath + ".part")
        var lastFailure: Throwable? = null
        remote.urls.forEach { url ->
            val result = runCatching {
                var existing = part.length().coerceAtMost(remote.size)
                val request = Request.Builder().url(url).apply {
                    if (existing > 0L) header("Range", "bytes=$existing-")
                }.build()
                client.newCall(request).execute().use { response ->
                    if (existing > 0L && response.code != 206) {
                        part.delete()
                        existing = 0L
                    }
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = checkNotNull(response.body) { "下载内容为空" }
                    FileOutputStream(part, existing > 0L).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var downloaded = existing
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                downloaded += count
                                progress(downloaded)
                            }
                        }
                    }
                }
                check(part.length() == remote.size) { "下载未完成" }
                remote.target.parentFile?.mkdirs()
                remote.target.delete()
                check(part.renameTo(remote.target)) { "无法安装下载文件" }
            }
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
        }
        throw IllegalStateException(
            "${remote.label} 下载失败：${lastFailure?.message ?: "未知错误"}",
            lastFailure
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val ASR_ENCODER_SIZE = 165_462_184L
        private const val ASR_DECODER_SIZE = 71_664_561L
        private const val ASR_TOKENS_SIZE = 75_756L
    }
}
