package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 格式的 ASR 文本后处理器，用于文本清理和 AI 编辑。
 * 使用与 Chat Completions 兼容的 API，并在存在简单字段时回退使用。
 */
class LlmPostProcessor(private val client: OkHttpClient? = null) {
  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  private fun resolveUrl(base: String): String {
    val raw = base.trim()
    if (raw.isEmpty()) return Prefs.DEFAULT_LLM_ENDPOINT.trimEnd('/') + "/chat/completions"
    val b = raw.trimEnd('/')
    val withScheme = if (b.startsWith("http://", true) || b.startsWith("https://", true)) b else "https://$b"
    return if (withScheme.endsWith("/chat/completions") || withScheme.endsWith("/responses")) withScheme else "$withScheme/chat/completions"
  }

  suspend fun process(input: String, prefs: Prefs): String = withContext(Dispatchers.IO) {
    if (input.isBlank()) return@withContext input
    val apiKey = prefs.llmApiKey
    val endpoint = prefs.llmEndpoint
    val model = prefs.llmModel
    val temperature = prefs.llmTemperature.toDouble()
    val prompt = prefs.activePromptContent.ifBlank { Prefs.DEFAULT_LLM_PROMPT }

    val url = resolveUrl(endpoint)
    val reqJson = JSONObject().apply {
      put("model", model)
      put("temperature", temperature)
      put("messages", JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", prompt)
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", input)
        })
      })
    }.toString()

    val body = reqJson.toRequestBody(jsonMedia)
    val http = (client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
    val req = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .post(body)
      .build()

    val resp = http.newCall(req).execute()
    if (!resp.isSuccessful) {
      resp.close()
      return@withContext input
    }
    val text = try {
      val s = resp.body?.string() ?: return@withContext input
      val obj = JSONObject(s)
      when {
        obj.has("choices") -> {
          val choices = obj.getJSONArray("choices")
          if (choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            msg?.optString("content")?.ifBlank { input } ?: input
          } else input
        }
        obj.has("output_text") -> obj.optString("output_text", input)
        else -> input
      }
    } catch (_: Throwable) {
      input
    } finally {
      resp.close()
    }
    return@withContext text
  }

  /**
   * 拼音转中文：使用专用的 System Prompt，尽量将纯拼音（可含空格/标点）映射为最合理的中文短语/句子。
   * - 仅输出中文结果，不要输出解释或引号；失败时回退为原输入。
   */
  suspend fun pinyinToChinese(pinyin: String, prefs: Prefs): String = withContext(Dispatchers.IO) {
    val src = pinyin.trim()
    if (src.isBlank()) return@withContext ""
    val apiKey = prefs.llmApiKey
    val endpoint = prefs.llmEndpoint
    val model = prefs.llmModel
    val temperature = prefs.llmTemperature.toDouble().coerceIn(0.0, 2.0)

    val url = resolveUrl(endpoint)
    // 动态注入：自定义词汇（汉字 + 拼音）辅助 disambiguation
    val vocab = prefs.getCustomVocabList()
    val vocabLines = if (vocab.isNotEmpty()) {
      vocab.take(100).map { term ->
        val py = PinyinUtil.toPinyin(term)
        "- $term (${py})"
      }.joinToString("\n")
    } else ""

    val vocabSection = if (vocabLines.isNotEmpty()) {
      """
      【领域/自定义词汇（优先还原为以下中文，括号内为拼音提示）】
      $vocabLines
      """.trimIndent()
    } else ""

    val systemPrompt = """
      你是一个精确的“拼音转汉字”助手。给你一段用户输入的汉语拼音（可能包含空格、分词标记或少量标点），请将其转换为最可能的中文文本。
      规则：
      - 只输出最终中文文本，不要输出任何解释、提示词或引号。
      - 优先符合口语表达与常见书写习惯。
      - 当拼音含糊时，选择最常见、最自然的中文表达；不要输出多种候选或注释。
      - 若输入并非拼音或不可解析，则原样返回（或输出接近原意的中文）。
      - 输出保持为简体中文。

      $vocabSection
    """.trimIndent()

    val userContent = """
      【拼音】
      $src
    """.trimIndent()

    val reqJson = JSONObject().apply {
      put("model", model)
      put("temperature", temperature)
      put("messages", JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", systemPrompt)
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", userContent)
        })
      })
    }.toString()

    val body = reqJson.toRequestBody(jsonMedia)
    val http = (client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
    val req = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .post(body)
      .build()

    val resp = http.newCall(req).execute()
    if (!resp.isSuccessful) {
      resp.close()
      return@withContext src
    }
    val out = try {
      val s = resp.body?.string() ?: return@withContext src
      val obj = JSONObject(s)
      when {
        obj.has("choices") -> {
          val choices = obj.getJSONArray("choices")
          if (choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            msg?.optString("content")?.ifBlank { src } ?: src
          } else src
        }
        obj.has("output_text") -> obj.optString("output_text", src)
        else -> src
      }
    } catch (_: Throwable) { src } finally { resp.close() }
    return@withContext out
  }

  /**
   * 使用自然语言指令编辑现有文本，兼容 Chat Completions API。
   * 返回编辑后的文本；任何失败时返回原始文本不变。
   */
  suspend fun editText(original: String, instruction: String, prefs: Prefs): String = withContext(Dispatchers.IO) {
    if (original.isBlank() || instruction.isBlank()) return@withContext original
    val apiKey = prefs.llmApiKey
    val endpoint = prefs.llmEndpoint
    val model = prefs.llmModel
    val temperature = prefs.llmTemperature.toDouble()

    val url = resolveUrl(endpoint)
    val systemPrompt = """
      你是一个精确的中文文本编辑助手。你的任务是根据“编辑指令”对“原文”进行最小必要修改。
      规则：
      - 只输出最终结果文本，不要输出任何解释、前后缀或引号。
      - 如指令含糊、矛盾或不可执行，原样返回原文。
      - 不要编造内容；除非指令明确要求，否则不要增删信息、不要改变语气与长度。
      - 保留原有段落、换行、空白与标点格式（除非指令要求变更）。
      - 保持语言/文字风格与原文一致；中文按原文简繁体维持不变。
      - 涉及脱敏时，仅将需脱敏片段替换为『[REDACTED]』，其余保持不变。
      - Output must be ONLY the edited text.

      示例（仅用于学习风格，不要照搬示例文本）：
      1) 指令：将口语化改为书面语；保留含义
         原文：我今天有点事儿，可能晚点到，你们先开始别等我
         输出：我今天有事，可能会晚到，请先开始，无需等待我。
      2) 指令：纠正错别字
         原文：这个方案挺好得，就是数据那块需要再核实一下
         输出：这个方案挺好的，就是数据那块需要再核实一下。
      3) 指令：把 comet 更改为 Kotlin
         原文：最近高强度写 comet,感觉效果还不错
         输出：最近高强度写 Kotlin,感觉效果还不错
      4) 指令：把列表换成逗号分隔的一行
         原文：苹果\n香蕉\n葡萄
         输出：苹果，香蕉，葡萄。
      5) 指令：脱敏姓名与电话
         原文：联系人张三，电话 13800000000
         输出：联系人[REDACTED]，电话[REDACTED]
    """.trimIndent()

    val userContent = """
      【编辑指令】
      $instruction

      【原文】
      $original
    """.trimIndent()

    val reqJson = JSONObject().apply {
      put("model", model)
      put("temperature", temperature)
      put("messages", JSONArray().apply {
        put(JSONObject().apply {
          put("role", "system")
          put("content", systemPrompt)
        })
        put(JSONObject().apply {
          put("role", "user")
          put("content", userContent)
        })
      })
    }.toString()

    val body = reqJson.toRequestBody(jsonMedia)
    val http = (client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
    val req = Request.Builder()
      .url(url)
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .post(body)
      .build()

    val resp = http.newCall(req).execute()
    if (!resp.isSuccessful) {
      resp.close()
      return@withContext original
    }
    val out = try {
      val s = resp.body?.string() ?: return@withContext original
      val obj = JSONObject(s)
      when {
        obj.has("choices") -> {
          val choices = obj.getJSONArray("choices")
          if (choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            msg?.optString("content")?.ifBlank { original } ?: original
          } else original
        }
        obj.has("output_text") -> obj.optString("output_text", original)
        else -> original
      }
    } catch (_: Throwable) { original } finally { resp.close() }
    return@withContext out
  }
}
