package com.brycewg.asrkb.ui.floatingball

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.asr.AsrTimeoutCalculator
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeStreamAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsStreamAsrEngine
import com.brycewg.asrkb.asr.FunAsrNanoFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.LOCAL_MODEL_READY_WAIT_MAX_MS
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.OpenAiFileAsrEngine
import com.brycewg.asrkb.asr.ParaformerStreamAsrEngine
import com.brycewg.asrkb.asr.ParallelAsrEngine
import com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine
import com.brycewg.asrkb.asr.SenseVoicePseudoStreamAsrEngine
import com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine
import com.brycewg.asrkb.asr.SonioxFileAsrEngine
import com.brycewg.asrkb.asr.SonioxStreamAsrEngine
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.TelespeechFileAsrEngine
import com.brycewg.asrkb.asr.TelespeechPseudoStreamAsrEngine
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.VolcStandardFileAsrEngine
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.ZhipuFileAsrEngine
import com.brycewg.asrkb.asr.awaitLocalAsrReady
import com.brycewg.asrkb.asr.isFunAsrNanoPrepared
import com.brycewg.asrkb.asr.isLocalAsrReady
import com.brycewg.asrkb.asr.isLocalAsrVendor
import com.brycewg.asrkb.asr.isSenseVoicePrepared
import com.brycewg.asrkb.asr.isTelespeechPrepared
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import com.brycewg.asrkb.util.TextSanitizer
import com.brycewg.asrkb.util.TypewriterTextAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * ASR 会话管理器
 * 负责 ASR 引擎的生命周期管理、结果处理和超时兜底
 */
