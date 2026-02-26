package com.brycewg.asrkb.api

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.asr.AsrTimeoutCalculator
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeStreamAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsStreamAsrEngine
import com.brycewg.asrkb.asr.FunAsrNanoFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.LOCAL_MODEL_READY_WAIT_MAX_MS
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
import com.brycewg.asrkb.asr.VadAutoStopGuard
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.VolcStandardFileAsrEngine
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.ZhipuFileAsrEngine
import com.brycewg.asrkb.asr.awaitLocalAsrReady
import com.brycewg.asrkb.asr.isLocalAsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TypewriterTextAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android 标准语音识别服务实现
 */
class AsrRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "AsrRecognitionSvc"
        private const val EXTRA_USED_BACKUP_ASR = "com.brycewg.asrkb.extra.USED_BACKUP_ASR"
    }

    private val prefs by lazy { Prefs(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当前活动会话
    private var currentSession: RecognitionSession? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            currentSession?.cancel()
            currentSession = null
            serviceScope.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy cleanup failed", t)
        }
    }

    override fun onStartListening(intent: Intent, callback: Callback) {
        Log.d(TAG, "onStartListening called")

        // 检查录音权限
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Recording permission not granted")
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // 校验调用方录音权限（系统通常已做校验，但此处显式防御，避免成为权限代理录音入口）
        val callingHasPermission = checkCallingOrSelfPermission(
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!callingHasPermission) {
            Log.w(TAG, "Calling app missing RECORD_AUDIO permission")
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // 检查是否有活动会话
        // 根据 SpeechRecognizer 文档，在上一次会话触发 onResults/onError 之前
        // 再次调用 startListening 应当直接返回 ERROR_RECOGNIZER_BUSY
        if (currentSession != null) {
            Log.w(TAG, "Recognizer busy - session already running")
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        // 解析 RecognizerIntent 参数
        val config = parseRecognizerIntent(intent)
        Log.d(
            TAG,
            "Parsed external config: language=${config.language}, partialResults=${config.partialResults}",
        )
        // language 等参数仅用于日志/回调控制（如是否返回 partialResults），
        // 不会影响内部供应商选择、本地/云端模型或具体识别配置，相关行为完全由 Prefs 决定。

        // 创建会话（先创建以便作为 listener 传递给引擎）
        val session = RecognitionSession(
            callback = callback,
            config = config,
        )

        // 构建引擎
        val engineContext = createEngineContext(callback)
        val engine = buildEngine(engineContext, session)
        if (engine == null) {
            Log.e(TAG, "Failed to build ASR engine")
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        // 设置引擎并激活会话
        session.setEngine(engine)
        currentSession = session

        // 通知就绪
        try {
            callback.readyForSpeech(Bundle())
        } catch (t: Throwable) {
            Log.w(TAG, "readyForSpeech callback failed", t)
        }

        // 启动引擎
        session.start()
    }

    override fun onStopListening(callback: Callback) {
        Log.d(TAG, "onStopListening called")
        currentSession?.stop()
    }

    override fun onCancel(callback: Callback) {
        Log.d(TAG, "onCancel called")
        currentSession?.cancel()
        currentSession = null
    }

    /**
     * 解析 RecognizerIntent 参数
     */
    private fun parseRecognizerIntent(intent: Intent): RecognitionConfig {
        // 语言选择优先级：EXTRA_LANGUAGE > EXTRA_LANGUAGE_PREFERENCE > 默认（null 表示走应用内配置/自动检测）
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?: intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
        val partialResults = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        val maxResults = intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1).coerceAtLeast(1)
        return RecognitionConfig(
            language = language,
            partialResults = partialResults,
            maxResults = maxResults,
        )
    }

    private fun createEngineContext(callback: Callback): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
        return try {
            Api31.createAttributionContext(this, callback)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create attribution context; fallback to service context", t)
            this
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun createAttributionContext(service: Context, callback: Callback): Context {
            val params = ContextParams.Builder()
                .setNextAttributionSource(callback.callingAttributionSource)
                .build()
            return service.createContext(params)
        }
    }

    /**
     * 构建 ASR 引擎（复用 ExternalSpeechService 的逻辑）
     */
    private fun buildEngine(engineContext: Context, listener: StreamingAsrEngine.Listener): StreamingAsrEngine? {
        val vendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val backupEnabled = shouldUseBackupAsr(vendor, backupVendor)
        if (backupEnabled) {
            return ParallelAsrEngine(
                context = engineContext,
                scope = serviceScope,
                prefs = prefs,
                listener = listener,
                primaryVendor = vendor,
                backupVendor = backupVendor,
            )
        }

        val streamingPref = resolveStreamingBySettings(vendor)
        val scope = serviceScope

        return when (vendor) {
            AsrVendor.Volc -> if (streamingPref) {
                VolcStreamAsrEngine(engineContext, scope, prefs, listener)
            } else {
                if (prefs.volcFileStandardEnabled) {
                    VolcStandardFileAsrEngine(engineContext, scope, prefs, listener)
                } else {
                    VolcFileAsrEngine(engineContext, scope, prefs, listener)
                }
            }
            AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(engineContext, scope, prefs, listener)
            AsrVendor.ElevenLabs -> if (streamingPref) {
                ElevenLabsStreamAsrEngine(engineContext, scope, prefs, listener)
            } else {
                ElevenLabsFileAsrEngine(engineContext, scope, prefs, listener)
            }
            AsrVendor.OpenAI -> OpenAiFileAsrEngine(engineContext, scope, prefs, listener)
            AsrVendor.DashScope -> if (streamingPref) {
                DashscopeStreamAsrEngine(engineContext, scope, prefs, listener)
            } else {
                DashscopeFileAsrEngine(engineContext, scope, prefs, listener)
            }
            AsrVendor.Gemini -> GeminiFileAsrEngine(engineContext, scope, prefs, listener)
            AsrVendor.Soniox -> if (streamingPref) {
                SonioxStreamAsrEngine(engineContext, scope, prefs, listener)
            } else {
                SonioxFileAsrEngine(engineContext, scope, prefs, listener)
            }
            AsrVendor.Zhipu -> ZhipuFileAsrEngine(engineContext, scope, prefs, listener)
            AsrVendor.SenseVoice -> {
                if (prefs.svPseudoStreamEnabled) {
                    SenseVoicePseudoStreamAsrEngine(engineContext, scope, prefs, listener)
                } else {
                    SenseVoiceFileAsrEngine(engineContext, scope, prefs, listener)
                }
            }
            AsrVendor.FunAsrNano -> {
                // FunASR Nano 模型算力开销高：不支持伪流式预览，仅保留整段离线识别
                FunAsrNanoFileAsrEngine(engineContext, scope, prefs, listener)
            }
            AsrVendor.Telespeech -> {
                if (prefs.tsPseudoStreamEnabled) {
                    TelespeechPseudoStreamAsrEngine(engineContext, scope, prefs, listener)
                } else {
                    TelespeechFileAsrEngine(engineContext, scope, prefs, listener)
                }
            }
            AsrVendor.Paraformer -> ParaformerStreamAsrEngine(engineContext, scope, prefs, listener)
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

    /**
     * 根据设置决定是否使用流式模式
     */
    private fun resolveStreamingBySettings(vendor: AsrVendor): Boolean {
        return when (vendor) {
            AsrVendor.Volc -> prefs.volcStreamingEnabled
            AsrVendor.DashScope -> prefs.isDashStreamingModelSelected()
            AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
            AsrVendor.ElevenLabs -> prefs.elevenStreamingEnabled
            AsrVendor.Paraformer -> true
            AsrVendor.SenseVoice, AsrVendor.FunAsrNano, AsrVendor.Telespeech -> false
            AsrVendor.OpenAI, AsrVendor.Gemini, AsrVendor.SiliconFlow, AsrVendor.Zhipu -> false
        }
    }

    /**
     * 将内部错误消息映射到 SpeechRecognizer 标准错误码
     */
    private fun mapToSpeechRecognizerError(message: String): Int {
        return when {
            message.contains("permission", ignoreCase = true) ->
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            message.contains("network", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true) ||
                message.contains("connect", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NETWORK
            message.contains("audio", ignoreCase = true) ||
                message.contains("microphone", ignoreCase = true) ||
                message.contains("record", ignoreCase = true) ->
                SpeechRecognizer.ERROR_AUDIO
            message.contains("busy", ignoreCase = true) ->
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            message.contains("empty", ignoreCase = true) ||
                message.contains("no speech", ignoreCase = true) ||
                message.contains("no match", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NO_MATCH
            message.contains("server", ignoreCase = true) ||
                message.contains("api", ignoreCase = true) ->
                SpeechRecognizer.ERROR_SERVER
            else -> SpeechRecognizer.ERROR_CLIENT
        }
    }

    /**
     * RecognizerIntent 配置
     */
    private data class RecognitionConfig(
        val language: String?,
        val partialResults: Boolean,
        val maxResults: Int,
    )

    /**
     * 识别会话 - 桥接 StreamingAsrEngine.Listener 到 RecognitionService.Callback
     */
    private inner class RecognitionSession(
        private val callback: Callback,
        private val config: RecognitionConfig,
    ) : StreamingAsrEngine.Listener {

        private var engine: StreamingAsrEngine? = null
        private var autoStopSuppression: AutoCloseable? = null

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        private var canceled: Boolean = false

        @Volatile
        private var finished: Boolean = false

        @Volatile
        private var finalReceived: Boolean = false

        private var speechDetected = false
        private var endOfSpeechDelivered = false

        private var sessionStartUptimeMs: Long = 0L
        private var lastAudioMsForTimeout: Long = 0L
        private var processingTimeoutJob: Job? = null
        private var lastPostprocPreview: String? = null

        private fun ensureAutoStopSuppressed() {
            if (autoStopSuppression != null) return
            autoStopSuppression = VadAutoStopGuard.acquire()
        }

        private fun releaseAutoStopSuppression() {
            val token = autoStopSuppression ?: return
            autoStopSuppression = null
            try {
                token.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to release auto-stop suppression", t)
            }
        }

        fun setEngine(engine: StreamingAsrEngine) {
            this.engine = engine
        }

        fun start() {
            isActive = true
            canceled = false
            finished = false
            finalReceived = false
            speechDetected = false
            endOfSpeechDelivered = false
            sessionStartUptimeMs = SystemClock.uptimeMillis()
            lastAudioMsForTimeout = 0L
            lastPostprocPreview = null
            cancelProcessingTimeout()
            ensureAutoStopSuppressed()
            engine?.start()
        }

        fun stop() {
            // 停止录音阶段时标记会话为非活动，避免异常情况下卡在 BUSY 状态
            isActive = false
            releaseAutoStopSuppression()
            snapshotAudioMsForTimeoutIfNeeded()
            deliverEndOfSpeechIfNeeded()
            scheduleProcessingTimeoutIfNeeded()
            engine?.stop()
        }

        fun cancel() {
            isActive = false
            canceled = true
            finished = true
            releaseAutoStopSuppression()
            cancelProcessingTimeout()
            try {
                engine?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Engine stop failed on cancel", t)
            }
        }

        override fun onFinal(text: String) {
            if (canceled || finished || finalReceived) return
            Log.d(TAG, "onFinal: $text")
            isActive = false
            finalReceived = true
            releaseAutoStopSuppression()
            cancelProcessingTimeout()

            val usedBackupResult = (engine as? ParallelAsrEngine)?.wasLastResultFromBackup() == true

            val doAi = try {
                prefs.postProcessEnabled && prefs.hasLlmKeys()
            } catch (_: Throwable) {
                false
            }

            serviceScope.launch {
                if (canceled || finished) return@launch
                val processedText = if (doAi) {
                    val allowPartial = config.partialResults
                    val typewriterEnabled = allowPartial && (
                        try {
                            prefs.postprocTypewriterEnabled
                        } catch (_: Throwable) {
                            true
                        }
                        )
                    var postprocCommitted = false
                    var lastPostprocTarget: String? = null
                    val typewriter = if (typewriterEnabled) {
                        TypewriterTextAnimator(
                            scope = this,
                            onEmit = emit@{ typed ->
                                if (canceled || finished || postprocCommitted) return@emit
                                if (typed.isEmpty() || typed == lastPostprocPreview) return@emit
                                lastPostprocPreview = typed
                                deliverPartialResults(typed)
                            },
                            frameDelayMs = 20L,
                            idleStopDelayMs = 1200L,
                        )
                    } else {
                        null
                    }
                    val onStreamingUpdate: ((String) -> Unit)? = if (allowPartial) {
                        onStreamingUpdate@{ streamed ->
                            if (canceled || finished || postprocCommitted) return@onStreamingUpdate
                            if (streamed.isEmpty() || streamed == lastPostprocTarget) return@onStreamingUpdate
                            lastPostprocTarget = streamed
                            if (typewriter != null) {
                                typewriter.submit(streamed)
                            } else {
                                if (streamed.isEmpty() || streamed == lastPostprocPreview) return@onStreamingUpdate
                                lastPostprocPreview = streamed
                                deliverPartialResults(streamed)
                            }
                        }
                    } else {
                        null
                    }
                    try {
                        val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                            this@AsrRecognitionService,
                            prefs,
                            text,
                            onStreamingUpdate = onStreamingUpdate,
                        )
                        val aiUsed = (res.usedAi && res.ok)
                        val finalOut = res.text.ifBlank {
                            try {
                                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                                    this@AsrRecognitionService,
                                    prefs,
                                    text,
                                )
                            } catch (_: Throwable) {
                                text
                            }
                        }
                        if (typewriter != null && aiUsed && finalOut.isNotEmpty()) {
                            typewriter?.submit(finalOut, rush = true)
                            val finalLen = finalOut.length
                            val t0 = SystemClock.uptimeMillis()
                            while (!canceled && !finished && (SystemClock.uptimeMillis() - t0) < 2_000L &&
                                typewriter?.currentText()?.length != finalLen
                            ) {
                                delay(20)
                            }
                        }
                        finalOut
                    } catch (t: Throwable) {
                        Log.w(TAG, "applyWithAi failed, fallback to simple", t)
                        try {
                            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                                this@AsrRecognitionService,
                                prefs,
                                text,
                            )
                        } catch (_: Throwable) {
                            text
                        }
                    } finally {
                        postprocCommitted = true
                        typewriter?.cancel()
                    }
                } else {
                    try {
                        com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                            this@AsrRecognitionService,
                            prefs,
                            text,
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "Post-processing failed", t)
                        text
                    }
                }

                if (canceled || finished) return@launch

                // 构建结果 Bundle
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(processedText),
                    )
                    // 可选：添加置信度分数
                    putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        // 单结果，置信度设为 1.0
                        floatArrayOf(1.0f),
                    )
                    putBoolean(EXTRA_USED_BACKUP_ASR, usedBackupResult)
                }

                try {
                    callback.results(results)
                } catch (t: Throwable) {
                    Log.w(TAG, "results callback failed", t)
                }

                finished = true
                // 清理会话
                if (currentSession === this@RecognitionSession) {
                    currentSession = null
                }
            }
        }

        override fun onPartial(text: String) {
            if (canceled || finished || finalReceived) return
            if (text.isEmpty()) return
            Log.d(TAG, "onPartial: $text")

            // 首次检测到语音时通知
            if (!speechDetected) {
                speechDetected = true
                try {
                    callback.beginningOfSpeech()
                } catch (t: Throwable) {
                    Log.w(TAG, "beginningOfSpeech callback failed", t)
                }
            }

            // 仅当请求了部分结果时才回调
            deliverPartialResults(text)
        }

        override fun onError(message: String) {
            if (canceled || finished || finalReceived) return
            Log.e(TAG, "onError: $message")
            isActive = false
            finished = true
            releaseAutoStopSuppression()
            cancelProcessingTimeout()

            val errorCode = mapToSpeechRecognizerError(message)
            try {
                callback.error(errorCode)
            } catch (t: Throwable) {
                Log.w(TAG, "error callback failed", t)
            }

            // 清理会话
            if (currentSession === this) {
                currentSession = null
            }
        }

        override fun onStopped() {
            if (canceled || finished || finalReceived) return
            Log.d(TAG, "onStopped")
            // 保底将会话标记为非活动，避免仅收到 onStopped 时长期占用 BUSY 状态
            isActive = false
            releaseAutoStopSuppression()
            snapshotAudioMsForTimeoutIfNeeded()
            deliverEndOfSpeechIfNeeded()
            scheduleProcessingTimeoutIfNeeded()
        }

        override fun onAmplitude(amplitude: Float) {
            if (canceled || finished) return
            // 将 0.0-1.0 映射到 Android 惯例的 RMS 范围 (-2.0 to 10.0)
            val rms = -2f + amplitude * 12f
            try {
                callback.rmsChanged(rms)
            } catch (t: Throwable) {
                // RMS 回调失败通常不需要记录
            }
        }

        private fun snapshotAudioMsForTimeoutIfNeeded() {
            if (lastAudioMsForTimeout != 0L) return
            val t0 = sessionStartUptimeMs
            if (t0 <= 0L) return
            lastAudioMsForTimeout = (SystemClock.uptimeMillis() - t0).coerceAtLeast(0L)
            sessionStartUptimeMs = 0L
        }

        private fun deliverEndOfSpeechIfNeeded() {
            if (endOfSpeechDelivered) return
            endOfSpeechDelivered = true
            try {
                callback.endOfSpeech()
            } catch (t: Throwable) {
                Log.w(TAG, "endOfSpeech callback failed", t)
            }
        }

        private fun cancelProcessingTimeout() {
            try {
                processingTimeoutJob?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Cancel processing timeout failed", t)
            } finally {
                processingTimeoutJob = null
            }
        }

        private fun scheduleProcessingTimeoutIfNeeded() {
            // stop->processing 后必须最终回调 results/error；否则会卡住 currentSession，导致后续 startListening 永久 BUSY。
            cancelProcessingTimeout()
            val audioMs = lastAudioMsForTimeout
            val baseTimeoutMs = com.brycewg.asrkb.asr.AsrTimeoutCalculator.calculateTimeoutMs(audioMs)
            val timeoutMs = if (engine is ParallelAsrEngine) baseTimeoutMs + 2_000L else baseTimeoutMs
            processingTimeoutJob = serviceScope.launch {
                val usingBackupEngine = engine is ParallelAsrEngine
                val shouldDeferForLocalModel = !usingBackupEngine && isLocalAsrVendor(prefs.asrVendor)
                if (shouldDeferForLocalModel) {
                    // 本地模型：将超时计时起点推移到“模型加载完成”之后，避免首次加载期间误触发超时
                    val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                    if (!ok) {
                        // 读取配置失败等异常场景：回退为原有策略（不阻塞、继续计时）
                        Log.w(TAG, "awaitLocalAsrReady returned false, fallback to immediate timeout countdown")
                    }
                    if (canceled || finished) return@launch
                    if (currentSession !== this@RecognitionSession) return@launch
                }
                delay(timeoutMs)
                if (canceled || finished) return@launch
                if (currentSession !== this@RecognitionSession) return@launch

                Log.w(TAG, "Processing timeout fired (audioMs=$audioMs, timeoutMs=$timeoutMs), forcing session end")
                finished = true
                try {
                    engine?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine stop failed on processing timeout", t)
                }
                try {
                    // 兜底：以标准错误结束会话，避免客户端永久等待。
                    callback.error(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                } catch (t: Throwable) {
                    Log.w(TAG, "error callback failed on processing timeout", t)
                } finally {
                    if (currentSession === this@RecognitionSession) {
                        currentSession = null
                    }
                }
            }
        }

        private fun deliverPartialResults(text: String) {
            if (!config.partialResults) return
            val partialBundle = Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(text),
                )
            }
            try {
                callback.partialResults(partialBundle)
            } catch (t: Throwable) {
                Log.w(TAG, "partialResults callback failed", t)
            }
        }
    }
}
