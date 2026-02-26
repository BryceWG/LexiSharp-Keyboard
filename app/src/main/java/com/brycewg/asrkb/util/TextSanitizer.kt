package com.brycewg.asrkb.util

/**
 * 文本清理工具
 * - 提供去除句末标点与 emoji 的统一实现，避免重复代码。
 */
object TextSanitizer {

    /**
     * 去除字符串末尾的标点和 emoji（包含组合序列）。
     * 规则：
     * - 剥离各类 Unicode 标点符号（含中英文常见标点）
     * - 剥离常见 emoji 范围，以及其修饰/连接符（ZWJ/变体选择符/肤色/标签/Keycap 组合）
     * - 支持从右往左按 code point 处理，避免截断 surrogate pair
     */
    fun trimTrailingPunctAndEmoji(s: String): String {
        if (s.isEmpty()) return s
        var end = s.length
        var removeNextKeycapBase = false // 处理 keycap 组合（例如 3️⃣ 的基字符）
        while (end > 0) {
            val cp = java.lang.Character.codePointBefore(s, end)
            val count = java.lang.Character.charCount(cp)

            val isPunct = when (java.lang.Character.getType(cp)) {
                java.lang.Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                java.lang.Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                java.lang.Character.OTHER_PUNCTUATION.toInt(),
                java.lang.Character.DASH_PUNCTUATION.toInt(),
                java.lang.Character.START_PUNCTUATION.toInt(),
                java.lang.Character.END_PUNCTUATION.toInt(),
                java.lang.Character.CONNECTOR_PUNCTUATION.toInt(),
                -> true
                else -> false
            } || when (cp) {
                // 常见中文标点（补充）
                '，'.code, '。'.code, '！'.code, '？'.code, '；'.code, '、'.code, '：'.code -> true
                else -> false
            }

            // Emoji 相关：常见表情范围、肤色修饰符、ZWJ、变体选择符、旗帜、标签等
            val isEmojiCore = (
                (cp in 0x1F600..0x1F64F) || // Emoticons
                    (cp in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
                    (cp in 0x1F680..0x1F6FF) || // Transport & Map
                    (cp in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
                    (cp in 0x1FA70..0x1FAFF) || // Symbols & Pictographs Extended-A
                    (cp in 0x1F1E6..0x1F1FF) || // Regional Indicator Symbols (flags)
                    (cp in 0x2600..0x26FF) || // Misc symbols
                    (cp in 0x2700..0x27BF) // Dingbats
                )
            val isEmojiModifier = (cp in 0x1F3FB..0x1F3FF) // 肤色修饰符
            val isEmojiJoinerOrSelector = (cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E)
            val isEmojiTag = (cp in 0xE0020..0xE007F) // 标签序列（部分旗帜等）
            val isKeycapEncloser = (cp == 0x20E3) // 组合按键包围符

            val isEmojiPart = isEmojiCore || isEmojiModifier || isEmojiJoinerOrSelector || isEmojiTag || isKeycapEncloser

            val isKeycapBase = (cp in '0'.code..'9'.code) || cp == '#'.code || cp == '*'.code

            val shouldRemove = isPunct || isEmojiPart || (removeNextKeycapBase && isKeycapBase)
            if (!shouldRemove) break

            end -= count

            // 如果遇到 keycap 包围符（U+20E3），向前继续移除其基字符（数字/#/*）
            if (isKeycapEncloser) {
                removeNextKeycapBase = true
            } else if (!isEmojiJoinerOrSelector) {
                // 移除了实际字符（非连接/选择符）后，重置 keycap 基字符移除标志
                removeNextKeycapBase = false
            }
        }
        return if (end < s.length) s.substring(0, end) else s
    }

    /**
     * 计算文本的"有效字符数"（智能统计）
     * 规则：
     * - CJK 字符（中文、日文、韩文）：每个字符算 1 个字
     * - 英文单词：连续字母算作 1 个词
     * - 数字：连续数字算作 1 个数字串
     * - 标点符号和空白符：不计入统计
     *
     * @param text 待统计的文本
     * @return 有效字符数
     */
    fun countEffectiveChars(text: String): Int {
        if (text.isEmpty()) return 0

        var count = 0
        var inWord = false // 是否在英文单词中
        var inNumber = false // 是否在数字串中

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)

            when {
                // CJK 统一表意文字（中文）
                isCJK(codePoint) -> {
                    count++
                    inWord = false
                    inNumber = false
                }
                // 英文字母
                Character.isLetter(codePoint) -> {
                    if (!inWord) {
                        count++ // 新单词开始
                        inWord = true
                    }
                    inNumber = false
                }
                // 数字
                Character.isDigit(codePoint) -> {
                    if (!inNumber) {
                        count++ // 新数字串开始
                        inNumber = true
                    }
                    inWord = false
                }
                // 其他（标点、空白等）
                else -> {
                    inWord = false
                    inNumber = false
                }
            }

            i += charCount
        }

        return count
    }

    /**
     * 判断是否为 CJK 字符（中文、日文、韩文）
     */
    private fun isCJK(codePoint: Int): Boolean {
        return when (codePoint) {
            in 0x4E00..0x9FFF -> true // CJK Unified Ideographs
            in 0x3400..0x4DBF -> true // CJK Unified Ideographs Extension A
            in 0x20000..0x2A6DF -> true // CJK Unified Ideographs Extension B
            in 0x2A700..0x2B73F -> true // CJK Unified Ideographs Extension C
            in 0x2B740..0x2B81F -> true // CJK Unified Ideographs Extension D
            in 0x2B820..0x2CEAF -> true // CJK Unified Ideographs Extension E
            in 0x2CEB0..0x2EBEF -> true // CJK Unified Ideographs Extension F
            in 0x30000..0x3134F -> true // CJK Unified Ideographs Extension G
            in 0xF900..0xFAFF -> true // CJK Compatibility Ideographs
            in 0x2F800..0x2FA1F -> true // CJK Compatibility Ideographs Supplement
            in 0x3040..0x309F -> true // Hiragana
            in 0x30A0..0x30FF -> true // Katakana
            in 0x31F0..0x31FF -> true // Katakana Phonetic Extensions
            in 0xAC00..0xD7AF -> true // Hangul Syllables
            in 0x1100..0x11FF -> true // Hangul Jamo
            in 0xA960..0xA97F -> true // Hangul Jamo Extended-A
            in 0xD7B0..0xD7FF -> true // Hangul Jamo Extended-B
            else -> false
        }
    }
}
