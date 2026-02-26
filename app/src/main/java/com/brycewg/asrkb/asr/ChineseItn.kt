package com.brycewg.asrkb.asr

/**
 * 中文数字 ITN（逆文本规范化）
 * 参考 CapsWriter-Offline 自研规则，支持范围、百分/分数/比值、日期与时间等场景。
 */
object ChineseItn {
    private val unitMapping: Map<String, String?> = linkedMapOf(
        "千米每小时" to "km/h",
        "千克" to "kg",
        "千米" to "千米",
        "克" to "g",
        "米" to "米",
        "个" to null,
        "只" to null,
        "分" to null,
        "万" to null,
        "亿" to null,
        "秒" to null,
        "年" to null,
        "月" to null,
        "日" to null,
        "天" to null,
        "时" to null,
        "钟" to null,
        "人" to null,
        "层" to null,
        "楼" to null,
        "倍" to null,
        "块" to null,
        "次" to null,
    )

    private val commonUnits: String = unitMapping.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    private val unitSuffixRegex = Regex("($commonUnits|[a-zA-Z]+)$")

    private val numMapper: Map<Char, Char> = mapOf(
        '零' to '0',
        '一' to '1',
        '幺' to '1',
        '二' to '2',
        '两' to '2',
        '三' to '3',
        '四' to '4',
        '五' to '5',
        '六' to '6',
        '七' to '7',
        '八' to '8',
        '九' to '9',
        '点' to '.',
    )

    private val valueMapper: Map<Char, Int> = mapOf(
        '零' to 0,
        '一' to 1,
        '二' to 2,
        '两' to 2,
        '三' to 3,
        '四' to 4,
        '五' to 5,
        '六' to 6,
        '七' to 7,
        '八' to 8,
        '九' to 9,
        '十' to 10,
        '百' to 100,
        '千' to 1000,
        '万' to 10000,
        '亿' to 100000000,
    )

    private val idioms: Set<String> = setOf(
        "正经八百", "五零二落", "五零四散", "五十步笑百步", "乌七八糟", "污七八糟", "四百四病", "思绪万千",
        "十有八九", "十之八九", "三十而立", "三十六策", "三十六计", "三十六行", "三五成群", "三百六十行", "三六九等",
        "七老八十", "七零八落", "七零八碎", "七七八八", "乱七八遭", "乱七八糟", "略知一二", "零零星星", "零七八碎",
        "九九归一", "二三其德", "二三其意", "无银三百两", "八九不离十", "百分之百", "年三十", "烂七八糟",
        "一点一滴", "路易十六", "九三学社", "五四运动", "入木三分", "三十六计", "九九八十一", "三七二十一",
        "十二五", "十三五", "十四五", "十五五", "十六五", "十七五", "十八五",
    )

