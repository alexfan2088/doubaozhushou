package com.fwp.doubaonewline.v2

import android.content.Context
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import java.io.File

data class WakeWordKeywordSource(
    val wakeWord: String,
    val modelDir: String,
    val keywordsFile: String,
    val useAssets: Boolean,
    val usingFallback: Boolean
)

object WakeWordKeywordBuilder {
    private val chineseRegex = Regex("^[\\u4e00-\\u9fa5]{2,8}$")
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITH_TONE_MARK
        vCharType = HanyuPinyinVCharType.WITH_U_UNICODE
    }
    private val initials = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), "")

    fun isSupportedWakeWord(text: String): Boolean =
        chineseRegex.matches(normalize(text))

    fun buildKeywordsLine(text: String, score: Float = 5.0f): String {
        val wakeWord = normalize(text)
        require(isSupportedWakeWord(wakeWord)) { "唤醒词只支持 2-8 个中文汉字" }
        val tokens = wakeWord.flatMap { char ->
            val pinyin = PinyinHelper.toHanyuPinyinStringArray(char, format)
                ?.firstOrNull()
                ?: error("无法转换唤醒词：$char")
            splitSyllable(normalizeToneMarks(pinyin))
        }
        return "${tokens.joinToString(" ")} :$score @$wakeWord"
    }

    fun createKeywordSource(context: Context, configuredWakeWord: String): WakeWordKeywordSource {
        val normalized = normalize(configuredWakeWord)
        return runCatching {
            val modelDir = ensureFileModelDir(context)
            val file = File(modelDir, "keywords.txt")
            file.writeText(buildKeywordsLine(normalized) + "\n")
            WakeWordKeywordSource(
                wakeWord = normalized,
                modelDir = modelDir.absolutePath,
                keywordsFile = file.absolutePath,
                useAssets = false,
                usingFallback = false
            )
        }.getOrElse {
            defaultSource(usingFallback = true)
        }
    }

    fun defaultSource(usingFallback: Boolean = false): WakeWordKeywordSource =
        WakeWordKeywordSource(
            wakeWord = V2TokenSavingConfig.DEFAULT_WAKE_WORD,
            modelDir = MODEL_DIR,
            keywordsFile = "$MODEL_DIR/keywords.txt",
            useAssets = true,
            usingFallback = usingFallback
        )

    private fun splitSyllable(syllable: String): List<String> {
        val initial = initials.firstOrNull { syllable.startsWith(it) }
        return if (initial == null || initial.length == syllable.length) {
            listOf(syllable)
        } else {
            listOf(initial, syllable.substring(initial.length))
        }
    }

    private fun normalizeToneMarks(value: String): String =
        value
            .replace('ă', 'ǎ')
            .replace('ĕ', 'ě')
            .replace('ĭ', 'ǐ')
            .replace('ŏ', 'ǒ')
            .replace('ŭ', 'ǔ')

    private fun ensureFileModelDir(context: Context): File {
        val destination = File(context.filesDir, MODEL_DIR)
        if (!destination.exists()) destination.mkdirs()
        listOf(
            "encoder.int8.onnx",
            "decoder.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        ).forEach { name ->
            val target = File(destination, name)
            if (!target.exists() || target.length() == 0L) {
                context.assets.open("$MODEL_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return destination
    }

    const val MODEL_DIR = "kws-zh-en"
}
