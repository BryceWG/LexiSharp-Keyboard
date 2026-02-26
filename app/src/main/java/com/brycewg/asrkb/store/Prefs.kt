/**
 * 偏好设置存取入口（SharedPreferences facade）。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmVendor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
    init {
        PrefsInitTasks.run(appContext, sp)
    }

    // --- JSON 配置：宽松模式（容忍未知键，优雅处理格式错误）---
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // --- 小工具：统一的偏好项委托，减少重复 getter/setter 代码 ---
    private fun stringPref(key: String, default: String = "") = object {
        @Suppress("unused")
        operator fun getValue(thisRef: Prefs, property: KProperty<*>): String =
            sp.getString(key, default) ?: default

        @Suppress("unused")
        operator fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
            sp.edit { putString(key, value.trim()) }
        }
    }

    // 直接从 SP 读取字符串，供通用导入/导出和校验使用
    internal fun getPrefString(key: String, default: String = ""): String =
        sp.getString(key, default) ?: default

    internal fun setPrefString(key: String, value: String) {
        sp.edit { putString(key, value.trim()) }
    }

    private fun normalizeAppLanguageTag(tag: String): String {
        return when (tag.trim().lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant" -> "zh-TW"
            else -> tag.trim()
        }
    }

    private fun createContextForLanguageTag(languageTag: String): Context {
        val normalizedTag = normalizeAppLanguageTag(languageTag)
        if (normalizedTag.isBlank()) return appContext
        val locales = LocaleListCompat.forLanguageTags(normalizedTag)
        if (locales.isEmpty) return appContext
        val tags = locales.toLanguageTags()
        if (tags.isBlank()) return appContext
        val localeList = LocaleList.forLanguageTags(tags)
        if (localeList.isEmpty) return appContext
        val config = Configuration(appContext.resources.configuration)
        config.setLocales(localeList)
        return appContext.createConfigurationContext(config)
    }

    private fun createContextForAppLanguage(): Context = createContextForLanguageTag(appLanguageTag)

    internal fun getLocalizedString(@StringRes resId: Int): String {
        return createContextForAppLanguage().getString(resId)
    }

    private fun buildKnownDefaultPromptPresetVariants(): List<List<PromptPreset>> {
        val tags = listOf("en", "zh-CN", "zh-TW", "ja")
        return tags
            .map { buildDefaultPromptPresets(createContextForLanguageTag(it)) }
            .distinctBy { list -> list.map { it.title to it.content } }
    }

    // 火山引擎凭证
    var appKey: String by stringPref(KEY_APP_KEY, "")

    var accessKey: String by stringPref(KEY_ACCESS_KEY, "")

    var trimFinalTrailingPunct: Boolean
        get() = sp.getBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, true)
        set(value) = sp.edit { putBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, value) }

    // 移除：键盘内“切换输入法”按钮显示开关（按钮始终显示）

    // 输入/点击触觉反馈强度
    var hapticFeedbackLevel: Int
        get() {
            val stored = if (sp.contains(KEY_HAPTIC_FEEDBACK_LEVEL)) {
                sp.getInt(KEY_HAPTIC_FEEDBACK_LEVEL, DEFAULT_HAPTIC_FEEDBACK_LEVEL)
            } else {
                if (sp.getBoolean(KEY_MIC_HAPTIC_ENABLED, true)) {
                    HAPTIC_FEEDBACK_LEVEL_SYSTEM
                } else {
                    HAPTIC_FEEDBACK_LEVEL_OFF
                }
            }
            return stored.coerceIn(HAPTIC_FEEDBACK_LEVEL_OFF, HAPTIC_FEEDBACK_LEVEL_HEAVY)
        }
        set(value) = sp.edit {
            putInt(
                KEY_HAPTIC_FEEDBACK_LEVEL,
                value.coerceIn(HAPTIC_FEEDBACK_LEVEL_OFF, HAPTIC_FEEDBACK_LEVEL_HEAVY),
            )
        }

    // 麦克风按钮触觉反馈（兼容旧开关）
    var micHapticEnabled: Boolean
        get() = hapticFeedbackLevel != HAPTIC_FEEDBACK_LEVEL_OFF
        set(value) {
            hapticFeedbackLevel = if (value) {
                HAPTIC_FEEDBACK_LEVEL_SYSTEM
            } else {
                HAPTIC_FEEDBACK_LEVEL_OFF
            }
        }

    // 麦克风点按控制（点按开始/停止），默认关闭：使用长按说话
    var micTapToggleEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_MIC_TAP_TOGGLE_ENABLED, value) }

    // 启动键盘面板时自动开始录音（默认关闭）
    var autoStartRecordingOnShow: Boolean
        get() = sp.getBoolean(KEY_AUTO_START_RECORDING_ON_SHOW, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO_START_RECORDING_ON_SHOW, value) }

    // 录音时音频避让（请求短时独占音频焦点以暂停/静音媒体），默认开启
    var duckMediaOnRecordEnabled: Boolean
        get() = sp.getBoolean(KEY_DUCK_MEDIA_ON_RECORD, true)
        set(value) = sp.edit { putBoolean(KEY_DUCK_MEDIA_ON_RECORD, value) }

    // 非流式音频降噪（上传前/本地离线识别前），默认开启
    var offlineDenoiseEnabled: Boolean
        get() = sp.getBoolean(KEY_OFFLINE_DENOISE_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_OFFLINE_DENOISE_ENABLED, value) }

    // AI 编辑默认范围：无选区时优先使用"上次识别结果"
    var aiEditDefaultToLastAsr: Boolean
        get() = sp.getBoolean(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, false)
        set(value) = sp.edit { putBoolean(KEY_AI_EDIT_DEFAULT_TO_LAST_ASR, value) }

    // AI 后处理：少于该字数时自动跳过（0 表示不启用）
    var postprocSkipUnderChars: Int
        get() = sp.getInt(KEY_POSTPROC_SKIP_UNDER_CHARS, 0).coerceIn(0, 1000)
        set(value) = sp.edit { putInt(KEY_POSTPROC_SKIP_UNDER_CHARS, value.coerceIn(0, 1000)) }

    // 耳机麦克风优先（自动切换到蓝牙/有线耳机麦克风），默认关闭
    var headsetMicPriorityEnabled: Boolean
        get() = sp.getBoolean(KEY_HEADSET_MIC_PRIORITY_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_HEADSET_MIC_PRIORITY_ENABLED, value) }

    // 允许外部输入法（如小企鹅）通过 AIDL 联动，默认关闭
    var externalAidlEnabled: Boolean
        get() = sp.getBoolean(KEY_EXTERNAL_AIDL_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_EXTERNAL_AIDL_ENABLED, value) }

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

    // 键盘高度档位（1/2/3），默认中档
    var keyboardHeightTier: Int
        get() = sp.getInt(KEY_KEYBOARD_HEIGHT_TIER, 2).coerceIn(1, 3)
        set(value) = sp.edit { putInt(KEY_KEYBOARD_HEIGHT_TIER, value.coerceIn(1, 3)) }

    // 键盘底部间距（单位 dp，范围 0-100，默认 0）
    var keyboardBottomPaddingDp: Int
        get() = sp.getInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, 0).coerceIn(0, 100)
        set(value) = sp.edit { putInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, value.coerceIn(0, 100)) }

    // 波形灵敏度（1-10，数值越大响应越明显），默认 5
    var waveformSensitivity: Int
        get() = sp.getInt(KEY_WAVEFORM_SENSITIVITY, 5).coerceIn(1, 10)
        set(value) = sp.edit { putInt(KEY_WAVEFORM_SENSITIVITY, value.coerceIn(1, 10)) }

    // 是否交换 AI 编辑与输入法切换按钮位置
    var swapAiEditWithImeSwitcher: Boolean
        get() = sp.getBoolean(KEY_SWAP_AI_EDIT_IME_SWITCHER, false)
        set(value) = sp.edit { putBoolean(KEY_SWAP_AI_EDIT_IME_SWITCHER, value) }

    // Fcitx5 联动：通过“输入法切换”键返回（隐藏自身）
    var fcitx5ReturnOnImeSwitch: Boolean
        get() = sp.getBoolean(KEY_FCITX5_RETURN_ON_SWITCHER, false)
        set(value) = sp.edit { putBoolean(KEY_FCITX5_RETURN_ON_SWITCHER, value) }

    // 键盘收起后切回上一个输入法（默认关闭）
    var returnPrevImeOnHide: Boolean
        get() = sp.getBoolean(KEY_RETURN_PREV_IME_ON_HIDE, false)
        set(value) = sp.edit { putBoolean(KEY_RETURN_PREV_IME_ON_HIDE, value) }

    // 输入法切换目标（空字符串表示使用上一个输入法）
    var imeSwitchTargetId: String
        get() = sp.getString(KEY_IME_SWITCH_TARGET_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_IME_SWITCH_TARGET_ID, value.trim()) }

    // 后台隐藏任务卡片（最近任务不显示预览图）
    var hideRecentTaskCard: Boolean
        get() = sp.getBoolean(KEY_HIDE_RECENT_TASK_CARD, false)
        set(value) = sp.edit { putBoolean(KEY_HIDE_RECENT_TASK_CARD, value) }

    // 应用内语言（空字符串表示跟随系统；如："zh-Hans"、"en"）
    var appLanguageTag: String
        get() = sp.getString(KEY_APP_LANGUAGE_TAG, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_LANGUAGE_TAG, value) }

    // 自动检查更新开关（用于“每天首次进入设置页自动检查”）
    var autoUpdateCheckEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, value) }

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

    /**
     * 悬浮球贴边锚点：
     * - side: 0=未设置 1=左 2=右 3=底
     * - fraction: 0..1，左右贴边使用 Y 比例，底部贴边使用 X 比例（-1 表示未设置）
     * - hidden: 左右贴边时是否处于半隐
     */
    var floatingBallDockSide: Int
        get() = sp.getInt(KEY_FLOATING_DOCK_SIDE, 0)
        set(value) = sp.edit { putInt(KEY_FLOATING_DOCK_SIDE, value) }

    var floatingBallDockFraction: Float
        get() = sp.getFloat(KEY_FLOATING_DOCK_FRACTION, -1f)
        set(value) = sp.edit {
            putFloat(
                KEY_FLOATING_DOCK_FRACTION,
                if (value < 0f) -1f else value.coerceIn(0f, 1f),
            )
        }

    var floatingBallDockHidden: Boolean
        get() = sp.getBoolean(KEY_FLOATING_DOCK_HIDDEN, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_DOCK_HIDDEN, value) }

    // 悬浮球：直接拖动移动（无需长按进入移动模式）
    var floatingBallDirectDragEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_DIRECT_DRAG_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_DIRECT_DRAG_ENABLED, value) }

    // 悬浮球语音识别模式开关
    var floatingAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_ASR_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_ASR_ENABLED, value) }

    // 悬浮球：前台保活开关
    var floatingKeepAliveEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_KEEP_ALIVE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_KEEP_ALIVE_ENABLED, value) }

    // 悬浮球：Shizuku / root 增强保活（用于辅助后台重新拉起前台保活服务）
    var floatingKeepAlivePrivilegedEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED, value) }

    // 悬浮球：写入文字兼容性模式（统一控制使用“全选+粘贴”等策略），默认开启
    var floatingWriteTextCompatEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_WRITE_COMPAT_ENABLED, value) }

    // 兼容目标包名（每行一个；支持前缀匹配，例如 org.telegram）
    var floatingWriteCompatPackages: String
        get() = sp.getString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES) ?: DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES
        set(value) = sp.edit { putString(KEY_FLOATING_WRITE_COMPAT_PACKAGES, value) }

    // 悬浮球：写入采取粘贴方案（根据包名将结果仅复制到粘贴板），默认关闭
    var floatingWriteTextPasteEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING_WRITE_PASTE_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_FLOATING_WRITE_PASTE_ENABLED, value) }

    // 粘贴方案目标包名（每行一个；all 表示全局生效；支持前缀匹配）
    var floatingWritePastePackages: String
        get() = sp.getString(KEY_FLOATING_WRITE_PASTE_PACKAGES, "") ?: ""
        set(value) = sp.edit { putString(KEY_FLOATING_WRITE_PASTE_PACKAGES, value) }

    // LLM后处理设置（旧版单一字段；当存在多配置且已选择活动项时仅作回退）
    var postProcessEnabled: Boolean
        get() = sp.getBoolean(KEY_POSTPROC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_POSTPROC_ENABLED, value) }

    // AI 后处理：后处理结果打字机效果（仅影响流式预览展示），默认开启
    var postprocTypewriterEnabled: Boolean
        get() = sp.getBoolean(KEY_POSTPROC_TYPEWRITER_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_POSTPROC_TYPEWRITER_ENABLED, value) }

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

    // 数字/符号小键盘：中文标点模式（true=中文形态，false=英文/ASCII 形态）
    var numpadCnPunctEnabled: Boolean
        get() = sp.getBoolean(KEY_NUMPAD_CN_PUNCT, true)
        set(value) = sp.edit { putBoolean(KEY_NUMPAD_CN_PUNCT, value) }

    @Serializable
    data class LlmProvider(
        val id: String,
        val name: String,
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Float,
        val models: List<String> = emptyList(),
        val enableReasoning: Boolean = false,
        val reasoningParamsOnJson: String = "",
        val reasoningParamsOffJson: String = "",
    )

    fun getLlmProviders(): List<LlmProvider> = PrefsLlmProviderStore.getLlmProviders(this, json)

    fun setLlmProviders(list: List<LlmProvider>) = PrefsLlmProviderStore.setLlmProviders(this, json, list)

    fun getActiveLlmProvider(): LlmProvider? = PrefsLlmProviderStore.getActiveLlmProvider(this, json)

    // 已弃用：单一提示词。保留用于向后兼容/迁移。
    var llmPrompt: String
        get() = sp.getString(KEY_LLM_PROMPT, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT, value) }

    // 多个预设提示词，包含标题和活动选择
    var promptPresetsJson: String
        get() = sp.getString(KEY_LLM_PROMPT_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_PRESETS, value) }

    var activePromptId: String
        get() = sp.getString(KEY_LLM_PROMPT_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_ACTIVE_ID, value) }

    fun getPromptPresets(): List<PromptPreset> {
        val legacyPrompt = llmPrompt.trim()
        val defaults = buildDefaultPromptPresets(createContextForAppLanguage())
        val knownDefaultVariants = buildKnownDefaultPromptPresetVariants()
        var initializedFromDefaults = false
        // 如果未设置预设，初始化默认预设
        if (promptPresetsJson.isBlank()) {
            initializedFromDefaults = true
            setPromptPresets(defaults)
            // 将第一个设为活动状态
            if (activePromptId.isBlank()) activePromptId = defaults.firstOrNull()?.id ?: ""
            return PromptPresetMigrations.migrateLegacyPromptIfNeeded(
                prefs = this,
                current = defaults,
                localizedDefaults = defaults,
                knownDefaultVariants = knownDefaultVariants,
                legacyPrompt = legacyPrompt,
                initializedFromDefaults = initializedFromDefaults,
            )
        }
        val parsed = try {
            val list = json.decodeFromString<List<PromptPreset>>(promptPresetsJson)
            list.ifEmpty { defaults }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PromptPresets JSON", e)
            defaults
        }
        val remapped = PromptPresetMigrations.remapLegacyDefaultPresetIdsIfNeeded(
            prefs = this,
            current = parsed,
            localizedDefaults = defaults,
            knownDefaultVariants = knownDefaultVariants,
        )
        val migrated = PromptPresetMigrations.migrateLegacyPromptIfNeeded(
            prefs = this,
            current = remapped,
            localizedDefaults = defaults,
            knownDefaultVariants = knownDefaultVariants,
            legacyPrompt = legacyPrompt,
            initializedFromDefaults = initializedFromDefaults,
        )
        val synced = PromptPresetMigrations.syncDefaultsForLanguageIfNeeded(
            prefs = this,
            current = migrated,
            localizedDefaults = defaults,
            knownDefaultVariants = knownDefaultVariants,
        )
        if (synced != parsed) {
            setPromptPresets(synced)
        }
        return synced
    }

    fun setPromptPresets(list: List<PromptPreset>) {
        try {
            promptPresetsJson = json.encodeToString(list)
            // 确保活动ID有效
            if (list.none { it.id == activePromptId }) {
                activePromptId = list.firstOrNull()?.id ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize PromptPresets", e)
        }
    }

    /**
     * 获取当前选中预设的 prompt 内容。
     * 回退顺序：选中的预设 -> 第一个预设（保证始终有值）
     */
    val activePromptContent: String
        get() {
            val presets = getPromptPresets()
            val id = activePromptId
            val legacyPrompt = llmPrompt.trim().takeIf { it.isNotEmpty() }
            return presets.firstOrNull { it.id == id }?.content
                ?: presets.firstOrNull()?.content
                ?: legacyPrompt
                ?: ""
        }

    // 语音预置信息（触发短语 -> 替换内容）
    var speechPresetsJson: String
        get() = sp.getString(KEY_SPEECH_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_SPEECH_PRESETS, value) }

    var activeSpeechPresetId: String
        get() = sp.getString(KEY_SPEECH_PRESET_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_SPEECH_PRESET_ACTIVE_ID, value) }

    fun getSpeechPresets(): List<SpeechPreset> = SpeechPresetStore.getSpeechPresets(this, json)

    fun setSpeechPresets(list: List<SpeechPreset>) = SpeechPresetStore.setSpeechPresets(this, json, list)

    fun findSpeechPresetReplacement(original: String): String? =
        SpeechPresetStore.findSpeechPresetReplacement(this, json, original)

    // SiliconFlow凭证
    var sfApiKey: String by stringPref(KEY_SF_API_KEY, "")

    var sfModel: String by stringPref(KEY_SF_MODEL, DEFAULT_SF_MODEL)

    // SiliconFlow：是否使用多模态（Qwen3-Omni 系列，通过 chat/completions）
    var sfUseOmni: Boolean
        get() = sp.getBoolean(KEY_SF_USE_OMNI, false)
        set(value) = sp.edit { putBoolean(KEY_SF_USE_OMNI, value) }

    // SiliconFlow：多模态识别提示词（chat/completions 文本部分）
    var sfOmniPrompt: String
        get() {
            val fallback = getLocalizedString(R.string.prompt_default_sf_omni)
            return sp.getString(KEY_SF_OMNI_PROMPT, fallback) ?: fallback
        }
        set(value) = sp.edit { putString(KEY_SF_OMNI_PROMPT, value) }

    // SiliconFlow 免费服务：是否启用免费 ASR 服务（新用户默认启用）
    var sfFreeAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_ASR_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_ASR_ENABLED, value) }

    // SiliconFlow 免费服务：ASR 模型选择
    var sfFreeAsrModel: String
        get() = sp.getString(KEY_SF_FREE_ASR_MODEL, DEFAULT_SF_FREE_ASR_MODEL) ?: DEFAULT_SF_FREE_ASR_MODEL
        set(value) = sp.edit { putString(KEY_SF_FREE_ASR_MODEL, value) }

    // SiliconFlow 免费服务：是否启用免费 LLM（AI 后处理，新用户默认启用）
    var sfFreeLlmEnabled: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_LLM_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_LLM_ENABLED, value) }

    // SiliconFlow 免费服务：LLM 模型选择
    var sfFreeLlmModel: String
        get() = sp.getString(KEY_SF_FREE_LLM_MODEL, DEFAULT_SF_FREE_LLM_MODEL) ?: DEFAULT_SF_FREE_LLM_MODEL
        set(value) = sp.edit { putString(KEY_SF_FREE_LLM_MODEL, value) }

    // SiliconFlow：是否使用自己的付费 API Key（而非免费服务）
    var sfFreeLlmUsePaidKey: Boolean
        get() = sp.getBoolean(KEY_SF_FREE_LLM_USE_PAID_KEY, false)
        set(value) = sp.edit { putBoolean(KEY_SF_FREE_LLM_USE_PAID_KEY, value) }

    // ========== LLM 供应商选择（新架构） ==========

    // 当前选择的 LLM 供应商（默认使用 SiliconFlow 免费服务）
    var llmVendor: LlmVendor
        get() {
            val stored = sp.getString(KEY_LLM_VENDOR, null)
            // 兼容旧版本：如果未设置供应商，进行智能迁移
            if (stored == null) {
                // 如果启用了 SF 免费 LLM，使用 SF_FREE
                if (sfFreeLlmEnabled) return LlmVendor.SF_FREE
                // 检查是否有配置完整的自定义供应商
                val provider = getActiveLlmProvider()
                if (provider != null && provider.endpoint.isNotBlank() && provider.model.isNotBlank()) {
                    return LlmVendor.CUSTOM
                }
                // 兜底：默认使用 SF_FREE（免费服务无需配置即可使用）
                return LlmVendor.SF_FREE
            }
            return LlmVendor.fromId(stored)
        }
        set(value) = sp.edit { putString(KEY_LLM_VENDOR, value.id) }

    // 内置供应商 API Key 存储（按供应商 ID 分别存储）
    fun getLlmVendorApiKey(vendor: LlmVendor): String =
        PrefsLlmVendorStore.getLlmVendorApiKey(sp, vendor)

    fun setLlmVendorApiKey(vendor: LlmVendor, apiKey: String) =
        PrefsLlmVendorStore.setLlmVendorApiKey(sp, vendor, apiKey)

    fun getLlmVendorModel(vendor: LlmVendor): String =
        PrefsLlmVendorStore.getLlmVendorModel(sp, vendor)

    fun setLlmVendorModel(vendor: LlmVendor, model: String) =
        PrefsLlmVendorStore.setLlmVendorModel(sp, vendor, model)

    fun getLlmVendorTemperature(vendor: LlmVendor): Float =
        PrefsLlmVendorStore.getLlmVendorTemperature(sp, vendor)

    fun setLlmVendorTemperature(vendor: LlmVendor, temperature: Float) =
        PrefsLlmVendorStore.setLlmVendorTemperature(sp, vendor, temperature)

    fun getLlmVendorReasoningEnabled(vendor: LlmVendor): Boolean =
        PrefsLlmVendorStore.getLlmVendorReasoningEnabled(sp, vendor)

    fun setLlmVendorReasoningEnabled(vendor: LlmVendor, enabled: Boolean) =
        PrefsLlmVendorStore.setLlmVendorReasoningEnabled(sp, vendor, enabled)

    fun getLlmVendorModels(vendor: LlmVendor): List<String> =
        PrefsLlmVendorStore.getLlmVendorModels(sp, json, vendor, sfFreeLlmUsePaidKey)

    fun setLlmVendorModels(vendor: LlmVendor, models: List<String>) =
        PrefsLlmVendorStore.setLlmVendorModels(sp, json, vendor, models)

    fun setLlmVendorModelsJson(vendor: LlmVendor, raw: String) =
        PrefsLlmVendorStore.setLlmVendorModelsJson(sp, vendor, raw)

    fun getLlmVendorReasoningParamsOnJson(vendor: LlmVendor): String =
        PrefsLlmVendorStore.getLlmVendorReasoningParamsOnJson(sp, vendor)

    fun setLlmVendorReasoningParamsOnJson(vendor: LlmVendor, json: String) =
        PrefsLlmVendorStore.setLlmVendorReasoningParamsOnJson(sp, vendor, json)

    fun getLlmVendorReasoningParamsOffJson(vendor: LlmVendor): String =
        PrefsLlmVendorStore.getLlmVendorReasoningParamsOffJson(sp, vendor)

    fun setLlmVendorReasoningParamsOffJson(vendor: LlmVendor, json: String) =
        PrefsLlmVendorStore.setLlmVendorReasoningParamsOffJson(sp, vendor, json)

    /**
     * 获取当前有效的 LLM 配置（根据选择的供应商）
     * @return EffectiveLlmConfig 或 null（如果配置无效）
     */
    fun getEffectiveLlmConfig(): EffectiveLlmConfig? =
        PrefsLlmVendorStore.getEffectiveLlmConfig(this, sp)

    /** 有效的 LLM 配置数据类 */
    data class EffectiveLlmConfig(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val temperature: Float,
        val vendor: LlmVendor,
        val enableReasoning: Boolean,
        val useCustomReasoningParams: Boolean,
        val reasoningParamsOnJson: String,
        val reasoningParamsOffJson: String,
    )

    // 阿里云百炼（DashScope）凭证
    var dashApiKey: String by stringPref(KEY_DASH_API_KEY, "")

    // DashScope：自定义识别上下文（提示词）
    var dashPrompt: String by stringPref(KEY_DASH_PROMPT, "")

    // DashScope：识别语言（空字符串表示自动/未指定）
    var dashLanguage: String
        get() = sp.getString(KEY_DASH_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_DASH_LANGUAGE, value.trim()) }

    // DashScope：地域（cn=中国大陆，intl=新加坡/国际）。默认 cn
    var dashRegion: String by stringPref(KEY_DASH_REGION, "cn")

    fun getDashHttpBaseUrl(): String = DashScopePrefsCompat.getDashHttpBaseUrl(dashRegion)

    // DashScope：ASR 模型选择（用于替代“流式开关 + Fun-ASR 开关”的组合）
    // - qwen3-asr-flash：非流式
    // - qwen3-asr-flash-realtime：流式（Qwen3）
    // - fun-asr-realtime：流式（Fun-ASR）
    var dashAsrModel: String
        get() {
            val v = (sp.getString(KEY_DASH_ASR_MODEL, "") ?: "").trim()
            if (v.isNotBlank()) return v

            val derived = DashScopePrefsCompat.deriveDashAsrModelFromLegacyFlags(sp)
            // 迁移：写回新 key，后续直接读取
            try {
                sp.edit { putString(KEY_DASH_ASR_MODEL, derived) }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to migrate DashScope model to dash_asr_model", t)
            }
            return derived
        }
        set(value) {
            val model = value.trim().ifBlank { DEFAULT_DASH_MODEL }
            sp.edit {
                putString(KEY_DASH_ASR_MODEL, model)
                // 同步旧开关，便于兼容旧版本导入/导出
                putBoolean(KEY_DASH_STREAMING_ENABLED, model.endsWith("-realtime", ignoreCase = true))
                putBoolean(KEY_DASH_FUNASR_ENABLED, model.startsWith("fun-asr", ignoreCase = true))
            }
        }

    fun isDashStreamingModelSelected(): Boolean {
        return dashAsrModel.endsWith("-realtime", ignoreCase = true)
    }

    fun isDashPromptSupportedByModel(): Boolean {
        return !dashAsrModel.startsWith("fun-asr", ignoreCase = true)
    }

    // DashScope: streaming toggle（legacy，已由 dashAsrModel 替代）
    var dashStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DASH_STREAMING_ENABLED, value) }

    // DashScope: streaming 使用 Fun-ASR 模型（legacy，已由 dashAsrModel 替代）
    var dashFunAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DASH_FUNASR_ENABLED, value) }

    // DashScope: Fun-ASR 使用语义断句（开启时关闭 VAD 断句）
    var dashFunAsrSemanticPunctEnabled: Boolean
        get() = sp.getBoolean(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_DASH_FUNASR_SEMANTIC_PUNCT_ENABLED, value) }

    // ElevenLabs凭证
    var elevenApiKey: String by stringPref(KEY_ELEVEN_API_KEY, "")

    // ElevenLabs：流式识别开关
    var elevenStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_ELEVEN_STREAMING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_ELEVEN_STREAMING_ENABLED, value) }

    // OpenAI 语音转文字（ASR）配置
    var oaAsrEndpoint: String by stringPref(KEY_OA_ASR_ENDPOINT, DEFAULT_OA_ASR_ENDPOINT)

    var oaAsrApiKey: String by stringPref(KEY_OA_ASR_API_KEY, "")

    var oaAsrModel: String by stringPref(KEY_OA_ASR_MODEL, DEFAULT_OA_ASR_MODEL)

    // OpenAI：是否启用自定义 Prompt（部分模型不支持）
    var oaAsrUsePrompt: Boolean
        get() = sp.getBoolean(KEY_OA_ASR_USE_PROMPT, false)
        set(value) = sp.edit { putBoolean(KEY_OA_ASR_USE_PROMPT, value) }

    // OpenAI：自定义识别 Prompt（可选）
    var oaAsrPrompt: String by stringPref(KEY_OA_ASR_PROMPT, "")

    // OpenAI：识别语言（空字符串表示不指定）
    var oaAsrLanguage: String
        get() = sp.getString(KEY_OA_ASR_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_OA_ASR_LANGUAGE, value.trim()) }

    // Google Gemini 语音理解（通过提示词转写）
    var gemEndpoint: String by stringPref(KEY_GEM_ENDPOINT, DEFAULT_GEM_ENDPOINT)

    var gemApiKey: String by stringPref(KEY_GEM_API_KEY, "")

    fun getGeminiApiKeys(): List<String> {
        return gemApiKey.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    var gemModel: String by stringPref(KEY_GEM_MODEL, DEFAULT_GEM_MODEL)

    var gemPrompt: String
        get() {
            val fallback = getLocalizedString(R.string.prompt_default_gem)
            return sp.getString(KEY_GEM_PROMPT, fallback) ?: fallback
        }
        set(value) = sp.edit { putString(KEY_GEM_PROMPT, value) }

    var geminiDisableThinking: Boolean
        get() = sp.getBoolean(KEY_GEMINI_DISABLE_THINKING, false)
        set(value) = sp.edit { putBoolean(KEY_GEMINI_DISABLE_THINKING, value) }

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

    // Soniox：语言严格限制（language_hints_strict）；默认关闭
    var sonioxLanguageHintsStrict: Boolean
        get() = sp.getBoolean(KEY_SONIOX_LANGUAGE_HINTS_STRICT, false)
        set(value) = sp.edit { putBoolean(KEY_SONIOX_LANGUAGE_HINTS_STRICT, value) }

    // 智谱 GLM ASR
    var zhipuApiKey: String by stringPref(KEY_ZHIPU_API_KEY, "")

    // 智谱 GLM：temperature 参数（0.0-1.0，默认 0.95）
    var zhipuTemperature: Float
        get() = sp.getFloat(KEY_ZHIPU_TEMPERATURE, DEFAULT_ZHIPU_TEMPERATURE).coerceIn(0f, 1f)
        set(value) = sp.edit { putFloat(KEY_ZHIPU_TEMPERATURE, value.coerceIn(0f, 1f)) }

    // 智谱 GLM：上下文提示（prompt），用于长文本场景的前文上下文，建议小于8000字
    var zhipuPrompt: String by stringPref(KEY_ZHIPU_PROMPT, "")

    fun getSonioxLanguages(): List<String> = SonioxLanguagesStore.getSonioxLanguages(this, json)

    fun setSonioxLanguages(list: List<String>) = SonioxLanguagesStore.setSonioxLanguages(this, json, list)

    // 火山引擎：流式识别开关（与文件模式共享凭证）
    var volcStreamingEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_STREAMING_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_STREAMING_ENABLED, value) }

    // 火山引擎：语义顺滑开关（enable_ddc）
    var volcDdcEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_DDC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_DDC_ENABLED, value) }

    // 火山引擎：VAD 分句开关（控制判停参数）
    var volcVadEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_VAD_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_VOLC_VAD_ENABLED, value) }

    // 火山引擎：二遍识别开关（enable_nonstream）
    var volcNonstreamEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_NONSTREAM_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_NONSTREAM_ENABLED, value) }

    // 火山引擎：识别语言（nostream 支持；空=自动中英/方言）
    var volcLanguage: String
        get() = sp.getString(KEY_VOLC_LANGUAGE, "") ?: ""
        set(value) = sp.edit { putString(KEY_VOLC_LANGUAGE, value.trim()) }

    // 火山引擎：文件识别标准版开关（submit/query）
    var volcFileStandardEnabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_FILE_STANDARD_ENABLED, value) }

    // 火山引擎：使用 2.0 模型（默认 true）
    var volcModelV2Enabled: Boolean
        get() = sp.getBoolean(KEY_VOLC_MODEL_V2_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_VOLC_MODEL_V2_ENABLED, value) }

    // 选中的ASR供应商（默认使用 SiliconFlow 免费服务）
    var asrVendor: AsrVendor
        get() = AsrVendor.fromId(sp.getString(KEY_ASR_VENDOR, AsrVendor.SiliconFlow.id))
        set(value) = sp.edit { putString(KEY_ASR_VENDOR, value.id) }

    // 备用 ASR 引擎：开关（默认关闭）
    var backupAsrEnabled: Boolean
        get() = sp.getBoolean(KEY_BACKUP_ASR_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_BACKUP_ASR_ENABLED, value) }

    // 备用 ASR 引擎：供应商（默认跟随主引擎）
    var backupAsrVendor: AsrVendor
        get() {
            val stored = sp.getString(KEY_BACKUP_ASR_VENDOR, null)
            return if (stored.isNullOrBlank()) asrVendor else AsrVendor.fromId(stored)
        }
        set(value) = sp.edit { putString(KEY_BACKUP_ASR_VENDOR, value.id) }

    // 备用 ASR 引擎：主引擎超时阈值敏感度（0/1/2，默认 1=均衡）
    var backupAsrTimeoutSensitivity: Int
        get() {
            try {
                return sp.getInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, 1).coerceIn(0, 2)
            } catch (t: ClassCastException) {
                val parsed = try {
                    sp.getString(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, null)?.toIntOrNull()
                } catch (_: Throwable) {
                    null
                }
                val normalized = (parsed ?: 1).coerceIn(0, 2)
                try {
                    sp.edit {
                        if (parsed == null) remove(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY)
                        putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, normalized)
                    }
                } catch (_: Throwable) {
                    // ignore: best-effort normalization
                }
                return normalized
            }
        }
        set(value) = sp.edit { putInt(KEY_BACKUP_ASR_TIMEOUT_SENSITIVITY, value.coerceIn(0, 2)) }

    // ElevenLabs：语言代码（空=自动识别）
    var elevenLanguageCode: String
        get() = sp.getString(KEY_ELEVEN_LANGUAGE_CODE, "") ?: ""
        set(value) = sp.edit { putString(KEY_ELEVEN_LANGUAGE_CODE, value.trim()) }

    // SenseVoice（本地 ASR）设置
    var svModelDir: String
        get() = sp.getString(KEY_SV_MODEL_DIR, "") ?: ""
        set(value) = sp.edit { putString(KEY_SV_MODEL_DIR, value.trim()) }

    // SenseVoice 模型版本：small-int8 / small-full（默认 small-int8）
    var svModelVariant: String
        get() = sp.getString(KEY_SV_MODEL_VARIANT, "small-int8") ?: "small-int8"
        set(value) = sp.edit { putString(KEY_SV_MODEL_VARIANT, value.trim().ifBlank { "small-int8" }) }

    var svNumThreads: Int
        get() = sp.getInt(KEY_SV_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_SV_NUM_THREADS, value.coerceIn(1, 8)) }

    // SenseVoice：语言（auto/zh/en/ja/ko/yue）与 ITN 开关
    var svLanguage: String
        get() = sp.getString(KEY_SV_LANGUAGE, "auto") ?: "auto"
        set(value) = sp.edit { putString(KEY_SV_LANGUAGE, value.trim().ifBlank { "auto" }) }

    var svUseItn: Boolean
        get() = sp.getBoolean(KEY_SV_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_SV_USE_ITN, value) }

    // SenseVoice：首次显示时预加载（默认关闭）
    var svPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_SV_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SV_PRELOAD_ENABLED, value) }

    // SenseVoice：模型保留时长（分钟）。-1=始终保持；0=识别后立即卸载。
    var svKeepAliveMinutes: Int
        get() = sp.getInt(KEY_SV_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_SV_KEEP_ALIVE_MINUTES, value) }

    // SenseVoice：伪流式模式开关（基于 VAD 分句预览）
    var svPseudoStreamEnabled: Boolean
        get() = sp.getBoolean(KEY_SV_PSEUDO_STREAM_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SV_PSEUDO_STREAM_ENABLED, value) }

    // FunASR Nano（本地 ASR）
    var fnModelVariant: String
        get() = sp.getString(KEY_FN_MODEL_VARIANT, "nano-int8") ?: "nano-int8"
        set(value) = sp.edit { putString(KEY_FN_MODEL_VARIANT, value.trim().ifBlank { "nano-int8" }) }

    var fnNumThreads: Int
        get() = sp.getInt(KEY_FN_NUM_THREADS, 4).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_FN_NUM_THREADS, value.coerceIn(1, 8)) }

    var fnUseItn: Boolean
        get() = sp.getBoolean(KEY_FN_USE_ITN, false)
        set(value) = sp.edit { putBoolean(KEY_FN_USE_ITN, value) }

    // FunASR Nano：LLM user prompt（用于引导转写格式/语言等；较长可能拖慢推理）
    var fnUserPrompt: String
        get() = sp.getString(KEY_FN_USER_PROMPT, "语音转写：") ?: "语音转写："
        set(value) = sp.edit { putString(KEY_FN_USER_PROMPT, value.trim()) }

    var fnPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_FN_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_FN_PRELOAD_ENABLED, value) }

    var fnKeepAliveMinutes: Int
        get() = sp.getInt(KEY_FN_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_FN_KEEP_ALIVE_MINUTES, value) }

    // TeleSpeech（本地 ASR）
    var tsModelVariant: String
        get() = sp.getString(KEY_TS_MODEL_VARIANT, "int8") ?: "int8"
        set(value) = sp.edit { putString(KEY_TS_MODEL_VARIANT, value.trim().ifBlank { "int8" }) }

    var tsNumThreads: Int
        get() = sp.getInt(KEY_TS_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_TS_NUM_THREADS, value.coerceIn(1, 8)) }

    var tsKeepAliveMinutes: Int
        get() = sp.getInt(KEY_TS_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_TS_KEEP_ALIVE_MINUTES, value) }

    var tsPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_TS_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_TS_PRELOAD_ENABLED, value) }

    // TeleSpeech：ITN 开关（反向文本规范化）
    var tsUseItn: Boolean
        get() = sp.getBoolean(KEY_TS_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_TS_USE_ITN, value) }

    // TeleSpeech：伪流式模式开关（基于 VAD 分句预览）
    var tsPseudoStreamEnabled: Boolean
        get() = sp.getBoolean(KEY_TS_PSEUDO_STREAM_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_TS_PSEUDO_STREAM_ENABLED, value) }

    // Paraformer（本地 ASR）
    var pfModelVariant: String
        get() = sp.getString(KEY_PF_MODEL_VARIANT, "bilingual-int8") ?: "bilingual-int8"
        set(value) = sp.edit { putString(KEY_PF_MODEL_VARIANT, value.trim().ifBlank { "bilingual-int8" }) }

    var pfNumThreads: Int
        get() = sp.getInt(KEY_PF_NUM_THREADS, 2).coerceIn(1, 8)
        set(value) = sp.edit { putInt(KEY_PF_NUM_THREADS, value.coerceIn(1, 8)) }

    var pfKeepAliveMinutes: Int
        get() = sp.getInt(KEY_PF_KEEP_ALIVE_MINUTES, -1)
        set(value) = sp.edit { putInt(KEY_PF_KEEP_ALIVE_MINUTES, value) }

    var pfPreloadEnabled: Boolean
        get() = sp.getBoolean(KEY_PF_PRELOAD_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_PF_PRELOAD_ENABLED, value) }

    // Paraformer: ITN 开关（反向文本规范化）
    var pfUseItn: Boolean
        get() = sp.getBoolean(KEY_PF_USE_ITN, true)
        set(value) = sp.edit { putBoolean(KEY_PF_USE_ITN, value) }

    // Zipformer 模型清理标记（移除 Zipformer 支持后仅执行一次）
    var zipformerCleanupDone: Boolean
        get() = sp.getBoolean(KEY_ZIPFORMER_CLEANUP_DONE, false)
        set(value) = sp.edit { putBoolean(KEY_ZIPFORMER_CLEANUP_DONE, value) }

    // --- 供应商配置通用化 ---
    internal val vendorFields: Map<AsrVendor, List<VendorField>> = PrefsAsrVendorFields.vendorFields

    fun hasVendorKeys(v: AsrVendor): Boolean {
        if (v == AsrVendor.OpenAI) {
            return hasOpenAiAsrConfigured()
        }
        val fields = vendorFields[v] ?: return false
        return fields.filter { it.required }.all { f ->
            getPrefString(f.key, f.default).isNotBlank()
        }
    }

    /**
     * OpenAI ASR 配置判定：
     * - 默认官方 endpoint 需要 API Key
     * - 自定义（OpenAI 兼容）endpoint 允许空 API Key
     */
    private fun hasOpenAiAsrConfigured(): Boolean {
        val endpoint = oaAsrEndpoint.ifBlank { DEFAULT_OA_ASR_ENDPOINT }.trim()
        val model = oaAsrModel.ifBlank { DEFAULT_OA_ASR_MODEL }.trim()
        if (endpoint.isBlank() || model.isBlank()) return false
        val endpointNormalized = endpoint.trimEnd('/')
        val defaultEndpointNormalized = DEFAULT_OA_ASR_ENDPOINT.trimEnd('/')
        return if (endpointNormalized.equals(defaultEndpointNormalized, ignoreCase = true)) {
            oaAsrApiKey.isNotBlank()
        } else {
            true
        }
    }

    fun hasVolcKeys(): Boolean = hasVendorKeys(AsrVendor.Volc)
    fun hasSfKeys(): Boolean = sfFreeAsrEnabled || sfApiKey.isNotBlank() // 免费服务启用或有 API Key
    fun hasDashKeys(): Boolean = hasVendorKeys(AsrVendor.DashScope)
    fun hasElevenKeys(): Boolean = hasVendorKeys(AsrVendor.ElevenLabs)
    fun hasOpenAiKeys(): Boolean = hasVendorKeys(AsrVendor.OpenAI)
    fun hasGeminiKeys(): Boolean = hasVendorKeys(AsrVendor.Gemini)
    fun hasSonioxKeys(): Boolean = hasVendorKeys(AsrVendor.Soniox)
    fun hasZhipuKeys(): Boolean = hasVendorKeys(AsrVendor.Zhipu)
    fun hasAsrKeys(): Boolean = hasVendorKeys(asrVendor)
    fun hasLlmKeys(): Boolean {
        // 使用新的 getEffectiveLlmConfig 检查配置有效性
        return getEffectiveLlmConfig() != null
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

    // 自定义扩展按钮（4个位置，存储动作类型ID）
    // 默认值（从左到右）：撤销、全选、复制、收起键盘
    var extBtn1: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_1, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[0]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_1, value.id) }

    var extBtn2: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_2, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[1]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_2, value.id) }

    var extBtn3: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_3, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[2]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_3, value.id) }

    var extBtn4: com.brycewg.asrkb.ime.ExtensionButtonAction
        get() {
            val stored = sp.getString(KEY_EXT_BTN_4, null)
            return if (stored == null) {
                com.brycewg.asrkb.ime.ExtensionButtonAction.getDefaults()[3]
            } else {
                com.brycewg.asrkb.ime.ExtensionButtonAction.fromId(stored)
            }
        }
        set(value) = sp.edit { putString(KEY_EXT_BTN_4, value.id) }

    // 历史语音识别总字数（仅统计最终提交到编辑器的识别结果；AI编辑不计入）
    var totalAsrChars: Long
        get() = sp.getLong(KEY_TOTAL_ASR_CHARS, 0L).coerceAtLeast(0L)
        set(value) = sp.edit { putLong(KEY_TOTAL_ASR_CHARS, value.coerceAtLeast(0L)) }

    // 首次启动引导是否已展示
    var hasShownQuickGuideOnce: Boolean
        get() = sp.getBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, false)
        set(value) = sp.edit { putBoolean(KEY_SHOWN_QUICK_GUIDE_ONCE, value) }

    // 模型选择引导是否已展示
    var hasShownModelGuideOnce: Boolean
        get() = sp.getBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, false)
        set(value) = sp.edit { putBoolean(KEY_SHOWN_MODEL_GUIDE_ONCE, value) }

    // 新手引导页（V2）是否已展示
    var hasShownOnboardingGuideV2Once: Boolean
        get() = sp.getBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, false)
        set(value) = sp.edit { putBoolean(KEY_SHOWN_ONBOARDING_GUIDE_V2_ONCE, value) }

    // Pro 版宣传弹窗是否已显示过
    var proPromoShown: Boolean
        get() = sp.getBoolean(KEY_PRO_PROMO_SHOWN, false)
        set(value) = sp.edit { putBoolean(KEY_PRO_PROMO_SHOWN, value) }

    // 隐私：关闭识别历史记录
    var disableAsrHistory: Boolean
        get() = sp.getBoolean(KEY_DISABLE_ASR_HISTORY, false)
        set(value) = sp.edit { putBoolean(KEY_DISABLE_ASR_HISTORY, value) }

    // 隐私：关闭数据统计记录
    var disableUsageStats: Boolean
        get() = sp.getBoolean(KEY_DISABLE_USAGE_STATS, false)
        set(value) = sp.edit { putBoolean(KEY_DISABLE_USAGE_STATS, value) }

    // 隐私：匿名数据采集（PocketBase）开关
    var dataCollectionEnabled: Boolean
        get() = sp.getBoolean(KEY_DATA_COLLECTION_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_DATA_COLLECTION_ENABLED, value) }

    // 匿名数据采集首次同意弹窗是否已展示
    var dataCollectionConsentShown: Boolean
        get() = sp.getBoolean(KEY_DATA_COLLECTION_CONSENT_SHOWN, false)
        set(value) = sp.edit { putBoolean(KEY_DATA_COLLECTION_CONSENT_SHOWN, value) }

    // 匿名统计用户标识（随机生成）
    var analyticsUserId: String
        get() = sp.getString(KEY_ANALYTICS_USER_ID, "").orEmpty()
        set(value) = sp.edit { putString(KEY_ANALYTICS_USER_ID, value) }

    // 每日随机上报时间（分钟，0..1439）
    var analyticsReportMinuteOfDay: Int
        get() = sp.getInt(KEY_ANALYTICS_REPORT_MINUTE, -1)
        set(value) = sp.edit { putInt(KEY_ANALYTICS_REPORT_MINUTE, value.coerceIn(0, 1439)) }

    // 上次成功上报的本地日期（epochDay）
    var analyticsLastUploadEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_UPLOAD_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_UPLOAD_EPOCH_DAY, value) }

    // 上次尝试上报的本地日期（epochDay，用于失败后当天不再重复触发）
    var analyticsLastAttemptEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_DAY, value) }

    // 上次尝试上报的时间戳（ms，用于冷却重试）
    var analyticsLastAttemptEpochMs: Long
        get() = sp.getLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_MS, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_LAST_ATTEMPT_EPOCH_MS, value) }

    // 当天是否已用过一次冷却重试（epochDay）
    var analyticsRetryUsedEpochDay: Long
        get() = sp.getLong(KEY_ANALYTICS_RETRY_USED_EPOCH_DAY, -1L)
        set(value) = sp.edit { putLong(KEY_ANALYTICS_RETRY_USED_EPOCH_DAY, value) }

    // 一次性迁移标志：重置同意弹窗以重新采集设备信息（v1 修复打包问题后）
    var analyticsConsentResetV1Done: Boolean
        get() = sp.getBoolean(KEY_ANALYTICS_CONSENT_RESET_V1_DONE, false)
        set(value) = sp.edit { putBoolean(KEY_ANALYTICS_CONSENT_RESET_V1_DONE, value) }

    fun addAsrChars(delta: Int) {
        if (delta <= 0) return
        val cur = totalAsrChars
        val next = (cur + delta).coerceAtLeast(0L)
        totalAsrChars = next
    }

    // ===== 使用统计（聚合） =====

    // 首次使用日期（yyyyMMdd）。若为空将在首次读取 UsageStats 时写入今天。
    var firstUseDate: String by stringPref(KEY_FIRST_USE_DATE, "")

    fun getUsageStats(): UsageStats = UsageStatsStore.getUsageStats(this, json)

    fun resetUsageStats() = UsageStatsStore.resetUsageStats(this)

    fun recordUsageCommit(source: String, vendor: AsrVendor, audioMs: Long, chars: Int, procMs: Long = 0L) =
        UsageStatsStore.recordUsageCommit(this, json, source, vendor, audioMs, chars, procMs)

    fun getDaysSinceFirstUse(): Long = UsageStatsStore.getDaysSinceFirstUse(this)

    // ---- SyncClipboard 偏好项 ----
    var syncClipboardEnabled: Boolean
        get() = sp.getBoolean(KEY_SC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SC_ENABLED, value) }

    var syncClipboardServerBase: String
        get() = sp.getString(KEY_SC_SERVER_BASE, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_SERVER_BASE, value.trim()) }

    var syncClipboardUsername: String
        get() = sp.getString(KEY_SC_USERNAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_USERNAME, value.trim()) }

    var syncClipboardPassword: String
        get() = sp.getString(KEY_SC_PASSWORD, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_PASSWORD, value.trim()) }

    var syncClipboardAutoPullEnabled: Boolean
        get() = sp.getBoolean(KEY_SC_AUTO_PULL, false)
        set(value) = sp.edit { putBoolean(KEY_SC_AUTO_PULL, value) }

    var syncClipboardPullIntervalSec: Int
        get() = sp.getInt(KEY_SC_PULL_INTERVAL_SEC, 15).coerceIn(1, 600)
        set(value) = sp.edit { putInt(KEY_SC_PULL_INTERVAL_SEC, value.coerceIn(1, 600)) }

    // 仅用于变更检测（不上报/不导出）
    var syncClipboardLastUploadedHash: String
        get() = sp.getString(KEY_SC_LAST_UP_HASH, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_LAST_UP_HASH, value) }

    // 记录最近一次处理的云端剪贴板文件名（用于避免重复文件预览）
    var syncClipboardLastFileName: String
        get() = sp.getString(KEY_SC_LAST_FILE_NAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_SC_LAST_FILE_NAME, value) }

    // ---- 备份/同步（WebDAV）偏好项 ----
    var webdavUrl: String
        get() = sp.getString(KEY_WD_URL, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_URL, value.trim()) }

    var webdavUsername: String
        get() = sp.getString(KEY_WD_USERNAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_USERNAME, value.trim()) }

    var webdavPassword: String
        get() = sp.getString(KEY_WD_PASSWORD, "") ?: ""
        set(value) = sp.edit { putString(KEY_WD_PASSWORD, value.trim()) }

    // ---- APK 更新：待安装文件路径（授权后自动继续安装使用；不参与导出） ----
    var pendingApkPath: String
        get() = sp.getString(KEY_PENDING_APK_PATH, "") ?: ""
        set(value) = sp.edit { putString(KEY_PENDING_APK_PATH, value.trim()) }

    companion object {
        private const val TAG = "Prefs"

        // 输入/点击触觉反馈等级（兼容旧开关）
        const val HAPTIC_FEEDBACK_LEVEL_OFF = 0
        const val HAPTIC_FEEDBACK_LEVEL_SYSTEM = 1
        const val HAPTIC_FEEDBACK_LEVEL_WEAK = 2
        const val HAPTIC_FEEDBACK_LEVEL_LIGHT = 3
        const val HAPTIC_FEEDBACK_LEVEL_MEDIUM = 4
        const val HAPTIC_FEEDBACK_LEVEL_STRONG = 5
        const val HAPTIC_FEEDBACK_LEVEL_HEAVY = 6
        const val DEFAULT_HAPTIC_FEEDBACK_LEVEL = HAPTIC_FEEDBACK_LEVEL_SYSTEM
        // SharedPreferences keys 见 PrefsKeys.kt

        const val DEFAULT_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val SF_CHAT_COMPLETIONS_ENDPOINT = "https://api.siliconflow.cn/v1/chat/completions"
        const val DEFAULT_SF_MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val DEFAULT_SF_OMNI_MODEL = "Qwen/Qwen3-Omni-30B-A3B-Instruct"

        // SiliconFlow 免费服务模型配置
        const val DEFAULT_SF_FREE_ASR_MODEL = "FunAudioLLM/SenseVoiceSmall" // 免费 ASR 默认模型
        const val DEFAULT_SF_FREE_LLM_MODEL = "Qwen/Qwen3-8B" // 免费 LLM 默认模型

        // 免费 ASR 可选模型列表
        val SF_FREE_ASR_MODELS: List<String> = PrefsOptionLists.SF_FREE_ASR_MODELS

        // 免费 LLM 可选模型列表
        val SF_FREE_LLM_MODELS: List<String> = PrefsOptionLists.SF_FREE_LLM_MODELS

        // OpenAI Audio Transcriptions 默认值
        const val DEFAULT_OA_ASR_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_OA_ASR_MODEL = "gpt-4o-mini-transcribe"

        // DashScope 默认
        const val DEFAULT_DASH_MODEL = "qwen3-asr-flash"
        const val DASH_MODEL_QWEN3_REALTIME = "qwen3-asr-flash-realtime-2026-02-10"
        const val DASH_MODEL_FUN_ASR_REALTIME = "fun-asr-realtime"

        // Gemini 默认
        const val DEFAULT_GEM_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_GEM_MODEL = "gemini-2.5-flash"

        // Zhipu GLM ASR 默认
        const val DEFAULT_ZHIPU_TEMPERATURE = 0.95f

        // 合理的OpenAI格式默认值
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_LLM_TEMPERATURE = 1.0f
        const val DEFAULT_CUSTOM_REASONING_PARAMS_ON_JSON = "{}"
        const val DEFAULT_CUSTOM_REASONING_PARAMS_OFF_JSON = "{}"

        // 静音自动判停默认值
        const val DEFAULT_SILENCE_WINDOW_MS = 1200
        const val DEFAULT_SILENCE_SENSITIVITY = 4 // 1-10

        // 标点按钮默认值
        const val DEFAULT_PUNCT_1 = "，"
        const val DEFAULT_PUNCT_2 = "。"
        const val DEFAULT_PUNCT_3 = "！"
        const val DEFAULT_PUNCT_4 = "？"

        // 悬浮球默认大小（dp）
        const val DEFAULT_FLOATING_BALL_SIZE_DP = 44

        // 悬浮写入兼容：默认目标包名（每行一个；支持前缀匹配）
        const val DEFAULT_FLOATING_WRITE_COMPAT_PACKAGES = "org.telegram.messenger\nnu.gpu.nagram"

        // Soniox 默认端点
        const val SONIOX_API_BASE_URL = "https://api.soniox.com"
        const val SONIOX_FILES_ENDPOINT = "$SONIOX_API_BASE_URL/v1/files"
        const val SONIOX_TRANSCRIPTIONS_ENDPOINT = "$SONIOX_API_BASE_URL/v1/transcriptions"
        const val SONIOX_WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
    }

    // 导出全部设置为 JSON 字符串（包含密钥，仅用于本地备份/迁移）
    fun exportJsonString(): String = PrefsBackup.exportJsonString(this)

    // 从 JSON 字符串导入。仅覆盖提供的键；解析失败返回 false。
    fun importJsonString(json: String): Boolean {
        val ok = PrefsBackup.importJsonString(this, json)
        if (!ok) return false
        PrefsInitTasks.migrateFunAsrFromSenseVoiceIfNeeded(sp)
        Log.i(TAG, "Successfully imported settings from JSON")
        return true
    }
}
