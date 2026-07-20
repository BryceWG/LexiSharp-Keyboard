/**
 * 统一读取系统剪贴板中的文本内容，供剪贴板历史与跨设备同步复用。
 *
 * 归属模块：clipboard
 */
package com.brycewg.asrkb.clipboard

import android.content.ClipData

internal fun readClipboardText(clip: ClipData): String? {
    if (clip.itemCount <= 0) return null
    // coerceToText() 会主动打开 URI 内容流，可能把图片/文件的二进制数据解码成乱码。
    return clip.getItemAt(0)
        .text
        ?.toString()
        ?.takeIf { it.isNotEmpty() }
}
