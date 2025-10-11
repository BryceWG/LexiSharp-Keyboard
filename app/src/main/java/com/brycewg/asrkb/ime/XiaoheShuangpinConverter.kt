package com.brycewg.asrkb.ime

/**
 * 小鹤双拼 -> 全拼 转换器（最小可用子集）
 * - 将用户在中文模式下输入的小鹤双拼字母，按每两键一音节转换为标准拼音后返回。
 * - 仅做字母序列的成对转换；空格及标点保持不变。
 * - 覆盖常见韵母与必要的歧义消解规则，未识别的对保留原样以便回退。
 */
object XiaoheShuangpinConverter {
    // 特殊零声母/独立音节（兼容用）
    private val specialZeroOrSyllable = mapOf(
        "er" to "er",
        "mm" to "m",
        "nn" to "n",
        "gn" to "ng"
    )

    // 声母键映射（y/w 在小鹤方案中通常不单独输入，这里仅作兼容）
    private val initialKeyToShengmu = mapOf(
        'b' to "b", 'p' to "p", 'm' to "m", 'f' to "f",
        'd' to "d", 't' to "t", 'n' to "n", 'l' to "l",
        'g' to "g", 'k' to "k", 'h' to "h",
        'j' to "j", 'q' to "q", 'x' to "x",
        'r' to "r", 'z' to "z", 'c' to "c", 's' to "s",
        'y' to "y", 'w' to "w",
        // 复合声母
        'v' to "zh", 'i' to "ch", 'u' to "sh"
    )

    private val uGroupForL = setOf("g", "k", "h", "zh", "ch", "sh")
    private val uGroupForX = setOf("g", "k", "h", "zh", "ch", "sh", "z", "c", "s", "r")
    private val uGroupForK = setOf("g", "k", "h", "zh", "ch", "sh")

    private fun mapFinal(key: Char, shengmu: String): String? = when (key) {
        // 单韵母
        'a' -> "a"
        'e' -> "e"
        'i' -> "i"
        'u' -> "u"
        // o/uo 共用按键：b/p/m/f 与 w 后为 o，其余多为 uo
        'o' -> if (shengmu == "w" || shengmu in setOf("b", "p", "m", "f")) "o" else "uo"

        // 基本复合韵母
        'd' -> "ai"
        'w' -> "ei"
        'c' -> "ao"
        'z' -> "ou"
        'j' -> "an"
        'f' -> "en"
        'h' -> "ang"
        'g' -> "eng"
        's' -> if (shengmu in setOf("j", "q", "x")) "iong" else "ong"
        'r' -> "uan"

        // i 系
        'x' -> if (shengmu in uGroupForX) "ua" else "ia"
        'p' -> "ie"
        'n' -> "iao"
        'q' -> "iu"
        'm' -> "ian"
        'b' -> "in"
        'l' -> if (shengmu in uGroupForL) "uang" else "iang"
        'k' -> if (shengmu in uGroupForK) "uai" else "ing"
        // ü 系 / ui
        't' -> when (shengmu) {
            "n", "l" -> "üe"
            "j", "q", "x" -> "ue"
            else -> "ue"
        }
        'v' -> when (shengmu) {
            "j", "q", "x" -> "u"    // ju/qu/xu
            "n", "l" -> "ü"          // nü/lü
            else -> "ui"              // sui/dui/zhui
        }
        'y' -> when (shengmu) {
            "n", "l" -> "ün"
            else -> "un"
        }
        else -> null
    }

    private fun convertPair(pair: String): String {
        if (pair.length != 2) return pair
        val lower = pair.lowercase()
        specialZeroOrSyllable[lower]?.let { return it }
        val k1 = lower[0]
        val k2 = lower[1]
        val shengmu = initialKeyToShengmu[k1] ?: return pair
        val yunmu = mapFinal(k2, shengmu) ?: return pair
        return shengmu + yunmu
    }

    /**
     * 将整段小鹤双拼文本转换为全拼：
     * - 连续字母按两字符一音节解析；
     * - 其他字符原样保留；
     * - 奇数字母长度的片段保留原样（避免误拆）。
     */
    fun convert(input: String): String {
        if (input.isEmpty()) return input
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch.isLetter()) {
                var j = i
                while (j < input.length && input[j].isLetter()) j++
                val word = input.substring(i, j)
                var k = 0
                while (k + 1 < word.length) {
                    out.append(convertPair(word.substring(k, k + 2)))
                    k += 2
                }
                // 若剩最后一个字母，原样附加，避免误拆
                if (k < word.length) out.append(word.substring(k))
                i = j
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
