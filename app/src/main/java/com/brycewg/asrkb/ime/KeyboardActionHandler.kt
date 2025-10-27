package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 键盘动作处理器：作为控制器/ViewModel 管理键盘的核心状态和业务逻辑
 *
 * 职责：
 * - 管理键盘状态机（使用 KeyboardState）
 * - 处理所有用户操作（麦克风、AI编辑、后处理等）
 * - 协调各个组件（AsrSessionManager, InputConnectionHelper, LlmPostProcessor）
 * - 处理 ASR 回调并触发状态转换
 * - 管理会话上下文（撤销快照、最后提交的文本等）
 * - 触发 UI 更新
 */
class KeyboardActionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val inputHelper: InputConnectionHelper,
    private val llmPostProcessor: LlmPostProcessor
) : AsrSessionManager.Listener {

    companion object {
        private const val TAG = "KeyboardActionHandler"
    }

    // 回调接口：通知 UI 更新
    interface UiListener {
        fun onStateChanged(state: KeyboardState)
        fun onStatusMessage(message: String)
        fun onVibrate()
        fun onShowClipboardPreview(preview: ClipboardPreview)
        fun onHideClipboardPreview()
        fun onShowRetryChip(label: String)
        fun onHideRetryChip()
    }

    private var uiListener: UiListener? = null

    // 当前键盘状态
    private var currentState: KeyboardState = KeyboardState.Idle

    // 会话上下文
    private var sessionContext = KeyboardSessionContext()

    // 全局撤销快照
    private var undoSnapshot: UndoSnapshot? = null

    // Processing 阶段兜底定时器（防止最终结果长时间未回调导致无法再次开始）
    private var processingTimeoutJob: Job? = null
    // 强制停止标记：用于忽略上一会话迟到的 onFinal/onStopped
    private var dropPendingFinal: Boolean = false
    // 操作序列号：用于取消在途处理（强制停止/新会话开始都会递增）
    private var opSeq: Long = 0L

    private fun scheduleProcessingTimeout() {
        try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
        processingTimeoutJob = scope.launch {
            delay(8000)
            // 若仍处于 Processing，则回到 Idle
            if (currentState is KeyboardState.Processing) {
                transitionToIdle()
            }
        }
    }

    fun setUiListener(listener: UiListener) {
        uiListener = listener
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): KeyboardState = currentState

    /**
     * 处理麦克风点击（点按切换模式）
     */
    fun handleMicTapToggle() {
        when (currentState) {
            is KeyboardState.Idle -> {
                // 开始录音
                startNormalListening()
            }
            is KeyboardState.Listening -> {
                // 停止录音：统一进入 Processing，显示“识别中”直到最终结果（即使未开启后处理）
                asrManager.stopRecording()
                transitionToState(KeyboardState.Processing)
                scheduleProcessingTimeout()
                uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
            }
            is KeyboardState.Processing -> {
                // 强制停止：立即回到 Idle，并忽略本会话迟到的 onFinal/onStopped
                try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
                processingTimeoutJob = null
                dropPendingFinal = true
                transitionToIdle(keepMessage = true)
                uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
            }
            else -> {
                // 其他状态忽略
                Log.w(TAG, "handleMicTapToggle: ignored in state $currentState")
            }
        }
    }

    /**
     * 处理麦克风按下（长按模式）
     */
    fun handleMicPressDown() {
        when (currentState) {
            is KeyboardState.Idle -> startNormalListening()
            is KeyboardState.Processing -> {
                // 强制停止（长按场景）：回到 Idle 并忽略迟到回调
                try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
                processingTimeoutJob = null
                dropPendingFinal = true
                transitionToIdle(keepMessage = true)
                uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
            }
            else -> {
                Log.w(TAG, "handleMicPressDown: ignored in state $currentState")
            }
        }
    }

    /**
     * 处理麦克风松开（长按模式）
     */
    fun handleMicPressUp() {
        handleMicPressUp(false)
    }

    /**
     * 处理麦克风松开（长按模式，可选：最终结果后自动回车）
     */
    fun handleMicPressUp(autoEnterAfterFinal: Boolean) {
        autoEnterOnce = autoEnterAfterFinal
        if (asrManager.isRunning()) {
            asrManager.stopRecording()
            // 进入处理阶段（无论是否开启后处理）
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout()
            uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
        }
    }

    /**
     * 处理 AI 编辑按钮点击
     */
    fun handleAiEditClick(ic: InputConnection?) {
        if (ic == null) {
            uiListener?.onStatusMessage(context.getString(R.string.status_idle))
            return
        }

        // 如果正在 AI 编辑录音，停止录音
        if (currentState is KeyboardState.AiEditListening) {
            asrManager.stopRecording()
            uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
            return
        }

        // 如果正在普通录音，忽略
        if (asrManager.isRunning()) {
            return
        }

        // 准备 AI 编辑
        val selected = inputHelper.getSelectedText(ic, 0)
        val targetIsSelection = !selected.isNullOrEmpty()
        val targetText = if (targetIsSelection) {
            selected.toString()
        } else {
            // 无选区：根据偏好选择来源（上次识别结果 或 整个输入框文本）
            if (prefs.aiEditDefaultToLastAsr) {
                val lastText = sessionContext.lastAsrCommitText
                if (lastText.isNullOrEmpty()) {
                    uiListener?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                    return
                }
                lastText
            } else {
                val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                val all = before + after
                if (all.isEmpty()) {
                    uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                    return
                }
                all
            }
        }

        // 开始 AI 编辑录音
        val state = KeyboardState.AiEditListening(targetIsSelection, targetText)
        transitionToState(state)
        asrManager.startRecording(state)
        uiListener?.onStatusMessage(context.getString(R.string.status_ai_edit_listening))
    }

    /**
     * 处理后处理开关切换
     */
    fun handlePostprocessToggle() {
        val enabled = !prefs.postProcessEnabled
        prefs.postProcessEnabled = enabled

        // 切换引擎实现（仅在空闲时）
        if (currentState is KeyboardState.Idle) {
            asrManager.rebuildEngine()
        }

        val state = if (enabled) context.getString(R.string.toggle_on) else context.getString(R.string.toggle_off)
        uiListener?.onStatusMessage(context.getString(R.string.status_postproc, state))
    }

    /**
     * 处理全局撤销（优先撤销 AI 后处理，否则恢复撤销快照）
     */
    fun handleUndo(ic: InputConnection?): Boolean {
        if (ic == null) return false

        // 1) 优先撤销最近一次 AI 后处理
        val postprocCommit = sessionContext.lastPostprocCommit
        if (postprocCommit != null && postprocCommit.processed.isNotEmpty()) {
            val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString()
            if (!before.isNullOrEmpty() && before.endsWith(postprocCommit.processed)) {
                if (inputHelper.replaceText(ic, postprocCommit.processed, postprocCommit.raw)) {
                    sessionContext = sessionContext.copy(lastPostprocCommit = null)
                    uiListener?.onStatusMessage(context.getString(R.string.status_reverted_to_raw))
                    return true
                }
            }
        }

        // 2) 否则恢复全局撤销快照
        val snapshot = undoSnapshot
        if (snapshot != null) {
            if (inputHelper.restoreSnapshot(ic, snapshot)) {
                undoSnapshot = null
                uiListener?.onStatusMessage(context.getString(R.string.status_undone))
                return true
            }
        }

        return false
    }

    /**
     * 保存撤销快照（在执行变更操作前调用）
     */
    fun saveUndoSnapshot(ic: InputConnection?) {
        if (ic == null || undoSnapshot != null) return
        undoSnapshot = inputHelper.captureUndoSnapshot(ic)
    }

    /**
     * 提交文本（用于标点按钮等）
     */
    fun commitText(ic: InputConnection?, text: String) {
        if (ic == null) return
        saveUndoSnapshot(ic)
        inputHelper.commitText(ic, text)
    }

    /**
     * 使用当前激活的 Prompt 对文本进行处理：优先处理选区，否则处理整个输入框文本。
     * 成功则用返回结果替换（保留撤销快照）。
     */
    fun applyActivePromptToSelectionOrAll(ic: InputConnection?) {
        if (ic == null) return
        scope.launch {
            // LLM 参数可用性校验
            if (!prefs.hasLlmKeys()) {
                uiListener?.onStatusMessage(context.getString(R.string.hint_need_llm_keys))
                return@launch
            }
            // 读取目标文本：优先选区，否则整个文本
            val selected = try { inputHelper.getSelectedText(ic, 0)?.toString() } catch (_: Throwable) { null }
            val targetText: String
            val mode: Int // 0=selection, 1=lastAsr, 2=entire
            if (!selected.isNullOrEmpty()) {
                targetText = selected
                mode = 0
            } else if (prefs.aiEditDefaultToLastAsr) {
                val last = sessionContext.lastAsrCommitText
                if (!last.isNullOrEmpty()) {
                    targetText = last
                    mode = 1
                } else {
                    val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                    val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                    val all = before + after
                    if (all.isEmpty()) {
                        uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                        return@launch
                    }
                    targetText = all
                    mode = 2
                }
            } else {
                val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                val all = before + after
                if (all.isEmpty()) {
                    uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                    return@launch
                }
                targetText = all
                mode = 2
            }

            // 显示处理状态
            uiListener?.onStatusMessage(context.getString(R.string.status_ai_processing))

            // 执行 LLM 处理
            val res = try {
                llmPostProcessor.processWithStatus(targetText, prefs)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "applyActivePromptToSelectionOrAll failed", t)
                null
            }

            val out = res?.text ?: targetText
            val ok = res?.ok == true

            // 应用结果（带撤销）
            saveUndoSnapshot(ic)
            when (mode) {
                0 -> {
                    // 在选区上直接提交将覆盖选区
                    inputHelper.commitText(ic, out)
                }
                1 -> {
                    // 替换最近一次 ASR 提交的文本
                    val replaced = inputHelper.replaceText(ic, targetText, out)
                    if (!replaced) {
                        uiListener?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                        return@launch
                    }
                    // 更新 lastAsrCommitText 为新文本
                    sessionContext = sessionContext.copy(lastAsrCommitText = out)
                }
                else -> {
                    // 清空并写入全文
                    val snapshot = inputHelper.captureUndoSnapshot(ic)
                    inputHelper.clearAllText(ic, snapshot)
                    inputHelper.commitText(ic, out)
                }
            }

            uiListener?.onVibrate()
            // 记录“后处理提交”，便于全局撤销优先恢复原文
            if (ok && out.isNotEmpty() && out != targetText) {
                sessionContext = sessionContext.copy(
                    lastPostprocCommit = PostprocCommit(processed = out, raw = targetText)
                )
            } else {
                sessionContext = sessionContext.copy(lastPostprocCommit = null)
            }

            if (!ok) {
                uiListener?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            } else {
                uiListener?.onStatusMessage(context.getString(R.string.status_idle))
            }
        }
    }

    /**
     * 使用指定的 Prompt 内容对文本进行处理：优先处理选区，否则处理整个输入框文本。
     * 不修改全局激活的 Prompt；成功则用返回结果替换（保留撤销快照）。
     */
    fun applyPromptToSelectionOrAll(ic: InputConnection?, promptContent: String) {
        if (ic == null) return
        scope.launch {
            if (!prefs.hasLlmKeys()) {
                uiListener?.onStatusMessage(context.getString(R.string.hint_need_llm_keys))
                return@launch
            }

            val selected = try { inputHelper.getSelectedText(ic, 0)?.toString() } catch (_: Throwable) { null }
            val targetText: String
            val mode: Int // 0=selection, 1=lastAsr, 2=entire
            if (!selected.isNullOrEmpty()) {
                targetText = selected
                mode = 0
            } else if (prefs.aiEditDefaultToLastAsr) {
                val last = sessionContext.lastAsrCommitText
                if (!last.isNullOrEmpty()) {
                    targetText = last
                    mode = 1
                } else {
                    val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                    val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                    val all = before + after
                    if (all.isEmpty()) {
                        uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                        return@launch
                    }
                    targetText = all
                    mode = 2
                }
            } else {
                val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
                val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
                val all = before + after
                if (all.isEmpty()) {
                    uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
                    return@launch
                }
                targetText = all
                mode = 2
            }

            uiListener?.onStatusMessage(context.getString(R.string.status_ai_processing))

            val res = try {
                llmPostProcessor.processWithStatus(targetText, prefs, promptOverride = promptContent)
            } catch (t: Throwable) {
                Log.e(TAG, "applyPromptToSelectionOrAll failed", t)
                null
            }

            val out = res?.text ?: targetText
            val ok = res?.ok == true

            saveUndoSnapshot(ic)
            when (mode) {
                0 -> inputHelper.commitText(ic, out)
                1 -> {
                    val replaced = inputHelper.replaceText(ic, targetText, out)
                    if (!replaced) {
                        uiListener?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                        return@launch
                    }
                    sessionContext = sessionContext.copy(lastAsrCommitText = out)
                }
                else -> {
                    val snapshot = inputHelper.captureUndoSnapshot(ic)
                    inputHelper.clearAllText(ic, snapshot)
                    inputHelper.commitText(ic, out)
                }
            }

            uiListener?.onVibrate()
            if (ok && out.isNotEmpty() && out != targetText) {
                sessionContext = sessionContext.copy(
                    lastPostprocCommit = PostprocCommit(processed = out, raw = targetText)
                )
            } else {
                sessionContext = sessionContext.copy(lastPostprocCommit = null)
            }

            if (!ok) {
                uiListener?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            } else {
                uiListener?.onStatusMessage(context.getString(R.string.status_idle))
            }
        }
    }

    /**
     * 显示剪贴板预览
     */
    fun showClipboardPreview(fullText: String) {
        val snippet = if (fullText.length <= 10) fullText else (fullText.substring(0, 10) + "…")
        val preview = ClipboardPreview(fullText, snippet)
        sessionContext = sessionContext.copy(clipboardPreview = preview)
        uiListener?.onShowClipboardPreview(preview)
    }

    /**
     * 处理剪贴板预览点击（粘贴）
     */
    fun handleClipboardPreviewClick(ic: InputConnection?) {
        if (ic == null) return
        val text = sessionContext.clipboardPreview?.fullText
        if (!text.isNullOrEmpty()) {
            inputHelper.finishComposingText(ic)
            saveUndoSnapshot(ic)
            inputHelper.commitText(ic, text)
        }
        hideClipboardPreview()
    }

    /**
     * 隐藏剪贴板预览
     */
    fun hideClipboardPreview() {
        sessionContext = sessionContext.copy(clipboardPreview = null)
        uiListener?.onHideClipboardPreview()
    }

    /**
     * 恢复中间结果为 composing（键盘重新显示时）
     */
    fun restorePartialAsComposing(ic: InputConnection?) {
        if (ic == null) return
        val state = currentState
        if (state is KeyboardState.Listening) {
            val partial = state.partialText
            if (!partial.isNullOrEmpty()) {
                // 检查并删除已固化的预览文本
                val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString()
                if (!before.isNullOrEmpty() && before.endsWith(partial)) {
                    inputHelper.deleteSurroundingText(ic, partial.length, 0)
                }
                inputHelper.setComposingText(ic, partial)
            }
        }
    }

    // ========== AsrSessionManager.Listener 实现 ==========

    override fun onAsrFinal(text: String, currentState: KeyboardState) {
        scope.launch {
            // 若强制停止，忽略迟到的 onFinal
            if (dropPendingFinal) {
                dropPendingFinal = false
                return@launch
            }
            // 捕获当前操作序列，用于在提交前判定是否已被新的操作序列取消
            val seq = opSeq
            // 若已启动新一轮录音（当前仍为 Listening 且引擎在运行），忽略旧会话迟到的 onFinal
            val stateNow = this@KeyboardActionHandler.currentState
            if (asrManager.isRunning() && stateNow is KeyboardState.Listening) return@launch
            when (currentState) {
                is KeyboardState.AiEditListening -> {
                    handleAiEditFinal(text, currentState, seq)
                }
                is KeyboardState.Listening -> {
                    handleNormalDictationFinal(text, currentState, seq)
                }
                is KeyboardState.Processing, is KeyboardState.Idle -> {
                    // 允许在 Idle/Processing 状态接收最终结果（例如提前切回 Idle 的路径）
                    val synthetic = KeyboardState.Listening()
                    handleNormalDictationFinal(text, synthetic, seq)
                }
                else -> {
                    // 兜底按普通听写处理
                    val synthetic = KeyboardState.Listening()
                    handleNormalDictationFinal(text, synthetic, seq)
                }
            }
        }
    }

    override fun onAsrPartial(text: String) {
        scope.launch {
            when (val state = currentState) {
                is KeyboardState.Listening -> {
                    // 更新中间结果
                    val newState = state.copy(partialText = text)
                    transitionToState(newState)
                }
                is KeyboardState.AiEditListening -> {
                    // 更新 AI 编辑指令
                    val newState = state.copy(instruction = text)
                    transitionToState(newState)
                }
                else -> {
                    Log.w(TAG, "onAsrPartial: unexpected state $currentState")
                }
            }
        }
    }

    override fun onAsrError(message: String) {
        scope.launch {
            // 先切换到 Idle，再显示错误，避免被 Idle 文案覆盖
            transitionToIdle(keepMessage = true)
            uiListener?.onStatusMessage(message)
            uiListener?.onVibrate()
            try {
                if (shouldOfferRetry(message)) {
                    uiListener?.onShowRetryChip(context.getString(R.string.btn_retry))
                } else {
                    uiListener?.onHideRetryChip()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to evaluate/show retry chip", t)
            }
        }
    }

    override fun onAsrStopped() {
        scope.launch {
            // 若强制停止，忽略迟到的 onStopped
            if (dropPendingFinal) return@launch
            // 引擎已停止采集：统一进入 Processing，等待最终结果或兜底
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout()
            uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
        }
    }

    override fun onLocalModelLoadStart() {
        val resId = if (currentState is KeyboardState.Listening || currentState is KeyboardState.AiEditListening) {
            R.string.sv_loading_model_while_listening
        } else {
            R.string.sv_loading_model
        }
        uiListener?.onStatusMessage(context.getString(resId))
    }

    override fun onLocalModelLoadDone() {
        uiListener?.onStatusMessage(context.getString(R.string.sv_model_ready))
    }

    // ========== 私有方法：状态转换 ==========

    private fun transitionToState(newState: KeyboardState) {
        // 不在进入 Processing 时主动 finishComposing，保留预览供最终结果做差量合并
        currentState = newState
        // 仅在携带文本上下文的状态下同步到 AsrSessionManager，
        // 避免切到 Processing 后丢失 partialText 影响最终合并
        when (newState) {
            is KeyboardState.Listening,
            is KeyboardState.AiEditListening -> asrManager.setCurrentState(newState)
            else -> { /* keep previous contextual state in AsrSessionManager */ }
        }
        if (newState !is KeyboardState.Idle) {
            try { uiListener?.onHideRetryChip() } catch (_: Throwable) {}
        }
        uiListener?.onStateChanged(newState)
    }

    private fun transitionToIdle(keepMessage: Boolean = false) {
        // 新的显式归位：递增操作序列，取消在途处理
        opSeq++
        try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
        processingTimeoutJob = null
        autoEnterOnce = false
        transitionToState(KeyboardState.Idle)
        if (!keepMessage) {
            uiListener?.onStatusMessage(context.getString(R.string.status_idle))
        }
    }

    private fun startNormalListening() {
        // 开启新一轮录音：递增操作序列，取消在途处理
        opSeq++
        try { processingTimeoutJob?.cancel() } catch (_: Throwable) {}
        processingTimeoutJob = null
        dropPendingFinal = false
        autoEnterOnce = false
        try { uiListener?.onHideRetryChip() } catch (_: Throwable) {}
        val state = KeyboardState.Listening()
        transitionToState(state)
        asrManager.startRecording(state)
        uiListener?.onStatusMessage(context.getString(R.string.status_listening))
    }

    /**
     * 判断是否应提供“重试”入口（仅非流式 + 网络错误 + 有片段 + 非空结果错误）。
     */
    private fun shouldOfferRetry(message: String): Boolean {
        val engine = try { asrManager.getEngine() } catch (_: Throwable) { null }
        val isFileEngine = engine is com.brycewg.asrkb.asr.BaseFileAsrEngine
        if (!isFileEngine) return false

        val msgLower = message.lowercase()
        val isEmptyResult = ("为空" in message) || ("empty" in msgLower)
        if (isEmptyResult) return false

        val networkKeywords = arrayOf(
            "网络", "超时", "timeout", "timed out", "connect", "connection", "socket", "host", "unreachable", "rate", "too many requests"
        )
        val looksNetwork = networkKeywords.any { kw -> kw in message || kw in msgLower }
        if (!looksNetwork) return false

        return try { asrManager.canRetryLastFileRecognition() } catch (_: Throwable) { false }
    }

    /**
     * 处理“重试”点击：隐藏芯片，进入 Processing，并触发重试。
     */
    fun handleRetryClick() {
        try { uiListener?.onHideRetryChip() } catch (_: Throwable) {}
        transitionToState(KeyboardState.Processing)
        scheduleProcessingTimeout()
        uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
        val ok = try { asrManager.retryLastFileRecognition() } catch (t: Throwable) {
            Log.e(TAG, "retryLastFileRecognition threw", t)
            false
        }
        if (!ok) {
            transitionToIdle()
        }
    }

    // ========== 私有方法：处理最终识别结果 ==========

    private suspend fun handleNormalDictationFinal(text: String, state: KeyboardState.Listening, seq: Long) {
        val ic = getCurrentInputConnection() ?: return

        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            // AI 后处理流程
            handleDictationWithPostprocess(ic, text, seq)
        } else {
            // 无后处理流程
            handleDictationWithoutPostprocess(ic, text, state, seq)
        }
    }

    private suspend fun handleDictationWithPostprocess(
        ic: InputConnection,
        text: String,
        seq: Long
    ) {
        // 若已被取消，不再更新预览
        if (seq != opSeq) return
        // 显示识别文本为 composing
        inputHelper.setComposingText(ic, text)
        uiListener?.onStatusMessage(context.getString(R.string.status_ai_processing))

        // AI 后处理
        val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
        var postprocFailed = false
        val processed = try {
            val res = llmPostProcessor.processWithStatus(raw, prefs)
            postprocFailed = !res.ok
            if (!res.ok) {
                uiListener?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            }
            res.text.ifBlank { raw }
        } catch (e: Throwable) {
            Log.e(TAG, "LLM post-processing failed", e)
            postprocFailed = true
            uiListener?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            raw
        }

        // 如果开启去除句尾标点，对 LLM 结果也修剪一次
        val finalProcessed = if (prefs.trimFinalTrailingPunct) {
            trimTrailingPunctuation(processed)
        } else {
            processed
        }

        // 若已被取消，不再提交
        if (seq != opSeq) return
        // 提交最终文本
        inputHelper.setComposingText(ic, finalProcessed)
        inputHelper.finishComposingText(ic)

        // 若需要，最终结果后自动发送回车（仅一次）
        if (finalProcessed.isNotEmpty() && autoEnterOnce) {
            try { inputHelper.sendEnter(ic) } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after postprocess failed", t)
            }
            autoEnterOnce = false
        }

        // 记录后处理提交（用于撤销）
        if (finalProcessed.isNotEmpty() && finalProcessed != raw) {
            sessionContext = sessionContext.copy(
                lastPostprocCommit = PostprocCommit(finalProcessed, raw)
            )
        }

        // 更新会话上下文
        sessionContext = sessionContext.copy(lastAsrCommitText = finalProcessed)

        // 统计字数
        try {
            prefs.addAsrChars(finalProcessed.length)
            // 记录使用统计（IME）
            try {
                val audioMs = asrManager.popLastAudioMsForStats()
                prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, finalProcessed.length)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to record usage stats (with postprocess)", t)
            }
        } catch (_: Throwable) { }

        uiListener?.onVibrate()

        // 分段录音期间保持 Listening；
        // 否则：若后处理失败，直接回到 Idle 并保留错误提示；成功则显示耗时后回到 Idle。
        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening())
        } else {
            if (postprocFailed) {
                // 回到 Idle 后再次设置错误提示，避免被 Idle 文案覆盖
                transitionToIdle()
                uiListener?.onStatusMessage(context.getString(R.string.status_llm_failed_used_raw))
            } else {
                transitionToState(KeyboardState.Processing)
                scheduleProcessingTimeout()
                transitionToIdleWithTiming()
            }
        }
    }

    private suspend fun handleDictationWithoutPostprocess(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long
    ) {
        val trimmedFinal = if (prefs.trimFinalTrailingPunct) {
            trimTrailingPunctuation(text)
        } else {
            text
        }

        // 检查语音预设替换
        val presetReplacement = try {
            prefs.findSpeechPresetReplacement(trimmedFinal)
        } catch (_: Throwable) {
            null
        }

        val finalText = presetReplacement ?: trimmedFinal

        // 如果识别为空，直接返回
        if (finalText.isBlank()) {
            // 空结果：先切换到 Idle 再提示，避免被 Idle 文案覆盖
            transitionToIdle(keepMessage = true)
            uiListener?.onStatusMessage(context.getString(R.string.asr_error_empty_result))
            uiListener?.onVibrate()
            return
        }

        // 若已被取消，退出
        if (seq != opSeq) return
        // 提交文本
        val partial = state.partialText
        if (!partial.isNullOrEmpty()) {
            // 有中间结果：智能合并
            inputHelper.finishComposingText(ic)
            if (finalText.startsWith(partial)) {
                val remainder = finalText.substring(partial.length)
                if (remainder.isNotEmpty()) {
                    inputHelper.commitText(ic, remainder)
                }
            } else {
                // 标点/大小写可能变化，删除中间结果并重新提交
                inputHelper.deleteSurroundingText(ic, partial.length, 0)
                inputHelper.commitText(ic, finalText)
            }
        } else {
            // 无中间结果：直接提交
            val committedStableLen = state.committedStableLen
            val remainder = if (finalText.length > committedStableLen) {
                finalText.substring(committedStableLen)
            } else {
                ""
            }
            inputHelper.finishComposingText(ic)
            if (remainder.isNotEmpty()) {
                inputHelper.commitText(ic, remainder)
            }
        }

        // 更新会话上下文
        sessionContext = sessionContext.copy(
            lastAsrCommitText = finalText,
            lastPostprocCommit = null
        )

        // 若需要，最终结果后自动发送回车（仅一次）
        if (finalText.isNotEmpty() && autoEnterOnce) {
            try { inputHelper.sendEnter(ic) } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after final failed", t)
            }
            autoEnterOnce = false
        }

        // 统计字数
        try {
            prefs.addAsrChars(finalText.length)
            // 记录使用统计（IME）
            try {
                val audioMs = asrManager.popLastAudioMsForStats()
                prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, finalText.length)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to record usage stats (no postprocess)", t)
            }
        } catch (_: Throwable) { }

        uiListener?.onVibrate()

        // 分段录音期间保持 Listening；否则进入 Processing 并延时返回 Idle
        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening())
        } else {
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout()
            transitionToIdleWithTiming()
        }
    }

    private suspend fun handleAiEditFinal(text: String, state: KeyboardState.AiEditListening, seq: Long) {
        val ic = getCurrentInputConnection() ?: run {
            transitionToIdleWithTiming()
            return
        }

        uiListener?.onStatusMessage(context.getString(R.string.status_ai_editing))

        val instruction = if (prefs.trimFinalTrailingPunct) {
            trimTrailingPunctuation(text)
        } else {
            text
        }

        val original = state.targetText
        if (original.isBlank()) {
            uiListener?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
            uiListener?.onVibrate()
            transitionToIdleWithTiming()
            return
        }

        val (ok, edited) = try {
            val res = llmPostProcessor.editTextWithStatus(original, instruction, prefs)
            res.ok to res.text
        } catch (e: Throwable) {
            Log.e(TAG, "AI edit failed", e)
            false to ""
        }

        // 若已被取消，退出
        if (seq != opSeq) return
        if (!ok) {
            uiListener?.onVibrate()
            // 先归位到 Idle，再设置错误消息，确保不会被 Idle 覆盖
            transitionToIdle()
            uiListener?.onStatusMessage(context.getString(R.string.status_llm_edit_failed))
            return
        }
        if (edited.isBlank()) {
            uiListener?.onVibrate()
            transitionToIdle()
            uiListener?.onStatusMessage(context.getString(R.string.status_llm_empty_result))
            return
        }

        // 执行替换
        if (seq != opSeq) return
        if (state.targetIsSelection) {
            // 替换选中文本
            inputHelper.commitText(ic, edited)
        } else {
            // 替换最后一次 ASR 提交的文本
            if (inputHelper.replaceText(ic, original, edited)) {
                // 更新最后提交的文本为编辑后的结果
                sessionContext = sessionContext.copy(lastAsrCommitText = edited)
            } else {
                uiListener?.onStatusMessage(context.getString(R.string.status_last_asr_not_found))
                uiListener?.onVibrate()
                transitionToIdleWithTiming()
                return
            }
        }

        uiListener?.onVibrate()

        // AI 编辑不计入后处理提交，清除记录
        sessionContext = sessionContext.copy(lastPostprocCommit = null)

        // 回到 Idle 或继续 Listening
        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening())
        } else {
            transitionToIdleWithTiming()
        }
    }

    private fun transitionToIdleWithTiming() {
        val ms = asrManager.getLastRequestDuration()
        if (ms != null) {
            uiListener?.onStatusMessage(context.getString(R.string.status_last_request_ms, ms))
            // 延迟恢复到 Idle
            scope.launch {
                kotlinx.coroutines.delay(1500)
                if (currentState !is KeyboardState.Listening) {
                    transitionToIdle()
                }
            }
        } else {
            transitionToIdle()
        }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        return s.replace(Regex("[\\p{Punct}，。！？；、：]+$"), "")
    }

    /**
     * 获取当前输入连接（需要从外部注入）
     * 这是一个临时方案，实际应该通过参数传递
     */
    private var currentInputConnectionProvider: (() -> InputConnection?)? = null

    fun setInputConnectionProvider(provider: () -> InputConnection?) {
        currentInputConnectionProvider = provider
    }

    private fun getCurrentInputConnection(): InputConnection? {
        return currentInputConnectionProvider?.invoke()
    }

    // 会话一次性标记：最终结果提交后是否自动发送回车
    private var autoEnterOnce: Boolean = false
}
