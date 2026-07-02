package com.fwp.doubaonewline.v3

import android.content.Context
import com.fwp.doubaonewline.v2.V2LocalWelcomeSpeaker
import java.util.ArrayDeque

class V3TtsPlayer(context: Context) {
    private val speaker = V2LocalWelcomeSpeaker(context)
    private val queue = ArrayDeque<String>()
    private var generation = 0L
    private var speakerId = 0
    private var gain = 20f
    private var completion: (() -> Unit)? = null

    fun speak(text: String, speakerId: Int, gain: Int, onComplete: () -> Unit) {
        stop()
        this.speakerId = speakerId.coerceIn(0, 4)
        this.gain = gain.coerceIn(1, 30).toFloat()
        completion = onComplete
        queue.addAll(splitForSpeech(text))
        val current = ++generation
        playNext(current)
    }

    fun stop() {
        generation++
        queue.clear()
        completion = null
        speaker.stop()
    }

    fun shutdown() {
        stop()
        speaker.shutdown()
    }

    private fun playNext(current: Long) {
        if (current != generation) return
        val next = if (queue.isEmpty()) null else queue.removeFirst()
        if (next == null) {
            completion?.also {
                completion = null
                it()
            }
            return
        }
        speaker.speak(next, speakerId, gain) {
            if (current == generation) playNext(current)
        }
    }

    private fun splitForSpeech(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        text.trim().forEach { char ->
            current.append(char)
            if (char in "。！？；\n" || current.length >= 45) {
                current.toString().trim().takeIf(String::isNotEmpty)?.let(chunks::add)
                current.clear()
            }
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(chunks::add)
        return chunks.ifEmpty { listOf("我准备好了") }
    }
}
