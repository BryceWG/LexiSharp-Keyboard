package com.brycewg.asrkb.store

import android.content.Context
import androidx.core.content.edit
import com.brycewg.asrkb.asr.AsrVendor
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)

    // --- 小工具：统一的偏好项委托，减少重复 getter/setter 代码 ---
    private fun stringPref(key: String, default: String = "") = object {
        operator fun getValue(thisRef: Prefs, property: KProperty<*>): String =
            sp.getString(key, default) ?: default

        operator fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
            sp.edit { putString(key, value.trim()) }
        }
    }

    // 直接从 SP 读取字符串，供通用导入/导出和校验使用
    private fun getPrefString(key: String, default: String = ""): String =
        sp.getString(key, default) ?: default

    private fun setPrefString(key: String, value: String) {
        sp.edit { putString(key, value.trim()) }
    }

    // 火山引擎凭证
    var appKey: String by stringPref(KEY_APP_KEY, "")

    var accessKey: String by stringPref(KEY_ACCESS_KEY, "")

    var trimFinalTrailingPunct: Boolean
        get() = sp.getBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, false)
        set(value) = sp.edit { putBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, value) }

    // 已移除：显示键盘内“切换输入法”按钮的开关（改为始终显示）

    // 在密码框中自动切换输入法
    var autoSwitchOnPassword: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, value) }

    // 麦克风按钮触觉反馈
    var micHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_HAPTIC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_MIC_HAPTIC_ENABLED, value) }

    // 26 键盘点击触觉反馈
    var qwertyHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_QWERTY_HAPTIC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_QWERTY_HAPTIC_ENABLED, value) }

    // 禁用合成态下划线（尽量避免“拼写检查/下划线预览”效果）
    var disableComposingUnderline: Boolean
        get() = sp.getBoolean(KEY_DISABLE_COMPOSING_UNDERLINE, true)
        set(value) = sp.edit { putBoolean(KEY_DISABLE_COMPOSING_UNDERLINE, value) }

    // 拼音自动 LLM 转换间隔（秒，支持小数；0 表示关闭）
    var qwertyPinyinLlmIntervalSec: Float
        get() {
            // 兼容旧版本的 Int 存储
            return try {
                sp.getFloat(KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC, DEFAULT_QWERTY_PINYIN_LLM_INTERVAL_SEC)
            } catch (_: Throwable) {
                val i = try { sp.getInt(KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC, DEFAULT_QWERTY_PINYIN_LLM_INTERVAL_SEC.toInt()) } catch (_: Throwable) { 0 }
                i.toFloat()
            }.coerceAtLeast(0f)
        }
        set(value) = sp.edit { putFloat(KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC, value.coerceAtLeast(0f)) }

    // 26 键默认语言（"en" 或 "zh"）
    var qwertyDefaultLang: String
        get() = sp.getString(KEY_QWERTY_DEFAULT_LANG, DEFAULT_QWERTY_DEFAULT_LANG) ?: DEFAULT_QWERTY_DEFAULT_LANG
        set(value) = sp.edit { putString(KEY_QWERTY_DEFAULT_LANG, if (value == "zh") "zh" else "en") }

    // 拼音输入模式（全拼/小鹤双拼）
    var pinyinMode: PinyinMode
        get() = PinyinMode.fromId(sp.getString(KEY_PINYIN_MODE, PinyinMode.Quanpin.id))
        set(value) = sp.edit { putString(KEY_PINYIN_MODE, value.id) }

    // 应用内语言（空字符串表示跟随系统；如："zh-Hans"、"en"）
    var appLanguageTag: String
        get() = sp.getString(KEY_APP_LANGUAGE_TAG, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_LANGUAGE_TAG, value) }

    // 悬浮球开关
    var floatingSwitcherEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_SWITCHER_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_SWITCHER_ENABLED, value) }

    // 悬浮球透明度（0.2f - 1.0f）
    var floatingSwitcherAlpha: Float
        get() = sp.getFloat(KEY_FLOATING_SWITCHER_ALPHA, 1.0f).coerceIn(0.2f, 1.0f)
        set(value) = sp.edit { putFloat(KEY_FLOATING_SWITCHER_ALPHA, value.coerceIn(0.2f, 1.0f)) }

    // LLM后处理设置
    var postProcessEnabled: Boolean
        get() = sp.getBoolean(KEY_POSTPROC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_POSTPROC_ENABLED, value) }

    var llmEndpoint: String
        get() = sp.getString(KEY_LLM_ENDPOINT, DEFAULT_LLM_ENDPOINT) ?: DEFAULT_LLM_ENDPOINT
        set(value) = sp.edit { putString(KEY_LLM_ENDPOINT, value.trim()) }

    var llmApiKey: String
        get() = sp.getString(KEY_LLM_API_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_API_KEY, value.trim()) }

    var llmModel: String
        get() = sp.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(value) = sp.edit { putString(KEY_LLM_MODEL, value.trim()) }

    var llmTemperature: Float
        get() = sp.getFloat(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE)
        set(value) = sp.edit { putFloat(KEY_LLM_TEMPERATURE, value) }

    // 已弃用：单一提示词。保留用于向后兼容/迁移。
    var llmPrompt: String
        get() = sp.getString(KEY_LLM_PROMPT, DEFAULT_LLM_PROMPT) ?: DEFAULT_LLM_PROMPT
        set(value) = sp.edit { putString(KEY_LLM_PROMPT, value) }

    // 多个预设提示词，包含标题和活动选择
    var promptPresetsJson: String
        get() = sp.getString(KEY_LLM_PROMPT_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_PRESETS, value) }

    var activePromptId: String
        get() = sp.getString(KEY_LLM_PROMPT_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_ACTIVE_ID, value) }

    fun getPromptPresets(): List<PromptPreset> {
        // 如果未设置预设，从旧的单一提示词迁移
        if (promptPresetsJson.isBlank()) {
            val defaults = buildDefaultPromptPresets()
            setPromptPresets(defaults)
            // 如果未设置，将第一个设为活动状态
            if (activePromptId.isBlank()) activePromptId = defaults.firstOrNull()?.id ?: ""
            return defaults
        }
        return try {
            val arr = org.json.JSONArray(promptPresetsJson)
            val list = mutableListOf<PromptPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                val title = o.optString("title").ifBlank { "未命名预设" }
                val content = o.optString("content")
                list.add(PromptPreset(id, title, content))
            }
            if (list.isEmpty()) buildDefaultPromptPresets() else list
        } catch (_: Throwable) {
            buildDefaultPromptPresets()
        }
    }

    fun setPromptPresets(list: List<PromptPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val o = org.json.JSONObject()
            o.put("id", p.id)
            o.put("title", p.title)
            o.put("content", p.content)
            arr.put(o)
        }
        promptPresetsJson = arr.toString()
        // 确保活动ID有效
        if (list.none { it.id == activePromptId }) {
            activePromptId = list.firstOrNull()?.id ?: ""
        }
    }

    val activePromptContent: String
        get() {
            val id = activePromptId
            val presets = getPromptPresets()
            val found = presets.firstOrNull { it.id == id }
            return found?.content ?: (llmPrompt.ifBlank { DEFAULT_LLM_PROMPT })
        }

    // SiliconFlow凭证
    var sfApiKey: String by stringPref(KEY_SF_API_KEY, "")

    var sfModel: String by stringPref(KEY_SF_MODEL, DEFAULT_SF_MODEL)

    // 阿里云百炼（DashScope）凭证
    var dashApiKey: String by stringPref(KEY_DASH_API_KEY, "")

    var dashModel: String by stringPref(KEY_DASH_MODEL, DEFAULT_DASH_MODEL)

    // ElevenLabs凭证
    var elevenApiKey: String by stringPref(KEY_ELEVEN_API_KEY, "")

    var elevenModelId: String by stringPref(KEY_ELEVEN_MODEL_ID, "")

    // OpenAI 语音转文字（ASR）配置
    var oaAsrEndpoint: String by stringPref(KEY_OA_ASR_ENDPOINT, DEFAULT_OA_ASR_ENDPOINT)

    var oaAsrApiKey: String by stringPref(KEY_OA_ASR_API_KEY, "")

    var oaAsrModel: String by stringPref(KEY_OA_ASR_MODEL, DEFAULT_OA_ASR_MODEL)

    // Google Gemini 语音理解（通过提示词转写）
    var gemApiKey: String by stringPref(KEY_GEM_API_KEY, "")

    var gemModel: String by stringPref(KEY_GEM_MODEL, DEFAULT_GEM_MODEL)

    var gemPrompt: String
        get() = sp.getString(KEY_GEM_PROMPT, DEFAULT_GEM_PROMPT) ?: DEFAULT_GEM_PROMPT
        set(value) = sp.edit { putString(KEY_GEM_PROMPT, value) }

    // 选中的ASR供应商
    var asrVendor: AsrVendor
        get() = AsrVendor.fromId(sp.getString(KEY_ASR_VENDOR, AsrVendor.Volc.id))
        set(value) = sp.edit { putString(KEY_ASR_VENDOR, value.id) }

    // --- 供应商配置通用化 ---
    private data class VendorField(val key: String, val required: Boolean = false, val default: String = "")

    private val vendorFields: Map<AsrVendor, List<VendorField>> = mapOf(
        AsrVendor.Volc to listOf(
            VendorField(KEY_APP_KEY, required = true),
            VendorField(KEY_ACCESS_KEY, required = true)
        ),
        AsrVendor.SiliconFlow to listOf(
            VendorField(KEY_SF_API_KEY, required = true),
            VendorField(KEY_SF_MODEL, default = DEFAULT_SF_MODEL)
        ),
        AsrVendor.ElevenLabs to listOf(
            VendorField(KEY_ELEVEN_API_KEY, required = true),
            VendorField(KEY_ELEVEN_MODEL_ID)
        ),
        AsrVendor.OpenAI to listOf(
            VendorField(KEY_OA_ASR_ENDPOINT, required = true, default = DEFAULT_OA_ASR_ENDPOINT),
            VendorField(KEY_OA_ASR_API_KEY, required = true),
            VendorField(KEY_OA_ASR_MODEL, required = true, default = DEFAULT_OA_ASR_MODEL)
        ),
        AsrVendor.DashScope to listOf(
            VendorField(KEY_DASH_API_KEY, required = true),
            VendorField(KEY_DASH_MODEL, default = DEFAULT_DASH_MODEL)
        ),
        AsrVendor.Gemini to listOf(
            VendorField(KEY_GEM_API_KEY, required = true),
            VendorField(KEY_GEM_MODEL, required = true, default = DEFAULT_GEM_MODEL),
            VendorField(KEY_GEM_PROMPT, default = DEFAULT_GEM_PROMPT)
        )
    )

    fun hasVendorKeys(v: AsrVendor): Boolean {
        val fields = vendorFields[v] ?: return false
        return fields.filter { it.required }.all { f ->
            getPrefString(f.key, f.default).isNotBlank()
        }
    }

    fun hasVolcKeys(): Boolean = hasVendorKeys(AsrVendor.Volc)
    fun hasSfKeys(): Boolean = hasVendorKeys(AsrVendor.SiliconFlow)
    fun hasDashKeys(): Boolean = hasVendorKeys(AsrVendor.DashScope)
    fun hasElevenKeys(): Boolean = hasVendorKeys(AsrVendor.ElevenLabs)
    fun hasOpenAiKeys(): Boolean = hasVendorKeys(AsrVendor.OpenAI)
    fun hasGeminiKeys(): Boolean = hasVendorKeys(AsrVendor.Gemini)
    fun hasAsrKeys(): Boolean = hasVendorKeys(asrVendor)
    fun hasLlmKeys(): Boolean = llmApiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()

    // 自定义标点按钮（扩展至 5 个位置）
    var punct1: String
        get() = (sp.getString(KEY_PUNCT_1, DEFAULT_PUNCT_1) ?: DEFAULT_PUNCT_1).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_1, value.trim()) }

    var punct2: String
        get() = (sp.getString(KEY_PUNCT_2, DEFAULT_PUNCT_2) ?: DEFAULT_PUNCT_2).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_2, value.trim()) }

    var punct3: String
        get() = (sp.getString(KEY_PUNCT_3, DEFAULT_PUNCT_3) ?: DEFAULT_PUNCT_3).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_3, value.trim()) }

    var punct4: String
        get() = (sp.getString(KEY_PUNCT_4, DEFAULT_PUNCT_4) ?: DEFAULT_PUNCT_4).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_4, value.trim()) }

    var punct5: String
        get() = (sp.getString(KEY_PUNCT_5, DEFAULT_PUNCT_5) ?: DEFAULT_PUNCT_5).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_5, value.trim()) }

    // 历史语音识别总字数（仅统计最终提交到编辑器的识别结果；AI编辑不计入）
    var totalAsrChars: Long
        get() = sp.getLong(KEY_TOTAL_ASR_CHARS, 0L).coerceAtLeast(0L)
        set(value) = sp.edit { putLong(KEY_TOTAL_ASR_CHARS, value.coerceAtLeast(0L)) }

    fun addAsrChars(delta: Int) {
        if (delta <= 0) return
        val cur = totalAsrChars
        val next = (cur + delta).coerceAtLeast(0L)
        totalAsrChars = next
    }

    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_TRIM_FINAL_TRAILING_PUNCT = "trim_final_trailing_punct"
        private const val KEY_AUTO_SWITCH_ON_PASSWORD = "auto_switch_on_password"
        private const val KEY_MIC_HAPTIC_ENABLED = "mic_haptic_enabled"
        private const val KEY_QWERTY_HAPTIC_ENABLED = "qwerty_haptic_enabled"
        private const val KEY_DISABLE_COMPOSING_UNDERLINE = "disable_composing_underline"
        private const val KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC = "qwerty_pinyin_llm_interval_sec"
        private const val KEY_QWERTY_DEFAULT_LANG = "qwerty_default_lang"
        private const val KEY_PINYIN_MODE = "pinyin_mode"
        private const val KEY_FLOATING_SWITCHER_ENABLED = "floating_switcher_enabled"
        private const val KEY_FLOATING_SWITCHER_ALPHA = "floating_switcher_alpha"
        private const val KEY_POSTPROC_ENABLED = "postproc_enabled"
        private const val KEY_APP_LANGUAGE_TAG = "app_language_tag"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_PROMPT = "llm_prompt"
        private const val KEY_LLM_PROMPT_PRESETS = "llm_prompt_presets"
        private const val KEY_LLM_PROMPT_ACTIVE_ID = "llm_prompt_active_id"
        private const val KEY_ASR_VENDOR = "asr_vendor"
        private const val KEY_SF_API_KEY = "sf_api_key"
        private const val KEY_SF_MODEL = "sf_model"
        private const val KEY_ELEVEN_API_KEY = "eleven_api_key"
        private const val KEY_ELEVEN_MODEL_ID = "eleven_model_id"
        private const val KEY_OA_ASR_ENDPOINT = "oa_asr_endpoint"
        private const val KEY_OA_ASR_API_KEY = "oa_asr_api_key"
        private const val KEY_OA_ASR_MODEL = "oa_asr_model"
        private const val KEY_GEM_API_KEY = "gem_api_key"
        private const val KEY_GEM_MODEL = "gem_model"
        private const val KEY_GEM_PROMPT = "gem_prompt"
        private const val KEY_DASH_API_KEY = "dash_api_key"
        private const val KEY_DASH_MODEL = "dash_model"
        private const val KEY_PUNCT_1 = "punct_1"
        private const val KEY_PUNCT_2 = "punct_2"
        private const val KEY_PUNCT_3 = "punct_3"
        private const val KEY_PUNCT_4 = "punct_4"
        private const val KEY_PUNCT_5 = "punct_5"
        private const val KEY_TOTAL_ASR_CHARS = "total_asr_chars"

        const val DEFAULT_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val DEFAULT_SF_MODEL = "FunAudioLLM/SenseVoiceSmall"

        // OpenAI Audio Transcriptions 默认值
        const val DEFAULT_OA_ASR_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_OA_ASR_MODEL = "gpt-4o-mini-transcribe"

        // DashScope 默认
        const val DEFAULT_DASH_MODEL = "qwen3-asr-flash"
        // Gemini 默认
        const val DEFAULT_GEM_MODEL = "gemini-2.5-flash"
        const val DEFAULT_GEM_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。"

        // 合理的OpenAI格式默认值
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_LLM_TEMPERATURE = 0.2f
        const val DEFAULT_LLM_PROMPT = "# 角色\n\n你是一个顶级的 ASR（自动语音识别）后处理专家。\n\n# 任务\n\n你的任务是接收一段由 ASR 系统转录的原始文本，并将其精炼成一段通顺、准确、书面化的文本。你需要严格遵循以下规则，仅输出修正后的最终文本。\n\n# 规则\n\n1.  **去除无关填充词**: 彻底删除所有无意义的语气词、犹豫词和口头禅。\n    - **示例**: \"嗯\"、\"啊\"、\"呃\"、\"那个\"、\"然后\"、\"就是说\"等。\n2.  **合并重复与修正口误**: 当说话者重复单词、短语或进行自我纠正时，你需要整合这些内容，只保留其最终的、最清晰的意图。\n    - **重复示例**: 将\"我想...我想去...\"修正为\"我想去...\"。\n    - **口误修正示例**: 将\"我们明天去上海，哦不对，去苏州开会\"修正为\"我们明天去苏州开会\"。\n3.  **修正识别错误**: 根据上下文语境，纠正明显不符合逻辑的同音、近音词汇。\n    - **同音词示例**: 将\"请大家准时参加明天的『会意』\"修正为\"请大家准时参加明天的『会议』\"\n4.  **保持语义完整性**: 确保修正后的文本忠实于说话者的原始意图，不要进行主观臆断或添加额外信息。\n\n# 示例\n\n- **原始文本**: \"嗯...那个...我想确认一下，我们明天，我们明天的那个会意，啊不对，会议，时间是不是...是不是上午九点？\"\n- **修正后文本**: \"我想确认一下，我们明天的那个会议时间是不是上午九点？\"\n  请根据以上所有规则，处理给定文本"

        // 26 键默认语言默认值
        const val DEFAULT_QWERTY_DEFAULT_LANG = "en"
        // 拼音自动 LLM 转换默认间隔（秒，支持小数）
        const val DEFAULT_QWERTY_PINYIN_LLM_INTERVAL_SEC = 2.0f

        // 标点按钮默认值
        const val DEFAULT_PUNCT_1 = "，"
        const val DEFAULT_PUNCT_2 = "。"
        const val DEFAULT_PUNCT_3 = "！"
        const val DEFAULT_PUNCT_4 = "？"
        const val DEFAULT_PUNCT_5 = "、"

        private fun buildDefaultPromptPresets(): List<PromptPreset> {
            val p1 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "基础文本润色",
                content = "你是一个专业的中文编辑器。请对以下由ASR（语音识别）生成的文本进行润色和修正。请遵循以下规则：\n1. 修正所有错别字和语法错误。\n2. 添加正确、自然的标点符号。\n3. 删除口语化的词语、重复和无意义的填充词（例如嗯、啊、那个）。\n4. 在保持原意不变的前提下，让句子表达更流畅、更书面化。\n5. 不要添加任何原始文本中没有的信息，不要附带任何解释说明，只输出润色后的内容。"
            )
            val p2 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "翻译为英文",
                content = "请将以下文本翻译为英语。在翻译过程中，请确保：\n1. 准确传达原文的核心意思。\n2. 保持原文的语气（例如，正式、非正式、紧急等）。\n3. 译文流畅、符合目标语言的表达习惯。不要附带任何解释说明，只输出翻译后的内容。"
            )
            val p3 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "提取关键要点",
                content = "请从以下文本中提取核心要点，并以无序列表（bullet points）的形式呈现。每个要点都应简洁明了。"
            )
            val p4 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "提取待办事项",
                content = "请从以下文本中识别并提取所有待办事项（Action Items）。如果文本中提到了负责人和截止日期，请一并列出。\n\n请使用以下格式输出：\n- [ ] [任务内容] (负责人: [姓名], 截止日期: [日期])\n\n如果信息不完整，则省略相应部分。"
            )
            val p5 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "仅纠错不改写",
                content = "仅在不改变原意的前提下进行最小必要的纠错：修正错别字、标点、大小写与明显的口误。不要重写或美化句式，不要添加或省略信息。输出纠正后的文本。"
            )
            val p6 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "保留口语风格",
                content = "在尽量保持口语风格的前提下，去除明显的口头禅与重复，统一人名/地名等专有名词的写法。尽量不改变原句结构。只输出处理后的文本。"
            )
            return listOf(p1, p2, p3, p4, p5, p6)
        }
    }

    // 导出全部设置为 JSON 字符串（包含密钥，仅用于本地备份/迁移）
    fun exportJsonString(): String {
        val o = org.json.JSONObject()
        o.put("_version", 1)
        o.put(KEY_APP_KEY, appKey)
        o.put(KEY_ACCESS_KEY, accessKey)
        o.put(KEY_TRIM_FINAL_TRAILING_PUNCT, trimFinalTrailingPunct)
        o.put(KEY_AUTO_SWITCH_ON_PASSWORD, autoSwitchOnPassword)
        o.put(KEY_MIC_HAPTIC_ENABLED, micHapticEnabled)
        o.put(KEY_QWERTY_HAPTIC_ENABLED, qwertyHapticEnabled)
        o.put(KEY_DISABLE_COMPOSING_UNDERLINE, disableComposingUnderline)
        o.put(KEY_QWERTY_DEFAULT_LANG, qwertyDefaultLang)
        o.put(KEY_PINYIN_MODE, pinyinMode.id)
        o.put(KEY_APP_LANGUAGE_TAG, appLanguageTag)
        o.put(KEY_FLOATING_SWITCHER_ENABLED, floatingSwitcherEnabled)
        o.put(KEY_FLOATING_SWITCHER_ALPHA, floatingSwitcherAlpha)
        o.put(KEY_POSTPROC_ENABLED, postProcessEnabled)
        o.put(KEY_LLM_ENDPOINT, llmEndpoint)
        o.put(KEY_LLM_API_KEY, llmApiKey)
        o.put(KEY_LLM_MODEL, llmModel)
        o.put(KEY_LLM_TEMPERATURE, llmTemperature.toDouble())
        // 兼容旧字段
        o.put(KEY_LLM_PROMPT, llmPrompt)
        o.put(KEY_LLM_PROMPT_PRESETS, promptPresetsJson)
        o.put(KEY_LLM_PROMPT_ACTIVE_ID, activePromptId)
        // 供应商设置（通用导出）
        o.put(KEY_ASR_VENDOR, asrVendor.id)
        // 遍历所有供应商字段，统一导出，避免逐个硬编码
        vendorFields.values.flatten().forEach { f ->
            o.put(f.key, getPrefString(f.key, f.default))
        }
        // 自定义标点
        o.put(KEY_PUNCT_1, punct1)
        o.put(KEY_PUNCT_2, punct2)
        o.put(KEY_PUNCT_3, punct3)
        o.put(KEY_PUNCT_4, punct4)
        o.put(KEY_PUNCT_5, punct5)
        // 拼音自动转换间隔
        o.put(KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC, qwertyPinyinLlmIntervalSec)
        // 统计信息
        o.put(KEY_TOTAL_ASR_CHARS, totalAsrChars)
        return o.toString()
    }

    // 从 JSON 字符串导入。仅覆盖提供的键；解析失败返回 false。
    fun importJsonString(json: String): Boolean {
        return try {
            val o = org.json.JSONObject(json)
            fun optBool(key: String, default: Boolean? = null): Boolean? =
                if (o.has(key)) o.optBoolean(key) else default
            fun optString(key: String, default: String? = null): String? =
                if (o.has(key)) o.optString(key) else default
            fun optFloat(key: String, default: Float? = null): Float? =
                if (o.has(key)) o.optDouble(key).toFloat() else default

            optString(KEY_APP_KEY)?.let { appKey = it }
            optString(KEY_ACCESS_KEY)?.let { accessKey = it }
            optBool(KEY_TRIM_FINAL_TRAILING_PUNCT)?.let { trimFinalTrailingPunct = it }
            optBool(KEY_AUTO_SWITCH_ON_PASSWORD)?.let { autoSwitchOnPassword = it }
            optBool(KEY_MIC_HAPTIC_ENABLED)?.let { micHapticEnabled = it }
            optBool(KEY_QWERTY_HAPTIC_ENABLED)?.let { qwertyHapticEnabled = it }
            optBool(KEY_DISABLE_COMPOSING_UNDERLINE)?.let { disableComposingUnderline = it }
            // 新增：拼音自动转换间隔（秒，支持小数）
            optFloat(KEY_QWERTY_PINYIN_LLM_INTERVAL_SEC)?.let { qwertyPinyinLlmIntervalSec = it.coerceAtLeast(0f) }
            optString(KEY_QWERTY_DEFAULT_LANG)?.let { qwertyDefaultLang = if (it == "zh") "zh" else "en" }
            optString(KEY_PINYIN_MODE)?.let { pinyinMode = PinyinMode.fromId(it) }
            optString(KEY_APP_LANGUAGE_TAG)?.let { appLanguageTag = it }
            optBool(KEY_POSTPROC_ENABLED)?.let { postProcessEnabled = it }
            optBool(KEY_FLOATING_SWITCHER_ENABLED)?.let { floatingSwitcherEnabled = it }
            optFloat(KEY_FLOATING_SWITCHER_ALPHA)?.let { floatingSwitcherAlpha = it.coerceIn(0.2f, 1.0f) }

            optString(KEY_LLM_ENDPOINT)?.let { llmEndpoint = it.ifBlank { DEFAULT_LLM_ENDPOINT } }
            optString(KEY_LLM_API_KEY)?.let { llmApiKey = it }
            optString(KEY_LLM_MODEL)?.let { llmModel = it.ifBlank { DEFAULT_LLM_MODEL } }
            optFloat(KEY_LLM_TEMPERATURE)?.let { llmTemperature = it.coerceIn(0f, 2f) }
            // 兼容：先读新预设；未提供时退回旧单一 Prompt
            optString(KEY_LLM_PROMPT_PRESETS)?.let { promptPresetsJson = it }
            optString(KEY_LLM_PROMPT_ACTIVE_ID)?.let { activePromptId = it }
            if (!o.has(KEY_LLM_PROMPT_PRESETS)) {
                optString(KEY_LLM_PROMPT)?.let { llmPrompt = it }
            }

            optString(KEY_ASR_VENDOR)?.let { asrVendor = AsrVendor.fromId(it) }
            // 供应商设置（通用导入）
            vendorFields.values.flatten().forEach { f ->
                optString(f.key)?.let { v ->
                    val final = v.ifBlank { f.default }
                    setPrefString(f.key, final)
                }
            }
            optString(KEY_PUNCT_1)?.let { punct1 = it }
            optString(KEY_PUNCT_2)?.let { punct2 = it }
            optString(KEY_PUNCT_3)?.let { punct3 = it }
            optString(KEY_PUNCT_4)?.let { punct4 = it }
            optString(KEY_PUNCT_5)?.let { punct5 = it }
            // 统计信息（可选）
            if (o.has(KEY_TOTAL_ASR_CHARS)) {
                // 使用 optLong，若类型为字符串/浮点将尽力转换
                val v = try { o.optLong(KEY_TOTAL_ASR_CHARS) } catch (_: Throwable) { 0L }
                if (v >= 0L) totalAsrChars = v
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
