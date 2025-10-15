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

    // 移除：键盘内“切换输入法”按钮显示开关（按钮始终显示）

    // 在密码框中自动切换输入法
    var autoSwitchOnPassword: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, value) }

    // 麦克风按钮触觉反馈
    var micHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_HAPTIC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_MIC_HAPTIC_ENABLED, value) }

    // 麦克风点按控制（点按开始/停止），默认关闭：使用长按说话
    var micTapToggleEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, value) }

    // 静音自动判停：开关
    var autoStopOnSilenceEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_STOP_ON_SILENCE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_STOP_ON_SILENCE_ENABLED, value) }

    // 静音自动判停：时间窗口（ms），连续低能量超过该时间则自动停止
    var autoStopSilenceWindowMs: Int
        get() = sp.getInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS, DEFAULT_SILENCE_WINDOW_MS).coerceIn(300, 5000)
        set(value) = sp.edit { putInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS, value.coerceIn(300, 5000)) }

    // 静音自动判停：灵敏度（1-10，数值越大越容易判定无人说话）
    var autoStopSilenceSensitivity: Int
        get() = sp.getInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY, DEFAULT_SILENCE_SENSITIVITY).coerceIn(1, 10)
        set(value) = sp.edit { putInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY, value.coerceIn(1, 10)) }

    // 键盘高度档位（1/2/3），默认一档
    var keyboardHeightTier: Int
        get() = sp.getInt(KEY_KEYBOARD_HEIGHT_TIER, 1).coerceIn(1, 3)
        set(value) = sp.edit { putInt(KEY_KEYBOARD_HEIGHT_TIER, value.coerceIn(1, 3)) }

    // 应用内语言（空字符串表示跟随系统；如："zh-Hans"、"en"）
    var appLanguageTag: String
        get() = sp.getString(KEY_APP_LANGUAGE_TAG, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_LANGUAGE_TAG, value) }

    // 最近一次检查更新的日期（格式：yyyyMMdd，本地时区）；用于“每天首次进入设置页自动检查”
    var lastUpdateCheckDate: String by stringPref(KEY_LAST_UPDATE_CHECK_DATE, "")

    // 输入法切换悬浮球开关
    var floatingSwitcherEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_SWITCHER_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_SWITCHER_ENABLED, value) }

    // 仅在输入法面板显示时显示悬浮球
    var floatingSwitcherOnlyWhenImeVisible: Boolean
        get() = sp.getBoolean(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, value) }

    // 键盘可见性兼容模式（默认关）
    var floatingImeVisibilityCompatEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_IME_VISIBILITY_COMPAT_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_IME_VISIBILITY_COMPAT_ENABLED, value) }

    // 悬浮球透明度（0.2f - 1.0f）
    var floatingSwitcherAlpha: Float
        get() = sp.getFloat(KEY_FLOATING_SWITCHER_ALPHA, 1.0f).coerceIn(0.2f, 1.0f)
        set(value) = sp.edit { putFloat(KEY_FLOATING_SWITCHER_ALPHA, value.coerceIn(0.2f, 1.0f)) }

    // 悬浮球大小（单位 dp，范围 28 - 96，默认 44）
    var floatingBallSizeDp: Int
        get() = sp.getInt(KEY_FLOATING_BALL_SIZE_DP, DEFAULT_FLOATING_BALL_SIZE_DP).coerceIn(28, 96)
        set(value) = sp.edit { putInt(KEY_FLOATING_BALL_SIZE_DP, value.coerceIn(28, 96)) }

    // 悬浮球位置（px，屏幕坐标，-1 表示未设置）
    var floatingBallPosX: Int
        get() = sp.getInt(KEY_FLOATING_POS_X, -1)
        set(value) = sp.edit { putInt(KEY_FLOATING_POS_X, value) }

    var floatingBallPosY: Int
        get() = sp.getInt(KEY_FLOATING_POS_Y, -1)
        set(value) = sp.edit { putInt(KEY_FLOATING_POS_Y, value) }

    // 悬浮球语音识别模式开关
    var floatingAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_ASR_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_ASR_ENABLED, value) }

    // 悬浮球：写入文字兼容性模式（统一控制使用“全选+粘贴”等策略），默认开启
    var floatingWriteTextCompatEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, value) }

    // 兼容目标包名（每行一个；支持前缀匹配，例如 org.telegram）
    var floatingWriteCompatPackages: String
        get() = sp.getString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES) ?: DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES
        set(value) = sp.edit { putString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, value) }

    fun getFloatingWriteCompatPackageRules(): List<String> =
        floatingWriteCompatPackages.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    // LLM后处理设置（旧版单一字段；当存在多配置且已选择活动项时仅作回退）
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

    // 多 LLM 配置（OpenAI 兼容 API）
    var llmProvidersJson: String
        get() = sp.getString(KEY_LLM_PROVIDERS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROVIDERS, value) }

    var activeLlmId: String
        get() = sp.getString(KEY_LLM_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_ACTIVE_ID, value) }

    data class LlmProvider(
        val id: String,
        val name: String,
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Float
    )

    fun getLlmProviders(): List<LlmProvider> {
        // 首次使用：若未初始化，迁移旧字段为一个默认配置
        if (llmProvidersJson.isBlank()) {
            val migrated = LlmProvider(
                id = "default",
                name = "默认",
                endpoint = llmEndpoint.ifBlank { DEFAULT_LLM_ENDPOINT },
                apiKey = llmApiKey,
                model = llmModel.ifBlank { DEFAULT_LLM_MODEL },
                temperature = llmTemperature
            )
            setLlmProviders(listOf(migrated))
            if (activeLlmId.isBlank()) activeLlmId = migrated.id
        }
        return try {
            val arr = org.json.JSONArray(llmProvidersJson)
            val list = mutableListOf<LlmProvider>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                val name = o.optString("name").ifBlank { id }
                val endpoint = o.optString("endpoint", DEFAULT_LLM_ENDPOINT)
                val apiKey = o.optString("apiKey", "")
                val model = o.optString("model", DEFAULT_LLM_MODEL)
                val temp = o.optDouble("temperature", DEFAULT_LLM_TEMPERATURE.toDouble()).toFloat()
                list.add(LlmProvider(id, name, endpoint, apiKey, model, temp))
            }
            list
        } catch (_: Throwable) { emptyList() }
    }

    fun setLlmProviders(list: List<LlmProvider>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val o = org.json.JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("endpoint", p.endpoint)
            o.put("apiKey", p.apiKey)
            o.put("model", p.model)
            o.put("temperature", p.temperature.toDouble())
            arr.put(o)
        }
        llmProvidersJson = arr.toString()
        if (list.none { it.id == activeLlmId }) {
            activeLlmId = list.firstOrNull()?.id ?: ""
        }
    }

    fun getActiveLlmProvider(): LlmProvider? {
        val id = activeLlmId
        val list = getLlmProviders()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

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

    // SiliconFlow：是否使用多模态（Qwen3-Omni 系列，通过 chat/completions）
    var sfUseOmni: Boolean
        get() = sp.getBoolean(KEY_SF_USE_OMNI, false)
        set(value) = sp.edit { putBoolean(KEY_SF_USE_OMNI, value) }

    // SiliconFlow：多模态识别提示词（chat/completions 文本部分）
    var sfOmniPrompt: String
        get() = sp.getString(KEY_SF_OMNI_PROMPT, DEFAULT_SF_OMNI_PROMPT) ?: DEFAULT_SF_OMNI_PROMPT
        set(value) = sp.edit { putString(KEY_SF_OMNI_PROMPT, value) }

    // 阿里云百炼（DashScope）凭证
    var dashApiKey: String by stringPref(KEY_DASH_API_KEY, "")

    var dashModel: String by stringPref(KEY_DASH_MODEL, DEFAULT_DASH_MODEL)

    // DashScope：自定义识别上下文（提示词）
    var dashPrompt: String by stringPref(KEY_DASH_PROMPT, "")

    // DashScope：识别语言（空字符串表示自动/未指定）
    var dashLanguage: String
        get() = sp.getString(KEY_DASH_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_DASH_LANGUAGE, value.trim()) }

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

    // Soniox 语音识别
    var sonioxApiKey: String by stringPref(KEY_SONIOX_API_KEY, "")

    // Soniox：流式识别开关（默认关闭）
    var sonioxStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_SONIOX_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SONIOX_STREAMING_ENABLED, value) }

    // Soniox：识别语言提示（language_hints）；空字符串表示不设置（多语言自动）
    var sonioxLanguage: String
        get() = sp.getString(KEY_SONIOX_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_SONIOX_LANGUAGE, value.trim()) }

    // Soniox：多语言提示（JSON 数组字符串），优先于单一字段
    var sonioxLanguagesJson: String by stringPref(KEY_SONIOX_LANGUAGES, "")

    fun getSonioxLanguages(): List<String> {
        val raw = sonioxLanguagesJson.trim()
        if (raw.isBlank()) {
            val single = sonioxLanguage.trim()
            return if (single.isNotEmpty()) listOf(single) else emptyList()
        }
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) list.add(v)
            }
            list.distinct()
        } catch (_: Throwable) { emptyList() }
    }

    fun setSonioxLanguages(list: List<String>) {
        val distinct = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val arr = org.json.JSONArray()
        distinct.forEach { arr.put(it) }
        sonioxLanguagesJson = arr.toString()
        // 兼容旧字段：保留第一个；为空则清空
        sonioxLanguage = distinct.firstOrNull() ?: ""
    }

    // 火山引擎：流式识别开关（与文件模式共享凭证）
    var volcStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_STREAMING_ENABLED, value) }

    // 火山引擎：语义顺滑开关（enable_ddc）
    var volcDdcEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_DDC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_DDC_ENABLED, value) }

    // 火山引擎：VAD 分句开关（控制判停参数）
    var volcVadEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_VAD_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_VAD_ENABLED, value) }

    // 火山引擎：二遍识别开关（enable_nonstream）
    var volcNonstreamEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_NONSTREAM_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_NONSTREAM_ENABLED, value) }

    // 火山引擎：识别语言（nostream 支持；空=自动中英/方言）
    var volcLanguage: String
        get() = sp.getString(KEY_VOLC_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_VOLC_LANGUAGE, value.trim()) }

    // 火山引擎：首字加速（客户端减小分包时长）
    var volcFirstCharAccelEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, value) }

    // 选中的ASR供应商
    var asrVendor: AsrVendor
        get() = AsrVendor.fromId(sp.getString(KEY_ASR_VENDOR, AsrVendor.Volc.id))
        set(value) = sp.edit { putString(KEY_ASR_VENDOR, value.id) }

    // ElevenLabs：语言代码（空=自动识别）
    var elevenLanguageCode: String
        get() = sp.getString(KEY_ELEVEN_LANGUAGE_CODE, "") ?: ""
        set(value) = sp.edit { putString(KEY_ELEVEN_LANGUAGE_CODE, value.trim()) }

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
            VendorField(KEY_ELEVEN_MODEL_ID),
            VendorField(KEY_ELEVEN_LANGUAGE_CODE)
        ),
        AsrVendor.OpenAI to listOf(
            VendorField(KEY_OA_ASR_ENDPOINT, required = true, default = DEFAULT_OA_ASR_ENDPOINT),
            VendorField(KEY_OA_ASR_API_KEY, required = true),
            VendorField(KEY_OA_ASR_MODEL, required = true, default = DEFAULT_OA_ASR_MODEL)
        ),
        AsrVendor.DashScope to listOf(
            VendorField(KEY_DASH_API_KEY, required = true),
            VendorField(KEY_DASH_MODEL, default = DEFAULT_DASH_MODEL),
            VendorField(KEY_DASH_PROMPT, default = ""),
            VendorField(KEY_DASH_LANGUAGE, default = "")
        ),
        AsrVendor.Gemini to listOf(
            VendorField(KEY_GEM_API_KEY, required = true),
            VendorField(KEY_GEM_MODEL, required = true, default = DEFAULT_GEM_MODEL),
            VendorField(KEY_GEM_PROMPT, default = DEFAULT_GEM_PROMPT)
        ),
        AsrVendor.Soniox to listOf(
            VendorField(KEY_SONIOX_API_KEY, required = true)
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
    fun hasSonioxKeys(): Boolean = hasVendorKeys(AsrVendor.Soniox)
    fun hasAsrKeys(): Boolean = hasVendorKeys(asrVendor)
    fun hasLlmKeys(): Boolean {
        val p = getActiveLlmProvider()
        return if (p != null) {
            p.apiKey.isNotBlank() && p.endpoint.isNotBlank() && p.model.isNotBlank()
        } else {
            llmApiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()
        }
    }

    // 自定义标点按钮（4个位置）
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
        // 移除：键盘内“切换输入法”按钮显示开关键
        private const val KEY_AUTO_SWITCH_ON_PASSWORD = "auto_switch_on_password"
        private const val KEY_MIC_HAPTIC_ENABLED = "mic_haptic_enabled"
        private const val KEY_MIC_TAP_TOGGLE_ENABLED = "mic_tap_toggle_enabled"
        private const val KEY_AUTO_STOP_ON_SILENCE_ENABLED = "auto_stop_on_silence_enabled"
        private const val KEY_AUTO_STOP_SILENCE_WINDOW_MS = "auto_stop_silence_window_ms"
        private const val KEY_AUTO_STOP_SILENCE_SENSITIVITY = "auto_stop_silence_sensitivity"
        private const val KEY_KEYBOARD_HEIGHT_TIER = "keyboard_height_tier"
        private const val KEY_FLOATING_SWITCHER_ENABLED = "floating_switcher_enabled"
        private const val KEY_FLOATING_SWITCHER_ALPHA = "floating_switcher_alpha"
        private const val KEY_FLOATING_BALL_SIZE_DP = "floating_ball_size_dp"
        private const val KEY_FLOATING_POS_X = "floating_ball_pos_x"
        private const val KEY_FLOATING_POS_Y = "floating_ball_pos_y"
        private const val KEY_FLOATING_WRITE_COMPAT_ENABLED = "floating_write_compat_enabled"
        private const val KEY_FLOATING_ASR_ENABLED = "floating_asr_enabled"
        private const val KEY_FLOATING_ONLY_WHEN_IME_VISIBLE = "floating_only_when_ime_visible"
        private const val KEY_FLOATING_IME_VISIBILITY_COMPAT_ENABLED = "floating_ime_visibility_compat_enabled"
        private const val KEY_FLOATING_WRITE_COMPAT_PACKAGES = "floating_write_compat_packages"
        private const val KEY_POSTPROC_ENABLED = "postproc_enabled"
        private const val KEY_APP_LANGUAGE_TAG = "app_language_tag"
        private const val KEY_LAST_UPDATE_CHECK_DATE = "last_update_check_date"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_PROVIDERS = "llm_providers"
        private const val KEY_LLM_ACTIVE_ID = "llm_active_id"
        private const val KEY_LLM_PROMPT = "llm_prompt"
        private const val KEY_LLM_PROMPT_PRESETS = "llm_prompt_presets"
        private const val KEY_LLM_PROMPT_ACTIVE_ID = "llm_prompt_active_id"
        private const val KEY_ASR_VENDOR = "asr_vendor"
        private const val KEY_SF_API_KEY = "sf_api_key"
        private const val KEY_SF_MODEL = "sf_model"
        private const val KEY_SF_USE_OMNI = "sf_use_omni"
        private const val KEY_SF_OMNI_PROMPT = "sf_omni_prompt"
        private const val KEY_ELEVEN_API_KEY = "eleven_api_key"
        private const val KEY_ELEVEN_MODEL_ID = "eleven_model_id"
        private const val KEY_ELEVEN_LANGUAGE_CODE = "eleven_language_code"
        private const val KEY_OA_ASR_ENDPOINT = "oa_asr_endpoint"
        private const val KEY_OA_ASR_API_KEY = "oa_asr_api_key"
        private const val KEY_OA_ASR_MODEL = "oa_asr_model"
        private const val KEY_GEM_API_KEY = "gem_api_key"
        private const val KEY_GEM_MODEL = "gem_model"
        private const val KEY_GEM_PROMPT = "gem_prompt"
        private const val KEY_VOLC_STREAMING_ENABLED = "volc_streaming_enabled"
        private const val KEY_VOLC_DDC_ENABLED = "volc_ddc_enabled"
        private const val KEY_VOLC_VAD_ENABLED = "volc_vad_enabled"
        private const val KEY_VOLC_NONSTREAM_ENABLED = "volc_nonstream_enabled"
        private const val KEY_VOLC_LANGUAGE = "volc_language"
        private const val KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED = "volc_first_char_accel_enabled"
        private const val KEY_DASH_API_KEY = "dash_api_key"
        private const val KEY_DASH_MODEL = "dash_model"
        private const val KEY_DASH_PROMPT = "dash_prompt"
        private const val KEY_DASH_LANGUAGE = "dash_language"
        private const val KEY_SONIOX_API_KEY = "soniox_api_key"
        private const val KEY_SONIOX_STREAMING_ENABLED = "soniox_streaming_enabled"
        private const val KEY_SONIOX_LANGUAGE = "soniox_language"
        private const val KEY_SONIOX_LANGUAGES = "soniox_languages"
        private const val KEY_PUNCT_1 = "punct_1"
        private const val KEY_PUNCT_2 = "punct_2"
        private const val KEY_PUNCT_3 = "punct_3"
        private const val KEY_PUNCT_4 = "punct_4"
        private const val KEY_TOTAL_ASR_CHARS = "total_asr_chars"

        const val DEFAULT_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val SF_CHAT_COMPLETIONS_ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
        const val DEFAULT_SF_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_SF_OMNI_MODEL = "Qwen/Qwen3-Omni-30B-A3B-Instruct"
        const val DEFAULT_SF_OMNI_PROMPT = "请将以下音频逐字转写为文本，不要输出解释或前后缀。输入语言可能是中文、英文或其他语言"

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

        // 静音自动判停默认值
        const val DEFAULT_SILENCE_WINDOW_MS = 1200
        const val DEFAULT_SILENCE_SENSITIVITY = 6 // 1-10

        // 标点按钮默认值
        const val DEFAULT_PUNCT_1 = "，"
        const val DEFAULT_PUNCT_2 = "。"
        const val DEFAULT_PUNCT_3 = "！"
        const val DEFAULT_PUNCT_4 = "？"

        // 悬浮球默认大小（dp）
        const val DEFAULT_FLOATING_BALL_SIZE_DP = 44
        // 悬浮写入兼容：默认目标包名（精准匹配，每行一个）
        const val DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES = "org.telegram.messenger\nnu.gpu.nagram\ncom.ss.android.ugc.aweme"

        // Soniox 默认端点
        const val SONIOX_API_BASE_URL = "https://api.soniox.com"
        const val SONIOX_FILES_ENDPOINT = "$SONIOX_API_BASE_URL/v1/files"
        const val SONIOX_TRANSCRIPTIONS_ENDPOINT = "$SONIOX_API_BASE_URL/v1/transcriptions"
        const val SONIOX_WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"

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
        o.put(KEY_MIC_TAP_TOGGLE_ENABLED, micTapToggleEnabled)
        o.put(KEY_AUTO_STOP_ON_SILENCE_ENABLED, autoStopOnSilenceEnabled)
        o.put(KEY_AUTO_STOP_SILENCE_WINDOW_MS, autoStopSilenceWindowMs)
        o.put(KEY_AUTO_STOP_SILENCE_SENSITIVITY, autoStopSilenceSensitivity)
        o.put(KEY_KEYBOARD_HEIGHT_TIER, keyboardHeightTier)
        o.put(KEY_APP_LANGUAGE_TAG, appLanguageTag)
        o.put(KEY_FLOATING_SWITCHER_ENABLED, floatingSwitcherEnabled)
        o.put(KEY_FLOATING_SWITCHER_ALPHA, floatingSwitcherAlpha)
        o.put(KEY_FLOATING_BALL_SIZE_DP, floatingBallSizeDp)
        o.put(KEY_FLOATING_POS_X, floatingBallPosX)
        o.put(KEY_FLOATING_POS_Y, floatingBallPosY)
        o.put(KEY_FLOATING_ASR_ENABLED, floatingAsrEnabled)
        o.put(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE, floatingSwitcherOnlyWhenImeVisible)
        o.put(KEY_FLOATING_IME_VISIBILITY_COMPAT_ENABLED, floatingImeVisibilityCompatEnabled)
        o.put(KEY_POSTPROC_ENABLED, postProcessEnabled)
        o.put(KEY_LLM_ENDPOINT, llmEndpoint)
        o.put(KEY_LLM_API_KEY, llmApiKey)
        o.put(KEY_LLM_MODEL, llmModel)
        o.put(KEY_LLM_TEMPERATURE, llmTemperature.toDouble())
        // Volcano streaming toggle
        o.put(KEY_VOLC_STREAMING_ENABLED, volcStreamingEnabled)
        // Volcano extras
        o.put(KEY_VOLC_DDC_ENABLED, volcDdcEnabled)
        o.put(KEY_VOLC_VAD_ENABLED, volcVadEnabled)
        o.put(KEY_VOLC_NONSTREAM_ENABLED, volcNonstreamEnabled)
        o.put(KEY_VOLC_LANGUAGE, volcLanguage)
        // Soniox（同时导出单值与数组，便于兼容）
        o.put(KEY_SONIOX_LANGUAGE, sonioxLanguage)
        o.put(KEY_SONIOX_LANGUAGES, sonioxLanguagesJson)
        o.put(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED, volcFirstCharAccelEnabled)
        // 多 LLM 配置
        o.put(KEY_LLM_PROVIDERS, llmProvidersJson)
        o.put(KEY_LLM_ACTIVE_ID, activeLlmId)
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
        // 统计信息
        o.put(KEY_TOTAL_ASR_CHARS, totalAsrChars)
        // 兼容性模式
        o.put(KEY_FLOATING_WRITE_COMPAT_ENABLED, floatingWriteTextCompatEnabled)
        o.put(KEY_FLOATING_WRITE_COMPAT_PACKAGES, floatingWriteCompatPackages)
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
            fun optInt(key: String, default: Int? = null): Int? =
                if (o.has(key)) o.optInt(key) else default

            optString(KEY_APP_KEY)?.let { appKey = it }
            optString(KEY_ACCESS_KEY)?.let { accessKey = it }
            optBool(KEY_TRIM_FINAL_TRAILING_PUNCT)?.let { trimFinalTrailingPunct = it }
            optBool(KEY_AUTO_SWITCH_ON_PASSWORD)?.let { autoSwitchOnPassword = it }
            optBool(KEY_MIC_HAPTIC_ENABLED)?.let { micHapticEnabled = it }
            optBool(KEY_MIC_TAP_TOGGLE_ENABLED)?.let { micTapToggleEnabled = it }
            optBool(KEY_AUTO_STOP_ON_SILENCE_ENABLED)?.let { autoStopOnSilenceEnabled = it }
            optInt(KEY_AUTO_STOP_SILENCE_WINDOW_MS)?.let { autoStopSilenceWindowMs = it }
            optInt(KEY_AUTO_STOP_SILENCE_SENSITIVITY)?.let { autoStopSilenceSensitivity = it }
            optInt(KEY_KEYBOARD_HEIGHT_TIER)?.let { keyboardHeightTier = it }
            optString(KEY_APP_LANGUAGE_TAG)?.let { appLanguageTag = it }
            optBool(KEY_POSTPROC_ENABLED)?.let { postProcessEnabled = it }
            optBool(KEY_FLOATING_SWITCHER_ENABLED)?.let { floatingSwitcherEnabled = it }
            optFloat(KEY_FLOATING_SWITCHER_ALPHA)?.let { floatingSwitcherAlpha = it.coerceIn(0.2f, 1.0f) }
            optInt(KEY_FLOATING_BALL_SIZE_DP)?.let { floatingBallSizeDp = it.coerceIn(28, 96) }
            optInt(KEY_FLOATING_POS_X)?.let { floatingBallPosX = it }
            optInt(KEY_FLOATING_POS_Y)?.let { floatingBallPosY = it }
            optBool(KEY_FLOATING_ASR_ENABLED)?.let { floatingAsrEnabled = it }
            optBool(KEY_FLOATING_ONLY_WHEN_IME_VISIBLE)?.let { floatingSwitcherOnlyWhenImeVisible = it }
            optBool(KEY_FLOATING_IME_VISIBILITY_COMPAT_ENABLED)?.let { floatingImeVisibilityCompatEnabled = it }
            optBool(KEY_FLOATING_WRITE_COMPAT_ENABLED)?.let { floatingWriteTextCompatEnabled = it }
            optString(KEY_FLOATING_WRITE_COMPAT_PACKAGES)?.let { floatingWriteCompatPackages = it }

            optString(KEY_LLM_ENDPOINT)?.let { llmEndpoint = it.ifBlank { DEFAULT_LLM_ENDPOINT } }
            optString(KEY_LLM_API_KEY)?.let { llmApiKey = it }
            optString(KEY_LLM_MODEL)?.let { llmModel = it.ifBlank { DEFAULT_LLM_MODEL } }
            optFloat(KEY_LLM_TEMPERATURE)?.let { llmTemperature = it.coerceIn(0f, 2f) }
            optBool(KEY_VOLC_STREAMING_ENABLED)?.let { volcStreamingEnabled = it }
            optBool(KEY_VOLC_DDC_ENABLED)?.let { volcDdcEnabled = it }
            optBool(KEY_VOLC_VAD_ENABLED)?.let { volcVadEnabled = it }
            optBool(KEY_VOLC_NONSTREAM_ENABLED)?.let { volcNonstreamEnabled = it }
            optString(KEY_VOLC_LANGUAGE)?.let { volcLanguage = it }
            optBool(KEY_VOLC_FIRST_CHAR_ACCEL_ENABLED)?.let { volcFirstCharAccelEnabled = it }
            // Soniox（若提供数组则优先；否则回退单值）
            if (o.has(KEY_SONIOX_LANGUAGES)) {
                optString(KEY_SONIOX_LANGUAGES)?.let { sonioxLanguagesJson = it }
            } else {
                optString(KEY_SONIOX_LANGUAGE)?.let { sonioxLanguage = it }
            }
            // 多 LLM 配置（优先于旧字段，仅当存在时覆盖）
            optString(KEY_LLM_PROVIDERS)?.let { llmProvidersJson = it }
            optString(KEY_LLM_ACTIVE_ID)?.let { activeLlmId = it }
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
