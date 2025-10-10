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
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener {

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
        serviceScope.cancel()
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
        btnEnter?.setOnClickListener { sendEnter() }
        btnAiEdit?.setOnClickListener {
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
            aiEditState = AiEditState(targetIsSelection, beforeLen, afterLen)
            currentSessionKind = SessionKind.AiEdit
            asrEngine = ensureEngineMatchesMode(asrEngine)
            updateUiListening()
            txtStatus?.text = getString(R.string.status_ai_edit_listening)
            asrEngine?.start()
        }
        // Backspace: tap to delete one; swipe up/left to clear all; swipe down to undo within gesture; long-press to repeat delete
        btnBackspace?.setOnClickListener { sendBackspace() }
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
        btnHide?.setOnClickListener { hideKeyboardPanel() }
        btnImeSwitcher?.setOnClickListener { showImePicker() }
        btnPromptPicker?.setOnClickListener { v ->
            showPromptPicker(v)
        }
        // Punctuation clicks
        btnPunct1?.setOnClickListener { commitText(prefs.punct1) }
        btnPunct2?.setOnClickListener { commitText(prefs.punct2) }
        btnPunct3?.setOnClickListener { commitText(prefs.punct3) }
        btnPunct4?.setOnClickListener { commitText(prefs.punct4) }
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
            }
        }

        // Apply visibility based on settings
        btnImeSwitcher?.visibility = if (prefs.showImeSwitcherButton) View.VISIBLE else View.GONE

        updateUiIdle()
        refreshPermissionUi()
        // Align system toolbar/navigation bar color to our surface color so they match
        syncSystemBarsToKeyboardBackground(view)
        return view
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

    private fun commitText(s: String) {
        try {
            currentInputConnection?.commitText(s, 1)
            vibrateTick()
        } catch (_: Throwable) { }
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
}
