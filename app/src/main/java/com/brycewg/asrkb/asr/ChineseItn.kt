// 中文规则 ITN 对外入口，供本地 ASR 识别结果做逆文本规范化。
package com.brycewg.asrkb.asr

/**
 * 中文数字 ITN（逆文本规范化）。
 *
 * 规则结构参考 references/Chinese-ITN：先用候选正则切出可能的中文数字片段，
 * 再经由 Tokenizer/Parser 与 reducer 流水线规约为百分数、分数、比值、日期、
 * 时间、范围或普通数值。
 */
object ChineseItn {
    fun normalize(input: String): String {
        if (input.isBlank()) return input
        ChineseItnRanges.parseRange(input)?.let { return it }
        val idiomRanges = ChineseItnMappings.findIdiomRanges(input)
        val sb = StringBuilder(input.length)
        var lastIndex = 0

        for (match in ChineseItnPatterns.candidateRegex.findAll(input)) {
            val start = match.range.first
            if (start > lastIndex) {
                sb.append(input.substring(lastIndex, start))
            }

            val head = match.groups[1]?.value.orEmpty()
            val original = match.groups[2]?.value.orEmpty()
            val converted = if (original.isEmpty() ||
                isWithinIdiomRange(match.range, idiomRanges) ||
                isColloquialPriceTail(input, match.range, original) ||
                isStandaloneChineseWordDigit(input, match.range, original)
            ) {
                head + original
            } else {
                head + ChineseItnReducer.replace(original)
            }
            sb.append(converted)
            lastIndex = match.range.last + 1
        }

        if (lastIndex < input.length) {
            sb.append(input.substring(lastIndex))
        }
        return ChineseItnText.normalizeArabicDecimalDotSpacing(sb.toString())
            .replace(Regex("(?<=\\d)千米每小时"), "km/h")
    }

    private fun isColloquialPriceTail(input: String, range: IntRange, original: String): Boolean {
        if (original.length != 1 || original[0] !in "零幺一二两三四五六七八九") return false
        val previous = input.getOrNull(range.first - 1)
        val next = input.getOrNull(range.last + 1)
        return previous == '块' || previous == '元' || next == '毛'
    }

    private fun isStandaloneChineseWordDigit(input: String, range: IntRange, original: String): Boolean {
        if (original.length != 1 || original[0] !in "零幺一二两三四五六七八九") return false
        val previous = input.getOrNull(range.first - 1)
        val next = input.getOrNull(range.last + 1)
        if (previous == '十' || next == '十') return false
        if (previous == '第') return true
        if (next == null || !isCjk(next)) return false
        return !ChineseItnMappings.startsWithKnownUnit(input, range.last + 1)
    }

    // 只保留固定词/成语本体，避免部分重叠的数字短语被误当成保护词。
    private fun isWithinIdiomRange(range: IntRange, idiomRanges: List<IntRange>): Boolean = idiomRanges.any { idiomRange ->
        idiomRange.first <= range.first && idiomRange.last >= range.last
    }

    private fun isCjk(ch: Char): Boolean = ch in '\u4E00'..'\u9FFF'
}
