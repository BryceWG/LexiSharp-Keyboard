/**
 * 为键盘信息栏和剪贴板列表生成严格有界的单行预览。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

internal fun clipboardTextPreview(text: String, maxChars: Int = CLIPBOARD_PREVIEW_CHARS): String {
    if (maxChars <= 0 || text.isEmpty()) return ""
    return buildString(minOf(text.length, maxChars)) {
        for (char in text) {
            if (length >= maxChars) break
            append(if (char == '\n' || char == '\r') ' ' else char)
        }
    }
}

internal const val CLIPBOARD_PREVIEW_CHARS = 160
