package com.fwp.doubaonewline.v3

import android.content.Context
import android.os.StatFs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V3ModelManager(context: Context) {
    data class InstalledFiles(
        val llm: File,
        val asrEncoder: File,
        val asrDecoder: File,
        val asrTokens: File
    )

    sealed interface State {
        data object Idle : State
        data class Downloading(val label: String, val percent: Int) : State
        data class Verifying(val label: String) : State
        data class Ready(val model: V3Model) : State
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
    private val root = File(application.filesDir, "v3-models").apply { mkdirs() }
    private val asrRoot = File(root, "asr-paraformer").apply { mkdirs() }
    private val llmRoot = File(root, "deepseek").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun installed(model: V3Model): InstalledFiles? {
        val llm = llmFile(model)
        val encoder = File(asrRoot, "encoder.int8.onnx")
        val decoder = File(asrRoot, "decoder.int8.onnx")
        val tokens = File(asrRoot, "tokens.txt")
        return InstalledFiles(llm, encoder, decoder, tokens).takeIf {
            llm.length() == model.downloadBytes &&
                encoder.length() == ASR_ENCODER_SIZE &&
                decoder.length() == ASR_DECODER_SIZE &&
                tokens.length() == ASR_TOKENS_SIZE
        }
    }

    fun isInstalled(model: V3Model): Boolean = installed(model) != null

    fun download(model: V3Model, onState: (State) -> Unit) {
        if (isInstalled(model)) {
            onState(State.Ready(model))
            return
        }
        val free = StatFs(root.absolutePath).availableBytes
        if (free < model.minimumFreeBytes) {
            onState(
                State.Failed(
                    "存储空间不足：${model.parameters} 至少需要 " +
                        "${model.minimumFreeBytes / GIB}GB 可用空间"
                )
            )
            return
        }
        executor.execute {
            runCatching {
                val files = asrRemoteFiles() + llmRemoteFile(model)
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
                checkNotNull(installed(model)) { "模型文件不完整" }
            }.onSuccess {
                onState(State.Ready(model))
            }.onFailure {
                onState(State.Failed(it.message ?: it.javaClass.simpleName))
            }
        }
    }

    fun delete(model: V3Model): Boolean {
        client.dispatcher.cancelAll()
        return llmFile(model).delete() or File(llmFile(model).absolutePath + ".part").delete()
    }

    fun close() {
        client.dispatcher.cancelAll()
        executor.shutdownNow()
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

    private fun asrRemoteFiles(): List<RemoteFile> {
        val base = "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
        fun urls(name: String) = listOf(
            "https://hf-mirror.com/$base/resolve/main/$name",
            "https://huggingface.co/$base/resolve/main/$name"
        )
        return listOf(
            RemoteFile(
                "中文流式识别编码器",
                File(asrRoot, "encoder.int8.onnx"),
                ASR_ENCODER_SIZE,
                "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a",
                urls("encoder.int8.onnx")
            ),
            RemoteFile(
                "中文流式识别解码器",
                File(asrRoot, "decoder.int8.onnx"),
                ASR_DECODER_SIZE,
                "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f",
                urls("decoder.int8.onnx")
            ),
            RemoteFile(
                "中文流式识别词表",
                File(asrRoot, "tokens.txt"),
                ASR_TOKENS_SIZE,
                "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
                urls("tokens.txt")
            )
        )
    }

    private fun llmRemoteFile(model: V3Model): RemoteFile {
        val (repo, name, sha) = when (model) {
            V3Model.DEEPSEEK_1_5B -> Triple(
                "QuantFactory/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
                "DeepSeek-R1-Distill-Qwen-1.5B.Q4_K_M.gguf",
                "41aa31689f2cbdcc5172e370db2ab7a10e17a9427520602437bd16d8d127d105"
            )
            V3Model.DEEPSEEK_7B -> Triple(
                "QuantFactory/DeepSeek-R1-Distill-Qwen-7B-GGUF",
                "DeepSeek-R1-Distill-Qwen-7B.Q4_K_M.gguf",
                "519abbc45e8de3cbcf70144fbb8a0bb46d67469b5142e0072b86759320f24cf7"
            )
        }
        return RemoteFile(
            "DeepSeek ${model.parameters}",
            llmFile(model),
            model.downloadBytes,
            sha,
            listOf("https://www.modelscope.cn/models/$repo/resolve/master/$name")
        )
    }

    private fun llmFile(model: V3Model) =
        File(llmRoot, "deepseek-r1-distill-qwen-${model.parameters.lowercase()}-q4_k_m.gguf")

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
        private const val GIB = 1024L * 1024 * 1024
        private const val ASR_ENCODER_SIZE = 165_462_184L
        private const val ASR_DECODER_SIZE = 71_664_561L
        private const val ASR_TOKENS_SIZE = 75_756L
    }
}
