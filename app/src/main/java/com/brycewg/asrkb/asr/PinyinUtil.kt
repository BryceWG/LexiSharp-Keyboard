package com.brycewg.asrkb.asr

import android.icu.text.Transliterator

/**
 * 轻量拼音转换工具：依赖 Android ICU（API 24+），将中文转为不带音调的拉丁字母（低位）
 * - 非中文字符尽量保留（字母数字），其余替换为空格
 * - 多个空白折叠为单一空格，并 trim
 */
object PinyinUtil {
  private val trans: Transliterator by lazy {
    // Han-Latin 转拼音；Latin-ASCII 去除音调；Lower 小写化
    Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
  }

  fun toPinyin(text: String): String {
    if (text.isBlank()) return ""
    return try {
      val latin = trans.transliterate(text)
      latin
        .replace("\u00A0", " ") // 不间断空格
        .replace("\u3000", " ") // 全角空格
        .replace(Regex("""[^a-z0-9\s]"""), " ") // 仅保留字母数字与空白
        .replace(Regex("""\n+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    } catch (_: Throwable) {
      text.trim()
    }
  }
}

