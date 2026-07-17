package com.fwp.doubaonewline.v2

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import kotlin.math.max

data class WakeMatchResult(
    val matched: Boolean,
    val normalizedText: String,
    val normalizedWakeWord: String,
    val reason: String,
    val textPinyin: String = "",
    val wakeWordPinyin: String = "",
    val similarityPercent: Int = 0
)

object WakeWordTextMatcher {
    private val supportedWakeWordRegex = Regex("^[\\u4e00-\\u9fa5]{2,8}$")
    private val fillerWords = listOf("请", "帮我", "那个", "一下", "啊", "呀", "呢", "喂")
    private val punctuationRegex = Regex("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+")
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    private val validPinyinSyllables = setOf(
        "a", "ai", "an", "ang", "ao",
        "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu",
        "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo",
        "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui", "dun", "duo",
        "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
        "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
        "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
        "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
        "ka", "kai", "kan", "kang", "kao", "ke", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
        "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "lv", "luan", "lve", "lun", "luo",
        "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
        "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nv", "nuan", "nve", "nuo",
        "o", "ou",
        "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun",
        "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run", "ruo",
        "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang", "shao", "she", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
        "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
        "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
        "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun",
        "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue", "yun",
        "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo"
    )

    fun isSupportedWakeWord(text: String): Boolean =
        supportedWakeWordRegex.matches(normalizeWakeWord(text))

    fun normalizeWakeWord(text: String): String =
        text.trim().replace(Regex("\\s+"), "")

    fun matches(text: String, wakeWord: String): WakeMatchResult {
        val normalizedText = normalize(text)
        val normalizedWakeWord = normalize(wakeWord).ifBlank {
            normalize(V2TokenSavingConfig.DEFAULT_WAKE_WORD)
        }
        val textPinyin = toPinyinSyllables(normalizedText)
        val wakePinyin = toPinyinSyllables(normalizedWakeWord)
        val textPinyinLabel = textPinyin.joinToString(" ")
        val wakePinyinLabel = wakePinyin.joinToString(" ")
        if (normalizedText.isBlank()) {
            return WakeMatchResult(
                false,
                normalizedText,
                normalizedWakeWord,
                "没有识别到有效语音",
                textPinyinLabel,
                wakePinyinLabel
            )
        }
        if (normalizedText == normalizedWakeWord) {
            return WakeMatchResult(
                true,
                normalizedText,
                normalizedWakeWord,
                "文字完整匹配",
                textPinyinLabel,
                wakePinyinLabel,
                100
            )
        }
        val index = normalizedText.indexOf(normalizedWakeWord)
        if (index >= 0) {
            return WakeMatchResult(
                true,
                normalizedText,
                normalizedWakeWord,
                "文字包含唤醒词",
                textPinyinLabel,
                wakePinyinLabel,
                100
            )
        }
        val similarity = pinyinSimilarityPercent(textPinyin, wakePinyin)
        val threshold = MIN_PINYIN_MATCH_PERCENT
        if (similarity >= threshold) {
            return WakeMatchResult(
                true,
                normalizedText,
                normalizedWakeWord,
                "拼音相似度 $similarity%，达到 $threshold%",
                textPinyinLabel,
                wakePinyinLabel,
                similarity
            )
        }
        return WakeMatchResult(
            false,
            normalizedText,
            normalizedWakeWord,
            "拼音相似度 $similarity%，低于 $threshold%",
            textPinyinLabel,
            wakePinyinLabel,
            similarity
        )
    }

    fun normalize(value: String): String {
        var text = value.lowercase()
            .replace(punctuationRegex, "")
            .trim()
        fillerWords.forEach { filler ->
            text = text.replace(filler, "")
        }
        return text
    }

    fun toPinyinSyllables(value: String): List<String> =
        buildList {
            var latinBuffer = StringBuilder()
            fun flushLatin() {
                if (latinBuffer.isNotEmpty()) {
                    addAll(splitLatinPinyin(latinBuffer.toString()))
                    latinBuffer = StringBuilder()
                }
            }
            value.lowercase().forEach { char ->
                when {
                    char in 'a'..'z' -> latinBuffer.append(char)
                    char in '\u4e00'..'\u9fa5' -> {
                        flushLatin()
                        PinyinHelper.toHanyuPinyinStringArray(char, pinyinFormat)
                            ?.firstOrNull()
                            ?.let { add(it.replace("u:", "v")) }
                    }
                    else -> flushLatin()
                }
            }
            flushLatin()
        }

    private fun pinyinSimilarityPercent(text: List<String>, wakeWord: List<String>): Int {
        if (text.isEmpty() || wakeWord.isEmpty()) return 0
        var best = 0.0
        for (start in text.indices) {
            var score = 0.0
            val maxLength = minOf(wakeWord.size, text.size - start)
            for (offset in 0 until maxLength) {
                score += syllableSimilarity(text[start + offset], wakeWord[offset])
                best = max(best, score / wakeWord.size)
            }
        }
        return (best * 100).toInt()
    }

    private fun syllableSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val leftParts = splitInitialFinal(left)
        val rightParts = splitInitialFinal(right)
        val initialScore = when {
            leftParts.first == rightParts.first -> 0.35
            leftParts.first.isNotEmpty() &&
                rightParts.first.isNotEmpty() &&
                similarInitials(leftParts.first, rightParts.first) -> 0.20
            else -> 0.0
        }
        val finalScore = when {
            leftParts.second == rightParts.second -> 0.65
            leftParts.second.isNotEmpty() &&
                rightParts.second.isNotEmpty() &&
                (leftParts.second.contains(rightParts.second) ||
                    rightParts.second.contains(leftParts.second) ||
                    levenshtein(leftParts.second, rightParts.second) <= 1) -> 0.45
            else -> 0.0
        }
        return initialScore + finalScore
    }

    private fun splitInitialFinal(syllable: String): Pair<String, String> {
        val initial = listOf(
            "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
            "j", "q", "x", "r", "z", "c", "s", "y", "w"
        ).firstOrNull { syllable.startsWith(it) }.orEmpty()
        return initial to syllable.removePrefix(initial)
    }

    private fun similarInitials(left: String, right: String): Boolean =
        setOf(left, right) in setOf(
            setOf("d", "t"),
            setOf("b", "p"),
            setOf("g", "k"),
            setOf("z", "zh"),
            setOf("c", "ch"),
            setOf("s", "sh"),
            setOf("j", "q"),
            setOf("n", "l")
        )

    private fun splitLatinPinyin(value: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val next = (6 downTo 1)
                .mapNotNull { size ->
                    value.substring(index, minOf(value.length, index + size))
                        .takeIf { it in validPinyinSyllables }
                }
                .firstOrNull()
            if (next == null) {
                index++
            } else {
                result += next
                index += next.length
            }
        }
        return result
    }

    private fun levenshtein(left: String, right: String): Int {
        val costs = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            var previous = costs[0]
            costs[0] = i + 1
            for (j in right.indices) {
                val old = costs[j + 1]
                costs[j + 1] = minOf(
                    costs[j + 1] + 1,
                    costs[j] + 1,
                    previous + if (left[i] == right[j]) 0 else 1
                )
                previous = old
            }
        }
        return costs[right.length]
    }

    private const val MIN_PINYIN_MATCH_PERCENT = 30
}
