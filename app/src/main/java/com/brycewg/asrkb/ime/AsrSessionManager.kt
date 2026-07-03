/**
 * IME 录音会话管理与 ASR 引擎生命周期协调器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ASR 会话管理器：统一管理 ASR 引擎的生命周期和回调处理
 *
 * 职责：
 * - 根据当前配置创建和切换 ASR 引擎
 * - 启动和停止 ASR 录音
 * - 处理引擎回调（onFinal, onPartial, onError, onStopped）
 * - 管理会话状态和上下文
 * - 记录 ASR 请求耗时
 */
class AsrSessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs
) : SenseVoiceFileAsrEngine.LocalModelLoadUi {

    companion object {
        private const val TAG = "AsrSessionManager"
        private const val LOCAL_MODEL_READY_WAIT_CONSUMED = -1L
    }

    // 回调接口
    interface Listener {
        /**
         * 最终识别结果
         * @param text 识别的文本
         * @param currentState 当前键盘状态
         */
        fun onAsrFinal(text: String, currentState: KeyboardState)

        /**
         * 中间识别结果（实时预览）
         * @param text 中间文本
         */
        fun onAsrPartial(text: String)

        /**
         * ASR 错误
         * @param message 错误信息
         */
        fun onAsrError(message: String)

        /**
         * ASR 停止录音
         */
        fun onAsrStopped()

        /**
         * 本地模型加载开始
         */
        fun onLocalModelLoadStart()

        /**
         * 本地模型加载完成
         */
        fun onLocalModelLoadDone()

        /**
         * 实时音频振幅回调（用于波形动画）
         * @param amplitude 归一化的振幅值（0.0-1.0）
         */
        fun onAmplitude(amplitude: Float) { /* 默认空实现 */ }
    }

    private var listener: Listener? = null
    private var asrEngine: StreamingAsrEngine? = null
    private var directEngineIdentity: AsrDirectMicrophoneEngineIdentity? = null
    private val directMicrophoneEngineFactory = AsrDirectMicrophoneEngineFactory()
    private val parallelEngineFactory = AsrParallelEngineFactory()

    // 当前会话状态
    private var currentState: KeyboardState = KeyboardState.Idle

    // 音频焦点请求句柄
    private var audioFocusRequest: AudioFocusRequest? = null

    // ASR 请求耗时记录
    private var lastRequestDurationMs: Long? = null

    // 统计/历史：本次会话主/备供应商快照（避免设置变更导致 vendorId 串台）
    private var sessionPrimaryVendor: AsrVendor = try {
        prefs.asrVendor
    } catch (
        _: Throwable
    ) {
        AsrVendor.Volc
    }
    private var lastFinalVendorForStats: AsrVendor? = null

    // 本地模型：Processing 阶段等待“模型就绪”的耗时（用于将处理耗时统计从模型就绪开始）
    private var sessionSeq: Long = 0L
    private var engineSessionSeq: Long = 0L
    private var engineListenerBridge: SessionBoundEngineListener? = null
    private var localModelWaitStartUptimeMs: Long = 0L
    private val localModelReadyWaitMs = AtomicLong(0L)
    private var localModelReadyWaitJob: Job? = null

    // 会话录音时长统计（毫秒）
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L

    // 统计/历史：端到端耗时起点（从开始录音到最终提交完成）
    private var sessionStartTotalUptimeMs: Long = 0L

    private fun nextSessionSeq(): Long {
        sessionSeq += 1L
        return sessionSeq
    }

    private fun clearActiveSession(expectedSeq: Long? = null) {
        if (expectedSeq == null || sessionSeq == expectedSeq) {
            if (sessionSeq != 0L) {
                ContinuousCaptureCoordinator.endSession(sessionSeq)
            }
            sessionSeq = 0L
        }
    }

    private fun isSessionActive(seq: Long): Boolean = seq != 0L && sessionSeq == seq

    private inner class SessionBoundEngineListener(
        initialSessionSeq: Long
    ) : StreamingAsrEngine.Listener {
        private val boundSessionSeq = AtomicLong(initialSessionSeq)

        fun currentSessionSeq(): Long = boundSessionSeq.get()

        fun bindPrewarmedSession(targetSessionSeq: Long): Boolean {
            if (targetSessionSeq == 0L) return false
            return boundSessionSeq.compareAndSet(0L, targetSessionSeq)
        }

        override fun onFinal(text: String) {
            this@AsrSessionManager.onFinal(currentSessionSeq(), text)
        }

        override fun onError(message: String) {
            this@AsrSessionManager.onError(currentSessionSeq(), message)
        }

        override fun onPartial(text: String) {
            this@AsrSessionManager.onPartial(currentSessionSeq(), text)
        }

        override fun onStopped() {
            this@AsrSessionManager.onStopped(currentSessionSeq())
        }

        override fun onAmplitude(amplitude: Float) {
            this@AsrSessionManager.onAmplitude(currentSessionSeq(), amplitude)
        }
    }

    private data class BuiltEngine(
        val engine: StreamingAsrEngine,
        val listenerBridge: SessionBoundEngineListener,
        val directIdentity: AsrDirectMicrophoneEngineIdentity?
    )

    private fun createEngineListener(seq: Long): SessionBoundEngineListener = SessionBoundEngineListener(seq)

    private fun snapshotAudioDurationIfPossible() {
        if (sessionStartUptimeMs == 0L || lastAudioMsForStats != 0L) return
        try {
            val now = SystemClock.uptimeMillis()
            if (now >= sessionStartUptimeMs) {
                lastAudioMsForStats = now - sessionStartUptimeMs
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot audio duration on stopRecording", t)
        }
    }

    fun setListener(l: Listener) {
        listener = l
    }

    /**
     * 获取当前 ASR 引擎
     */
    fun getEngine(): StreamingAsrEngine? = asrEngine

    /**
     * ASR 引擎是否正在运行
     */
    fun isRunning(): Boolean = asrEngine?.isRunning == true

    /**
     * 获取最后一次请求耗时
     */
    fun getLastRequestDuration(): Long? = lastRequestDurationMs

    fun peekLastFinalVendorForStats(): AsrVendor = lastFinalVendorForStats ?: sessionPrimaryVendor

    /**
     * 构建符合当前配置的 ASR 引擎
     */
    fun buildEngine(): StreamingAsrEngine? = createBuiltEngine(0L)?.engine

    private fun createBuiltEngine(targetSessionSeq: Long): BuiltEngine? {
        val engineListener = createEngineListener(targetSessionSeq)
        val requestDurationCallback: (Long) -> Unit = { ms ->
            onRequestDuration(engineListener.currentSessionSeq(), ms)
        }
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val preferences = prefs.asrEngineModePreferencesSnapshot()
        val parallelEngine = parallelEngineFactory.createOrNull(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = engineListener,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = false,
            onPrimaryRequestDuration = requestDurationCallback
        )
        if (parallelEngine != null) {
            return BuiltEngine(
                engine = parallelEngine,
                listenerBridge = engineListener,
                directIdentity = null
            )
        }
        if (!isPrimaryVendorConstructible(primaryVendor)) return null
        val directPlan = directMicrophoneEngineFactory.resolvePlan(
            vendor = primaryVendor,
            preferences = preferences,
            source = AsrEngineConstructionSource.App
        )
        return BuiltEngine(
            // 主键盘路径已在这里完成在线配置预校验；本地供应商保持可构造，
            // 让后续模型加载与缺模型 UI 继续由既有会话流程处理。
            engine = directMicrophoneEngineFactory.create(
                context = context,
                scope = scope,
                prefs = prefs,
                listener = engineListener,
                vendor = primaryVendor,
                preferences = preferences,
                source = AsrEngineConstructionSource.App,
                onRequestDuration = requestDurationCallback
            ),
            listenerBridge = engineListener,
            directIdentity = directPlan.identity
        )
    }

    /**
     * 确保引擎与当前模式匹配（用于模式切换时避免重建引擎）
     */
    fun ensureEngineMatchesMode(): StreamingAsrEngine? = ensureEngineMatchesMode(0L)

    private fun tryReuseMatchedEngine(
        matched: StreamingAsrEngine?,
        targetSessionSeq: Long
    ): StreamingAsrEngine? {
        val engine = matched ?: return null
        if (engineSessionSeq == targetSessionSeq) return engine
        if (engineSessionSeq != 0L || targetSessionSeq == 0L) return null
        val bound = engineListenerBridge?.bindPrewarmedSession(targetSessionSeq) == true
        if (!bound) return null
        engineSessionSeq = targetSessionSeq
        return engine
    }

    private fun ensureEngineMatchesMode(targetSessionSeq: Long): StreamingAsrEngine? {
        val primaryVendor = prefs.asrVendor
        if (!isPrimaryVendorConstructible(primaryVendor)) {
            clearEngineBinding()
            return null
        }

        val backupVendor = prefs.backupAsrVendor
        val parallelPlan = parallelEngineFactory.resolvePlan(
            context = context,
            prefs = prefs,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = false
        )
        val current = asrEngine
        val matched = if (parallelPlan.shouldUseParallel) {
            when (current) {
                is ParallelAsrEngine -> if (current.primaryVendor == primaryVendor &&
                    current.backupVendor == backupVendor
                ) {
                    current
                } else {
                    null
                }
                else -> null
            }
        } else {
            matchedDirectEngine(current, primaryVendor)
        }

        val reusable = tryReuseMatchedEngine(matched, targetSessionSeq)
        val built = if (reusable == null) {
            try {
                createBuiltEngine(targetSessionSeq)
            } catch (t: Throwable) {
                clearEngineBinding()
                throw t
            }
        } else {
            null
        }
        val engine = reusable ?: built?.engine ?: return null
        if (engine !== asrEngine) {
            asrEngine?.stop()
            asrEngine = engine
            engineListenerBridge = built?.listenerBridge
            engineSessionSeq = targetSessionSeq
            directEngineIdentity = built?.directIdentity
        }
        return asrEngine
    }

    private fun matchedDirectEngine(
        current: StreamingAsrEngine?,
        primaryVendor: AsrVendor
    ): StreamingAsrEngine? {
        val engine = current ?: return null
        val directIdentity = directEngineIdentity ?: return null
        val plan = directMicrophoneEngineFactory.resolvePlan(
            vendor = primaryVendor,
            preferences = prefs.asrEngineModePreferencesSnapshot(),
            source = AsrEngineConstructionSource.App
        )
        return if (directIdentity == plan.identity) engine else null
    }

    private fun isPrimaryVendorConstructible(vendor: AsrVendor): Boolean = when {
        // 本地模型即使尚未就绪也允许构造，保留加载等待与缺模型提示路径。
        isLocalAsrVendor(vendor) -> true
        vendor == AsrVendor.SiliconFlow -> prefs.hasSfKeys()
        else -> prefs.hasVendorKeys(vendor)
    }

    /**
     * 重新构建引擎（设置改变时使用）
     */
    fun rebuildEngine() {
        val built = try {
            createBuiltEngine(0L)
        } catch (t: Throwable) {
            clearEngineBinding()
            throw t
        }
        asrEngine = built?.engine
        engineListenerBridge = built?.listenerBridge
        directEngineIdentity = built?.directIdentity
        engineSessionSeq = 0L
    }

    private fun clearEngineBinding() {
        asrEngine = null
        engineSessionSeq = 0L
        engineListenerBridge = null
        directEngineIdentity = null
    }

    /**
     * 启动 ASR 录音
     * @param state 启动时的键盘状态
     */
    fun startRecording(state: KeyboardState) {
        currentState = state
        val activeSeq = nextSessionSeq()
        try {
            sessionPrimaryVendor = prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot vendors on startRecording", t)
        } finally {
            lastFinalVendorForStats = null
        }
        localModelWaitStartUptimeMs = 0L
        localModelReadyWaitMs.set(0L)
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed on startRecording", t)
        }
        localModelReadyWaitJob = null
        try {
            sessionStartUptimeMs = SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get uptime for session start", t)
            sessionStartUptimeMs = 0L
        }
        // 端到端耗时使用独立的起点，避免在 onStopped/onFinal 中被清零影响后续统计
        sessionStartTotalUptimeMs = sessionStartUptimeMs
        lastAudioMsForStats = 0L
        // 新会话开始时重置上次请求耗时，避免串台（流式模式不会更新此值）
        lastRequestDurationMs = null
        try {
            val eng = ensureEngineMatchesMode(activeSeq)
            if (eng == null) {
                clearEngineBinding()
                clearActiveSession(activeSeq)
            }
            DebugLogManager.log(
                category = "asr",
                event = "start",
                data = mapOf(
                    "sessionSeq" to activeSeq,
                    "vendor" to prefs.asrVendor.name,
                    "engine" to (eng?.javaClass?.simpleName ?: "null"),
                    "state" to state::class.java.simpleName,
                    "duckMedia" to prefs.duckMediaOnRecordEnabled
                )
            )
        } catch (_: Throwable) { }
        // 开始录音前根据设置决定是否请求短时独占音频焦点（音频避让）
        if (prefs.duckMediaOnRecordEnabled) {
            requestTransientAudioFocus()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }
        // 本地模型在录音开始时后台预热，让加载耗时尽量与录音阶段重叠。
        try {
            preloadLocalAsrForImmediateUse(
                context = context,
                prefs = prefs,
                onLoadStart = { onLocalModelLoadStart() },
                onLoadDone = { onLocalModelLoadDone() }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Local model preload guard failed", t)
        }
        asrEngine?.let { engine ->
            ContinuousCaptureCoordinator.beginSession(activeSeq)
            engine.start()
        }
        try {
            DebugLogManager.log(
                category = "asr",
                event = "start_state",
                data = mapOf(
                    "engine" to (asrEngine?.javaClass?.simpleName ?: "null"),
                    "running" to (asrEngine?.isRunning == true)
                )
            )
        } catch (_: Throwable) { }
        // 录音期间保持耳机路由
        try {
            BluetoothRouteManager.onRecordingStarted(context)
        } catch (
            t: Throwable
        ) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t)
        }
    }

    /**
     * 停止 ASR 录音
     */
    fun stopRecording() {
        val activeSeq = sessionSeq
        snapshotAudioDurationIfPossible()
        markLocalModelProcessingStartIfNeeded(activeSeq)
        ContinuousCaptureCoordinator.endSession(activeSeq)
        asrEngine?.stop()
        try {
            DebugLogManager.log(
                category = "asr",
                event = "stop",
                data = mapOf(
                    "sessionSeq" to activeSeq,
                    "state" to currentState::class.java.simpleName,
                    "engineRunning" to (asrEngine?.isRunning == true)
                )
            )
        } catch (_: Throwable) { }
        // 归还音频焦点
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on stopRecording", t)
        }
        // 若无键盘可见，录音结束后可撤销预热
        try {
            BluetoothRouteManager.onRecordingStopped(context)
        } catch (
            t: Throwable
        ) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStopped", t)
        }
    }

    /**
     * 取消录音并可选丢弃已采集的片段，避免上传识别。
     */
    fun cancelRecording(discardPending: Boolean) {
        if (discardPending) {
            try {
                (asrEngine as? BaseFileAsrEngine)?.markDiscardOnStop()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to mark discard on stop", t)
            }
        }
        stopRecording()
    }

    /**
     * 读取并清空最近一次会话的录音时长（毫秒）。
     */
    fun popLastAudioMsForStats(): Long {
        val v = lastAudioMsForStats
        lastAudioMsForStats = 0L
        return v
    }

    /**
     * 读取并清空最近一次会话的端到端总耗时（毫秒）。
     * 口径：从开始录音到最终提交完成（含识别/后处理/打字机动画等待等）。
     */
    fun popLastTotalElapsedMsForStats(): Long {
        val start = sessionStartTotalUptimeMs
        if (start <= 0L) return 0L
        val now = try {
            SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get uptime for total elapsed ms", t)
            // 无法读取时间时，避免串台，直接清零
            sessionStartTotalUptimeMs = 0L
            return 0L
        }
        val elapsed = if (now >= start) (now - start).coerceAtLeast(0L) else 0L
        // 若仍在录音（分段/连续识别），将下一段的起点更新为当前时间；否则清零
        sessionStartTotalUptimeMs = if (isRunning()) now else 0L
        return elapsed
    }

    /**
     * 读取最近一次会话的录音时长（毫秒），不清空。
     * 用于在 onStopped 等场景下进行早停判断，避免影响后续统计/历史写入。
     */
    fun peekLastAudioMsForStats(): Long = lastAudioMsForStats

    /**
     * 设置当前状态（用于外部状态变更）
     */
    fun setCurrentState(state: KeyboardState) {
        currentState = state
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        clearActiveSession()
        ContinuousCaptureCoordinator.endAnySession()
        asrEngine?.stop()
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed on cleanup", t)
        }
        localModelReadyWaitJob = null
        engineSessionSeq = 0L
        engineListenerBridge = null
        directEngineIdentity = null
        sessionStartTotalUptimeMs = 0L
        listener = null
    }

    /**
     * 是否可以对最近一次非流式片段进行重试
     */
    fun canRetryLastFileRecognition(): Boolean {
        val e = asrEngine
        return try {
            (e is BaseFileAsrEngine) && e.hasRetryableSegment()
        } catch (t: Throwable) {
            Log.e(TAG, "canRetryLastFileRecognition check failed", t)
            false
        }
    }

    /**
     * 发起对最近一次非流式片段的重新识别（不重新录音）。
     * 返回是否成功触发。
     */
    fun retryLastFileRecognition(): Boolean {
        val e = asrEngine
        return if (e is BaseFileAsrEngine && e.hasRetryableSegment()) {
            try {
                if (engineSessionSeq == 0L) {
                    Log.w(TAG, "retryLastFileRecognition: missing engine session sequence")
                    return false
                }
                sessionSeq = engineSessionSeq
                lastRequestDurationMs = null
                e.retryLastSegment()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "retryLastFileRecognition failed", t)
                false
            }
        } else {
            Log.w(TAG, "retryLastFileRecognition: engine not retryable or no segment")
            false
        }
    }

    // ========== StreamingAsrEngine.Listener 实现 ==========

    private fun onFinal(seq: Long, text: String) {
        if (!isSessionActive(seq)) {
            Log.d(TAG, "onFinal ignored for stale sessionSeq=$seq")
            return
        }
        Log.d(TAG, "onFinal: text='$text', state=$currentState")
        lastFinalVendorForStats = when (val e = asrEngine) {
            is ParallelAsrEngine -> if (e.wasLastResultFromBackup()) e.backupVendor else e.primaryVendor
            else -> sessionPrimaryVendor
        }
        // 若尚未收到 onStopped，则以当前时间近似计算一次时长
        if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
            try {
                val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                lastAudioMsForStats = dur
                sessionStartUptimeMs = 0L
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on onFinal", t)
            }
        }
        try {
            DebugLogManager.log(
                category = "asr",
                event = "final",
                data = mapOf(
                    "sessionSeq" to seq,
                    "len" to text.length,
                    "state" to currentState::class.java.simpleName
                )
            )
        } catch (_: Throwable) { }
        if (asrEngine?.isRunning != true) {
            clearActiveSession(seq)
        }
        listener?.onAsrFinal(text, currentState)
    }

    private fun onPartial(seq: Long, text: String) {
        if (!isSessionActive(seq)) {
            Log.d(TAG, "onPartial ignored for stale sessionSeq=$seq")
            return
        }
        // 若引擎已停止（用户已松手），忽略后续中间结果，避免重复追加
        if (!isRunning()) {
            Log.d(TAG, "onPartial ignored: engine stopped")
            return
        }
        Log.d(TAG, "onPartial: text='$text'")
        listener?.onAsrPartial(text)
    }

    private fun onError(seq: Long, message: String) {
        if (!isSessionActive(seq)) {
            Log.d(TAG, "onError ignored for stale sessionSeq=$seq")
            return
        }
        Log.e(TAG, "onError: message='$message', state=$currentState")
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed onError", t)
        }
        localModelReadyWaitJob = null
        val friendlyMessage = AsrErrorMessageMapper.map(context, message)
        try {
            DebugLogManager.log(
                category = "asr",
                event = "error",
                data = mapOf(
                    "sessionSeq" to seq,
                    "state" to currentState::class.java.simpleName,
                    "msgType" to if (friendlyMessage != null) "friendly" else "raw"
                )
            )
        } catch (_: Throwable) { }
        clearActiveSession(seq)
        listener?.onAsrError(friendlyMessage ?: message)
    }

    private fun onStopped(seq: Long) {
        if (!isSessionActive(seq)) {
            Log.d(TAG, "onStopped ignored for stale sessionSeq=$seq")
            return
        }
        Log.d(TAG, "onStopped: state=$currentState")
        ContinuousCaptureCoordinator.endSession(seq)
        markLocalModelProcessingStartIfNeeded(seq)
        // 计算本次会话录音时长
        if (sessionStartUptimeMs > 0L) {
            try {
                if (lastAudioMsForStats == 0L) {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on onStopped", t)
            } finally {
                sessionStartUptimeMs = 0L
            }
        }
        // 确保归还音频焦点（覆盖静音判停等路径）
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on onStopped", t)
        }
        try {
            val ms = lastAudioMsForStats
            DebugLogManager.log(
                category = "asr",
                event = "stopped",
                data = mapOf(
                    "sessionSeq" to seq,
                    "audioMs" to ms,
                    "state" to currentState::class.java.simpleName
                )
            )
        } catch (_: Throwable) { }
        listener?.onAsrStopped()
    }

    private fun onAmplitude(seq: Long, amplitude: Float) {
        if (!isSessionActive(seq)) return
        listener?.onAmplitude(amplitude)
    }

    // ========== SenseVoiceFileAsrEngine.LocalModelLoadUi 实现 ==========

    override fun onLocalModelLoadStart() {
        Log.d(TAG, "onLocalModelLoadStart")
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    listener?.onLocalModelLoadStart()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to deliver onLocalModelLoadStart to UI", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post onLocalModelLoadStart to main", t)
        }
    }

    override fun onLocalModelLoadDone() {
        Log.d(TAG, "onLocalModelLoadDone")
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    listener?.onLocalModelLoadDone()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to deliver onLocalModelLoadDone to UI", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post onLocalModelLoadDone to main", t)
        }
    }

    // ========== 私有方法 ==========

    private fun markLocalModelProcessingStartIfNeeded(seq: Long) {
        if (!isSessionActive(seq)) return
        val vendor = try {
            prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read asrVendor for local model timing", t)
            return
        }
        if (!isLocalAsrVendor(vendor)) return
        if (localModelWaitStartUptimeMs != 0L) return

        val startMs = try {
            SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for local model timing", t)
            0L
        }
        localModelWaitStartUptimeMs = startMs
        localModelReadyWaitMs.set(0L)

        // 已就绪：无需等待
        if (isLocalAsrReady(prefs)) return

        val waitSeq = seq
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed", t)
        }
        localModelReadyWaitJob = scope.launch(Dispatchers.Default) {
            val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
            if (!ok) return@launch
            if (!isSessionActive(waitSeq)) return@launch
            val readyAt = try {
                SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            if (readyAt > 0L && startMs > 0L && readyAt >= startMs) {
                localModelReadyWaitMs.compareAndSet(0L, (readyAt - startMs).coerceAtLeast(0L))
            }
        }
    }

    private fun onRequestDuration(seq: Long, ms: Long) {
        if (!isSessionActive(seq)) {
            Log.d(TAG, "onRequestDuration ignored for stale sessionSeq=$seq")
            return
        }
        val waitMs = localModelReadyWaitMs.getAndSet(LOCAL_MODEL_READY_WAIT_CONSUMED)
        val adjusted = if (waitMs > 0L && ms > waitMs) ms - waitMs else ms
        lastRequestDurationMs = adjusted
        // 仅对首次“等待模型就绪”的请求做一次扣减，避免后续分段请求被重复扣除（同时避免晚写覆盖）。
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed onRequestDuration", t)
        } finally {
            localModelReadyWaitJob = null
        }
        Log.d(TAG, "Request duration: ${adjusted}ms")
    }

    // ========== 音频焦点控制 ==========

    /**
     * 请求短时独占音频焦点，促使其他媒体暂停或静音。
     */
    private fun requestTransientAudioFocus(): Boolean {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (am == null) {
                Log.w(TAG, "AudioManager is null, skip audio focus")
                return false
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener({ /* no-op */ })
                .build()
            val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) {
                audioFocusRequest = req
                Log.d(TAG, "Audio focus granted (TRANSIENT_EXCLUSIVE)")
            } else {
                Log.w(TAG, "Audio focus not granted")
            }
            return granted
        } catch (t: Throwable) {
            Log.e(TAG, "requestTransientAudioFocus exception", t)
            return false
        }
    }

    /**
     * 归还之前请求的音频焦点。
     */
    private fun abandonAudioFocusIfNeeded() {
        val req = audioFocusRequest ?: return
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (am == null) {
                Log.w(TAG, "AudioManager is null when abandoning focus")
                return
            }
            am.abandonAudioFocusRequest(req)
            Log.d(TAG, "Audio focus abandoned")
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusRequest exception", t)
        } finally {
            audioFocusRequest = null
        }
    }
}