    private val idiomRegex = Regex(
        idioms
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) },
    )

    private val fuzzyRegex = Regex("几")

    private val pureNumRegex = Regex("[零幺一二两三四五六七八九]+(点[零幺一二两三四五六七八九]+)* *([a-zA-Z]|$commonUnits)?")
    private val valueNumRegex = Regex("十?(零?[一二两三四五六七八九十][十百千万]{1,2})*零?十?[一二三四五六七八九]?(点[零一二三四五六七八九]+)? *([a-zA-Z]|$commonUnits)?")
    private val consecutiveTensRegex = Regex("^((?:十[一二三四五六七八九])+)(?:($commonUnits))?$")
    private val consecutiveHundredsRegex = Regex("^((?:[一二三四五六七八九]百零?[一二三四五六七八九])+)(?:($commonUnits))?$")

    private val percentRegex = Regex("(?<![一二三四五六七八九])百分之[零一二三四五六七八九十百千万]+(?:点[零一二三四五六七八九]+)?")
    private val fractionRegex = Regex("([零一二三四五六七八九十百千万]+(?:点[零一二三四五六七八九]+)?)分之([零一二三四五六七八九十百千万]+(?:点[零一二三四五六七八九]+)?)")
    private val timeRegex = Regex("[0-9零一二两三四五六七八九十]+点[0-9零一二两三四五六七八九十]+分(?:[0-9零一二两三四五六七八九十]+秒)?")
    private val dateRegex = Regex("([0-9零一二两三四五六七八九十]+年)?([0-9零一二两三四五六七八九十]+月)?([0-9零一二两三四五六七八九十]+[日号])?")

    private val rangePattern1 = Regex("([二三四五六七八九])([二三四五六七八九])([十百千万亿])([万千百亿])?")
    private val rangePattern2 = Regex("(十|[一二三四五六七八九十]+[十百千万])([一二三四五六七八九])([一二三四五六七八九])([万千亿])?")
    private val rangePattern3 = Regex("^([一二三四五六七八九])([一二三四五六七八九])$")

    private val candidateRegex = Regex("[${buildAllowedCharClass()}A-Za-z0-9\\s]+")

    fun normalize(input: String): String {
        if (input.isBlank()) return input
        val idiomRanges = findIdiomRanges(input)
        val sb = StringBuilder()
        var lastIndex = 0
        for (m in candidateRegex.findAll(input)) {
            val start = m.range.first
            if (start > lastIndex) {
                sb.append(input.substring(lastIndex, start))
            }
            if (idiomRanges.any { rangesOverlap(it, m.range) }) {
                sb.append(m.value)
            } else {
                sb.append(replaceSegment(m.value))
            }
            lastIndex = m.range.last + 1
        }
        if (lastIndex < input.length) {
            sb.append(input.substring(lastIndex))
        }
        return normalizeArabicDecimalDotSpacing(sb.toString())
    }

    private fun replaceSegment(segment: String): String {
        val (leading, core, trailing) = splitOuterWhitespace(segment)
        if (core.isEmpty()) return segment
        return leading + replaceSegmentCore(core) + trailing
    }

    private fun replaceSegmentCore(segment: String): String {
        if (!containsChineseNumber(segment)) return segment
        if (idioms.any { segment.contains(it) }) return segment
        if (fuzzyRegex.containsMatchIn(segment)) return segment

        val prefixMatch = Regex("^([a-zA-Z]+\\s*)(.+)$").find(segment)
        if (prefixMatch != null && containsChineseNumber(prefixMatch.groupValues[2])) {
            return prefixMatch.groupValues[1] + replaceSegment(prefixMatch.groupValues[2])
        }

        val range = convertRange(segment)
        if (range != null) return range

        val dateTime = convertDateTimeConnected(segment)
        if (dateTime != null) return dateTime

        val time = convertTime(segment)
        if (time != null) return time

        val matchBase = stripTrailingUnit(segment)
        val compactBase = stripWhitespace(matchBase)
        if (pureNumRegex.matches(compactBase)) {
            val pure = convertPureNum(segment, strict = false)
            if (pure != null) return pure
        }

        val consecutive = convertConsecutive(segment)
        if (consecutive != null) return consecutive

        if (valueNumRegex.matches(compactBase)) {
            val value = convertValueNum(segment)
            if (value != null) return value
        }

        val percent = convertPercent(segment)
        if (percent != null) return percent

        val fraction = convertFraction(segment)
        if (fraction != null) return fraction

        val date = convertDate(segment)
        if (date != null) return date

        return segment
    }

    private fun convertRange(input: String): String? {
        if (input.contains('点')) return null
        val (baseRaw, unit) = stripUnit(input)
        val base = stripWhitespace(baseRaw)

        rangePattern1.find(base)?.let { m ->
            val d1 = valueMapper[m.groupValues[1][0]] ?: return null
            val d2 = valueMapper[m.groupValues[2][0]] ?: return null
            val scale = m.groupValues[3]
            val suffix = m.groupValues.getOrNull(4).orEmpty()
            val out = when (scale) {
                "十" -> "${d1 * 10}~${d2 * 10}$suffix"
                "百" -> "${d1 * 100}~${d2 * 100}$suffix"
                "千" -> "${d1 * 1000}~${d2 * 1000}$suffix"
                "万", "亿" -> "$d1~$d2$scale$suffix"
                else -> return null
            }
            return out + unit
        }

        rangePattern2.find(base)?.let { m ->
            val basePart = m.groupValues[1]
            val d1 = valueMapper[m.groupValues[2][0]] ?: return null
            val d2 = valueMapper[m.groupValues[3][0]] ?: return null
            val suffix = m.groupValues.getOrNull(4).orEmpty()
            val lastChar = basePart.lastOrNull() ?: return null
            val lastValue = valueMapper[lastChar] ?: return null
            val baseValue = parseValueWithoutUnit(basePart) ?: return null
            val multiplier = lastValue / 10
            val left = baseValue + d1 * multiplier
            val right = baseValue + d2 * multiplier
            return "$left~${right}$suffix$unit"
        }

        rangePattern3.find(base)?.let { m ->
            val d1 = valueMapper[m.groupValues[1][0]] ?: return null
            val d2 = valueMapper[m.groupValues[2][0]] ?: return null
            return "$d1~$d2$unit"
        }

        return null
    }

    private fun convertTime(input: String): String? {
        val text = stripWhitespace(input)
        if (!timeRegex.matches(text)) return null
        val dot = text.indexOf('点')
        val fen = text.indexOf('分')
        if (dot <= 0 || fen <= dot) return null
        val miao = text.indexOf('秒')
        val hourText = text.substring(0, dot)
        val minuteText = text.substring(dot + 1, fen)
        val secondText = if (miao > fen) text.substring(fen + 1, miao) else ""

        val hour = parseIntValue(hourText) ?: return null
        val minute = parseIntValue(minuteText) ?: return null
        val second = if (secondText.isNotEmpty()) parseIntValue(secondText) else null

        val base = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        return if (second != null) {
            "$base:${second.toString().padStart(2, '0')}"
        } else {
            base
        }
    }

    private fun convertPercent(input: String): String? {
        val m = percentRegex.matchEntire(input) ?: return null
        val num = m.value.removePrefix("百分之")
        val converted = convertValueNum(num) ?: return null
        return converted + "%"
    }

    private fun convertFraction(input: String): String? {
        val m = fractionRegex.matchEntire(input) ?: return null
        val left = m.groupValues[1]
        val right = m.groupValues[2]
        val leftValue = convertValueNum(left) ?: return null
        val rightValue = convertValueNum(right) ?: return null
        return "$rightValue/$leftValue"
    }

    private fun convertDate(input: String): String? {
        val text = stripWhitespace(input)
        val m = dateRegex.matchEntire(text) ?: return null
        val yearRaw = m.groupValues.getOrNull(1).orEmpty()
        val monthRaw = m.groupValues.getOrNull(2).orEmpty()
        val dayRaw = m.groupValues.getOrNull(3).orEmpty()
        if (yearRaw.isEmpty() && monthRaw.isEmpty() && dayRaw.isEmpty()) return null

        val year = if (yearRaw.isNotEmpty()) {
            val part = yearRaw.removeSuffix("年")
            part.toIntOrNull()?.toString()
                ?: convertPureNum(part, strict = true)?.takeIf { it.isNotEmpty() }
                ?: convertValueNum(part)
        } else {
            null
        }

        val month = if (monthRaw.isNotEmpty()) {
            val part = monthRaw.removeSuffix("月")
            part.toIntOrNull()?.toString() ?: convertValueNum(part)
        } else {
            null
        }

        val day = if (dayRaw.isNotEmpty()) {
            val suffix = if (dayRaw.endsWith("日")) "日" else "号"
            val part = dayRaw.removeSuffix(suffix)
            (part.toIntOrNull()?.toString() ?: convertValueNum(part))?.let { it + suffix }
        } else {
            null
        }

        val sb = StringBuilder()
        if (year != null) sb.append(year).append("年")
        if (month != null) sb.append(month).append("月")
        if (day != null) sb.append(day)
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun convertConsecutive(input: String): String? {
        consecutiveTensRegex.matchEntire(input)?.let { m ->
            val body = m.groupValues[1]
            val unit = mapUnit(m.groupValues.getOrNull(2).orEmpty())
            val parts = Regex("十[一二三四五六七八九]").findAll(body).mapNotNull {
                convertValueNum(it.value)
            }.toList()
            if (parts.isNotEmpty()) {
                return parts.joinToString(" ") + unit
            }
        }
        consecutiveHundredsRegex.matchEntire(input)?.let { m ->
            val body = m.groupValues[1]
            val unit = mapUnit(m.groupValues.getOrNull(2).orEmpty())
            val parts = Regex("[一二三四五六七八九]百零?[一二三四五六七八九]").findAll(body).mapNotNull {
                convertValueNum(it.value)
            }.toList()
            if (parts.isNotEmpty()) {
                return parts.joinToString(" ") + unit
            }
        }
        return null
    }

    private fun convertPureNum(input: String, strict: Boolean): String? {
        val (raw, unit) = stripUnit(input)
        val text = stripWhitespace(raw)
        if (text.isEmpty()) return null
        if (!strict && text.length == 1 && text[0] == '一' && unit.isEmpty()) return input
        val out = StringBuilder()
        for (ch in text) {
            val mapped = numMapper[ch] ?: return null
            out.append(mapped)
        }
        return out.toString() + unit
    }

    private fun convertValueNum(input: String): String? {
        val (raw, unit) = stripUnit(input)
        val text = stripWhitespace(raw)
        if (text.isEmpty()) return null

        val parts = text.split('点', limit = 2)
        val intPart = parts[0]
        val decPart = if (parts.size > 1) parts[1] else ""

        var value = 0L
        var temp = 0L
        var base = 1L
        for (ch in intPart) {
            when (ch) {
                '十' -> {
                    temp = if (temp == 0L) 10L else temp * 10L
                    base = 1L
                }
                '零' -> base = 1L
                '一', '二', '两', '三', '四', '五', '六', '七', '八', '九' -> {
                    temp += valueMapper[ch] ?: 0
                }
                '万' -> {
                    value += temp
                    value *= 10000L
                    base = 1000L
                    temp = 0L
                }
                '百', '千' -> {
                    val weight = valueMapper[ch] ?: return null
                    value += temp * weight
                    base = weight / 10L
                    temp = 0L
                }
                else -> return null
            }
        }
        value += temp * base

        val decOut = if (decPart.isNotEmpty()) {
            val sb = StringBuilder()
            for (ch in decPart) {
                val mapped = numMapper[ch] ?: return null
                sb.append(mapped)
            }
            sb.toString()
        } else {
            ""
        }

        val out = if (decOut.isNotEmpty()) "$value.$decOut" else value.toString()
        return out + unit
    }

    private fun parseIntValue(input: String): Int? {
        val text = stripWhitespace(input)
        text.toIntOrNull()?.let { return it }
        val value = convertValueNum(text) ?: convertPureNum(text, strict = true)
        return value?.toIntOrNull()
    }

    private fun parseValueWithoutUnit(input: String): Long? {
        val text = stripWhitespace(input)
        if (text.isEmpty()) return null

        val parts = text.split('点', limit = 2)
        val intPart = parts[0]
        val decPart = if (parts.size > 1) parts[1] else ""
        if (decPart.isNotEmpty()) return null

        var value = 0L
        var temp = 0L
        var base = 1L
        for (ch in intPart) {
            when (ch) {
                '十' -> {
                    temp = if (temp == 0L) 10L else temp * 10L
                    base = 1L
                }
                '零' -> base = 1L
                '一', '二', '两', '三', '四', '五', '六', '七', '八', '九' -> {
                    temp += valueMapper[ch] ?: 0
                }
                '万' -> {
                    value += temp
                    value *= 10000L
                    base = 1000L
                    temp = 0L
                }
                '百', '千' -> {
                    val weight = valueMapper[ch] ?: return null
                    value += temp * weight
                    base = weight / 10L
                    temp = 0L
                }
                else -> return null
            }
        }
        value += temp * base
        return value
    }

    private fun stripTrailingUnit(input: String): String {
        val m = unitSuffixRegex.find(input) ?: return input
        return input.substring(0, m.range.first)
    }

    private fun stripUnit(input: String): Pair<String, String> {
        val m = unitSuffixRegex.find(input)
        if (m == null) return input to ""
        val unit = m.value
        val mapped = mapUnit(unit)
        return input.substring(0, m.range.first) to mapped
    }

    private fun mapUnit(unit: String): String {
        if (unit.isEmpty()) return ""
        val mapped = unitMapping[unit] ?: return unit
        return mapped ?: unit
    }

    private fun convertDateTimeConnected(input: String): String? {
        val text = stripWhitespace(input)
        val dotIndex = text.indexOf('点')
        if (dotIndex <= 0) return null
        val splitIndex = text.lastIndexOfAny(charArrayOf('日', '号', '月', '年'), startIndex = dotIndex - 1)
        if (splitIndex < 0) return null

        val dateCandidate = text.substring(0, splitIndex + 1)
        val timeCandidate = text.substring(splitIndex + 1)
        if (dateCandidate.isEmpty() || timeCandidate.isEmpty()) return null

        val convertedDate = convertDate(dateCandidate) ?: dateCandidate
        val convertedTime = convertTime(timeCandidate) ?: timeCandidate
        val out = convertedDate + convertedTime
        return out.takeIf { it != text }
    }

    private fun containsChineseNumber(input: String): Boolean {
        for (ch in input) {
            if (numMapper.containsKey(ch) || valueMapper.containsKey(ch) || ch == '幺' || ch == '两') {
                return true
            }
        }
        return false
    }

    private fun findIdiomRanges(input: String): List<IntRange> {
        val ranges = idiomRegex.findAll(input).map { it.range }.toList()
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun rangesOverlap(a: IntRange, b: IntRange): Boolean {
        return a.first <= b.last && b.first <= a.last
    }

    private fun splitOuterWhitespace(input: String): Triple<String, String, String> {
        if (input.isEmpty()) return Triple("", "", "")
        var start = 0
        while (start < input.length && isAnyWhitespace(input[start])) start++
        var end = input.length
        while (end > start && isAnyWhitespace(input[end - 1])) end--
        return Triple(input.substring(0, start), input.substring(start, end), input.substring(end))
    }

    private fun stripWhitespace(input: String): String {
        if (input.isEmpty()) return input
        return input.filterNot { isAnyWhitespace(it) }
    }

    private fun isAnyWhitespace(ch: Char): Boolean {
        return ch.isWhitespace() || Character.isSpaceChar(ch)
    }

    private fun normalizeArabicDecimalDotSpacing(input: String): String {
        if (input.isEmpty()) return input
        if (!input.contains('.') && !input.contains('．')) return input

        fun isAsciiDigit(ch: Char): Boolean = ch in '0'..'9'

        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (isAsciiDigit(ch)) {
                out.append(ch)
                val afterDigit = i + 1
                var k = afterDigit
                while (k < input.length && isAnyWhitespace(input[k])) k++
                if (k < input.length && (input[k] == '.' || input[k] == '．')) {
                    var l = k + 1
                    while (l < input.length && isAnyWhitespace(input[l])) l++
                    if (l < input.length && isAsciiDigit(input[l])) {
                        out.append('.')
                        out.append(input[l])
                        i = l + 1
                        continue
                    }
                }
                i = afterDigit
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun buildAllowedCharClass(): String {
        val chars = LinkedHashSet<Char>()
        numMapper.keys.forEach { chars.add(it) }
        valueMapper.keys.forEach { chars.add(it) }
        chars.add('几')
        chars.add('分')
        chars.add('之')
        chars.add('年')
        chars.add('月')
        chars.add('日')
        chars.add('号')
        chars.add('秒')
        unitMapping.keys.forEach { unit ->
            unit.forEach { chars.add(it) }
        }

        val sb = StringBuilder()
        for (ch in chars) {
            when (ch) {
                '\\', '-', ']', '^' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
