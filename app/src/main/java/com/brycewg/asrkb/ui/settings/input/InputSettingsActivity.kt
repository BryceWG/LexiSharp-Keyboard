/**
 * 输入相关设置页面。
 *
 * 归属模块：ui/settings/input
 */
package com.brycewg.asrkb.ui.settings.input

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.ui.installExplainedSwitch
import com.brycewg.asrkb.ui.settings.search.SettingsSearchNavigator
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class InputSettingsActivity : BaseActivity() {

    companion object {
        private const val TAG = "InputSettingsActivity"
        private const val REQ_BT_CONNECT = 2001
    }

    private lateinit var prefs: Prefs

    // 标志位：防止程序内部修改开关状态时触发监听器递归
    private var isUpdatingSwitchTrimTrailingPunct = false
    private var isUpdatingSwitchMicTapToggle = false
    private var isUpdatingSwitchAutoStartRecordingOnShow = false
    private var isUpdatingSwitchFcitx5ReturnOnSwitcher = false
    private var isUpdatingSwitchReturnPrevImeOnHide = false
    private var isUpdatingSwitchHideRecentTasks = false
    private var isUpdatingSwitchDuckMediaOnRecord = false
    private var isUpdatingSwitchOfflineDenoise = false
    private var isUpdatingSwitchHeadsetMicPriority = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)

        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)
        val switchAutoStartRecordingOnShow = findViewById<MaterialSwitch>(R.id.switchAutoStartRecordingOnShow)
        val switchFcitx5ReturnOnSwitcher = findViewById<MaterialSwitch>(R.id.switchFcitx5ReturnOnSwitcher)
        val switchReturnPrevImeOnHide = findViewById<MaterialSwitch>(R.id.switchReturnPrevImeOnHide)
        val switchHideRecentTasks = findViewById<MaterialSwitch>(R.id.switchHideRecentTasks)
        val switchDuckMediaOnRecord = findViewById<MaterialSwitch>(R.id.switchDuckMediaOnRecord)
        val switchOfflineDenoise = findViewById<MaterialSwitch>(R.id.switchOfflineDenoise)
        val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
        val switchExternalImeAidl = findViewById<MaterialSwitch>(R.id.switchExternalImeAidl)
        val toggleKeyboardHeight = findViewById<MaterialButtonToggleGroup>(R.id.toggleKeyboardHeight)
        val sliderHapticFeedbackStrength = findViewById<com.google.android.material.slider.Slider>(R.id.sliderHapticFeedbackStrength)
        val tvHapticFeedbackStrengthValue = findViewById<TextView>(R.id.tvHapticFeedbackStrengthValue)
        val tvLanguage = findViewById<TextView>(R.id.tvLanguageValue)
        val tvImeSwitchTarget = findViewById<TextView>(R.id.tvImeSwitchTargetValue)
        val sliderBottomPadding = findViewById<com.google.android.material.slider.Slider>(R.id.sliderBottomPadding)
        val tvBottomPaddingValue = findViewById<TextView>(R.id.tvBottomPaddingValue)
        val sliderWaveformSensitivity = findViewById<com.google.android.material.slider.Slider>(R.id.sliderWaveformSensitivity)
        val tvWaveformSensitivityValue = findViewById<TextView>(R.id.tvWaveformSensitivityValue)
        val tvExtensionButtons = findViewById<TextView>(R.id.tvExtensionButtonsValue)

        fun applyPrefsToUi() {
            isUpdatingSwitchTrimTrailingPunct = true
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            isUpdatingSwitchTrimTrailingPunct = false

            isUpdatingSwitchMicTapToggle = true
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            isUpdatingSwitchMicTapToggle = false

            isUpdatingSwitchFcitx5ReturnOnSwitcher = true
            switchFcitx5ReturnOnSwitcher.isChecked = prefs.fcitx5ReturnOnImeSwitch
            isUpdatingSwitchFcitx5ReturnOnSwitcher = false

            isUpdatingSwitchReturnPrevImeOnHide = true
            switchReturnPrevImeOnHide.isChecked = prefs.returnPrevImeOnHide
            isUpdatingSwitchReturnPrevImeOnHide = false

            isUpdatingSwitchHideRecentTasks = true
            switchHideRecentTasks.isChecked = prefs.hideRecentTaskCard
            isUpdatingSwitchHideRecentTasks = false

            isUpdatingSwitchDuckMediaOnRecord = true
            switchDuckMediaOnRecord.isChecked = prefs.duckMediaOnRecordEnabled
            isUpdatingSwitchDuckMediaOnRecord = false

            isUpdatingSwitchOfflineDenoise = true
            switchOfflineDenoise.isChecked = prefs.offlineDenoiseEnabled
            isUpdatingSwitchOfflineDenoise = false

            isUpdatingSwitchHeadsetMicPriority = true
            switchHeadsetMicPriority.isChecked = prefs.headsetMicPriorityEnabled
            isUpdatingSwitchHeadsetMicPriority = false

            switchExternalImeAidl.isChecked = prefs.externalAidlEnabled

            isUpdatingSwitchAutoStartRecordingOnShow = true
            switchAutoStartRecordingOnShow.isChecked = prefs.autoStartRecordingOnShow
            isUpdatingSwitchAutoStartRecordingOnShow = false
        }
        applyPrefsToUi()

        // 键盘高度：三档（分段按钮）
        setupKeyboardHeightToggle(prefs, toggleKeyboardHeight)

        // 触觉反馈强度
        setupHapticFeedbackStrengthSlider(prefs, sliderHapticFeedbackStrength, tvHapticFeedbackStrengthValue)

        // 底部间距调节
        setupBottomPaddingSlider(prefs, sliderBottomPadding, tvBottomPaddingValue)

        // 波形灵敏度调节
        setupWaveformSensitivitySlider(prefs, sliderWaveformSensitivity, tvWaveformSensitivityValue)

        // 切换目标输入法（点击弹出单选对话框）
        setupImeSwitchTargetSelection(prefs, tvImeSwitchTarget)

        // 应用语言选择（点击弹出单选对话框）
        setupLanguageSelection(prefs, tvLanguage)

        // 扩展按钮配置（点击弹出多选对话框）
        setupExtensionButtonsSelection(prefs, tvExtensionButtons)

        // 监听与保存（统一通过触摸拦截 + 弹窗确认）
        switchTrimTrailingPunct.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_trim_trailing_punct,
            offDescRes = R.string.feature_trim_trailing_punct_off_desc,
            onDescRes = R.string.feature_trim_trailing_punct_on_desc,
            preferenceKey = "trim_trailing_punct_explained",
            readPref = { prefs.trimFinalTrailingPunct },
            writePref = { v -> prefs.trimFinalTrailingPunct = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchExternalImeAidl.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.externalAidlEnabled = isChecked

            // 当开启时显示使用指引（每次开启都提醒）
            if (isChecked) {
                showExternalAidlGuide()
            }
        }
        switchMicTapToggle.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_mic_tap_toggle,
            offDescRes = R.string.feature_mic_tap_toggle_off_desc,
            onDescRes = R.string.feature_mic_tap_toggle_on_desc,
            preferenceKey = "mic_tap_toggle_explained",
            readPref = { prefs.micTapToggleEnabled },
            writePref = { v -> prefs.micTapToggleEnabled = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchAutoStartRecordingOnShow.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_auto_start_recording_on_show,
            offDescRes = R.string.feature_auto_start_recording_on_show_off_desc,
            onDescRes = R.string.feature_auto_start_recording_on_show_on_desc,
            preferenceKey = "auto_start_recording_on_show_explained",
            readPref = { prefs.autoStartRecordingOnShow },
            writePref = { v -> prefs.autoStartRecordingOnShow = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchReturnPrevImeOnHide.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_return_prev_ime_on_hide,
            offDescRes = R.string.feature_return_prev_ime_on_hide_off_desc,
            onDescRes = R.string.feature_return_prev_ime_on_hide_on_desc,
            preferenceKey = "return_prev_ime_on_hide_explained",
            readPref = { prefs.returnPrevImeOnHide },
            writePref = { v -> prefs.returnPrevImeOnHide = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchFcitx5ReturnOnSwitcher.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_fcitx5_return_on_switcher,
            offDescRes = R.string.feature_fcitx5_return_on_switcher_off_desc,
            onDescRes = R.string.feature_fcitx5_return_on_switcher_on_desc,
            preferenceKey = "fcitx5_return_on_switcher_explained",
            readPref = { prefs.fcitx5ReturnOnImeSwitch },
            writePref = { v -> prefs.fcitx5ReturnOnImeSwitch = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchHideRecentTasks.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_hide_recent_task_card,
            offDescRes = R.string.feature_hide_recent_tasks_off_desc,
            onDescRes = R.string.feature_hide_recent_tasks_on_desc,
            preferenceKey = "hide_recent_tasks_explained",
            readPref = { prefs.hideRecentTaskCard },
            writePref = { v -> prefs.hideRecentTaskCard = v },
            onChanged = { enabled -> applyExcludeFromRecents(enabled) },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchDuckMediaOnRecord.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_audio_ducking_on_record,
            offDescRes = R.string.feature_duck_media_on_record_off_desc,
            onDescRes = R.string.feature_duck_media_on_record_on_desc,
            preferenceKey = "duck_media_on_record_explained",
            readPref = { prefs.duckMediaOnRecordEnabled },
            writePref = { v -> prefs.duckMediaOnRecordEnabled = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchOfflineDenoise.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_offline_denoise,
            offDescRes = R.string.feature_offline_denoise_off_desc,
            onDescRes = R.string.feature_offline_denoise_on_desc,
            preferenceKey = "offline_denoise_explained",
            readPref = { prefs.offlineDenoiseEnabled },
            writePref = { v -> prefs.offlineDenoiseEnabled = v },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )
        switchHeadsetMicPriority.installExplainedSwitch(
            context = this,
            titleRes = R.string.label_headset_mic_priority,
            offDescRes = R.string.feature_headset_mic_priority_off_desc,
            onDescRes = R.string.feature_headset_mic_priority_on_desc,
            preferenceKey = "headset_mic_priority_explained",
            readPref = { prefs.headsetMicPriorityEnabled },
            writePref = { v -> prefs.headsetMicPriorityEnabled = v },
            preCheck = { target ->
                if (target && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT_CONNECT)
                        false
                    } else {
                        true
                    }
                } else {
                    true
                }
            },
            onChanged = { enabled ->
                if (!enabled) {
                    com.brycewg.asrkb.asr.BluetoothRouteManager.onRecordingStopped(this)
                    com.brycewg.asrkb.asr.BluetoothRouteManager.setImeActive(this, false)
                }
            },
            hapticFeedback = { hapticTapIfEnabled(it) },
        )

        // 初始应用一次"从最近任务中排除"设置
        applyExcludeFromRecents(prefs.hideRecentTaskCard)
    }

    override fun onPostResume() {
        super.onPostResume()
        SettingsSearchNavigator.applyScrollAndHighlightIfNeeded(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            val switchHeadsetMicPriority = findViewById<MaterialSwitch>(R.id.switchHeadsetMicPriority)
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.headsetMicPriorityEnabled = true
                isUpdatingSwitchHeadsetMicPriority = true
                switchHeadsetMicPriority.isChecked = true
                isUpdatingSwitchHeadsetMicPriority = false
            } else {
                prefs.headsetMicPriorityEnabled = false
                isUpdatingSwitchHeadsetMicPriority = true
                switchHeadsetMicPriority.isChecked = false
                isUpdatingSwitchHeadsetMicPriority = false
                Toast.makeText(this, getString(R.string.toast_bt_connect_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 设置键盘高度分段按钮
     */
    private fun setupKeyboardHeightToggle(prefs: Prefs, toggleGroup: MaterialButtonToggleGroup) {
        // 按钮 ID 与 tier 值的映射：small=1, medium=2, large=3
        val buttonIds = listOf(R.id.btnHeightSmall, R.id.btnHeightMedium, R.id.btnHeightLarge)

        // 初始化选中状态
        val currentTier = prefs.keyboardHeightTier.coerceIn(1, 3)
        toggleGroup.check(buttonIds[currentTier - 1])

        // 监听选择变化
        toggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                hapticTapIfEnabled(group)
                val newTier = when (checkedId) {
                    R.id.btnHeightSmall -> 1
                    R.id.btnHeightMedium -> 2
                    R.id.btnHeightLarge -> 3
                    else -> 2
                }
                if (prefs.keyboardHeightTier != newTier) {
                    prefs.keyboardHeightTier = newTier
                    sendRefreshBroadcast()
                }
            }
        }
    }

    /**
     * 设置触觉反馈强度滑块
     */
    private fun setupHapticFeedbackStrengthSlider(
        prefs: Prefs,
        slider: com.google.android.material.slider.Slider,
        tvValue: TextView,
    ) {
        var lastLevel = prefs.hapticFeedbackLevel
        slider.value = lastLevel.toFloat()
        tvValue.text = getHapticFeedbackStrengthLabel(lastLevel)

        slider.addOnChangeListener { _, value, fromUser ->
            val newLevel = value.toInt().coerceIn(
                Prefs.HAPTIC_FEEDBACK_LEVEL_OFF,
                Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY,
            )
            if (prefs.hapticFeedbackLevel != newLevel) {
                prefs.hapticFeedbackLevel = newLevel
            }
            if (lastLevel != newLevel || !fromUser) {
                lastLevel = newLevel
                tvValue.text = getHapticFeedbackStrengthLabel(newLevel)
            }
            if (fromUser) {
                HapticFeedbackHelper.performTap(this, prefs, slider)
            }
        }
    }

    private fun getHapticFeedbackStrengthLabel(level: Int): String {
        val resId = when (level) {
            Prefs.HAPTIC_FEEDBACK_LEVEL_OFF -> R.string.haptic_strength_off
            Prefs.HAPTIC_FEEDBACK_LEVEL_SYSTEM -> R.string.haptic_strength_system
            Prefs.HAPTIC_FEEDBACK_LEVEL_WEAK -> R.string.haptic_strength_weak
            Prefs.HAPTIC_FEEDBACK_LEVEL_LIGHT -> R.string.haptic_strength_light
            Prefs.HAPTIC_FEEDBACK_LEVEL_MEDIUM -> R.string.haptic_strength_medium
            Prefs.HAPTIC_FEEDBACK_LEVEL_STRONG -> R.string.haptic_strength_strong
            Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY -> R.string.haptic_strength_heavy
            else -> R.string.haptic_strength_system
        }
        return getString(resId)
    }

    /**
     * 设置底部间距调节滑动条
     */
    private fun setupBottomPaddingSlider(
        prefs: Prefs,
        slider: com.google.android.material.slider.Slider,
        tvValue: TextView,
    ) {
        // 初始化滑动条值
        slider.value = prefs.keyboardBottomPaddingDp.toFloat()
        tvValue.text = getString(R.string.keyboard_bottom_padding_value, prefs.keyboardBottomPaddingDp)

        // 监听滑动条变化
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val dp = value.toInt()
                prefs.keyboardBottomPaddingDp = dp
                tvValue.text = getString(R.string.keyboard_bottom_padding_value, dp)
                sendRefreshBroadcast()
            }
        }
    }

    /**
     * 设置波形灵敏度滑杆
     */
    private fun setupWaveformSensitivitySlider(
        prefs: Prefs,
        slider: com.google.android.material.slider.Slider,
        tvValue: TextView,
    ) {
        // 初始化滑动条值
        slider.value = prefs.waveformSensitivity.toFloat()
        tvValue.text = prefs.waveformSensitivity.toString()

        // 监听滑动条变化
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val sens = value.toInt()
                prefs.waveformSensitivity = sens
                tvValue.text = sens.toString()
                sendRefreshBroadcast()
            }
        }
    }

    /**
     * 设置应用语言选择对话框
     */
    private fun setupLanguageSelection(prefs: Prefs, tvLanguage: TextView) {
        val options = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_zh_tw),
            getString(R.string.lang_ja),
            getString(R.string.lang_en),
        )

        fun getLanguageIndex(tag: String): Int = when (tag) {
            "zh", "zh-CN", "zh-Hans" -> 1
            "zh-TW", "zh-Hant" -> 2
            "ja" -> 3
            "en" -> 4
            else -> 0
        }

        fun updateSummary() {
            tvLanguage.text = options[getLanguageIndex(prefs.appLanguageTag)]
        }
        updateSummary()

        tvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val currentIndex = getLanguageIndex(prefs.appLanguageTag)
            showSingleChoiceDialog(
                titleRes = R.string.label_language,
                items = options,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val newTag = when (selectedIndex) {
                        1 -> "zh-CN"
                        2 -> "zh-TW"
                        3 -> "ja"
                        4 -> "en"
                        else -> ""
                    }
                    if (newTag != prefs.appLanguageTag) {
                        prefs.appLanguageTag = newTag
                        updateSummary()
                        val locales = if (newTag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(newTag)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                },
            )
        }
    }

    /**
     * 设置切换目标输入法选择对话框
     */
    private fun setupImeSwitchTargetSelection(prefs: Prefs, tvTarget: TextView) {
        data class ImeOption(val id: String, val label: String)

        fun buildOptions(): List<ImeOption> {
            val imm = getSystemService(InputMethodManager::class.java)
            val enabledImes = imm?.enabledInputMethodList ?: emptyList()
            val options = mutableListOf(
                ImeOption("", getString(R.string.ime_switch_target_previous)),
            )
            enabledImes
                .filter { it.packageName != packageName }
                .forEach { info ->
                    val label = info.loadLabel(packageManager)?.toString()?.trim()
                    val display = if (!label.isNullOrBlank()) label else info.id
                    options.add(ImeOption(info.id, display))
                }
            return options
        }

        fun updateSummary(options: List<ImeOption>) {
            val currentId = prefs.imeSwitchTargetId
            val label = options.firstOrNull { it.id == currentId }?.label
                ?: options.first().label
            tvTarget.text = label
        }

        val initialOptions = buildOptions()
        updateSummary(initialOptions)

        tvTarget.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val options = buildOptions()
            val items = options.map { it.label }
            val currentIndex = options.indexOfFirst { it.id == prefs.imeSwitchTargetId }
                .takeIf { it >= 0 } ?: 0
            showSingleChoiceDialog(
                titleRes = R.string.label_ime_switch_target,
                items = items,
                currentIndex = currentIndex,
                onSelected = { selectedIndex ->
                    val selectedId = options.getOrNull(selectedIndex)?.id ?: ""
                    if (prefs.imeSwitchTargetId != selectedId) {
                        prefs.imeSwitchTargetId = selectedId
                    }
                    updateSummary(options)
                },
            )
        }
    }

    /**
     * 通用单选对话框显示函数
     *
     * @param titleRes 对话框标题资源 ID
     * @param items 选项列表
     * @param currentIndex 当前选中的索引
     * @param onSelected 选中回调，参数为选中的索引
     */
    private fun showSingleChoiceDialog(
        titleRes: Int,
        items: List<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        SettingsOptionSheet.showSingleChoice(
            context = this,
            titleResId = titleRes,
            items = items,
            selectedIndex = currentIndex,
            onSelected = onSelected,
        )
    }

    /**
     * 发送刷新 IME UI 的广播
     */
    private fun sendRefreshBroadcast() {
        sendBroadcast(
            Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).apply {
                setPackage(packageName)
            },
        )
    }

    /**
     * 根据设置执行触觉反馈
     */
    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    /**
     * 应用"从最近任务中排除"设置
     */
    private fun applyExcludeFromRecents(enabled: Boolean) {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
    }

    /**
     * 设置扩展按钮配置对话框
     */
    private fun setupExtensionButtonsSelection(prefs: Prefs, tvExtensionButtons: TextView) {
        // 获取所有可用的按钮动作（排除 NONE 与 NUMPAD：数字/符号键盘改为固定入口）
        val allActions = com.brycewg.asrkb.ime.ExtensionButtonAction.values()
            .filter {
                it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE &&
                    it != com.brycewg.asrkb.ime.ExtensionButtonAction.NUMPAD &&
                    it != com.brycewg.asrkb.ime.ExtensionButtonAction.CLIPBOARD
            }

        fun updateSummary() {
            val current = listOf(prefs.extBtn1, prefs.extBtn2, prefs.extBtn3, prefs.extBtn4)
                .filter { it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
            if (current.isEmpty()) {
                tvExtensionButtons.text = getString(R.string.ext_btn_none_selected)
            } else {
                val names = current.map { getString(it.titleResId) }
                tvExtensionButtons.text = names.joinToString(", ")
            }
        }
        updateSummary()

        tvExtensionButtons.setOnClickListener { v ->
            hapticTapIfEnabled(v)

            // 当前配置
            val current = listOf(prefs.extBtn1, prefs.extBtn2, prefs.extBtn3, prefs.extBtn4)
                .filter { it != com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }

            // 构建选项列表
            val items = allActions.map { getString(it.titleResId) }.toTypedArray()
            val checked = BooleanArray(allActions.size) { idx ->
                current.contains(allActions[idx])
            }
            // 根据当前配置初始化“选中顺序”（保持 extBtn1~4 的顺序）
            val selectedOrder = mutableListOf<Int>()
            current.forEach { action ->
                val idx = allActions.indexOf(action)
                if (idx >= 0 && !selectedOrder.contains(idx)) {
                    selectedOrder.add(idx)
                }
            }

            fun updateListVisual(listView: android.widget.ListView?) {
                val lv = listView ?: return
                val selectedCount = checked.count { it }
                for (i in 0 until lv.childCount) {
                    val position = lv.firstVisiblePosition + i
                    if (position >= 0 && position < checked.size) {
                        val itemView = lv.getChildAt(i)
                        val shouldDisable = (selectedCount >= 4 && !checked[position])
                        if (shouldDisable) {
                            // 已选4个且当前项未选中：变灰并禁用
                            itemView?.alpha = 0.5f
                            itemView?.isEnabled = false
                        } else {
                            // 恢复正常
                            itemView?.alpha = 1.0f
                            itemView?.isEnabled = true
                        }

                        // 为已选按钮显示顺序编号（1~4）
                        val orderIndex = selectedOrder.indexOf(position)
                        val baseLabel = items[position]
                        val textView = itemView?.findViewById<android.widget.CheckedTextView>(android.R.id.text1)
                        if (textView != null) {
                            if (orderIndex >= 0) {
                                textView.text = "${orderIndex + 1}. $baseLabel"
                            } else {
                                textView.text = baseLabel
                            }
                        }
                    }
                }
            }

            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ext_btn_must_select_4)
                .setMultiChoiceItems(items, checked) { dialog, which, isChecked ->
                    val alertDialog = dialog as? androidx.appcompat.app.AlertDialog
                    val listView = alertDialog?.listView

                    // 先模拟这次操作的结果
                    val tempChecked = checked.copyOf()
                    tempChecked[which] = isChecked
                    val wouldBeSelected = tempChecked.count { it }

                    // 如果操作后选中数量超过4个，拒绝操作
                    if (wouldBeSelected > 4) {
                        listView?.setItemChecked(which, false)
                        Toast.makeText(this, R.string.ext_btn_max_4, Toast.LENGTH_SHORT).show()
                        return@setMultiChoiceItems
                    }

                    // 操作合法，更新 checked 数组
                    checked[which] = isChecked
                    // 按点击顺序维护已选动作顺序
                    if (isChecked) {
                        if (!selectedOrder.contains(which)) {
                            selectedOrder.add(which)
                        }
                    } else {
                        selectedOrder.remove(which)
                    }
                    val selectedCount = checked.count { it }

                    // 更新确定按钮的启用状态
                    alertDialog?.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = (selectedCount == 4)

                    // 更新列表项的视觉状态和禁用状态 + 顺序编号
                    listView?.post {
                        updateListVisual(listView)
                    }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    // 收集选中的按钮动作
                    val selected = selectedOrder.mapNotNull { idx -> allActions.getOrNull(idx) }

                    // 填充到4个位置
                    prefs.extBtn1 = selected.getOrElse(0) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn2 = selected.getOrElse(1) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn3 = selected.getOrElse(2) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }
                    prefs.extBtn4 = selected.getOrElse(3) { com.brycewg.asrkb.ime.ExtensionButtonAction.NONE }

                    updateSummary()
                    sendRefreshBroadcast()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()

            dialog.show()

            // 初始根据当前选择数量设置确定按钮的启用状态
            val initialSelectedCount = checked.count { it }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = (initialSelectedCount == 4)

            // 初始化列表项的视觉状态、禁用状态和顺序编号
            dialog.listView?.post {
                updateListVisual(dialog.listView)
            }

            // 滚动时也刷新一次文本（保证编号正确）
            dialog.listView?.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                    updateListVisual(dialog.listView)
                }

                override fun onScroll(
                    view: android.widget.AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int,
                ) {
                    updateListVisual(dialog.listView)
                }
            })
        }
    }

    /**
     * 显示外部输入法联动使用指引弹窗
     * 按钮布局：打开Release页(左) - 关闭(右)
     */
    private fun showExternalAidlGuide() {
        val message = getString(R.string.external_aidl_guide_message)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_aidl_guide_title)
            .setMessage(message)
            .setNegativeButton(R.string.external_aidl_guide_btn_open_release) { _, _ ->
                showExternalAidlReleaseChooser()
            }
            .setPositiveButton(R.string.external_aidl_guide_btn_close, null)
            .show()
    }

    private fun showExternalAidlReleaseChooser() {
        val items =
            arrayOf(
                getString(R.string.external_aidl_guide_release_fcitx),
                getString(R.string.external_aidl_guide_release_trime),
            )
        val urls =
            arrayOf(
                "https://github.com/BryceWG/fcitx5-android-lexi-keyboard/releases",
                "https://github.com/BryceWG/trime-bibi-keyboard/releases",
            )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_aidl_guide_choose_release_title)
            .setItems(items) { _, which ->
                val url = urls.getOrNull(which) ?: return@setItems
                val intent = Intent(Intent.ACTION_VIEW).apply { data = android.net.Uri.parse(url) }
                startActivity(intent)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
