package com.brycewg.asrkb.ime

import android.Manifest
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Vibrator
import android.view.LayoutInflater
import android.graphics.Color
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import android.view.KeyEvent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.graphics.Canvas
import android.graphics.Paint
import android.view.inputmethod.EditorInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.OpenAiFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PinyinMode
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.AudioWaveView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.google.android.material.color.MaterialColors
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.annotation.StringRes
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private lateinit var prefs: Prefs
    private var rootView: View? = null
    private var asrPanelView: View? = null
    private var qwertyPanelView: View? = null
    private var symbolsPanelView: View? = null
    private var qwertyLettersPanelView: View? = null
    private var isQwertyVisible: Boolean = false
    private enum class ShiftMode { Off, Once, Lock }
    private var shiftMode: ShiftMode = ShiftMode.Off
    private enum class LangMode { English, Chinese }
    private var langMode: LangMode = LangMode.English
    private var qwertyLetterKeys: MutableList<TextView> = mutableListOf()
    private var lastShiftTapTime: Long = 0L

    // Qwerty toolbar views
    private var qwertyHideTop: ImageButton? = null
    private var qwertyTextBuffer: TextView? = null
    private var qwertyTextBufferConv: TextView? = null
    private var qwertyRawScroll: HorizontalScrollView? = null
    private var qwertyConvScroll: HorizontalScrollView? = null
    private var qwertyPinyin: TextView? = null
    private var qwertyLangSwitch: TextView? = null
    // 拼音缓冲（中文模式）
    private val pinyinBuffer = StringBuilder()
    private var pinyinBufferSnapshot: String? = null
    // 拼音光标（位于 [0, length] 间）
    private var pinyinCursor: Int = 0
    private var isSyncScrolling: Boolean = false
    // 中文 26 键：拼音自动 LLM 转换与候选
    private var pinyinAutoJob: kotlinx.coroutines.Job? = null
    private var pinyinAutoLastInput: String = ""
    private var pinyinAutoRunning: Boolean = false
    private var pendingPinyinSuggestion: String? = null
    // 防抖与防过期：为每次 LLM 预览请求打序号；当拼音变更/清空/停止预览时递增，晚到结果将被忽略
    private var pinyinAutoSeq: Long = 0L

    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnAiEdit: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnPromptPicker: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
    private var btnLetters: TextView? = null
    private var btnPunct1: TextView? = null
    private var btnPunct2: TextView? = null
    private var btnPunct3: TextView? = null
    private var btnPunct4: TextView? = null
    private var btnPunct5: TextView? = null
    private var txtStatus: TextView? = null
    private var audioWaveView: AudioWaveView? = null
    // Qwerty/Symbol 空格键引用（用于覆盖显示 ASR 状态）
    private var qwertySpaceView: TextView? = null
    private var symSpaceView: TextView? = null
    private var spaceStatusActive: Boolean = false
    private var spaceStatusClearRunnable: Runnable? = null
    private var committedStableLen: Int = 0
    private var postproc: LlmPostProcessor = LlmPostProcessor()
    private var micLongPressStarted: Boolean = false
    private var micLongPressPending: Boolean = false
    private var micLongPressRunnable: Runnable? = null

    // Qwerty space gestures
    private var spaceLongPressStarted: Boolean = false
    private var spaceLongPressPending: Boolean = false
    private var spaceLongPressRunnable: Runnable? = null
    private var spaceStartX: Float = 0f
    private var spaceStartY: Float = 0f
    private var spaceSwipedUp: Boolean = false

    // Backspace gesture state
    private var backspaceStartX: Float = 0f
    private var backspaceStartY: Float = 0f
    private var backspaceClearedInGesture: Boolean = false
    private var backspaceSnapshotBefore: CharSequence? = null
    private var backspaceSnapshotAfter: CharSequence? = null
    private var backspaceSnapshotValid: Boolean = false
    private var backspacePressed: Boolean = false
    private var backspaceLongPressStarted: Boolean = false
    private var backspaceLongPressStarter: Runnable? = null
    private var backspaceRepeatRunnable: Runnable? = null
    // 是否在本次手势中清空了整个编辑框（用于下滑撤销时区分恢复范围）
    private var backspaceClearedFieldInGesture: Boolean = false

    // Track latest AI post-processed commit to allow swipe-down revert to raw
    private data class PostprocCommit(val processed: String, val raw: String)
    private var lastPostprocCommit: PostprocCommit? = null

    // Track last committed ASR result so AI Edit (no selection) can modify it
    private var lastAsrCommitText: String? = null
    // 最近一次 ASR API 请求耗时（毫秒）
    private var lastRequestDurationMs: Long? = null
    // 跟踪上次应用的应用内语言，确保 IME 面板使用最新本地化资源
    private var lastAppliedLanguageTag: String = ""

    private enum class SessionKind { AiEdit }
    private data class AiEditState(
        val targetIsSelection: Boolean,
        val beforeLen: Int,
        val afterLen: Int
    )
    private var currentSessionKind: SessionKind? = null
    private var aiEditState: AiEditState? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
        // 记录当前语言，便于后续检测变化
        lastAppliedLanguageTag = try { prefs.appLanguageTag } catch (_: Throwable) { "" }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 应用并同步最新的应用内语言选择（若用户刚在设置里切换）
        applyAppLanguageIfNeeded()
        // If current field is a password and user opted in, auto-switch back to previous IME
        if (prefs.autoSwitchOnPassword && isPasswordEditor(info)) {
            try {
                val ok = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!ok) {
                    val imm = getSystemService(InputMethodManager::class.java)
                    imm?.showInputMethodPicker()
                }
            } catch (_: Throwable) { }
            try { requestHideSelf(0) } catch (_: Throwable) { }
            return
        }
        // 始终显示输入法切换按钮
        btnImeSwitcher?.visibility = View.VISIBLE
        // Refresh custom punctuation labels
        applyPunctuationLabels()
        refreshPermissionUi()
        // 进入时重置状态文本，确保使用最新语言
        if (asrEngine?.isRunning != true) {
            updateUiIdle()
        }
        // Keep system toolbar/nav colors in sync with our panel background
        syncSystemBarsToKeyboardBackground(rootView)
    }

    private fun applyAppLanguageIfNeeded() {
        val tag = try { prefs.appLanguageTag } catch (_: Throwable) { "" }
        if (tag == lastAppliedLanguageTag) return
        lastAppliedLanguageTag = tag
        try {
            val locales = if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (_: Throwable) { }
    }

    // 基于应用内语言构建一个带本地化配置的 Context（Service 不受 AppCompat 自动本地化影响）
    private fun buildLocaleAppliedContext(base: Context): Context {
        val tag = try { prefs.appLanguageTag } catch (_: Throwable) { "" }
        if (tag.isBlank()) return base
        return try {
            val cur = base.resources.configuration
            val cfg = Configuration(cur)
            val list = LocaleList.forLanguageTags(tag)
            cfg.setLocales(list)
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) {
            base
        }
    }

    // 使用当前键盘视图上下文（已带语言配置）获取字符串
    private fun s(@StringRes id: Int, vararg args: Any): String {
        // 始终基于当前偏好构建最新的语言上下文，避免 rootView 的旧上下文滞后
        val ctx = buildLocaleAppliedContext(this)
        return try { ctx.getString(id, *args) } catch (_: Throwable) { getString(id, *args) }
    }

    private fun isPasswordEditor(info: EditorInfo?): Boolean {
        if (info == null) return false
        val it = info.inputType
        val klass = it and InputType.TYPE_MASK_CLASS
        val variation = it and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrEngine?.stop()
        stopPinyinAutoSuggest(true)
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // 为 IME 构建带应用内语言的上下文，再施加主题与动态色
        val localeCtx = buildLocaleAppliedContext(this)
        val themedContext = android.view.ContextThemeWrapper(localeCtx, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val container = FrameLayout(dynamicContext)
        // Inflate ASR 面板
        val asr = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, container, false)
        // Inflate 26 键（内部包含字母与符号两个主区域）
        val qwerty = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_qwerty_view, container, false)
        qwerty.visibility = View.GONE
        container.addView(asr)
        container.addView(qwerty)
        rootView = container
        asrPanelView = asr
        qwertyPanelView = qwerty
        // 在 qwerty 内部找到主区域的两个子面板
        qwertyLettersPanelView = qwerty.findViewById(R.id.qwertyLettersPanel)
        symbolsPanelView = qwerty.findViewById(R.id.qwertySymbolsPanel)

        // Bind ASR panel views
        btnMic = asr.findViewById(R.id.btnMic)
        btnSettings = asr.findViewById(R.id.btnSettings)
        btnEnter = asr.findViewById(R.id.btnEnter)
        btnPostproc = asr.findViewById(R.id.btnPostproc)
        btnAiEdit = asr.findViewById(R.id.btnAiEdit)
        btnBackspace = asr.findViewById(R.id.btnBackspace)
        btnPromptPicker = asr.findViewById(R.id.btnPromptPicker)
        btnHide = asr.findViewById(R.id.btnHide)
        btnImeSwitcher = asr.findViewById(R.id.btnImeSwitcher)
        btnPunct1 = asr.findViewById(R.id.btnPunct1)
        btnPunct2 = asr.findViewById(R.id.btnPunct2)
        btnPunct3 = asr.findViewById(R.id.btnPunct3)
        btnPunct4 = asr.findViewById(R.id.btnPunct4)
        btnPunct5 = asr.findViewById(R.id.btnPunct5)
        txtStatus = asr.findViewById(R.id.txtStatus)
        audioWaveView = asr.findViewById(R.id.audioWaveView)
        btnLetters = asr.findViewById(R.id.btnLetters)

        btnMic?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Crisp haptic on press (respects system settings)
                    if (prefs.micHapticEnabled) {
                        try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
                    }
                    if (!hasRecordAudioPermission()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // Require configured keys before starting ASR
                    if (!prefs.hasAsrKeys()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // Ensure engine type matches current post-processing mode
                    asrEngine = ensureEngineMatchesMode(asrEngine)
                    micLongPressStarted = false
                    micLongPressPending = true
                    val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                    val r = Runnable {
                        if (micLongPressPending && asrEngine?.isRunning != true) {
                            micLongPressStarted = true
                            committedStableLen = 0
                            updateUiListening()
                            asrEngine?.start()
                        }
                    }
                    micLongPressRunnable = r
                    v.postDelayed(r, timeout)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    micLongPressPending = false
                    micLongPressRunnable?.let { v.removeCallbacks(it) }
                    micLongPressRunnable = null
                    if (micLongPressStarted && asrEngine?.isRunning == true) {
                        asrEngine?.stop()
                        // When post-processing is enabled, keep composing text visible
                        // and let onFinal() transition UI state after processing.
                        if (!prefs.postProcessEnabled) {
                            updateUiIdle()
                        } else {
                            // File-based recognition happens now
                            txtStatus?.text = s(R.string.status_recognizing)
                            // 停止录音后隐藏波形
                            audioWaveView?.stop()
                            audioWaveView?.visibility = View.GONE
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnSettings?.setOnClickListener { openSettings() }
        btnEnter?.setOnClickListener {
            sendEnter()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnLetters?.setOnClickListener {
            showLettersPanel()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnAiEdit?.setOnClickListener {
            // Tap-to-toggle: start/stop instruction capture for AI edit
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                txtStatus?.text = s(R.string.hint_need_keys)
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                txtStatus?.text = s(R.string.hint_need_llm_keys)
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            val running = asrEngine?.isRunning == true
            if (running && currentSessionKind == SessionKind.AiEdit) {
                // Stop capture -> will trigger onFinal once recognition finishes
                asrEngine?.stop()
                txtStatus?.text = s(R.string.status_recognizing)
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            if (running) {
                // Engine currently in dictation; ignore to avoid conflicts
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            // Prepare snapshot of target text
            val ic = currentInputConnection
            if (ic == null) {
                txtStatus?.text = s(R.string.status_idle)
                return@setOnClickListener
            }
            var targetIsSelection = false
            var beforeLen = 0
            var afterLen = 0
            val selected = try { ic.getSelectedText(0) } catch (_: Throwable) { null }
            if (selected != null && selected.isNotEmpty()) {
                targetIsSelection = true
            } else {
                // No selection: AI Edit will target last ASR commit text.
                // We keep snapshot lengths for legacy fallback only.
                val before = try { ic.getTextBeforeCursor(10000, 0) } catch (_: Throwable) { null }
                val after = try { ic.getTextAfterCursor(10000, 0) } catch (_: Throwable) { null }
                beforeLen = before?.length ?: 0
                afterLen = after?.length ?: 0
                if (lastAsrCommitText.isNullOrEmpty()) {
                    // No last ASR result to edit — avoid starting capture unnecessarily
                txtStatus?.text = s(R.string.status_last_asr_not_found)
                    return@setOnClickListener
                }
            }
            aiEditState = AiEditState(targetIsSelection, beforeLen, afterLen)
            currentSessionKind = SessionKind.AiEdit
            asrEngine = ensureEngineMatchesMode(asrEngine)
            updateUiListening()
            txtStatus?.text = s(R.string.status_ai_edit_listening)
            asrEngine?.start()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        // Backspace: tap to delete one; swipe up/left to clear all; swipe down to undo within gesture; long-press to repeat delete
        btnBackspace?.setOnClickListener {
            sendBackspace()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnBackspace?.setOnTouchListener { v, event ->
            val ic = currentInputConnection
            if (ic == null) return@setOnTouchListener false
            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    backspaceStartX = event.x
                    backspaceStartY = event.y
                    backspaceClearedInGesture = false
                    backspacePressed = true
                    backspaceLongPressStarted = false
                    // schedule long-press repeat starter
                    backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                    backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                    val starter = Runnable {
                        if (!backspacePressed || backspaceClearedInGesture) return@Runnable
                        backspaceLongPressStarted = true
                        // initial delete then start repeating
                        sendBackspace()
                        val rep = object : Runnable {
                            override fun run() {
                                if (!backspacePressed || backspaceClearedInGesture) return
                                sendBackspace()
                                v.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                            }
                        }
                        backspaceRepeatRunnable = rep
                        v.postDelayed(rep, ViewConfiguration.getKeyRepeatDelay().toLong())
                    }
                    backspaceLongPressStarter = starter
                    v.postDelayed(starter, ViewConfiguration.getLongPressTimeout().toLong())
                    // Take a snapshot so we can restore on downward swipe
                    try {
                        val before = ic.getTextBeforeCursor(10000, 0)
                        val after = ic.getTextAfterCursor(10000, 0)
                        backspaceSnapshotBefore = before
                        backspaceSnapshotAfter = after
                        backspaceSnapshotValid = before != null && after != null
                    } catch (_: Throwable) {
                        backspaceSnapshotBefore = null
                        backspaceSnapshotAfter = null
                        backspaceSnapshotValid = false
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - backspaceStartX
                    val dy = event.y - backspaceStartY
                    // Trigger clear when swiping up or left beyond slop
                    if (!backspaceClearedInGesture && (dy <= -slop || dx <= -slop)) {
                        // cancel any repeat
                        backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                        backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                        clearAllTextWithSnapshot(ic)
                        backspaceClearedInGesture = true
                        vibrateTick()
                        return@setOnTouchListener true
                    }
                    // Swipe down: revert last AI post-processing result to raw transcription
                    if (!backspaceClearedInGesture && dy >= slop) {
                        backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                        backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                        if (revertLastPostprocToRaw(ic)) {
                            vibrateTick()
                            return@setOnTouchListener true
                        }
                    }
                    // If already cleared in this gesture, allow swipe down to undo
                    if (backspaceClearedInGesture && dy >= slop) {
                        if (backspaceSnapshotValid) {
                            restoreSnapshot(ic)
                        }
                        backspaceClearedInGesture = false
                        vibrateTick()
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - backspaceStartX
                    val dy = event.y - backspaceStartY
                    val isTap = kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop && !backspaceClearedInGesture && !backspaceLongPressStarted
                    backspacePressed = false
                    // cancel long-press repeat tasks
                    backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                    backspaceLongPressStarter = null
                    backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                    backspaceRepeatRunnable = null
                    if (isTap && event.actionMasked == MotionEvent.ACTION_UP) {
                        // Treat as a normal backspace tap
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // If finger lifted after an already-cleared gesture, keep result.
                    // If swiped down without having cleared, do nothing (cancel).
                    backspaceSnapshotBefore = null
                    backspaceSnapshotAfter = null
                    backspaceSnapshotValid = false
                    backspaceClearedInGesture = false
                    true
                }
                else -> false
            }
        }
        btnHide?.setOnClickListener {
            hideKeyboardPanel()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnImeSwitcher?.setOnClickListener {
            showImePicker()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPromptPicker?.setOnClickListener { v ->
            showPromptPicker(v)
            maybeHapticKeyTap(v)
        }
        // Punctuation clicks
        btnPunct1?.setOnClickListener {
            commitTextCore(prefs.punct1, vibrate = false)
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPunct2?.setOnClickListener {
            commitTextCore(prefs.punct2, vibrate = false)
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPunct3?.setOnClickListener {
            commitTextCore(prefs.punct3, vibrate = false)
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPunct4?.setOnClickListener {
            commitTextCore(prefs.punct4, vibrate = false)
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPunct5?.setOnClickListener {
            commitTextCore(prefs.punct5, vibrate = false)
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        btnPostproc?.apply {
            isSelected = prefs.postProcessEnabled
            alpha = if (prefs.postProcessEnabled) 1f else 0.45f
            setOnClickListener {
                val enabled = !prefs.postProcessEnabled
                prefs.postProcessEnabled = enabled
                isSelected = enabled
                alpha = if (enabled) 1f else 0.45f
                // Provide quick feedback via status line (localized)
                val state = if (enabled) s(R.string.toggle_on) else s(R.string.toggle_off)
                txtStatus?.text = s(R.string.status_postproc, state)
                // Swap ASR engine implementation when toggled (only if not running)
                if (asrEngine?.isRunning != true) {
                    asrEngine = buildEngineForCurrentMode()
                }
                maybeHapticKeyTap(this)
            }
        }

        // 始终显示输入法切换按钮
        btnImeSwitcher?.visibility = View.VISIBLE

        updateUiIdle()
        refreshPermissionUi()
        // Align system toolbar/navigation bar color to our surface color so they match
        // Setup qwerty panel actions
        setupQwertyPanel()
        setupSymbolsPanel()

        // 启动默认面板（语音/26键）
        try {
            if (prefs.startupPanel == "qwerty") {
                showLettersPanel()
            } else {
                showAsrPanel()
            }
        } catch (_: Throwable) { }

        syncSystemBarsToKeyboardBackground(container)
        return container
    }

    private fun setupQwertyPanel() {
        val v = qwertyPanelView ?: return

        // Bind toolbar views
        qwertyHideTop = v.findViewById(R.id.qwertyHideTop)
        qwertyTextBuffer = v.findViewById(R.id.qwertyTextBuffer)
        qwertyTextBufferConv = v.findViewById(R.id.qwertyTextBufferConv)
        qwertyRawScroll = v.findViewById(R.id.qwertyRawScroll)
        qwertyConvScroll = v.findViewById(R.id.qwertyConvScroll)
        qwertyPinyin = v.findViewById(R.id.qwertyPinyin)
        qwertyLangSwitch = v.findViewById(R.id.qwertyLangSwitch)

        // Setup toolbar listeners
        qwertyHideTop?.setOnClickListener {
            hideKeyboardPanel()
            it?.let { v -> maybeHapticKeyTap(v) }
        }
        qwertyPinyin?.setOnClickListener {
            // 中文“中”键：若最后一次定时调用后拼音有更改，则在上屏前再调用一次LLM；否则直接上屏
            if (langMode != LangMode.Chinese) { it?.let { v2 -> maybeHapticKeyTap(v2) }; return@setOnClickListener }
            val rawInput = pinyinBuffer.toString().trim()
            if (rawInput.isEmpty()) { it?.let { v2 -> maybeHapticKeyTap(v2) }; return@setOnClickListener }
            // 若未配置LLM，则退回一次性转换逻辑（内部会提示缺少配置）
            if (!prefs.hasLlmKeys()) { submitPinyinBufferWithLlm(); it?.let { v2 -> maybeHapticKeyTap(v2) }; return@setOnClickListener }

            // 停止周期任务，避免竞态；不清除合成文本
            stopPinyinAutoSuggest(clearComposition = false)
            val pinyinNow = when (prefs.pinyinMode) {
                PinyinMode.Quanpin -> rawInput
                PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(rawInput) } catch (_: Throwable) { rawInput }
            }
            val needFinalRefresh = (pendingPinyinSuggestion == null) || (pinyinNow != pinyinAutoLastInput)
            val ic = currentInputConnection
            if (!needFinalRefresh && !pendingPinyinSuggestion.isNullOrBlank()) {
                // 直接将当前预览上屏
                try {
                    ic?.beginBatchEdit()
                    if (prefs.disableComposingUnderline) {
                        ic?.commitText(pendingPinyinSuggestion, 1)
                    } else {
                        // 使用合成：结束即可上屏
                        ic?.finishComposingText()
                    }
                    ic?.endBatchEdit()
                } catch (_: Throwable) { }
                clearPinyinBuffer()
                pendingPinyinSuggestion = null
                pinyinAutoLastInput = ""
                it?.let { v2 -> maybeHapticKeyTap(v2) }
                maybeStartPinyinAutoSuggest()
                return@setOnClickListener
            }

            // 需要最终刷新：再调用一次 LLM，使用返回值替换合成并上屏
            txtStatus?.text = s(R.string.status_pinyin_processing)
            serviceScope.launch {
                val out = try { postproc.pinyinToChinese(pinyinNow, prefs).ifBlank { pinyinNow } } catch (_: Throwable) { pinyinNow }
                try {
                    val ic2 = currentInputConnection
                    ic2?.beginBatchEdit()
                    if (prefs.disableComposingUnderline) {
                        ic2?.commitText(out, 1)
                    } else {
                        ic2?.setComposingText(out, 1)
                        ic2?.finishComposingText()
                    }
                    ic2?.endBatchEdit()
                } catch (_: Throwable) { }
                clearPinyinBuffer()
                pendingPinyinSuggestion = null
                pinyinAutoLastInput = ""
                vibrateTick()
                txtStatus?.text = s(R.string.status_idle)
                maybeStartPinyinAutoSuggest()
            }
            it?.let { v2 -> maybeHapticKeyTap(v2) }
        }

        // Language switch button
        qwertyLangSwitch?.setOnClickListener {
            langMode = when (langMode) {
                LangMode.English -> LangMode.Chinese
                LangMode.Chinese -> LangMode.English
            }
            updateLangModeUI()
            it?.let { v -> maybeHapticKeyTap(v) }
        }

        // Initialize UI
        updateLangModeUI()

        // 点击左侧原始字母区：定位拼音光标（滚动由 HorizontalScrollView 处理）
        qwertyRawScroll?.setOnTouchListener { view, event ->
            if (langMode != LangMode.Chinese) return@setOnTouchListener false
            if (event.actionMasked != MotionEvent.ACTION_UP) return@setOnTouchListener false
            val tv = qwertyTextBuffer ?: return@setOnTouchListener false
            val layout = tv.layout ?: return@setOnTouchListener false
            val hsv = view as HorizontalScrollView
            val xInText = (event.x + hsv.scrollX - tv.left).coerceAtLeast(0f)
            val offWithCaret = try { layout.getOffsetForHorizontal(0, xInText) } catch (_: Throwable) { 0 }
            // caret 索引基于原始字母（raw）插入的占位
            val caretIndex = pinyinCursor.coerceIn(0, pinyinBuffer.length)
            val plainOffset = if (offWithCaret <= caretIndex) offWithCaret else (offWithCaret - 1)
            setPinyinCursor(plainOffset)
            maybeHapticKeyTap(view)
            true
        }

        // 左右分区滚动联动
        qwertyRawScroll?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSyncScrolling) return@setOnScrollChangeListener
            val other = qwertyConvScroll ?: return@setOnScrollChangeListener
            isSyncScrolling = true
            try { other.scrollTo(scrollX, 0) } finally { isSyncScrolling = false }
        }
        qwertyConvScroll?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSyncScrolling) return@setOnScrollChangeListener
            val other = qwertyRawScroll ?: return@setOnScrollChangeListener
            isSyncScrolling = true
            try { other.scrollTo(scrollX, 0) } finally { isSyncScrolling = false }
        }

        // Letters
        qwertyLetterKeys.clear()
        fun bindLetters(group: View) {
            if (group is ViewGroup) {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    val tag = child.tag?.toString()
                    if (tag == "letter_key" && child is TextView) {
                        qwertyLetterKeys.add(child)
                        // 点击：字母；上滑：符号/数字（见 symbolic_table.md）
                        child.setOnClickListener {
                            val s = child.text?.toString() ?: return@setOnClickListener
                            if (langMode == LangMode.Chinese) {
                                // 中文模式：
                                // - Shift 开启（Once/Lock）时，直接将大写字母上屏，不进入拼音缓冲/双拼
                                // - 其它情况：按小写拼音写入缓冲
                                if (shiftMode != ShiftMode.Off) {
                                    commitTextCore(s.uppercase(), vibrate = false)
                                    // Shift 为一次性时用后即关
                                    if (shiftMode == ShiftMode.Once) {
                                        shiftMode = ShiftMode.Off
                                        updateShiftUi()
                                        applyLetterCase()
                                    }
                                } else {
                                    insertIntoPinyinBuffer(s.lowercase())
                                }
                            } else {
                                // 英文模式：直接提交（跟随当前显示大小写）
                                commitTextCore(s, vibrate = false)
                                if (shiftMode == ShiftMode.Once) {
                                    shiftMode = ShiftMode.Off
                                    updateShiftUi()
                                    applyLetterCase()
                                }
                            }
                            maybeHapticKeyTap(child)
                        }
                        if (prefs.qwertySwipeAltEnabled) child.setOnTouchListener { v, event ->
                            val tv = v as? TextView ?: return@setOnTouchListener false
                            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    v.setTag(R.id.tag_swipe_start_x, event.x)
                                    v.setTag(R.id.tag_swipe_start_y, event.y)
                                    v.setTag(R.id.tag_swipe_handled, false)
                                    false
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val sy = (v.getTag(R.id.tag_swipe_start_y) as? Float) ?: return@setOnTouchListener false
                                    val handled = (v.getTag(R.id.tag_swipe_handled) as? Boolean) ?: false
                                    if (!handled) {
                                        val dy = event.y - sy
                                        if (dy <= -slop) {
                                            val base = tv.text?.toString()?.firstOrNull()?.lowercaseChar()
                                            val alt = base?.let { getSwipeAltForLetter(it) }
                                            if (!alt.isNullOrEmpty()) {
                                                commitTextCore(alt, vibrate = false)
                                                vibrateTick()
                                                v.setTag(R.id.tag_swipe_handled, true)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }
                                    false
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    val handled = (v.getTag(R.id.tag_swipe_handled) as? Boolean) ?: false
                                    if (handled) return@setOnTouchListener true
                                    false
                                }
                                else -> false
                            }
                        } else {
                            child.setOnTouchListener(null)
                        }
                    } else if (tag == "punct_key" && child is TextView) {
                        child.setOnClickListener {
                            val s = child.text?.toString() ?: return@setOnClickListener
                            commitTextCore(s, vibrate = false)
                            maybeHapticKeyTap(child)
                        }
                        if (prefs.qwertySwipeAltEnabled) child.setOnTouchListener { v, event ->
                            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    v.setTag(R.id.tag_swipe_start_y, event.y)
                                    v.setTag(R.id.tag_swipe_handled, false)
                                    false
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val sy = (v.getTag(R.id.tag_swipe_start_y) as? Float) ?: return@setOnTouchListener false
                                    val handled = (v.getTag(R.id.tag_swipe_handled) as? Boolean) ?: false
                                    if (!handled) {
                                        val dy = event.y - sy
                                        if (dy <= -slop) {
                                            val alt = getSwipeAltForComma()
                                            commitTextCore(alt, vibrate = false)
                                            vibrateTick()
                                            v.setTag(R.id.tag_swipe_handled, true)
                                            return@setOnTouchListener true
                                        }
                                    }
                                    false
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    val handled = (v.getTag(R.id.tag_swipe_handled) as? Boolean) ?: false
                                    if (handled) return@setOnTouchListener true
                                    false
                                }
                                else -> false
                            }
                        } else {
                            child.setOnTouchListener(null)
                        }
                    } else if (child is ViewGroup) {
                        bindLetters(child)
                    }
                }
            }
        }
        bindLetters(v)
        applyLetterCase()
        applySwipeAltBadges()

        // Space gestures:
        // - Tap: 输入空格
        // - Long press: 按住说话（进入ASR监听，松手停止并返回QWERTY）
        // - Swipe up: 进入语音输入面板（不直接开始录音）
        v.findViewById<TextView?>(R.id.qwertySpace)?.apply {
            // 缓存引用，便于状态覆盖
            qwertySpaceView = this
            setOnTouchListener { view, event ->
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        spaceStartX = event.x
                        spaceStartY = event.y
                        spaceSwipedUp = false
                        spaceLongPressStarted = false
                        spaceLongPressPending = true
                        // 预设长按启动语音识别（按住说话）
                        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                        val r = Runnable {
                            if (!spaceLongPressPending || asrEngine?.isRunning == true) return@Runnable
                            // 权限与配置校验
                            if (!hasRecordAudioPermission() || !prefs.hasAsrKeys()) {
                                spaceLongPressPending = false
                                // 展示权限/配置提示需在ASR面板，若需要可引导：这里保持静默
                                return@Runnable
                            }
                            // 准备引擎并切换到ASR面板展示监听状态
                            asrEngine = ensureEngineMatchesMode(asrEngine)
                            spaceLongPressStarted = true
                            committedStableLen = 0
                            showAsrPanel()
                            updateUiListening()
                            asrEngine?.start()
                        }
                        spaceLongPressRunnable = r
                        view.postDelayed(r, timeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - spaceStartY
                        // 上滑进入语音输入面板（取消长按）
                        if (!spaceSwipedUp && !spaceLongPressStarted && dy <= -slop) {
                            spaceSwipedUp = true
                            spaceLongPressPending = false
                            spaceLongPressRunnable?.let { view.removeCallbacks(it) }
                            spaceLongPressRunnable = null
                            showAsrPanel()
                            vibrateTick()
                            return@setOnTouchListener true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = event.x - spaceStartX
                        val dy = event.y - spaceStartY
                        val isTap = !spaceLongPressStarted && !spaceSwipedUp &&
                                kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop
                        // 取消长按触发
                        spaceLongPressPending = false
                        spaceLongPressRunnable?.let { view.removeCallbacks(it) }
                        spaceLongPressRunnable = null

                        if (spaceLongPressStarted && asrEngine?.isRunning == true) {
                            // 松手停止识别，并返回QWERTY
                            asrEngine?.stop()
                            if (!prefs.postProcessEnabled) {
                                updateUiIdle()
                                // 文件识别（无后处理）通常很快，仍提示“识别中”以覆盖空格，随后由耗时提示恢复
                                showSpaceStatus(s(R.string.status_recognizing))
                            } else {
                                txtStatus?.text = s(R.string.status_recognizing)
                                // 返回 QWERTY 后镜像状态到空格键
                                showSpaceStatus(s(R.string.status_recognizing))
                            }
                            showLettersPanel()
                            return@setOnTouchListener true
                        }
                        if (spaceSwipedUp) {
                            // 已上滑进入语音面板，不再当作点击
                            return@setOnTouchListener true
                        }
                        if (isTap) {
                            if (langMode == LangMode.Chinese) {
                                insertIntoPinyinBuffer(" ")
                            } else {
                                commitTextCore(" ", vibrate = false)
                            }
                            maybeHapticKeyTap(view)
                            return@setOnTouchListener true
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        // Backspace gestures: tap delete; swipe up/left clear all; swipe down undo or revert; long-press repeat
        v.findViewById<ImageButton?>(R.id.qwertyBackspace)?.apply {
            setOnClickListener {
                handleQwertyBackspaceTap()
                maybeHapticKeyTap(this)
            }
            setOnTouchListener { view, event ->
                val ic = currentInputConnection ?: return@setOnTouchListener false
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        backspaceStartX = event.x
                        backspaceStartY = event.y
                        backspaceClearedInGesture = false
                        backspaceClearedFieldInGesture = false
                        backspacePressed = true
                        backspaceLongPressStarted = false
                        // cancel any pending
                        backspaceLongPressStarter?.let { view.removeCallbacks(it) }
                        backspaceRepeatRunnable?.let { view.removeCallbacks(it) }
                        val starter = Runnable {
                            if (!backspacePressed || backspaceClearedInGesture) return@Runnable
                            backspaceLongPressStarted = true
                            // initial
                            handleQwertyBackspaceTap()
                            val rep = object : Runnable {
                                override fun run() {
                                    if (!backspacePressed || backspaceClearedInGesture) return
                                    handleQwertyBackspaceTap()
                                    view.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                                }
                            }
                            backspaceRepeatRunnable = rep
                            view.postDelayed(rep, ViewConfiguration.getKeyRepeatDelay().toLong())
                        }
                        backspaceLongPressStarter = starter
                        view.postDelayed(starter, ViewConfiguration.getLongPressTimeout().toLong())
                        // snapshot editor and pinyin buffer
                        try {
                            backspaceSnapshotBefore = ic.getTextBeforeCursor(10000, 0)
                            backspaceSnapshotAfter = ic.getTextAfterCursor(10000, 0)
                            backspaceSnapshotValid = backspaceSnapshotBefore != null && backspaceSnapshotAfter != null
                        } catch (_: Throwable) {
                            backspaceSnapshotBefore = null
                            backspaceSnapshotAfter = null
                            backspaceSnapshotValid = false
                        }
                        pinyinBufferSnapshot = if (langMode == LangMode.Chinese) pinyinBuffer.toString() else null
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - backspaceStartX
                        val dy = event.y - backspaceStartY
                        if (!backspaceClearedInGesture && (dy <= -slop || dx <= -slop)) {
                            backspaceLongPressStarter?.let { view.removeCallbacks(it) }
                            backspaceRepeatRunnable?.let { view.removeCallbacks(it) }
                            // 优先清空拼音缓冲与预览；若无拼音再清空整个输入框
                            if (langMode == LangMode.Chinese && pinyinBuffer.isNotEmpty()) {
                                // 清空拼音缓冲并移除合成预览
                                clearPinyinBuffer()
                                clearPinyinPreviewComposition()
                                backspaceClearedFieldInGesture = false
                            } else {
                                clearAllTextWithSnapshot(ic)
                                clearPinyinBuffer()
                                backspaceClearedFieldInGesture = true
                            }
                            backspaceClearedInGesture = true
                            vibrateTick()
                            return@setOnTouchListener true
                        }
                        if (!backspaceClearedInGesture && dy >= slop) {
                            backspaceLongPressStarter?.let { view.removeCallbacks(it) }
                            backspaceRepeatRunnable?.let { view.removeCallbacks(it) }
                            if (revertLastPostprocToRaw(ic)) {
                                vibrateTick()
                                return@setOnTouchListener true
                            }
                        }
                        if (backspaceClearedInGesture && dy >= slop) {
                            // 撤销：若清空了编辑框，则恢复快照；否则只恢复拼音缓冲
                            if (backspaceClearedFieldInGesture && backspaceSnapshotValid) {
                                restoreSnapshot(ic)
                            }
                            // 恢复拼音缓冲
                            val snap = pinyinBufferSnapshot
                            if (snap != null) {
                                pinyinBuffer.clear()
                                pinyinBuffer.append(snap)
                                refreshPinyinTextView()
                                // 恢复后尝试重新启动预览
                                onPinyinBufferChanged()
                            }
                            backspaceClearedInGesture = false
                            backspaceClearedFieldInGesture = false
                            vibrateTick()
                            return@setOnTouchListener true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = event.x - backspaceStartX
                        val dy = event.y - backspaceStartY
                        val isTap = kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop && !backspaceClearedInGesture && !backspaceLongPressStarted
                        backspacePressed = false
                        backspaceLongPressStarter?.let { view.removeCallbacks(it) }
                        backspaceLongPressStarter = null
                        backspaceRepeatRunnable?.let { view.removeCallbacks(it) }
                        backspaceRepeatRunnable = null
                        if (isTap && event.actionMasked == MotionEvent.ACTION_UP) {
                            view.performClick()
                            return@setOnTouchListener true
                        }
                        backspaceSnapshotBefore = null
                        backspaceSnapshotAfter = null
                        backspaceSnapshotValid = false
                        pinyinBufferSnapshot = null
                        backspaceClearedInGesture = false
                        backspaceClearedFieldInGesture = false
                        true
                    }
                    else -> false
                }
            }
        }
        // Enter & 123
        v.findViewById<ImageButton?>(R.id.qwertyEnter)?.setOnClickListener { sendEnter() }
        v.findViewById<TextView?>(R.id.qwertyNum)?.setOnClickListener {
            showSymbolsPanel()
            it?.let { v2 -> maybeHapticKeyTap(v2) }
        }

        // Shift toggle
        v.findViewById<ImageButton?>(R.id.qwertyShift)?.apply {
            isSelected = shiftMode != ShiftMode.Off
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                val dt = now - lastShiftTapTime
                lastShiftTapTime = now
                shiftMode = if (dt <= ViewConfiguration.getDoubleTapTimeout()) {
                    // double tap -> lock
                    ShiftMode.Lock
                } else {
                    // single tap -> toggle once/off
                    when (shiftMode) {
                        ShiftMode.Off -> ShiftMode.Once
                        ShiftMode.Once -> ShiftMode.Off
                        ShiftMode.Lock -> ShiftMode.Off
                    }
                }
                updateShiftUi()
                applyLetterCase()
                maybeHapticKeyTap(this)
            }
        }
    }

    private fun applyLetterCase() {
        // In Chinese mode, always show uppercase letters
        // In English mode, follow shift state
        val upper = when (langMode) {
            LangMode.Chinese -> true
            LangMode.English -> shiftMode != ShiftMode.Off
        }
        qwertyLetterKeys.forEach { tv ->
            val t = tv.text?.toString() ?: return@forEach
            if (t.length == 1) {
                tv.text = if (upper) t.uppercase() else t.lowercase()
            }
        }
    }

    private fun updateLangModeUI() {
        when (langMode) {
            LangMode.Chinese -> {
                qwertyLangSwitch?.text = s(R.string.label_chinese_mode)
                // 切换到中文时，默认将光标移动到末尾
                pinyinCursor = pinyinBuffer.length
                refreshPinyinTextView()
                maybeStartPinyinAutoSuggest()
            }
            LangMode.English -> {
                qwertyLangSwitch?.text = s(R.string.label_english_mode)
                stopPinyinAutoSuggest(true)
                qwertyTextBuffer?.text = ""
                qwertyConvScroll?.visibility = View.GONE
            }
        }
        // 同步更新字母大小写与标点/符号的本地化显示
        applyLetterCase()
        applySymbolsForCurrentLang()
    }

    private fun updateShiftUi() {
        val shift = qwertyPanelView?.findViewById<ImageButton?>(R.id.qwertyShift)
        when (shiftMode) {
            ShiftMode.Off -> { shift?.isSelected = false; shift?.isActivated = false }
            ShiftMode.Once -> { shift?.isSelected = true; shift?.isActivated = false }
            ShiftMode.Lock -> { shift?.isSelected = true; shift?.isActivated = true }
        }
    }

    private fun setPinyinCursor(idx: Int) {
        val newIdx = idx.coerceIn(0, pinyinBuffer.length)
        if (newIdx == pinyinCursor) return
        pinyinCursor = newIdx
        refreshPinyinTextView()
    }

    // 自定义插入竖线光标的展示
    private fun refreshPinyinTextView() {
        val tvRaw = qwertyTextBuffer ?: return
        val tvConv = qwertyTextBufferConv
        if (langMode != LangMode.Chinese) {
            tvRaw.text = ""
            tvConv?.visibility = View.GONE
            return
        }
        val raw = pinyinBuffer.toString()
        val caretPos = pinyinCursor.coerceIn(0, raw.length)
        // 构造原始字母行 + 光标
        val ssb = SpannableStringBuilder()
        try { ssb.append(raw) } catch (_: Throwable) { tvRaw.text = raw; return }
        val place = "\u200B"
        val insertAt = caretPos.coerceIn(0, ssb.length)
        ssb.insert(insertAt, place)
        val color = tvRaw.currentTextColor
        val widthPx = (tvRaw.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1)
        ssb.setSpan(CaretSpan(color, widthPx), insertAt, insertAt + place.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvRaw.text = ssb
        // 转换行：仅在小鹤双拼时显示
        if (prefs.pinyinMode == PinyinMode.Xiaohe) {
            qwertyConvScroll?.visibility = View.VISIBLE
            tvConv?.visibility = View.VISIBLE
            tvConv?.text = try { XiaoheShuangpinConverter.convert(raw) } catch (_: Throwable) { raw }
        } else {
            tvConv?.text = ""
            tvConv?.visibility = View.GONE
            qwertyConvScroll?.visibility = View.GONE
        }
        // 确保光标可见
        ensureCursorVisible(insertAt)
    }

    private fun ensureCursorVisible(displayCaretIndexWithPlace: Int) {
        val tv = qwertyTextBuffer ?: return
        val hsv = qwertyRawScroll ?: return
        tv.post {
            val layout = tv.layout ?: return@post
            val xCaret = try { layout.getPrimaryHorizontal(displayCaretIndexWithPlace) } catch (_: Throwable) { 0f }
            val left = hsv.scrollX.toFloat()
            val right = left + hsv.width
            val padding = (tv.resources.displayMetrics.density * 16).toInt()
            if (xCaret < left + padding) {
                hsv.smoothScrollTo((xCaret - padding).toInt().coerceAtLeast(0), 0)
            } else if (xCaret > right - padding) {
                hsv.smoothScrollTo((xCaret - hsv.width + padding).toInt().coerceAtLeast(0), 0)
            }
        }
    }

    private class CaretSpan(private val color: Int, private val widthPx: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            return widthPx
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val p = Paint(paint)
            p.color = color
            val hPad = 2f
            val left = x
            val right = x + widthPx
            val t = top.toFloat() + hPad
            val b = bottom.toFloat() - hPad
            canvas.drawRect(left, t, right, b, p)
        }
    }

    // 在光标处插入文本
    private fun insertIntoPinyinBuffer(s: String) {
        if (s.isEmpty()) return
        val idx = pinyinCursor.coerceIn(0, pinyinBuffer.length)
        pinyinBuffer.insert(idx, s)
        pinyinCursor = idx + s.length
        refreshPinyinTextView()
        onPinyinBufferChanged()
    }

    // 删除光标前一个字符
    private fun deleteBeforeCursorInPinyin(): Boolean {
        if (pinyinBuffer.isEmpty() || pinyinCursor <= 0) return false
        val delAt = (pinyinCursor - 1).coerceIn(0, pinyinBuffer.length - 1)
        pinyinBuffer.deleteCharAt(delAt)
        pinyinCursor = delAt
        refreshPinyinTextView()
        onPinyinBufferChanged()
        return true
    }

    private fun clearPinyinBuffer() {
        if (pinyinBuffer.isEmpty()) return
        pinyinBuffer.clear()
        pinyinCursor = 0
        refreshPinyinTextView()
        onPinyinBufferChanged()
    }

    private fun handleQwertyBackspaceTap() {
        if (langMode == LangMode.Chinese) {
            if (deleteBeforeCursorInPinyin()) return
        }
        sendBackspace()
    }

    private fun submitPinyinBufferWithLlm() {
        if (langMode != LangMode.Chinese) return
        val rawInput = pinyinBuffer.toString().trim()
        val pinyin = when (prefs.pinyinMode) {
            PinyinMode.Quanpin -> rawInput
            PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(rawInput) } catch (_: Throwable) { rawInput }
        }
        if (pinyin.isEmpty()) {
            txtStatus?.text = s(R.string.hint_pinyin_empty)
            vibrateTick()
            return
        }
        if (!prefs.hasLlmKeys()) {
            txtStatus?.text = s(R.string.hint_need_llm_keys)
            vibrateTick()
            return
        }
        txtStatus?.text = s(R.string.status_pinyin_processing)
        serviceScope.launch {
            val out = try {
                postproc.pinyinToChinese(pinyin, prefs).ifBlank { pinyin }
            } catch (_: Throwable) { pinyin }
            // 提交并清空缓冲
            currentInputConnection?.commitText(out, 1)
            clearPinyinBuffer()
            vibrateTick()
            txtStatus?.text = s(R.string.status_idle)
        }
    }

    private fun setupSymbolsPanel() {
        val v = symbolsPanelView ?: return
        fun bind(group: View) {
            if (group is ViewGroup) {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    val tag = child.tag?.toString()
                    if (tag == "sym_key" && child is TextView) {
                        child.setOnClickListener {
                            val s = child.text?.toString() ?: return@setOnClickListener
                            commitTextCore(s, vibrate = false)
                            maybeHapticKeyTap(child)
                        }
                    } else if (child is ViewGroup) {
                        bind(child)
                    }
                }
            }
        }
        bind(v)
        // Space click/long-press
        v.findViewById<TextView?>(R.id.symSpace)?.apply {
            // 缓存引用，便于状态覆盖
            symSpaceView = this
            setOnClickListener {
                commitTextCore(" ", vibrate = false)
                maybeHapticKeyTap(this)
            }
            setOnLongClickListener {
                showAsrPanel()
                vibrateTick()
                true
            }
        }
        // Backspace long-press repeat
        v.findViewById<ImageButton?>(R.id.symBackspace)?.setOnTouchListener { view, event ->
            var pressed: Boolean
            var longStarted: Boolean
            val starter = view.getTag(R.id.tag_starter) as? Runnable
            val repeater = view.getTag(R.id.tag_repeater) as? Runnable
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    longStarted = false
                    starter?.let { view.removeCallbacks(it) }
                    repeater?.let { view.removeCallbacks(it) }
                    val r = Runnable {
                        if (!(view.getTag(R.id.tag_pressed) as? Boolean ?: false)) return@Runnable
                        view.setTag(R.id.tag_long_started, true)
                        sendBackspace()
                        val rep = object : Runnable {
                            override fun run() {
                                if (!(view.getTag(R.id.tag_pressed) as? Boolean ?: false)) return
                                sendBackspace()
                                view.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                            }
                        }
                        view.setTag(R.id.tag_repeater, rep)
                        view.postDelayed(rep, ViewConfiguration.getKeyRepeatDelay().toLong())
                    }
                    view.setTag(R.id.tag_starter, r)
                    view.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                    view.setTag(R.id.tag_pressed, pressed)
                    view.setTag(R.id.tag_long_started, longStarted)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    val ls = view.getTag(R.id.tag_long_started) as? Boolean ?: false
                    (view.getTag(R.id.tag_starter) as? Runnable)?.let { view.removeCallbacks(it) }
                    (view.getTag(R.id.tag_repeater) as? Runnable)?.let { view.removeCallbacks(it) }
                    view.setTag(R.id.tag_starter, null)
                    view.setTag(R.id.tag_repeater, null)
                    view.setTag(R.id.tag_pressed, pressed)
                    view.setTag(R.id.tag_long_started, false)
                    if (!ls && event.actionMasked == MotionEvent.ACTION_UP) {
                        sendBackspace()
                        maybeHapticKeyTap(view)
                    }
                    true
                }
                else -> false
            }
        }
        // Enter / Hide / ABC
        v.findViewById<ImageButton?>(R.id.symEnter)?.setOnClickListener {
            sendEnter()
            it?.let { vv -> maybeHapticKeyTap(vv) }
        }
        v.findViewById<ImageButton?>(R.id.symHide)?.setOnClickListener {
            hideKeyboardPanel()
            it?.let { vv -> maybeHapticKeyTap(vv) }
        }
        v.findViewById<TextView?>(R.id.symToLetters)?.setOnClickListener {
            showLettersPanel()
            it?.let { vv -> maybeHapticKeyTap(vv) }
        }
    }

    private fun showLettersPanel() {
        // 切走 ASR 时停止采集
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
        }
        updateUiIdle()
        // 切换顶层：显示 qwerty，隐藏 asr
        asrPanelView?.visibility = View.GONE
        qwertyPanelView?.visibility = View.VISIBLE
        // 切换 qwerty 主区域：显示字母，隐藏符号
        qwertyLettersPanelView?.visibility = View.VISIBLE
        symbolsPanelView?.visibility = View.GONE
        isQwertyVisible = true
        // 应用默认语言模式（设置项）
        try {
            val wantZh = prefs.qwertyDefaultLang == "zh"
            langMode = if (wantZh) LangMode.Chinese else LangMode.English
        } catch (_: Throwable) { langMode = LangMode.English }
        updateLangModeUI()
        updateShiftUi()
        if (langMode == LangMode.Chinese) maybeStartPinyinAutoSuggest() else stopPinyinAutoSuggest(true)
    }

    private fun showAsrPanel() {
        qwertyPanelView?.visibility = View.GONE
        asrPanelView?.visibility = View.VISIBLE
        isQwertyVisible = false
        stopPinyinAutoSuggest(true)
        // 切换到 ASR 面板时，清除空格键上的临时状态覆盖
        clearSpaceStatus()
    }

    private fun showSymbolsPanel() {
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
        }
        updateUiIdle()
        // 进入符号：显示 qwerty 顶层，仅切换主区域
        asrPanelView?.visibility = View.GONE
        qwertyPanelView?.visibility = View.VISIBLE
        qwertyLettersPanelView?.visibility = View.GONE
        symbolsPanelView?.visibility = View.VISIBLE
        isQwertyVisible = false
        stopPinyinAutoSuggest(true)
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Avoid fullscreen candidates for a compact mic-only keyboard
        return false
    }

    private fun refreshPermissionUi() {
        val granted = hasRecordAudioPermission()
        val hasKeys = prefs.hasAsrKeys()
        if (!granted) {
            btnMic?.isEnabled = false
            txtStatus?.text = s(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnMic?.isEnabled = false
            txtStatus?.text = s(R.string.hint_need_keys)
        } else {
            btnMic?.isEnabled = true
            txtStatus?.text = s(R.string.status_idle)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveKeyboardSurfaceColor(from: View? = null): Int {
        // Use the same attribute the keyboard container uses for background
        val ctx = from?.context ?: this
        return try {
            MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        } catch (_: Throwable) {
            Color.BLACK
        }
    }

    @Suppress("DEPRECATION")
    private fun syncSystemBarsToKeyboardBackground(anchorView: View? = null) {
        val w = window?.window ?: return
        val color = resolveKeyboardSurfaceColor(anchorView)
        try {
            w.navigationBarColor = color
        } catch (_: Throwable) { }
        // Adjust nav bar icon contrast depending on background brightness
        val isLight = try { ColorUtils.calculateLuminance(color) > 0.5 } catch (_: Throwable) { false }
        val controller = WindowInsetsControllerCompat(w, anchorView ?: w.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }

    // --- 在空格键上覆盖显示 ASR 状态（QWERTY/符号面板均支持） ---
    private fun showSpaceStatus(text: String) {
        try { spaceStatusClearRunnable?.let { (rootView ?: qwertySpaceView ?: symSpaceView)?.removeCallbacks(it) } } catch (_: Throwable) { }
        spaceStatusClearRunnable = null
        spaceStatusActive = true
        try { qwertySpaceView?.text = text } catch (_: Throwable) { }
        try { symSpaceView?.text = text } catch (_: Throwable) { }
    }

    private fun showSpaceStatusForMs(text: String, duration: Long = 1500L) {
        showSpaceStatus(text)
        val anchor = rootView ?: qwertySpaceView ?: symSpaceView
        val r = Runnable {
            if (asrEngine?.isRunning != true) {
                clearSpaceStatus()
            }
        }
        spaceStatusClearRunnable = r
        try { anchor?.postDelayed(r, duration) } catch (_: Throwable) { }
    }

    private fun clearSpaceStatus() {
        spaceStatusActive = false
        try { qwertySpaceView?.setText(R.string.label_space) } catch (_: Throwable) { }
        try { symSpaceView?.setText(R.string.label_space) } catch (_: Throwable) { }
        try { spaceStatusClearRunnable?.let { (rootView ?: qwertySpaceView ?: symSpaceView)?.removeCallbacks(it) } } catch (_: Throwable) { }
        spaceStatusClearRunnable = null
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                VolcFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                ElevenLabsFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                DashscopeFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
        }
    }

    private fun ensureEngineMatchesMode(current: StreamingAsrEngine?): StreamingAsrEngine? {
        if (!prefs.hasAsrKeys()) return null
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> when (current) {
                is VolcFileAsrEngine -> current
                else -> VolcFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.SiliconFlow -> when (current) {
                is SiliconFlowFileAsrEngine -> current
                else -> SiliconFlowFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.ElevenLabs -> when (current) {
                is ElevenLabsFileAsrEngine -> current
                else -> ElevenLabsFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.OpenAI -> when (current) {
                is OpenAiFileAsrEngine -> current
                else -> OpenAiFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.DashScope -> when (current) {
                is DashscopeFileAsrEngine -> current
                else -> DashscopeFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.Gemini -> when (current) {
                is GeminiFileAsrEngine -> current
                else -> GeminiFileAsrEngine(buildLocaleAppliedContext(this@AsrKeyboardService), serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
        }
    }

    private fun onAsrRequestDuration(ms: Long) {
        lastRequestDurationMs = ms
    }

    private fun goIdleWithTimingHint() {
        updateUiIdle()
        val ms = lastRequestDurationMs ?: return
        try {
            txtStatus?.text = s(R.string.status_last_request_ms, ms)
            // 同步将“上次识别耗时”显示在空格键上，短暂展示后恢复
            showSpaceStatusForMs(s(R.string.status_last_request_ms, ms))
            val v = rootView ?: txtStatus
            v?.postDelayed({
                if (asrEngine?.isRunning != true) {
                    txtStatus?.text = s(R.string.status_idle)
                    // 若 ASR 已闲置，则确保空格状态也回到默认
                    clearSpaceStatus()
                }
            }, 1500)
        } catch (_: Throwable) { }
    }

    private fun updateUiIdle() {
        txtStatus?.text = s(R.string.status_idle)
        btnMic?.isSelected = false
        currentInputConnection?.finishComposingText()
        audioWaveView?.stop()
        audioWaveView?.visibility = View.GONE
    }

    private fun updateUiListening() {
        txtStatus?.text = s(R.string.status_listening)
        btnMic?.isSelected = true
        audioWaveView?.visibility = View.VISIBLE
        audioWaveView?.start()
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        // 优化：在 QWERTY 中文模式下，若拼音缓冲中含有字母，则直接将缓冲原样上屏（不做双拼转换）
        if (isQwertyVisible && langMode == LangMode.Chinese) {
            val raw = pinyinBuffer.toString()
            // “含有字母”判断：存在任意 A-Z/a-z 即认为需要上屏
            val hasLetter = raw.any { it.isLetter() }
            if (hasLetter) {
                // 清除可能存在的合成预览，避免被上屏干扰
                clearPinyinPreviewComposition()
                try {
                    ic.commitText(raw, 1)
                } catch (_: Throwable) { }
                clearPinyinBuffer()
                // 直接上屏字母后不再发送回车
                return
            }
        }
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun sendBackspace() {
        val ic = currentInputConnection ?: return
        // Delete one character before cursor
        ic.deleteSurroundingText(1, 0)
    }

    private fun clearAllTextWithSnapshot(ic: android.view.inputmethod.InputConnection) {
        // If snapshot is invalid (e.g., secure fields), fall back to max deletion
        if (!backspaceSnapshotValid) {
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
            } catch (_: Throwable) { }
            return
        }
        try {
            val beforeLen = backspaceSnapshotBefore?.length ?: 0
            val afterLen = backspaceSnapshotAfter?.length ?: 0
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.finishComposingText()
            ic.endBatchEdit()
        } catch (_: Throwable) {
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
            } catch (_: Throwable) { }
        }
    }

    private fun restoreSnapshot(ic: android.view.inputmethod.InputConnection) {
        if (!backspaceSnapshotValid) return
        val before = backspaceSnapshotBefore?.toString() ?: return
        val after = backspaceSnapshotAfter?.toString() ?: ""
        try {
            ic.beginBatchEdit()
            ic.commitText(before + after, 1)
            val sel = before.length
            try {
                ic.setSelection(sel, sel)
            } catch (_: Throwable) { }
            ic.finishComposingText()
            ic.endBatchEdit()
        } catch (_: Throwable) { }
    }

    private fun hideKeyboardPanel() {
        // Stop any ongoing ASR session and return to idle
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
        }
        updateUiIdle()
        try {
            requestHideSelf(0)
        } catch (_: Exception) { }
    }

    private fun showImePicker() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
            // no-op
        }
    }

    private fun applyPunctuationLabels() {
        btnPunct1?.text = prefs.punct1
        btnPunct2?.text = prefs.punct2
        btnPunct3?.text = prefs.punct3
        btnPunct4?.text = prefs.punct4
        btnPunct5?.text = prefs.punct5
    }

    // 内部提交文本，允许选择是否触发默认振动（保留原有行为给非 26 键场景）。
    private fun commitTextCore(s: String, vibrate: Boolean) {
        try {
            currentInputConnection?.commitText(s, 1)
            if (vibrate) vibrateTick()
        } catch (_: Throwable) { }
    }

    // 26 键盘点击触觉反馈：跟随系统触觉设置
    private fun maybeHapticKeyTap(view: View) {
        if (!prefs.qwertyHapticEnabled) return
        try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
    }

    private fun vibrateTick() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
        } catch (_: Exception) {
        }
    }

    // 清除中文 26 键的 LLM 预览合成文本（不把预览“上屏”）
    private fun clearPinyinPreviewComposition() {
        try {
            val ic = currentInputConnection
            ic?.beginBatchEdit()
            ic?.setComposingText("", 1)
            ic?.finishComposingText()
            ic?.endBatchEdit()
        } catch (_: Throwable) { }
    }

    private fun showPromptPicker(anchor: View) {
        try {
            val presets = prefs.getPromptPresets()
            if (presets.isEmpty()) return
            val popup = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
            presets.forEachIndexed { idx, p ->
                val item = popup.menu.add(0, idx, idx, p.title)
                item.isCheckable = true
                if (p.id == prefs.activePromptId) item.isChecked = true
            }
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { mi ->
                val position = mi.itemId
                val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
                prefs.activePromptId = preset.id
                txtStatus?.text = s(R.string.switched_preset, preset.title)
                true
            }
            popup.show()
        } catch (_: Throwable) { }
    }

    override fun onFinal(text: String) {
        // Ensure all UI/InputConnection operations happen on main thread
        serviceScope.launch {
            if (currentSessionKind == SessionKind.AiEdit && prefs.hasLlmKeys()) {
                // AI edit flow: use recognized text as instruction to edit selection or full text
                val ic = currentInputConnection
                val state = aiEditState
                if (ic == null || state == null) {
                    goIdleWithTimingHint()
                    currentSessionKind = null
                    aiEditState = null
                    return@launch
                }
                txtStatus?.text = s(R.string.status_ai_editing)
                // Build original text: selection or last ASR commit (no selection)
                val original = try {
                    if (state.targetIsSelection) {
                        ic.getSelectedText(0)?.toString() ?: ""
                    } else {
                        lastAsrCommitText ?: ""
                    }
                } catch (_: Throwable) { "" }
                val instruction = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                val edited = try {
                    postproc.editText(original, instruction, prefs)
                } catch (_: Throwable) { "" }
                if (original.isBlank()) {
                    // Safety: if we failed to reconstruct original text, do not delete anything
                    txtStatus?.text = s(R.string.hint_cannot_read_text)
                    vibrateTick()
                    currentSessionKind = null
                    aiEditState = null
                    committedStableLen = 0
                    goIdleWithTimingHint()
                    return@launch
                }
                if (edited.isBlank()) {
                    // LLM returned empty or failed — do not change
                    txtStatus?.text = s(R.string.status_llm_empty_result)
                    vibrateTick()
                    currentSessionKind = null
                    aiEditState = null
                    committedStableLen = 0
                    goIdleWithTimingHint()
                    return@launch
                }
                try {
                    ic.beginBatchEdit()
                    if (state.targetIsSelection) {
                        // Replace current selection
                        ic.commitText(edited, 1)
                    } else {
                        // Replace the last ASR committed segment when possible
                        val lastText = lastAsrCommitText ?: ""
                        val before = try { ic.getTextBeforeCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
                        val after = try { ic.getTextAfterCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
                        var replaced = false
                        if (lastText.isNotEmpty()) {
                            if (!before.isNullOrEmpty() && before.endsWith(lastText)) {
                                ic.deleteSurroundingText(lastText.length, 0)
                                ic.commitText(edited, 1)
                                replaced = true
                            } else if (!after.isNullOrEmpty() && after.startsWith(lastText)) {
                                ic.deleteSurroundingText(0, lastText.length)
                                ic.commitText(edited, 1)
                                replaced = true
                            } else if (before != null && after != null) {
                                // Attempt to find last occurrence in the surrounding context and move selection
                                val combined = before + after
                                val pos = combined.lastIndexOf(lastText)
                                if (pos >= 0) {
                                    val end = pos + lastText.length
                                    try { ic.setSelection(end, end) } catch (_: Throwable) { }
                                    // Recompute relative to the new cursor: ensure deletion still safe
                                    val before2 = try { ic.getTextBeforeCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
                                    if (!before2.isNullOrEmpty() && before2.endsWith(lastText)) {
                                        ic.deleteSurroundingText(lastText.length, 0)
                                        ic.commitText(edited, 1)
                                        replaced = true
                                    }
                                }
                            }
                        }
                        if (!replaced) {
                            // Fallback: do nothing but inform user for safety
                            txtStatus?.text = s(R.string.status_last_asr_not_found)
                            ic.finishComposingText()
                            ic.endBatchEdit()
                            vibrateTick()
                            currentSessionKind = null
                            aiEditState = null
                            committedStableLen = 0
                            goIdleWithTimingHint()
                            return@launch
                        }
                        // Update last ASR record to the new edited text for future edits
                        lastAsrCommitText = edited
                    }
                    ic.finishComposingText()
                    ic.endBatchEdit()
                } catch (_: Throwable) { }
                vibrateTick()
                currentSessionKind = null
                aiEditState = null
                committedStableLen = 0
                lastPostprocCommit = null
                goIdleWithTimingHint()
            } else if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                // 可选：在等待 AI 后处理时是否使用合成文本展示（带下划线）
                if (!prefs.disableComposingUnderline) {
                    currentInputConnection?.setComposingText(text, 1)
                }
                txtStatus?.text = s(R.string.status_ai_processing)
                // 同步状态到空格键（用户已回到 QWERTY 时可见）
                showSpaceStatus(s(R.string.status_ai_processing))
                val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                val processed = try {
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (_: Throwable) {
                    raw
                }
                // 如果开启去除句尾标点，对LLM后处理结果也执行一次修剪，避免模型重新补回标点导致设置失效
                val finalProcessed = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(processed) else processed
                val ic = currentInputConnection
                if (prefs.disableComposingUnderline) {
                    ic?.commitText(finalProcessed, 1)
                } else {
                    ic?.setComposingText(finalProcessed, 1)
                    ic?.finishComposingText()
                }
                // Record this commit so user can swipe-down on backspace to revert to raw
                lastPostprocCommit = if (finalProcessed.isNotEmpty() && finalProcessed != raw) PostprocCommit(finalProcessed, raw) else null
                vibrateTick()
                committedStableLen = 0
                // Track last ASR commit as what we actually inserted
                lastAsrCommitText = finalProcessed
                // 统计：累加本次识别最终提交的字数（AI编辑不计入，上面分支已排除）
                try { prefs.addAsrChars(finalProcessed.length) } catch (_: Throwable) { }
                goIdleWithTimingHint()
            } else {
                val ic = currentInputConnection
                val finalText = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                val trimDelta = text.length - finalText.length
                // If some trailing punctuation was already committed as stable before final,
                // delete it from the editor so the final result matches the trimmed output.
                if (prefs.trimFinalTrailingPunct && trimDelta > 0) {
                    val alreadyCommittedOverrun = (committedStableLen - finalText.length).coerceAtLeast(0)
                    if (alreadyCommittedOverrun > 0) {
                        ic?.deleteSurroundingText(alreadyCommittedOverrun, 0)
                        committedStableLen -= alreadyCommittedOverrun
                    }
                }
                val remainder = if (finalText.length > committedStableLen) finalText.substring(committedStableLen) else ""
                ic?.finishComposingText()
                if (remainder.isNotEmpty()) {
                    ic?.commitText(remainder, 1)
                }
                vibrateTick()
                committedStableLen = 0
                // Track last ASR commit as the full final text (not just remainder)
                lastAsrCommitText = finalText
                // 统计：累加本次识别最终提交的字数
                try { prefs.addAsrChars(finalText.length) } catch (_: Throwable) { }
                // Always return to idle after finalizing one utterance
                goIdleWithTimingHint()
                // Clear any previous postproc commit context
                lastPostprocCommit = null
            }
        }
    }

    override fun onError(message: String) {
        // Switch to main thread before touching views
        serviceScope.launch {
            txtStatus?.text = message
            vibrateTick()
            // 短暂在空格键上展示错误信息
            showSpaceStatusForMs(message)
            audioWaveView?.stop()
            audioWaveView?.visibility = View.GONE
        }
    }

    override fun onAudioLevel(level: Float) {
        val v = audioWaveView ?: return
        v.post { v.setLevel(level) }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        // Remove trailing ASCII and common CJK punctuation marks at end of utterance
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }

    // Attempt to revert last AI post-processed output to raw transcript.
    // Returns true if a change was applied.
    private fun revertLastPostprocToRaw(ic: android.view.inputmethod.InputConnection): Boolean {
        val commit = lastPostprocCommit ?: return false
        if (commit.processed.isEmpty()) return false
        val before = try { ic.getTextBeforeCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
        if (before.isNullOrEmpty()) return false
        if (!before.endsWith(commit.processed)) {
            // Only support immediate trailing replacement at cursor for simplicity and safety
            return false
        }
        return try {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(commit.processed.length, 0)
            ic.commitText(commit.raw, 1)
            ic.finishComposingText()
            ic.endBatchEdit()
            lastPostprocCommit = null
            txtStatus?.text = s(R.string.status_reverted_to_raw)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- 中文 26 键：拼音自动 LLM 转换预览 ----------
    private fun maybeStartPinyinAutoSuggest() {
        if (!isQwertyVisible) return
        if (langMode != LangMode.Chinese) return
        if (!prefs.hasLlmKeys()) return
        val interval = prefs.qwertyPinyinLlmIntervalSec
        if (interval <= 0f) { stopPinyinAutoSuggest(true); return }
        if (pinyinAutoJob?.isActive == true) return
        pinyinAutoJob = serviceScope.launch {
            while (isActive) {
                val raw = pinyinBuffer.toString().trim()
                if (raw.isEmpty()) {
                    clearPinyinPreviewComposition()
                    pendingPinyinSuggestion = null
                    pinyinAutoLastInput = ""
                } else if (!pinyinAutoRunning) {
                    val pinyin = when (prefs.pinyinMode) {
                        PinyinMode.Quanpin -> raw
                        PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(raw) } catch (_: Throwable) { raw }
                    }
                    if (pinyin != pinyinAutoLastInput) {
                        // 标记当前请求序号
                        pinyinAutoRunning = true
                        val reqSeq = ++pinyinAutoSeq
                        val out = try { postproc.pinyinToChinese(pinyin, prefs).ifBlank { pinyin } } catch (_: Throwable) { pinyin }
                        // 若期间拼音已变更或被清空/停止，丢弃结果
                        if (reqSeq == pinyinAutoSeq) {
                            // 再做一次原样校验：当前缓冲与请求时拼音一致才应用
                            val curRaw = pinyinBuffer.toString().trim()
                            val curPin = when (prefs.pinyinMode) {
                                PinyinMode.Quanpin -> curRaw
                                PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(curRaw) } catch (_: Throwable) { curRaw }
                            }
                            if (curPin == pinyin && curPin.isNotEmpty()) {
                                pendingPinyinSuggestion = out
                                // 始终设置合成预览，确保“自动转换”为用户可见
                                try { currentInputConnection?.setComposingText(out, 1) } catch (_: Throwable) { }
                                pinyinAutoLastInput = pinyin
                            }
                        }
                        pinyinAutoRunning = false
                    }
                }
                val delayMs = ((interval).coerceAtLeast(0f) * 1000f).toLong().coerceAtLeast(500L)
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    private fun stopPinyinAutoSuggest(clearComposition: Boolean) {
        try { pinyinAutoJob?.cancel() } catch (_: Throwable) { }
        pinyinAutoJob = null
        pinyinAutoRunning = false
        pinyinAutoLastInput = ""
        pendingPinyinSuggestion = null
        // 序号递增，确保在途结果无效
        pinyinAutoSeq++
        if (clearComposition) clearPinyinPreviewComposition()
    }

    private fun onPinyinBufferChanged() {
        pinyinAutoLastInput = ""
        // 拼音内容变化，序号递增，避免在途结果落地
        pinyinAutoSeq++
        if (pinyinBuffer.isEmpty()) {
            pendingPinyinSuggestion = null
            clearPinyinPreviewComposition()
        }
    }

    // ---------- 符号/标点：随中英模式切换 ----------
    private fun applySymbolsForCurrentLang() {
        applyQwertyInlinePunctuation()
        applySymbolsPanelKeys()
        applySwipeAltBadges()
    }

    private fun applyQwertyInlinePunctuation() {
        val root = qwertyLettersPanelView ?: qwertyPanelView ?: return
        fun traverse(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) traverse(v.getChildAt(i))
            } else if (v is TextView) {
                val tag = v.tag?.toString()
                if (tag == "punct_key") {
                    val cur = v.text?.toString() ?: return
                    val normalized = toAsciiFromAny(cur)
                    val newText = when (langMode) {
                        LangMode.Chinese -> toChineseFromAscii(normalized)
                        LangMode.English -> normalized
                    }
                    if (newText != cur) v.text = newText
                }
            }
        }
        traverse(root)
    }

    private fun applySymbolsPanelKeys() {
        val root = symbolsPanelView ?: return
        fun traverse(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) traverse(v.getChildAt(i))
            } else if (v is TextView) {
                val tag = v.tag?.toString()
                if (tag == "sym_key") {
                    val cur = v.text?.toString() ?: return
                    val normalized = toAsciiFromAny(cur)
                    val newText = when (langMode) {
                        LangMode.Chinese -> toChineseFromAscii(normalized)
                        LangMode.English -> normalized
                    }
                    if (newText != cur) v.text = newText
                }
            }
        }
        traverse(root)
    }

    private fun toChineseFromAscii(s: String): String {
        if (s.length != 1) return s
        return when (s[0]) {
            ',' -> "，"
            '.' -> "。"
            '?' -> "？"
            '!' -> "！"
            ':' -> "："
            ';' -> "；"
            '"' -> "“"
            '\'' -> "’"
            '(' -> "（"
            ')' -> "）"
            '[' -> "［"
            ']' -> "］"
            '{' -> "｛"
            '}' -> "｝"
            '@' -> "＠"
            '-' -> "－"
            '/' -> "／"
            '$' -> "￥"
            else -> s
        }
    }

    private fun toAsciiFromAny(s: String): String {
        if (s.length != 1) return s
        return when (s[0]) {
            '，' -> ","
            '。' -> "."
            '？' -> "?"
            '！' -> "!"
            '：' -> ":"
            '；' -> ";"
            '“', '”' -> "\""
            '‘', '’' -> "'"
            '（' -> "("
            '）' -> ")"
            '［', '【' -> "["
            '］', '】' -> "]"
            '｛' -> "{"
            '｝' -> "}"
            '＠' -> "@"
            '－' -> "-"
            '／' -> "/"
            '￥' -> "$"
            else -> s
        }
    }

    // 为字母键和逗号键应用右上角角标，提示上滑可输入的数字/符号
    private fun applySwipeAltBadges() {
        val root = qwertyLettersPanelView ?: qwertyPanelView ?: return
        fun colorWithAlpha(base: Int, alphaFraction: Float = 0.75f): Int {
            val a = ((Color.alpha(base) * alphaFraction).toInt()).coerceIn(0, 255)
            return (base and 0x00FFFFFF) or (a shl 24)
        }
        // 如果禁用上滑功能，移除所有角标/覆盖提示
        if (!prefs.qwertySwipeAltEnabled) {
            fun clear(v: View) {
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) clear(v.getChildAt(i))
                } else if (v is TextView) {
                    val tag = v.tag?.toString()
                    if (tag == "letter_key" || tag == "punct_key") v.foreground = null
                }
            }
            clear(root)
            return
        }
        fun traverse(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) traverse(v.getChildAt(i))
            } else if (v is TextView) {
                val tag = v.tag?.toString()
                if (tag == "letter_key") {
                    val base = v.text?.toString()?.firstOrNull()?.lowercaseChar()
                    val alt = base?.let { getSwipeAltForLetter(it) }
                    if (!alt.isNullOrEmpty()) {
                        val size = (v.textSize * 0.5f).coerceAtLeast(8f)
                        val c = colorWithAlpha(v.currentTextColor, 0.6f)
                        v.foreground = CornerBadgeDrawable(alt, size, c, paddingPx = 6f)
                    } else {
                        v.foreground = null
                    }
                } else if (tag == "punct_key") {
                    val alt = getSwipeAltForComma()
                    val size = (v.textSize * 0.6f).coerceAtLeast(8f)
                    val c = colorWithAlpha(v.currentTextColor, 0.6f)
                    v.foreground = CornerBadgeDrawable(alt, size, c, paddingPx = 6f)
                }
            }
        }
        traverse(root)
    }

    // ---------- QWERTY 上滑映射（symbolic_table.md） ----------
    private fun getSwipeAltForLetter(lower: Char): String? {
        return when (langMode) {
            LangMode.Chinese -> when (lower) {
                // 第一行：数字
                'q' -> "1"; 'w' -> "2"; 'e' -> "3"; 'r' -> "4"; 't' -> "5"
                'y' -> "6"; 'u' -> "7"; 'i' -> "8"; 'o' -> "9"; 'p' -> "0"
                // 第二行：符号
                'a' -> "-"; 's' -> "/"; 'd' -> "："; 'f' -> "；"; 'g' -> "（"; 'h' -> "）"
                'j' -> "～"; 'k' -> "“"; 'l' -> "”"
                // 第三行：符号
                'z' -> "@"; 'x' -> "."; 'c' -> "＃"; 'v' -> "、"; 'b' -> "？"; 'n' -> "！"; 'm' -> "……"
                else -> null
            }
            LangMode.English -> when (lower) {
                // 第一行：数字
                'q' -> "1"; 'w' -> "2"; 'e' -> "3"; 'r' -> "4"; 't' -> "5"
                'y' -> "6"; 'u' -> "7"; 'i' -> "8"; 'o' -> "9"; 'p' -> "0"
                // 第二行：符号
                'a' -> "-"; 's' -> "/"; 'd' -> ":"; 'f' -> ";"; 'g' -> "("; 'h' -> ")"
                'j' -> "~"; 'k' -> "'"; 'l' -> "\""
                // 第三行：符号
                'z' -> "@"; 'x' -> "-"; 'c' -> "#"; 'v' -> "&"; 'b' -> "?"; 'n' -> "!"; 'm' -> "…"
                else -> null
            }
        }
    }

    private fun getSwipeAltForComma(): String {
        return when (langMode) {
            LangMode.Chinese -> "。"
            LangMode.English -> "."
        }
    }
}
