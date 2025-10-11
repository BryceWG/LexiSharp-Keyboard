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
import android.widget.ImageButton
import android.widget.TextView
import android.view.KeyEvent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.text.InputType
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.google.android.material.color.MaterialColors

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private lateinit var prefs: Prefs
    private var rootView: View? = null
    private var asrPanelView: View? = null
    private var qwertyPanelView: View? = null
    private var symbolsPanelView: View? = null
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
    private var qwertyPinyin: TextView? = null
    private var qwertyLangSwitch: TextView? = null
    // 拼音缓冲（中文模式）
    private val pinyinBuffer = StringBuilder()
    private var pinyinBufferSnapshot: String? = null
    // 中文 26 键：拼音自动 LLM 转换与候选
    private var pinyinAutoJob: kotlinx.coroutines.Job? = null
    private var pinyinAutoLastInput: String = ""
    private var pinyinAutoRunning: Boolean = false
    private var pendingPinyinSuggestion: String? = null

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
    private var txtStatus: TextView? = null
    private var committedStableLen: Int = 0
    private var postproc: LlmPostProcessor = LlmPostProcessor()
    private var micLongPressStarted: Boolean = false
    private var micLongPressPending: Boolean = false
    private var micLongPressRunnable: Runnable? = null

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

    // Track latest AI post-processed commit to allow swipe-down revert to raw
    private data class PostprocCommit(val processed: String, val raw: String)
    private var lastPostprocCommit: PostprocCommit? = null

    // Track last committed ASR result so AI Edit (no selection) can modify it
    private var lastAsrCommitText: String? = null
    // 最近一次 ASR API 请求耗时（毫秒）
    private var lastRequestDurationMs: Long? = null

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
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        // Re-apply visibility in case user toggled setting while IME was backgrounded
        btnImeSwitcher?.visibility = if (prefs.showImeSwitcherButton) View.VISIBLE else View.GONE
        // Refresh custom punctuation labels
        applyPunctuationLabels()
        refreshPermissionUi()
        // Keep system toolbar/nav colors in sync with our panel background
        syncSystemBarsToKeyboardBackground(rootView)
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
        // IME context often uses a framework theme; wrap with our theme and Material dynamic colors.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val container = FrameLayout(dynamicContext)
        // Inflate ASR panel (existing)
        val asr = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, container, false)
        // Inflate 26-key letters panel (hidden by default)
        val qwerty = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_qwerty_view, container, false)
        qwerty.visibility = View.GONE
        val symbols = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_symbols_view, container, false)
        symbols.visibility = View.GONE
        container.addView(asr, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(qwerty, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(symbols, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootView = container
        asrPanelView = asr
        qwertyPanelView = qwerty
        symbolsPanelView = symbols

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
        txtStatus = asr.findViewById(R.id.txtStatus)
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
                            txtStatus?.text = getString(R.string.status_recognizing)
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
                txtStatus?.text = getString(R.string.hint_need_keys)
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                txtStatus?.text = getString(R.string.hint_need_llm_keys)
                it?.let { v -> maybeHapticKeyTap(v) }
                return@setOnClickListener
            }
            val running = asrEngine?.isRunning == true
            if (running && currentSessionKind == SessionKind.AiEdit) {
                // Stop capture -> will trigger onFinal once recognition finishes
                asrEngine?.stop()
                txtStatus?.text = getString(R.string.status_recognizing)
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
                txtStatus?.text = getString(R.string.status_idle)
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
                    txtStatus?.text = getString(R.string.status_last_asr_not_found)
                    return@setOnClickListener
                }
            }
            aiEditState = AiEditState(targetIsSelection, beforeLen, afterLen)
            currentSessionKind = SessionKind.AiEdit
            asrEngine = ensureEngineMatchesMode(asrEngine)
            updateUiListening()
            txtStatus?.text = getString(R.string.status_ai_edit_listening)
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
        btnPostproc?.apply {
            isSelected = prefs.postProcessEnabled
            alpha = if (prefs.postProcessEnabled) 1f else 0.45f
            setOnClickListener {
                val enabled = !prefs.postProcessEnabled
                prefs.postProcessEnabled = enabled
                isSelected = enabled
                alpha = if (enabled) 1f else 0.45f
                // Provide quick feedback via status line
                txtStatus?.text = if (enabled) getString(R.string.cd_postproc_toggle) + ": ON" else getString(R.string.cd_postproc_toggle) + ": OFF"
                // Swap ASR engine implementation when toggled (only if not running)
                if (asrEngine?.isRunning != true) {
                    asrEngine = buildEngineForCurrentMode()
                }
                maybeHapticKeyTap(this)
            }
        }

        // Apply visibility based on settings
        btnImeSwitcher?.visibility = if (prefs.showImeSwitcherButton) View.VISIBLE else View.GONE

        updateUiIdle()
        refreshPermissionUi()
        // Align system toolbar/navigation bar color to our surface color so they match
        // Setup qwerty panel actions
        setupQwertyPanel()
        setupSymbolsPanel()

        syncSystemBarsToKeyboardBackground(container)
        return container
    }

    private fun setupQwertyPanel() {
        val v = qwertyPanelView ?: return

        // Bind toolbar views
        qwertyHideTop = v.findViewById(R.id.qwertyHideTop)
        qwertyTextBuffer = v.findViewById(R.id.qwertyTextBuffer)
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
                // 直接将当前预览上屏（结束合成）
                try {
                    ic?.beginBatchEdit()
                    ic?.finishComposingText()
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
            txtStatus?.text = getString(R.string.status_pinyin_processing)
            serviceScope.launch {
                val out = try { postproc.pinyinToChinese(pinyinNow, prefs).ifBlank { pinyinNow } } catch (_: Throwable) { pinyinNow }
                try {
                    val ic2 = currentInputConnection
                    ic2?.beginBatchEdit()
                    ic2?.setComposingText(out, 1)
                    ic2?.finishComposingText()
                    ic2?.endBatchEdit()
                } catch (_: Throwable) { }
                clearPinyinBuffer()
                pendingPinyinSuggestion = null
                pinyinAutoLastInput = ""
                vibrateTick()
                txtStatus?.text = getString(R.string.status_idle)
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

        // Letters
        qwertyLetterKeys.clear()
        fun bindLetters(group: View) {
            if (group is ViewGroup) {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    val tag = child.tag?.toString()
                    if (tag == "letter_key" && child is TextView) {
                        qwertyLetterKeys.add(child)
                        child.setOnClickListener {
                            val s = child.text?.toString() ?: return@setOnClickListener
                            if (langMode == LangMode.Chinese) {
                                // 中文模式：字母进入拼音缓冲（以小写拼音为准）
                                appendToPinyinBuffer(s.lowercase())
                            } else {
                                // 英文模式：直接提交
                                commitTextCore(s, vibrate = false)
                            }
                            maybeHapticKeyTap(child)
                            if (shiftMode == ShiftMode.Once && langMode == LangMode.English) {
                                shiftMode = ShiftMode.Off
                                updateShiftUi()
                                applyLetterCase()
                            }
                        }
                    } else if (tag == "punct_key" && child is TextView) {
                        child.setOnClickListener {
                            val s = child.text?.toString() ?: return@setOnClickListener
                            commitTextCore(s, vibrate = false)
                            maybeHapticKeyTap(child)
                        }
                    } else if (child is ViewGroup) {
                        bindLetters(child)
                    }
                }
            }
        }
        bindLetters(v)
        applyLetterCase()

        // Space (tap to insert, long-press to return to ASR panel)
        v.findViewById<TextView?>(R.id.qwertySpace)?.apply {
            setOnClickListener {
                if (langMode == LangMode.Chinese) {
                    appendToPinyinBuffer(" ")
                } else {
                    commitTextCore(" ", vibrate = false)
                }
                maybeHapticKeyTap(this)
            }
            setOnLongClickListener {
                showAsrPanel()
                vibrateTick()
                true
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
                            clearAllTextWithSnapshot(ic)
                            clearPinyinBuffer()
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
                            if (backspaceSnapshotValid) {
                                restoreSnapshot(ic)
                                // restore pinyin buffer if any
                                val snap = pinyinBufferSnapshot
                                if (snap != null) {
                                    pinyinBuffer.clear()
                                    pinyinBuffer.append(snap)
                                    qwertyTextBuffer?.text = getDisplayPinyinText()
                                }
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
        v.findViewById<TextView?>(R.id.qwertyShift)?.apply {
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
                qwertyLangSwitch?.text = getString(R.string.label_chinese_mode)
                qwertyTextBuffer?.text = getDisplayPinyinText()
                maybeStartPinyinAutoSuggest()
            }
            LangMode.English -> {
                qwertyLangSwitch?.text = getString(R.string.label_english_mode)
                stopPinyinAutoSuggest(true)
            }
        }
        applyLetterCase()
    }

    private fun updateShiftUi() {
        val shift = qwertyPanelView?.findViewById<TextView?>(R.id.qwertyShift)
        when (shiftMode) {
            ShiftMode.Off -> { shift?.isSelected = false; shift?.isActivated = false }
            ShiftMode.Once -> { shift?.isSelected = true; shift?.isActivated = false }
            ShiftMode.Lock -> { shift?.isSelected = true; shift?.isActivated = true }
        }
    }

    // --- 中文模式：拼音缓冲工具 ---
    private fun getDisplayPinyinText(): String {
        val raw = pinyinBuffer.toString()
        return when (prefs.pinyinMode) {
            PinyinMode.Quanpin -> raw
            PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(raw) } catch (_: Throwable) { raw }
        }
    }

    private fun appendToPinyinBuffer(s: String) {
        if (s.isEmpty()) return
        pinyinBuffer.append(s)
        qwertyTextBuffer?.text = getDisplayPinyinText()
        onPinyinBufferChanged()
    }

    private fun popFromPinyinBuffer() : Boolean {
        if (pinyinBuffer.isEmpty()) return false
        pinyinBuffer.deleteCharAt(pinyinBuffer.length - 1)
        qwertyTextBuffer?.text = getDisplayPinyinText()
        onPinyinBufferChanged()
        return true
    }

    private fun clearPinyinBuffer() {
        if (pinyinBuffer.isEmpty()) return
        pinyinBuffer.clear()
        qwertyTextBuffer?.text = ""
        onPinyinBufferChanged()
    }

    private fun handleQwertyBackspaceTap() {
        if (langMode == LangMode.Chinese) {
            if (popFromPinyinBuffer()) return
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
            txtStatus?.text = getString(R.string.hint_pinyin_empty)
            vibrateTick()
            return
        }
        if (!prefs.hasLlmKeys()) {
            txtStatus?.text = getString(R.string.hint_need_llm_keys)
            vibrateTick()
            return
        }
        txtStatus?.text = getString(R.string.status_pinyin_processing)
        serviceScope.launch {
            val out = try {
                postproc.pinyinToChinese(pinyin, prefs).ifBlank { pinyin }
            } catch (_: Throwable) { pinyin }
            // 提交并清空缓冲
            currentInputConnection?.commitText(out, 1)
            clearPinyinBuffer()
            vibrateTick()
            txtStatus?.text = getString(R.string.status_idle)
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
        // Stop any ongoing ASR capture when switching away
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
        }
        updateUiIdle()
        asrPanelView?.visibility = View.GONE
        qwertyPanelView?.visibility = View.VISIBLE
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
        symbolsPanelView?.visibility = View.GONE
        asrPanelView?.visibility = View.VISIBLE
        isQwertyVisible = false
        stopPinyinAutoSuggest(true)
    }

    private fun showSymbolsPanel() {
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
        }
        updateUiIdle()
        asrPanelView?.visibility = View.GONE
        qwertyPanelView?.visibility = View.GONE
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
            txtStatus?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnMic?.isEnabled = false
            txtStatus?.text = getString(R.string.hint_need_keys)
        } else {
            btnMic?.isEnabled = true
            txtStatus?.text = getString(R.string.status_idle)
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

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                ElevenLabsFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                DashscopeFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            } else null
        }
    }

    private fun ensureEngineMatchesMode(current: StreamingAsrEngine?): StreamingAsrEngine? {
        if (!prefs.hasAsrKeys()) return null
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> when (current) {
                is VolcFileAsrEngine -> current
                else -> VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.SiliconFlow -> when (current) {
                is SiliconFlowFileAsrEngine -> current
                else -> SiliconFlowFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.ElevenLabs -> when (current) {
                is ElevenLabsFileAsrEngine -> current
                else -> ElevenLabsFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.OpenAI -> when (current) {
                is OpenAiFileAsrEngine -> current
                else -> OpenAiFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.DashScope -> when (current) {
                is DashscopeFileAsrEngine -> current
                else -> DashscopeFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.Gemini -> when (current) {
                is GeminiFileAsrEngine -> current
                else -> GeminiFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
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
            txtStatus?.text = getString(R.string.status_last_request_ms, ms)
            val v = rootView ?: txtStatus
            v?.postDelayed({
                if (asrEngine?.isRunning != true) {
                    txtStatus?.text = getString(R.string.status_idle)
                }
            }, 1500)
        } catch (_: Throwable) { }
    }

    private fun updateUiIdle() {
        txtStatus?.text = getString(R.string.status_idle)
        btnMic?.isSelected = false
        currentInputConnection?.finishComposingText()
    }

    private fun updateUiListening() {
        txtStatus?.text = getString(R.string.status_listening)
        btnMic?.isSelected = true
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
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
                txtStatus?.text = getString(R.string.switched_preset, preset.title)
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
                txtStatus?.text = getString(R.string.status_ai_editing)
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
                    txtStatus?.text = getString(R.string.hint_cannot_read_text)
                    vibrateTick()
                    currentSessionKind = null
                    aiEditState = null
                    committedStableLen = 0
                    goIdleWithTimingHint()
                    return@launch
                }
                if (edited.isBlank()) {
                    // LLM returned empty or failed — do not change
                    txtStatus?.text = getString(R.string.status_llm_empty_result)
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
                            txtStatus?.text = getString(R.string.status_last_asr_not_found)
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
                // Keep recognized text as composing while we post-process
                currentInputConnection?.setComposingText(text, 1)
                txtStatus?.text = getString(R.string.status_ai_processing)
                val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                val processed = try {
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (_: Throwable) {
                    raw
                }
                // 如果开启去除句尾标点，对LLM后处理结果也执行一次修剪，避免模型重新补回标点导致设置失效
                val finalProcessed = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(processed) else processed
                val ic = currentInputConnection
                ic?.setComposingText(finalProcessed, 1)
                ic?.finishComposingText()
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
        }
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
            txtStatus?.text = getString(R.string.status_reverted_to_raw)
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
                    try { currentInputConnection?.finishComposingText() } catch (_: Throwable) { }
                    pendingPinyinSuggestion = null
                    pinyinAutoLastInput = ""
                } else if (!pinyinAutoRunning) {
                    val pinyin = when (prefs.pinyinMode) {
                        PinyinMode.Quanpin -> raw
                        PinyinMode.Xiaohe -> try { XiaoheShuangpinConverter.convert(raw) } catch (_: Throwable) { raw }
                    }
                    if (pinyin != pinyinAutoLastInput) {
                        pinyinAutoRunning = true
                        val out = try { postproc.pinyinToChinese(pinyin, prefs).ifBlank { pinyin } } catch (_: Throwable) { pinyin }
                        pendingPinyinSuggestion = out
                        try { currentInputConnection?.setComposingText(out, 1) } catch (_: Throwable) { }
                        pinyinAutoLastInput = pinyin
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
        if (clearComposition) try { currentInputConnection?.finishComposingText() } catch (_: Throwable) { }
    }

    private fun onPinyinBufferChanged() {
        pinyinAutoLastInput = ""
        if (pinyinBuffer.isEmpty()) {
            pendingPinyinSuggestion = null
            try { currentInputConnection?.finishComposingText() } catch (_: Throwable) { }
        }
    }
}