class AsrSessionManager(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val listener: AsrSessionListener,
) : StreamingAsrEngine.Listener {

    companion object {
        private const val TAG = "AsrSessionManager"
        private const val LOCAL_MODEL_READY_WAIT_CONSUMED = -1L
    }

    interface AsrSessionListener {
        fun onSessionStateChanged(state: FloatingBallState)
        fun onResultCommitted(text: String, success: Boolean)
        fun onError(message: String)
    }

    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()

    // 会话上下文
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null
    private var markerInserted: Boolean = false
    private var markerChar: String? = null

    // 超时控制
    private var processingTimeoutJob: Job? = null
    private var hasCommittedResult: Boolean = false

    // 统计：录音时长
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L

    // 统计/历史：端到端耗时起点（从开始录音到最终提交完成）
    private var sessionStartTotalUptimeMs: Long = 0L

    // 统计：非流式请求处理耗时（毫秒）
    private var lastRequestDurationMs: Long? = null

    // 本地模型：Processing 阶段等待“模型就绪”的耗时（用于将处理耗时统计从模型就绪开始）
    private val localModelReadyWaitMs = AtomicLong(0L)

    // 标记：最近一次提交是否实际使用了 AI 输出
    private var lastAiUsed: Boolean = false

    // 统计/历史：最近一次 AI 后处理耗时与状态
    private var lastAiPostMs: Long = 0L
    private var lastAiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE

    // 统计/历史：最近一次最终结果的实际供应商（备用引擎场景下不再固定记录 prefs.asrVendor）
    private var sessionPrimaryVendor: AsrVendor = try {
        prefs.asrVendor
    } catch (_: Throwable) {
        AsrVendor.Volc
    }
    private var lastFinalVendorForStats: AsrVendor? = null

    // 音频焦点请求句柄
    private var audioFocusRequest: AudioFocusRequest? = null

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

    /** 开始录音 */
    fun startRecording() {
        Log.d(TAG, "startRecording called")
        try {
            sessionPrimaryVendor = prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot vendor on startRecording", t)
        } finally {
            lastFinalVendorForStats = null
        }
        try {
            sessionStartUptimeMs = SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for session start", t)
            sessionStartUptimeMs = 0L
        }
        sessionStartTotalUptimeMs = sessionStartUptimeMs
        lastAudioMsForStats = 0L
        // 新会话开始：重置请求耗时，避免上一轮的值串台
        lastRequestDurationMs = null
        localModelReadyWaitMs.set(0L)
        lastAiUsed = false
        lastAiPostMs = 0L
        lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
        // 开始录音前根据设置决定是否请求短时独占音频焦点（音频避让）
        if (prefs.duckMediaOnRecordEnabled) {
            requestTransientAudioFocus()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }

        // 检查本地 SenseVoice 模型（如果需要）
        if (!checkSenseVoiceModel()) {
            val errRes = when (prefs.asrVendor) {
                AsrVendor.Telespeech -> com.brycewg.asrkb.R.string.error_telespeech_model_missing
                else -> com.brycewg.asrkb.R.string.error_sensevoice_model_missing
            }
            listener.onError(context.getString(errRes))
            return
        }

        // 清理上次会话
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = null
        hasCommittedResult = false

        // 写入兼容模式：为命中包名注入占位符（粘贴方式），屏蔽原文本干扰
        tryFixCompatPlaceholderIfNeeded()

        // 构建引擎
        asrEngine = buildEngineForCurrentMode()
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")

        // 记录焦点上下文（占位后再取，保持与参考版本一致）
        focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh focus context", e)
            }
        }, 120)
        lastPartialForPreview = null

        // 启动引擎
        listener.onSessionStateChanged(FloatingBallState.Recording)
        asrEngine?.start()
        try {
            BluetoothRouteManager.onRecordingStarted(context)
        } catch (t: Throwable) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t)
        }
    }

    /** 停止录音 */
    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        snapshotAudioDurationIfPossible()
        asrEngine?.stop()
        // 归还音频焦点
        try {
            abandonAudioFocusIfNeeded()
        } catch (t: Throwable) {
            Log.w(TAG, "abandonAudioFocusIfNeeded failed on stopRecording", t)
        }
        try {
            BluetoothRouteManager.onRecordingStopped(context)
        } catch (t: Throwable) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStopped", t)
        }

        // 进入处理阶段
        listener.onSessionStateChanged(FloatingBallState.Processing)

        // 启动超时兜底
        startProcessingTimeout(lastAudioMsForStats)
    }

    /**
     * 读取并清空最近一次会话的录音时长（毫秒）。
     */
    fun popLastAudioMsForStats(): Long {
        val v = lastAudioMsForStats
        lastAudioMsForStats = 0L
        return v
    }

    /** 最近一次请求耗时（毫秒），仅非流式模式有效 */
    fun getLastRequestDuration(): Long? = lastRequestDurationMs

    /** 最近一次提交是否实际使用了 AI 输出 */
    fun wasLastAiUsed(): Boolean = lastAiUsed

    /** 读取并清空最近一次会话的端到端总耗时（毫秒）。 */
    fun popLastTotalElapsedMsForStats(): Long {
        val start = sessionStartTotalUptimeMs
        if (start <= 0L) return 0L
        val now = try {
            SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for total elapsed ms", t)
            sessionStartTotalUptimeMs = 0L
            return 0L
        }
        val elapsed = if (now >= start) (now - start).coerceAtLeast(0L) else 0L
        sessionStartTotalUptimeMs = if (asrEngine?.isRunning == true) now else 0L
        return elapsed
    }

    /** 最近一次 AI 后处理耗时（毫秒）；未尝试时为 0 */
    fun getLastAiPostMs(): Long = lastAiPostMs

    /** 最近一次 AI 后处理状态 */
    fun getLastAiPostStatus(): AsrHistoryStore.AiPostStatus = lastAiPostStatus

    fun peekLastFinalVendorForStats(): AsrVendor {
        return lastFinalVendorForStats ?: sessionPrimaryVendor
    }

    /** 清理会话 */
    fun cleanup() {
        try {
            asrEngine?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop ASR engine", e)
        }
        sessionStartTotalUptimeMs = 0L
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel timeout job", e)
        }
        try {
            abandonAudioFocusIfNeeded()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to abandon audio focus in cleanup", e)
        }
    }

    // ==================== StreamingAsrEngine.Listener ====================

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        lastFinalVendorForStats = when (val e = asrEngine) {
            is ParallelAsrEngine -> if (e.wasLastResultFromBackup()) e.backupVendor else e.primaryVendor
            else -> sessionPrimaryVendor
        }
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onFinal", e)
            }
            processingTimeoutJob = null

            // 若已由兜底提交，忽略后续 onFinal
            if (hasCommittedResult && asrEngine?.isRunning != true) {
                Log.w(TAG, "Result already committed by fallback; ignoring residual onFinal")
                return@launch
            }

            var finalText = text
            lastAiUsed = false
            lastAiPostMs = 0L
            lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            val stillRecording = (asrEngine?.isRunning == true)
            // 若未收到 onStopped，则在此近似计算录音时长
            if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration in onFinal", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }

            // 统一使用 AsrFinalFilters：含预修剪/LLM/后修剪/繁体转换
            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                Log.d(TAG, "Starting AI post-processing (stillRecording=$stillRecording)")
                if (!stillRecording) {
                    listener.onSessionStateChanged(FloatingBallState.Processing)
                }
                if (lastPartialForPreview.isNullOrEmpty()) {
                    updatePreviewText(text)
                }
                val typewriterEnabled = try {
                    prefs.postprocTypewriterEnabled
                } catch (_: Throwable) {
                    true
                }
                var postprocCommitted = false
                val typewriter = if (typewriterEnabled) {
                    TypewriterTextAnimator(
                        scope = serviceScope,
                        onEmit = emit@{ typed ->
                            if (postprocCommitted) return@emit
                            updatePreviewText(typed)
                        },
                        frameDelayMs = 60L,
                        idleStopDelayMs = 1200L,
                        normalTargetFrames = 12,
                        normalMaxStep = 8,
                    )
                } else {
                    null
                }
                var lastStreamingText: String? = null
                val onStreamingUpdate: (String) -> Unit = onStreamingUpdate@{ streamed ->
                    if (postprocCommitted) return@onStreamingUpdate
                    if (streamed.isEmpty() || streamed == lastStreamingText) return@onStreamingUpdate
                    lastStreamingText = streamed
                    if (typewriter != null) {
                        typewriter.submit(streamed)
                    } else {
                        updatePreviewText(streamed)
                    }
                }
                val res = try {
                    com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                        context,
                        prefs,
                        text,
                        postproc,
                        onStreamingUpdate = onStreamingUpdate,
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "applyWithAi failed", t)
                    com.brycewg.asrkb.asr.LlmPostProcessor.LlmProcessResult(
                        ok = false,
                        text = text,
                        errorMessage = t.message,
                        httpCode = null,
                        usedAi = false,
                        attempted = true,
                        llmMs = 0,
                    )
                }
                if (!res.ok) Log.w(TAG, "Post-processing failed; using processed text anyway")
                val aiUsed = (res.usedAi && res.ok)
                lastAiPostMs = if (res.attempted) res.llmMs else 0L
                lastAiPostStatus = when {
                    res.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                    res.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                    else -> AsrHistoryStore.AiPostStatus.NONE
                }
                finalText = res.text.ifBlank { text }
                if (typewriter != null && aiUsed && finalText.isNotEmpty() && focusContext != null) {
                    // 最终结果到达后：让打字机以最快速度追到最终文本，再进行最终提交
                    typewriter.submit(finalText, rush = true)
                    val finalLen = finalText.length
                    val t0 = try {
                        SystemClock.uptimeMillis()
                    } catch (_: Throwable) {
                        0L
                    }
                    while (!postprocCommitted &&
                        (t0 <= 0L || (SystemClock.uptimeMillis() - t0) < 2_000L) &&
                        typewriter.currentText().length != finalLen
                    ) {
                        delay(20)
                    }
                }
                postprocCommitted = true
                typewriter?.cancel()
                lastAiUsed = aiUsed
                Log.d(TAG, "Post-processing completed: $finalText")
            } else {
                finalText = com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                lastAiUsed = false
                lastAiPostMs = 0L
                lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            }

            // 更新状态
            if (asrEngine?.isRunning == true) {
                listener.onSessionStateChanged(FloatingBallState.Recording)
            } else {
                listener.onSessionStateChanged(FloatingBallState.Idle)
            }

            // 插入文本
            if (finalText.isNotEmpty()) {
                val success = insertTextToFocus(finalText)
                listener.onResultCommitted(finalText, success)
                if ((asrEngine as? ParallelAsrEngine)?.wasLastResultFromBackup() == true) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.brycewg.asrkb.R.string.toast_backup_asr_used),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Log.w(TAG, "Final text is empty")
                listener.onError(context.getString(com.brycewg.asrkb.R.string.asr_error_empty_result))
            }

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    override fun onStopped() {
        serviceScope.launch {
            listener.onSessionStateChanged(FloatingBallState.Processing)
            // 计算本次会话录音时长
            if (sessionStartUptimeMs > 0L) {
                try {
                    if (lastAudioMsForStats == 0L) {
                        val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                        lastAudioMsForStats = dur
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration in onStopped", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }
            // 确保归还音频焦点
            try {
                abandonAudioFocusIfNeeded()
            } catch (t: Throwable) {
                Log.w(TAG, "abandonAudioFocusIfNeeded failed in onStopped", t)
            }
            startProcessingTimeout(lastAudioMsForStats)
        }
    }

    // ========== 音频焦点控制 ==========
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

    override fun onPartial(text: String) {
        updatePreviewText(text)
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onError", e)
            }
            processingTimeoutJob = null

            listener.onSessionStateChanged(FloatingBallState.Error(message))
            listener.onError(message)

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun checkSenseVoiceModel(): Boolean {
        if (
            prefs.asrVendor != AsrVendor.SenseVoice &&
            prefs.asrVendor != AsrVendor.FunAsrNano &&
            prefs.asrVendor != AsrVendor.Telespeech
        ) {
            return true
        }

        val prepared = try {
            when (prefs.asrVendor) {
                AsrVendor.SenseVoice -> com.brycewg.asrkb.asr.isSenseVoicePrepared()
                AsrVendor.FunAsrNano -> com.brycewg.asrkb.asr.isFunAsrNanoPrepared()
                AsrVendor.Telespeech -> com.brycewg.asrkb.asr.isTelespeechPrepared()
                else -> true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check local model preparation", e)
            false
        }
        if (prepared) return true

        // 检查模型文件
        val base = try {
            context.getExternalFilesDir(null)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get external files dir", e)
            context.filesDir
        }
        return if (prefs.asrVendor == AsrVendor.Telespeech) {
            val probeRoot = java.io.File(base, "telespeech")
            val variant = try {
                prefs.tsModelVariant
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get TeleSpeech variant", e)
                "int8"
            }
            val variantDir = when (variant) {
                "full" -> java.io.File(probeRoot, "full")
                else -> java.io.File(probeRoot, "int8")
            }
            val found = com.brycewg.asrkb.asr.findTsModelDir(variantDir)
                ?: com.brycewg.asrkb.asr.findTsModelDir(probeRoot)
            found != null
        } else if (prefs.asrVendor == AsrVendor.FunAsrNano) {
            val probeRoot = java.io.File(base, "funasr_nano")
            val variantDir = java.io.File(probeRoot, "nano-int8")
            val found = com.brycewg.asrkb.asr.findFnModelDir(variantDir)
                ?: com.brycewg.asrkb.asr.findFnModelDir(probeRoot)
            found != null
        } else {
            val rawVariant = try {
                prefs.svModelVariant
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get local variant", e)
                "small-int8"
            }
            val variant = if (rawVariant == "small-full") "small-full" else "small-int8"
            val probeRoot = java.io.File(base, "sensevoice")
            val variantDir = if (variant == "small-full") {
                java.io.File(probeRoot, "small-full")
            } else {
                java.io.File(probeRoot, "small-int8")
            }
            val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
                ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
            found != null
        }
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val backupEnabled = shouldUseBackupAsr(primaryVendor, backupVendor)
        if (backupEnabled) {
            return ParallelAsrEngine(
                context = context,
                scope = serviceScope,
                prefs = prefs,
                listener = this,
                primaryVendor = primaryVendor,
                backupVendor = backupVendor,
                onPrimaryRequestDuration = ::onRequestDuration,
            )
        }

        return when (primaryVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    if (prefs.volcFileStandardEnabled) {
                        VolcStandardFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                    } else {
                        VolcFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                    }
                }
            } else {
                null
            }
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else {
                null
            }
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                if (prefs.elevenStreamingEnabled) {
                    ElevenLabsStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else {
                null
            }
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else {
                null
            }
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                if (prefs.isDashStreamingModelSelected()) {
                    DashscopeStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else {
                null
            }
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else {
                null
            }
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            } else {
                null
            }
            AsrVendor.Zhipu -> if (prefs.hasZhipuKeys()) {
                ZhipuFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            } else {
                null
            }
            AsrVendor.SenseVoice -> {
                if (prefs.svPseudoStreamEnabled) {
                    SenseVoicePseudoStreamAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                } else {
                    SenseVoiceFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            }
            AsrVendor.FunAsrNano -> {
                // FunASR Nano 算力开销高：不支持伪流式预览，仅保留整段离线识别
                FunAsrNanoFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
            }
            AsrVendor.Telespeech -> {
                if (prefs.tsPseudoStreamEnabled) {
                    TelespeechPseudoStreamAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                } else {
                    TelespeechFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = ::onRequestDuration)
                }
            }
            AsrVendor.Paraformer -> {
                ParaformerStreamAsrEngine(context, serviceScope, prefs, this)
            }
        }
    }

    private fun shouldUseBackupAsr(primaryVendor: AsrVendor, backupVendor: AsrVendor): Boolean {
        val enabled = try {
            prefs.backupAsrEnabled
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return false
        if (backupVendor == primaryVendor) return false
        return try {
            when (backupVendor) {
                AsrVendor.SiliconFlow -> prefs.hasSfKeys()
                else -> prefs.hasVendorKeys(backupVendor)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to check backup vendor keys: $backupVendor", t)
            false
        }
    }

    private fun onRequestDuration(ms: Long) {
        val waitMs = localModelReadyWaitMs.getAndSet(LOCAL_MODEL_READY_WAIT_CONSUMED)
        val adjusted = if (waitMs > 0L && ms > waitMs) ms - waitMs else ms
        lastRequestDurationMs = adjusted
        // 仅对首次“等待模型就绪”的请求做一次扣减，避免后续分段请求被重复扣除（同时避免晚写覆盖）。
        Log.d(TAG, "Request duration: ${adjusted}ms")
    }

    private fun startProcessingTimeout(audioMsOverride: Long? = null) {
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        val audioMs = audioMsOverride ?: lastAudioMsForStats
        val usingBackupEngine = asrEngine is ParallelAsrEngine
        val baseTimeoutMs = AsrTimeoutCalculator.calculateTimeoutMs(audioMs)
        val timeoutMs = if (usingBackupEngine) baseTimeoutMs + 2_000L else baseTimeoutMs
        processingTimeoutJob = serviceScope.launch {
            val shouldDeferForLocalModel = try {
                !usingBackupEngine && isLocalAsrVendor(prefs.asrVendor)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to determine local ASR vendor for timeout gating", t)
                false
            }
            if (shouldDeferForLocalModel) {
                val wasReady = try {
                    isLocalAsrReady(prefs)
                } catch (_: Throwable) {
                    false
                }
                val t0 = try {
                    SystemClock.uptimeMillis()
                } catch (_: Throwable) {
                    0L
                }
                val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                if (!ok) {
                    Log.w(TAG, "awaitLocalAsrReady returned false, fallback to immediate timeout countdown")
                }
                if (ok && !wasReady && t0 > 0L) {
                    val t1 = try {
                        SystemClock.uptimeMillis()
                    } catch (_: Throwable) {
                        0L
                    }
                    if (t1 >= t0) {
                        localModelReadyWaitMs.compareAndSet(0L, (t1 - t0).coerceAtLeast(0L))
                    }
                }
            }
            delay(timeoutMs)
            if (!hasCommittedResult) {
                Log.d(TAG, "Processing timeout fired: audioMs=$audioMs, timeoutMs=$timeoutMs")
                handleProcessingTimeout()
            }
        }
        Log.d(TAG, "Processing timeout scheduled: audioMs=$audioMs, timeoutMs=$timeoutMs")
    }

    private suspend fun handleProcessingTimeout() {
        val candidate = lastPartialForPreview?.trim().orEmpty()
        Log.w(TAG, "Finalize timeout; fallback with preview='$candidate'")
        if (candidate.isEmpty()) {
            Log.w(TAG, "Fallback has no candidate text; only clear state")
            listener.onSessionStateChanged(FloatingBallState.Idle)
            return
        }

        val textOut = if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            try {
                val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(context, prefs, candidate, postproc)
                val aiUsed = (res.usedAi && res.ok)
                lastAiUsed = aiUsed
                lastAiPostMs = if (res.attempted) res.llmMs else 0L
                lastAiPostStatus = when {
                    res.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                    res.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                    else -> AsrHistoryStore.AiPostStatus.NONE
                }
                res.text.ifBlank { com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate) }
            } catch (t: Throwable) {
                Log.e(TAG, "applyWithAi failed in timeout fallback", t)
                lastAiUsed = false
                lastAiPostMs = 0L
                lastAiPostStatus = AsrHistoryStore.AiPostStatus.FAILED
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
            }
        } else {
            lastAiUsed = false
            lastAiPostMs = 0L
            lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
        }

        val success = insertTextToFocus(textOut)
        Log.d(TAG, "Fallback inserted=$success text='$textOut'")
        listener.onResultCommitted(textOut, success)
        hasCommittedResult = true

        listener.onSessionStateChanged(FloatingBallState.Idle)
        focusContext = null
        lastPartialForPreview = null
        markerInserted = false
        markerChar = null
    }

    private fun insertTextToFocus(text: String): Boolean {
        val ctx = focusContext ?: com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        var toWrite = if (ctx != null) ctx.prefix + text + ctx.suffix else text
        toWrite = stripMarkersIfAny(toWrite)
        Log.d(TAG, "Inserting text: $toWrite (previewCtx=${ctx != null})")

        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage()
        // 写入粘贴方案：命中规则则仅复制到剪贴板并提示
        val writePaste = try {
            prefs.floatingWriteTextPasteEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write paste preference", e)
            false
        }
        val pasteTarget = pkg != null && isPackageInPasteTargets(pkg)
        if (writePaste && pasteTarget) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                cm.setPrimaryClip(clip)
                android.widget.Toast.makeText(
                    context,
                    context.getString(com.brycewg.asrkb.R.string.floating_asr_copied),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to copy to clipboard (writePaste)", e)
            }
            // 不尝试插入文本：返回 false 表示未写入
            return false
        }

        // 统一使用通用插入方法（兼容模式的区别仅在于占位符的注入与清理）
        val wrote: Boolean = com.brycewg.asrkb.ui.AsrAccessibilityService.insertText(context, toWrite)

        if (wrote) {
            try {
                if (!prefs.disableUsageStats) {
                    prefs.addAsrChars(TextSanitizer.countEffectiveChars(text))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to add ASR chars", e)
            }
            // 光标应定位到“前缀 + 新文本”的末尾；占位符已从前缀中移除
            val prefixLenForCursor = stripMarkersIfAny(ctx?.prefix ?: "").length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }

        return wrote
    }

    private fun isPackageInPasteTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWritePastePackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get paste packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rules.any { it.equals("all", ignoreCase = true) }) return true
        // 前缀匹配（包名边界）
        return rules.any { rule -> pkg == rule || pkg.startsWith("$rule.") }
    }

    private fun tryFixCompatPlaceholderIfNeeded() {
        markerInserted = false
        markerChar = null
        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage() ?: return
        val compat = try {
            prefs.floatingWriteTextCompatEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write compat preference", e)
            true
        }
        if (!compat || !isPackageInCompatTargets(pkg)) return

        val candidates = listOf("\u2060", "\u200B")
        for (m in candidates) {
            val ok = com.brycewg.asrkb.ui.AsrAccessibilityService.pasteTextSilent(m)
            if (ok) {
                markerInserted = true
                markerChar = m
                Log.d(TAG, "Compat fix: injected marker ${Integer.toHexString(m.codePointAt(0))}")
                break
            }
        }
    }

    private fun stripMarkersIfAny(s: String): String {
        var out = s
        markerChar?.let { if (it.isNotEmpty()) out = out.replace(it, "") }
        out = out.replace("\u2060", "")
        out = out.replace("\u200B", "")
        return out
    }

    private fun updatePreviewText(text: String) {
        if (text.isEmpty() || lastPartialForPreview == text) return
        val ctx = focusContext ?: return
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "preview update: $text")

        serviceScope.launch {
            com.brycewg.asrkb.ui.AsrAccessibilityService.insertTextSilent(toWrite)
            val prefixLenForCursor = stripMarkersIfAny(ctx.prefix).length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }
        lastPartialForPreview = text
    }

    private fun isPackageInCompatTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWriteCompatPackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get compat packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // 前缀匹配（包名边界）
        return rules.any { rule -> pkg == rule || pkg.startsWith("$rule.") }
    }
}
