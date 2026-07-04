package com.fwp.doubaonewline.v3

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class V3DeepSeekEngine(context: Context) {
    interface Listener {
        fun onModelReady()
        fun onPartialAnswer(text: String)
        fun onFinalAnswer(text: String)
        fun onError(message: String)
    }

    private val application = context.applicationContext
    private val engine: InferenceEngine = AiChat.getInferenceEngine(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generationJob: Job? = null
    private var loadedModel: File? = null
    private var systemPrompt = ""
    private var completedRounds = 0
    private val generation = AtomicLong(0)

    fun load(
        model: File,
        threads: Int,
        maxSentences: Int,
        listener: Listener
    ) {
        cancelGeneration()
        scope.launch {
            runCatching {
                if (
                    sharedLoadedModelPath == model.absolutePath &&
                    engine.state.value is InferenceEngine.State.ModelReady
                ) {
                    loadedModel = model
                    systemPrompt = buildSystemPrompt(maxSentences)
                    completedRounds = 0
                    return@runCatching
                }
                unloadInternal()
                engine.state.filter {
                    it is InferenceEngine.State.Initialized ||
                        it is InferenceEngine.State.ModelReady
                }.first()
                engine.configure(threads, 2_048)
                engine.loadModel(model.absolutePath)
                systemPrompt = buildSystemPrompt(maxSentences)
                engine.setSystemPrompt(systemPrompt)
                loadedModel = model
                sharedLoadedModelPath = model.absolutePath
                completedRounds = 0
            }.onSuccess {
                withContext(Dispatchers.Main) { listener.onModelReady() }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    listener.onError(it.message ?: "DeepSeek 模型加载失败")
                }
            }
        }
    }

    fun generate(
        prompt: String,
        maxContextRounds: Int,
        listener: Listener
    ) {
        cancelGeneration()
        val id = generation.incrementAndGet()
        generationJob = scope.launch {
            runCatching {
                if (completedRounds >= maxContextRounds) {
                    val model = checkNotNull(loadedModel)
                    unloadInternal()
                    engine.loadModel(model.absolutePath)
                    engine.setSystemPrompt(systemPrompt)
                    loadedModel = model
                    completedRounds = 0
                }
                val raw = StringBuilder()
                engine.sendUserPrompt(prompt, MAX_OUTPUT_TOKENS).collect { token ->
                    if (id != generation.get()) return@collect
                    raw.append(token)
                }
                if (id != generation.get()) return@runCatching
                val answer = finalAnswer(raw.toString())
                    .trim()
                    .ifBlank { "这个问题我暂时没有生成出合适的回答。" }
                completedRounds++
                withContext(Dispatchers.Main) { listener.onFinalAnswer(answer) }
            }.onFailure {
                if (id == generation.get()) {
                    withContext(Dispatchers.Main) {
                        listener.onError(it.message ?: "DeepSeek 本地推理失败")
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        generation.incrementAndGet()
        runCatching { engine.cancelGeneration() }
        generationJob?.cancel()
        generationJob = null
    }

    fun unload() {
        cancelGeneration()
        scope.launch { unloadInternal() }
    }

    fun shutdown(keepModelLoaded: Boolean = false) {
        cancelGeneration()
        if (!keepModelLoaded) {
            runCatching { engine.cleanUp() }
            sharedLoadedModelPath = null
        }
        scope.cancel()
    }

    private fun unloadInternal() {
        runCatching { engine.cleanUp() }
        loadedModel = null
        sharedLoadedModelPath = null
    }

    private fun buildSystemPrompt(maxSentences: Int) =
        "你是运行在手机上的中文语音助手。不要展示、复述或输出任何推理过程，" +
            "不要输出<think>标签，直接从最终答案的第一个字开始回答，不重复用户问题。" +
            "优先使用已有知识简短作答，不进行冗长分析。除非用户要求详细说明，" +
            "每次回答不超过${maxSentences.coerceIn(1, 3)}句话。"

    private fun finalAnswer(raw: String): String =
        when {
            "</think>" in raw -> raw.substringAfterLast("</think>")
            "<think>" in raw -> ""
            else -> raw
        }.replace(Regex("</?think>", RegexOption.IGNORE_CASE), "")
            .trimStart()

    companion object {
        private const val MAX_OUTPUT_TOKENS = 128
        @Volatile private var sharedLoadedModelPath: String? = null
    }
}
