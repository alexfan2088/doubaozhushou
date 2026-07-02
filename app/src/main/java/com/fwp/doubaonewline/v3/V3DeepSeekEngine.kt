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
                    val visible = visibleAnswer(raw.toString(), generationFinished = false)
                    if (visible.isNotBlank()) {
                        withContext(Dispatchers.Main) { listener.onPartialAnswer(visible) }
                    }
                }
                if (id != generation.get()) return@runCatching
                val answer = visibleAnswer(raw.toString(), generationFinished = true)
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

    fun shutdown() {
        cancelGeneration()
        runCatching { engine.cleanUp() }
        scope.cancel()
    }

    private fun unloadInternal() {
        runCatching { engine.cleanUp() }
        loadedModel = null
    }

    private fun buildSystemPrompt(maxSentences: Int) =
        "你是运行在手机上的中文语音助手。直接给出最终答案，不展示思考过程，" +
            "不要输出<think>标签，不重复用户问题。除非用户要求详细说明，" +
            "每次回答不超过${maxSentences.coerceIn(1, 3)}句话。"

    private fun visibleAnswer(raw: String, generationFinished: Boolean): String {
        val afterThinking = if ("</think>" in raw) {
            raw.substringAfterLast("</think>")
        } else if ("<think>" in raw && !generationFinished) {
            ""
        } else {
            raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .replace("<think>", "")
                .replace("</think>", "")
        }
        return afterThinking.trimStart()
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 128
    }
}
