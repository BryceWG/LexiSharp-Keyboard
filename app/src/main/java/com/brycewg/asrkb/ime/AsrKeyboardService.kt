package com.brycewg.asrkb.ime

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import android.widget.ImageButton
import android.widget.TextView
import android.view.KeyEvent
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
import com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.SonioxFileAsrEngine
import com.brycewg.asrkb.asr.SonioxStreamAsrEngine
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors
import com.brycewg.asrkb.LocaleHelper

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener, com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine.LocalModelLoadUi {
    companion object {
        const val ACTION_REFRESH_IME_UI = "com.brycewg.asrkb.action.REFRESH_IME_UI"
    }
    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private lateinit var prefs: Prefs
    private var rootView: View? = null

    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnAiEdit: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnPromptPicker: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
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

    // Global undo snapshot: capture before/after text prior to a mutating action
    private var undoSnapshotBefore: CharSequence? = null
    private var undoSnapshotAfter: CharSequence? = null
    private var undoSnapshotValid: Boolean = false

    // Track last committed ASR result so AI Edit (no selection) can modify it
    private var lastAsrCommitText: String? = null
    // 记录最近一次流式中间结果，用于合并最终结果
    private var lastPartialText: String? = null
    // 最近一次 ASR API 请求耗时（毫秒）
    private var lastRequestDurationMs: Long? = null

    private enum class SessionKind { AiEdit }
    private data class AiEditState(
        val targetIsSelection: Boolean,
        val beforeLen: Int,
        val afterLen: Int,
        val selected: String
    )
    private var currentSessionKind: SessionKind? = null
    private var aiEditState: AiEditState? = null
    private var prefsReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
        // 监听设置变化以即时刷新键盘 UI
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_REFRESH_IME_UI) {
                    val v = rootView
                    if (v != null) {
                        applyKeyboardHeightScale(v)
                        applyButtonSwapAccordingToPrefs(v)
                        v.requestLayout()
                    }
                }
            }
        }
        prefsReceiver = r
        try { registerReceiver(r, IntentFilter(ACTION_REFRESH_IME_UI)) } catch (_: Throwable) { }
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
        btnImeSwitcher?.visibility = View.VISIBLE
        // Refresh custom punctuation labels
        applyPunctuationLabels()
        refreshPermissionUi()
        // Keep system toolbar/nav colors in sync with our panel background
        syncSystemBarsToKeyboardBackground(rootView)
        // 重新按偏好应用键盘高度缩放
        applyKeyboardHeightScale(rootView)
        // 重新按偏好应用按钮位置交换
        applyButtonSwapAccordingToPrefs(rootView)

        // 若正在录音（点按切换开启），恢复预览为 composing
        if (asrEngine?.isRunning == true && currentSessionKind == null) {
            // 不再调用 requestShowSelf，避免“输入法一直出现”
            updateUiListening()
            val partial = lastPartialText
            if (!partial.isNullOrEmpty()) {
                val ic = currentInputConnection
                try {
                    val beforeAll = try { ic?.getTextBeforeCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
                    if (!beforeAll.isNullOrEmpty() && beforeAll.endsWith(partial)) {
                        // 退出前系统可能隐式 finishComposing 导致预览固化，这里将其删除后恢复为 composing
                        try { ic?.deleteSurroundingText(partial.length, 0) } catch (_: Throwable) { }
                    }
                    ic?.setComposingText(partial, 1)
                } catch (_: Throwable) { }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 若正在录音：在连接建立时同步一次预览为 composing
        if (asrEngine?.isRunning == true && currentSessionKind == null) {
            val partial = lastPartialText
            if (!partial.isNullOrEmpty()) {
                val ic = currentInputConnection
                try {
                    val beforeAll = try { ic?.getTextBeforeCursor(10000, 0)?.toString() } catch (_: Throwable) { null }
                    if (!beforeAll.isNullOrEmpty() && beforeAll.endsWith(partial)) {
                        try { ic?.deleteSurroundingText(partial.length, 0) } catch (_: Throwable) { }
                    }
                    ic?.setComposingText(partial, 1)
                } catch (_: Throwable) { }
            }
        }
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
        serviceScope.cancel()
        try { prefsReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) { }
        prefsReceiver = null
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // IME context often uses a framework theme; wrap with our theme and Material dynamic colors.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val view = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, null, false)
        rootView = view
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnPostproc = view.findViewById(R.id.btnPostproc)
        btnAiEdit = view.findViewById(R.id.btnAiEdit)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnPromptPicker = view.findViewById(R.id.btnPromptPicker)
        btnHide = view.findViewById(R.id.btnHide)
        btnImeSwitcher = view.findViewById(R.id.btnImeSwitcher)
        btnPunct1 = view.findViewById(R.id.btnPunct1)
        btnPunct2 = view.findViewById(R.id.btnPunct2)
        btnPunct3 = view.findViewById(R.id.btnPunct3)
        btnPunct4 = view.findViewById(R.id.btnPunct4)
        txtStatus = view.findViewById(R.id.txtStatus)

        // 初次创建时按偏好应用按钮位置
        applyButtonSwapAccordingToPrefs(view)

        // 统一绑定：根据偏好在事件时分支，免去重开键盘
        btnMic?.setOnClickListener { v ->
            if (!prefs.micTapToggleEnabled) return@setOnClickListener
            performKeyHaptic(v)
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            // 本地 SenseVoice：若已缓存加载则跳过文件检查；否则检查模型目录
            if (prefs.asrVendor == AsrVendor.SenseVoice) {
                val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
                if (!prepared) {
                    val base = try { getExternalFilesDir(null) } catch (_: Throwable) { null } ?: filesDir
                    val probeRoot = java.io.File(base, "sensevoice")
                    val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
                    val variantDir = if (variant == "small-full") java.io.File(probeRoot, "small-full") else java.io.File(probeRoot, "small-int8")
                    val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir) ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                    if (found == null) {
                        txtStatus?.text = getString(R.string.error_sensevoice_model_missing)
                        return@setOnClickListener
                    }
                }
            }
            asrEngine = ensureEngineMatchesMode(asrEngine) ?: buildEngineForCurrentMode()
            val running = asrEngine?.isRunning == true
            if (running) {
                asrEngine?.stop()
                if (!prefs.postProcessEnabled) {
                    updateUiIdle()
                } else {
                    txtStatus?.text = getString(R.string.status_recognizing)
                }
            } else {
                currentSessionKind = null
                aiEditState = null
                lastPartialText = null
                committedStableLen = 0
                updateUiListening()
                asrEngine?.start()
            }
        }

        btnMic?.setOnTouchListener { v, event ->
            // 长按模式：处理；点按模式：放行让 onClick 处理
            if (prefs.micTapToggleEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    if (!hasRecordAudioPermission()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    if (!prefs.hasAsrKeys()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    if (prefs.asrVendor == AsrVendor.SenseVoice) {
                        val prepared = try { com.brycewg.asrkb.asr.isSenseVoicePrepared() } catch (_: Throwable) { false }
                        if (!prepared) {
                            val base = try { getExternalFilesDir(null) } catch (_: Throwable) { null } ?: filesDir
                            val probeRoot = java.io.File(base, "sensevoice")
                            val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
                            val variantDir = if (variant == "small-full") java.io.File(probeRoot, "small-full") else java.io.File(probeRoot, "small-int8")
                            val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir) ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                            if (found == null) {
                                txtStatus?.text = getString(R.string.error_sensevoice_model_missing)
                                v.performClick()
                                return@setOnTouchListener true
                            }
                        }
                    }
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
                        if (!prefs.postProcessEnabled) {
                            updateUiIdle()
                        } else {
                            txtStatus?.text = getString(R.string.status_recognizing)
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnSettings?.setOnClickListener { v ->
            performKeyHaptic(v)
            openSettings()
        }
        btnEnter?.setOnClickListener { v ->
            performKeyHaptic(v)
            sendEnter()
        }
        btnAiEdit?.setOnClickListener { v ->
            performKeyHaptic(v)
            // Tap-to-toggle: start/stop instruction capture for AI edit
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                txtStatus?.text = getString(R.string.hint_need_keys)
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                txtStatus?.text = getString(R.string.hint_need_llm_keys)
                return@setOnClickListener
            }
            val running = asrEngine?.isRunning == true
            if (running && currentSessionKind == SessionKind.AiEdit) {
                // Stop capture -> will trigger onFinal once recognition finishes
                asrEngine?.stop()
                txtStatus?.text = getString(R.string.status_recognizing)
                return@setOnClickListener
            }
            if (running) {
                // Engine currently in dictation; ignore to avoid conflicts
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
            aiEditState = AiEditState(targetIsSelection, beforeLen, afterLen,selected.toString())
            currentSessionKind = SessionKind.AiEdit
            asrEngine = ensureEngineMatchesMode(asrEngine)
            updateUiListening()
            txtStatus?.text = getString(R.string.status_ai_edit_listening)
            asrEngine?.start()
        }
        // Backspace: tap to delete one; swipe up/left to clear all; swipe down to undo within gesture; long-press to repeat delete
        btnBackspace?.setOnClickListener { v ->
            // 对于单击退格，提供一次轻触反馈
            performKeyHaptic(v)
            sendBackspace()
        }
        btnBackspace?.setOnTouchListener { v, event ->
            val ic = currentInputConnection
            if (ic == null) return@setOnTouchListener false
            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 手势开始即震动一次
                    performKeyHaptic(v)
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
                    // Swipe down: perform undo. If there is a recent AI post-process, revert it; otherwise general undo.
                    if (!backspaceClearedInGesture && dy >= slop) {
                        backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                        backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                        if (performUndo(ic)) {
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
        btnHide?.setOnClickListener { v -> performKeyHaptic(v); hideKeyboardPanel() }
        btnImeSwitcher?.setOnClickListener { v -> performKeyHaptic(v); showImePicker() }
        btnPromptPicker?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPicker(v)
        }
        // Punctuation clicks
        btnPunct1?.setOnClickListener { v -> performKeyHaptic(v); commitText(prefs.punct1) }
        btnPunct2?.setOnClickListener { v -> performKeyHaptic(v); commitText(prefs.punct2) }
        btnPunct3?.setOnClickListener { v -> performKeyHaptic(v); commitText(prefs.punct3) }
        btnPunct4?.setOnClickListener { v -> performKeyHaptic(v); commitText(prefs.punct4) }
        btnPostproc?.apply {
            isSelected = prefs.postProcessEnabled
            alpha = if (prefs.postProcessEnabled) 1f else 0.45f
            setOnClickListener { v ->
                performKeyHaptic(v)
                val enabled = !prefs.postProcessEnabled
                prefs.postProcessEnabled = enabled
                isSelected = enabled
                alpha = if (enabled) 1f else 0.45f
                // 本地化开关提示
                val state = if (enabled) getString(R.string.toggle_on) else getString(R.string.toggle_off)
                txtStatus?.text = getString(R.string.status_postproc, state)
                // Swap ASR engine implementation when toggled (only if not running)
                if (asrEngine?.isRunning != true) {
                    asrEngine = buildEngineForCurrentMode()
                }
            }
        }

        // Apply visibility based on settings
        btnImeSwitcher?.visibility = View.VISIBLE

        // 应用键盘高度缩放
        applyKeyboardHeightScale(view)

        updateUiIdle()
        refreshPermissionUi()
        // Align system toolbar/navigation bar color to our surface color so they match
        syncSystemBarsToKeyboardBackground(view)
        return view
    }

    private fun applyButtonSwapAccordingToPrefs(view: View?) {
        if (view == null) return
        val ai = btnAiEdit ?: return
        val ime = btnImeSwitcher ?: return
        val lpAi = ai.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        val lpIme = ime.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

        // 将 6dp 转换为像素，用于左右边距
        val sixPx = try {
            val d = view.resources.displayMetrics.density
            (6f * d + 0.5f).toInt()
        } catch (_: Throwable) { 0 }

        if (prefs.swapAiEditWithImeSwitcher) {
            // AI -> 紧邻“设置”右侧
            lpAi.startToStart = unset
            lpAi.endToEnd = unset
            lpAi.endToStart = unset
            lpAi.startToEnd = R.id.btnSettings
            lpAi.marginStart = sixPx
            lpAi.marginEnd = 0
            lpAi.horizontalBias = 0.5f
            ai.layoutParams = lpAi

            // IME 切换 -> 位于 AI 与 回车之间
            lpIme.startToStart = unset
            lpIme.endToEnd = unset
            lpIme.startToEnd = R.id.btnAiEdit
            lpIme.endToStart = R.id.btnEnter
            lpIme.marginStart = 0
            lpIme.marginEnd = sixPx
            lpIme.horizontalBias = 1.0f
            ime.layoutParams = lpIme
        } else {
            // 还原 XML 默认：IME 紧邻“设置”右侧；AI 位于 IME 与“回车”之间
            lpIme.startToStart = unset
            lpIme.endToEnd = unset
            lpIme.endToStart = unset
            lpIme.startToEnd = R.id.btnSettings
            lpIme.marginStart = sixPx
            lpIme.marginEnd = 0
            lpIme.horizontalBias = 0.5f
            ime.layoutParams = lpIme

            lpAi.startToStart = unset
            lpAi.endToEnd = unset
            lpAi.startToEnd = R.id.btnImeSwitcher
            lpAi.endToStart = R.id.btnEnter
            lpAi.marginStart = 0
            lpAi.marginEnd = sixPx
            lpAi.horizontalBias = 1.0f
            ai.layoutParams = lpAi
        }
    }

    private fun applyKeyboardHeightScale(view: View?) {
        if (view == null) return
        val tier = try { prefs.keyboardHeightTier } catch (_: Throwable) { 1 }
        val scale = when (tier) { 2 -> 1.15f; 3 -> 1.30f; else -> 1.0f }
        if (scale == 1.0f) return

        fun dp(v: Float): Int {
            val d = view.resources.displayMetrics.density
            return (v * d + 0.5f).toInt()
        }

        // 根容器 padding 垂直方向按比例
        try {
            val fl = view as? android.widget.FrameLayout
            if (fl != null) {
                val ps = fl.paddingStart
                val pe = fl.paddingEnd
                val pt = dp(8f * scale)
                val pb = dp(12f * scale)
                fl.setPaddingRelative(ps, pt, pe, pb)
            }
        } catch (_: Throwable) {}

        // 顶部行高度（默认 80dp）
        try {
            val topRow = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowTop)
            if (topRow != null) {
                val lp = topRow.layoutParams
                lp.height = dp(80f * scale)
                topRow.layoutParams = lp
            }
        } catch (_: Throwable) {}

        // 统一缩放小图标按钮（默认 40dp）
        fun scaleSquareButton(id: Int) {
            try {
                val v = view.findViewById<View>(id) ?: return
                val lp = v.layoutParams
                lp.width = dp(40f * scale)
                lp.height = dp(40f * scale)
                v.layoutParams = lp
            } catch (_: Throwable) {}
        }
        val ids40 = intArrayOf(
            R.id.btnHide, R.id.btnPostproc, R.id.btnBackspace, R.id.btnPromptPicker,
            R.id.btnSettings, R.id.btnImeSwitcher, R.id.btnEnter, R.id.btnAiEdit,
            R.id.btnPunct1, R.id.btnPunct2, R.id.btnPunct3, R.id.btnPunct4
        )
        ids40.forEach { scaleSquareButton(it) }

        // Mic 按钮自定义尺寸（默认 72dp）
        try {
            btnMic?.setCustomSize(dp(72f * scale))
        } catch (_: Throwable) {}

        // 状态文本左右边距（默认 90dp）
        try {
            val tv = view.findViewById<TextView>(R.id.txtStatus)
            val lp = tv?.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (lp != null) {
                lp.marginStart = dp(90f * scale)
                lp.marginEnd = dp(90f * scale)
                tv.layoutParams = lp
            }
        } catch (_: Throwable) {}
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
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                } else {
                    VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
                }
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
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                } else {
                    SonioxFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
                }
            } else null
            AsrVendor.SenseVoice -> {
                // 本地引擎无需鉴权；占位实现会在依赖缺失时给出提示
                SenseVoiceFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
        }
    }

    private fun ensureEngineMatchesMode(current: StreamingAsrEngine?): StreamingAsrEngine? {
        if (!prefs.hasAsrKeys()) return null
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> when (current) {
                is VolcFileAsrEngine -> if (!prefs.volcStreamingEnabled) current else VolcStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                is VolcStreamAsrEngine -> if (prefs.volcStreamingEnabled) current else VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
                else -> if (prefs.volcStreamingEnabled) VolcStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                        else VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
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
            AsrVendor.Soniox -> when (current) {
                is SonioxFileAsrEngine -> if (!prefs.sonioxStreamingEnabled) current else SonioxStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                is SonioxStreamAsrEngine -> if (prefs.sonioxStreamingEnabled) current else SonioxFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
                else -> if (prefs.sonioxStreamingEnabled) SonioxStreamAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
                        else SonioxFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
            }
            AsrVendor.SenseVoice -> when (current) {
                is SenseVoiceFileAsrEngine -> current
                else -> SenseVoiceFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService, this@AsrKeyboardService::onAsrRequestDuration)
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
        try {
            // 先结束任何悬浮的 composing，避免目标应用将退格当作“撤销整段组合文本”而把光标重置到开头
            ic.finishComposingText()
        } catch (_: Throwable) { }

        // 在执行变更前记录撤回快照（若尚未存在）
        saveUndoSnapshot(ic)

        // 若有选区，按退格语义应删除选区内容
        val selected = try { ic.getSelectedText(0) } catch (_: Throwable) { null }
        if (!selected.isNullOrEmpty()) {
            try {
                ic.commitText("", 1)
                return
            } catch (_: Throwable) { /* fall through */ }
        }

        // 对部分应用，使用硬件 DEL 事件能更稳定地保持光标位置
        try {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        } catch (_: Throwable) { }

        // 兜底：删除光标前一个字符
        try { ic.deleteSurroundingText(1, 0) } catch (_: Throwable) { }
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
            // 使用本次手势按下时的快照作为全局撤回点
            setUndoFromBackspaceSnapshot()
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

    // 本地模型加载阶段：将提示显示到键盘状态栏，而非 Toast
    override fun onLocalModelLoadStart() {
        try { rootView?.post { txtStatus?.text = getString(R.string.sv_loading_model) } } catch (_: Throwable) { }
    }
    override fun onLocalModelLoadDone() {
        try {
            rootView?.post {
                txtStatus?.text = getString(R.string.sv_model_ready)
                // 短暂展示后恢复为“正在聆听”状态，避免长期占据状态栏
                rootView?.postDelayed({
                    if (asrEngine?.isRunning == true) {
                        txtStatus?.text = getString(R.string.status_listening)
                    }
                }, 1200)
            }
        } catch (_: Throwable) { }
    }

    private fun applyPunctuationLabels() {
        btnPunct1?.text = prefs.punct1
        btnPunct2?.text = prefs.punct2
        btnPunct3?.text = prefs.punct3
        btnPunct4?.text = prefs.punct4
    }

    private fun commitText(s: String) {
        try {
            // 记录撤回快照（若尚未存在）
            currentInputConnection?.let { saveUndoSnapshot(it) }
            currentInputConnection?.commitText(s, 1)
        } catch (_: Throwable) { }
    }

    private fun vibrateTick() {
        // 将原“麦克风振动”偏好作为全局开关
        if (!prefs.micHapticEnabled) return
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
        } catch (_: Exception) {
        }
    }

    private fun performKeyHaptic(view: View?) {
        if (!prefs.micHapticEnabled) return
        try { view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
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
                        state.selected
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
                if (asrEngine?.isRunning == true) {
                    // 连续分段期间保持“正在聆听”，不打断录音观感
                    updateUiListening()
                } else {
                    goIdleWithTimingHint()
                }
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
                if (asrEngine?.isRunning == true) {
                    updateUiListening()
                } else {
                    goIdleWithTimingHint()
                }
            } else {
                val trimmedFinal = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                val presetReplacement = try {
                    prefs.findSpeechPresetReplacement(trimmedFinal)
                } catch (_: Throwable) {
                    null
                }
                if (prefs.postProcessEnabled && prefs.hasLlmKeys() && presetReplacement == null) {
                    // Keep recognized text as composing while we post-process
                    currentInputConnection?.setComposingText(text, 1)
                    txtStatus?.text = getString(R.string.status_ai_processing)
                    val raw = trimmedFinal
                    val processed = try {
                        postproc.process(raw, prefs).ifBlank { raw }
                    } catch (_: Throwable) {
                        raw
                    }
                    // �������ȥ����β��㣬��LLM�������Ҳִ��һ���޼�������ģ�����²��ر�㵼������ʧЧ
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
                    // ͳ�ƣ��ۼӱ���ʶ�������ύ��������AI�༭�����룬�����֧���ų���
                    try { prefs.addAsrChars(finalProcessed.length) } catch (_: Throwable) { }
                    goIdleWithTimingHint()
                } else {
                    val ic = currentInputConnection
                    val finalText = presetReplacement ?: trimmedFinal
                    // ������ʶ��Ϊ�գ�ֱ�ӷ�����ȷ��ʾ����������ʾ�ȴ�
                    if (finalText.isBlank()) {
                        txtStatus?.text = getString(R.string.asr_error_empty_result)
                        vibrateTick()
                        committedStableLen = 0
                        lastPartialText = null
                        lastPostprocCommit = null
                        goIdleWithTimingHint()
                        return@launch
                    }
                    val partial = lastPartialText
                    if (!partial.isNullOrEmpty()) {
                        // ������ʽ�м�����Ϊ composing
                        ic?.finishComposingText()
                        if (finalText.startsWith(partial)) {
                            val remainder = finalText.substring(partial.length)
                            if (remainder.isNotEmpty()) ic?.commitText(remainder, 1)
                        } else {
                            // �����������/��д���Ƴ�����ʾ���м�����ֱ��д������
                            ic?.deleteSurroundingText(partial.length, 0)
                            ic?.commitText(finalText, 1)
                        }
                    } else {
                        // ��ͳһ�����ļ�ʶ��·��
                        val trimDelta = text.length - trimmedFinal.length
                        if (prefs.trimFinalTrailingPunct && trimDelta > 0) {
                            val alreadyCommittedOverrun = (committedStableLen - finalText.length).coerceAtLeast(0)
                            if (alreadyCommittedOverrun > 0) {
                                ic?.deleteSurroundingText(alreadyCommittedOverrun, 0)
                                committedStableLen -= alreadyCommittedOverrun
                            }
                        }
                        val remainder = if (finalText.length > committedStableLen) finalText.substring(committedStableLen) else ""
                        ic?.finishComposingText()
                        if (remainder.isNotEmpty()) ic?.commitText(remainder, 1)
                    }
                    vibrateTick()
                    committedStableLen = 0
                    // Track last ASR commit as the full final text (not just remainder)
                    lastAsrCommitText = finalText
                    lastPartialText = null
                    // 统计：累加本次识别最终提交的字数
                    try { prefs.addAsrChars(finalText.length) } catch (_: Throwable) { }
                    // 分段期间保持“正在聆听”，否则回到空闲并显示耗时提示
                    if (asrEngine?.isRunning == true) {
                        updateUiListening()
                    } else {
                        goIdleWithTimingHint()
                    }
                    // Clear any previous postproc commit context
                    lastPostprocCommit = null
                }
            }
        }
    }

    override fun onPartial(text: String) {
        // 若引擎已停止（用户已松手），忽略后续中间结果，避免重复追加
        val running = asrEngine?.isRunning == true
        if (!running) return
        // 主线程更新 composing（实时预览）
        serviceScope.launch {
            val ic = currentInputConnection
            ic?.setComposingText(text, 1)
            lastPartialText = text
        }
    }

    override fun onError(message: String) {
        // Switch to main thread before touching views
        serviceScope.launch {
            val mapped = mapErrorToFriendlyMessage(message)
            txtStatus?.text = mapped ?: message
            vibrateTick()
        }
    }

    /**
     * 将底层错误字符串归类为用户可理解的提示文案（与悬浮球一致）。
     */
    private fun mapErrorToFriendlyMessage(raw: String): String? {
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)

        // 空结果/空音频
        try {
            val emptyHints = listOf(
                getString(R.string.error_asr_empty_result),
                getString(R.string.error_audio_empty),
                "empty asr result",
                "empty audio",
                "识别返回为空",
                "空音频"
            )
            if (emptyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_empty_result)
            }
        } catch (_: Throwable) { }

        // HTTP 状态码分类（401/403）
        try {
            val httpCode = Regex("HTTP\\s+(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (httpCode) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (_: Throwable) { }

        // 通用 code 提示（如：ASR Error 401）
        try {
            val code = Regex("(?:ASR\\s*Error|status|code)\\s*(\\d{3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when (code) {
                401 -> return getString(R.string.asr_error_auth_invalid)
                403 -> return getString(R.string.asr_error_auth_forbidden)
            }
        } catch (_: Throwable) { }

        // 录音权限
        try {
            val permHints = listOf(
                getString(R.string.error_record_permission_denied),
                getString(R.string.hint_need_permission),
                "record audio permission"
            )
            if (permHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_permission_denied)
            }
        } catch (_: Throwable) { }

        // 麦克风被占用/录音初始化失败
        try {
            val micBusyHints = listOf(
                getString(R.string.error_audio_init_failed),
                "audio recorder busy",
                "resource busy",
                "in use",
                "device busy"
            )
            if (micBusyHints.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
                return getString(R.string.asr_error_mic_in_use)
            }
        } catch (_: Throwable) { }

        // 握手失败（SSL/TLS、证书）
        if (lower.contains("handshake") || lower.contains("sslhandshakeexception") || lower.contains("trust anchor") || lower.contains("certificate")) {
            return getString(R.string.asr_error_network_handshake)
        }

        // 网络不可用/连接失败/超时
        if (
            lower.contains("unable to resolve host") ||
            lower.contains("no address associated") ||
            lower.contains("failed to connect") ||
            lower.contains("connect exception") ||
            lower.contains("network is unreachable") ||
            lower.contains("software caused connection abort") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        ) {
            return getString(R.string.asr_error_network_unavailable)
        }

        return null
    }

    override fun onStopped() {
        // 录音阶段结束：与手动停止的表现保持一致
        serviceScope.launch {
            if (!prefs.postProcessEnabled) {
                updateUiIdle()
            } else {
                txtStatus?.text = getString(R.string.status_recognizing)
                btnMic?.isSelected = false
            }
        }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        // Remove trailing ASCII and common CJK punctuation marks at end of utterance
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }

    // Global undo: prefer reverting AI post-processing when available; otherwise restore last mutation snapshot.
    private fun performUndo(ic: android.view.inputmethod.InputConnection): Boolean {
        // 1) 若可撤销最近一次 AI 后处理，优先执行
        if (revertLastPostprocToRaw(ic)) {
            return true
        }
        // 2) 否则尝试通用撤回快照
        if (!undoSnapshotValid) return false
        val before = undoSnapshotBefore?.toString() ?: return false
        val after = undoSnapshotAfter?.toString() ?: ""
        return try {
            ic.beginBatchEdit()
            // 清空当前上下文
            val currBeforeLen = try { ic.getTextBeforeCursor(10000, 0)?.length ?: 0 } catch (_: Throwable) { 0 }
            val currAfterLen = try { ic.getTextAfterCursor(10000, 0)?.length ?: 0 } catch (_: Throwable) { 0 }
            ic.deleteSurroundingText(currBeforeLen, currAfterLen)
            // 还原为撤回点
            ic.commitText(before + after, 1)
            val sel = before.length
            try { ic.setSelection(sel, sel) } catch (_: Throwable) { }
            ic.finishComposingText()
            ic.endBatchEdit()
            // 清除已使用的撤回快照
            undoSnapshotBefore = null
            undoSnapshotAfter = null
            undoSnapshotValid = false
            txtStatus?.text = getString(R.string.status_undone)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun saveUndoSnapshot(ic: android.view.inputmethod.InputConnection) {
        if (undoSnapshotValid) return
        try {
            val before = ic.getTextBeforeCursor(10000, 0)
            val after = ic.getTextAfterCursor(10000, 0)
            undoSnapshotBefore = before
            undoSnapshotAfter = after
            undoSnapshotValid = before != null && after != null
        } catch (_: Throwable) {
            undoSnapshotBefore = null
            undoSnapshotAfter = null
            undoSnapshotValid = false
        }
    }

    private fun setUndoFromBackspaceSnapshot() {
        undoSnapshotBefore = backspaceSnapshotBefore
        undoSnapshotAfter = backspaceSnapshotAfter
        undoSnapshotValid = backspaceSnapshotValid
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
}
