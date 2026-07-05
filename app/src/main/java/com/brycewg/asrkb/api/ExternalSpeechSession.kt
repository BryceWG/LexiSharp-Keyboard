/**
 * 外部语音识别共享会话，供说点啥自有 AIDL 与 fxliang fcitx5 Provider AIDL 复用。
 *
 * 归属模块：api
 */
package com.brycewg.asrkb.api

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.getAsrRuntimeStatsSnapshotOrNull
import com.brycewg.asrkb.store.recordPrimaryAsrRuntimeRequestIfSuccessful
import com.brycewg.asrkb.util.TypewriterTextAnimator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface ExternalSpeechCallbacks {
    fun onState(sessionId: Int, state: Int, message: String)
    fun onPartial(sessionId: Int, text: String)
    fun onFinal(sessionId: Int, text: String)
    fun onError(sessionId: Int, code: Int, message: String)
    fun onAmplitude(sessionId: Int, amplitude: Float)
    fun onSessionDone(sessionId: Int)
}

internal object ExternalSpeechSessionState {
    const val IDLE = 0
    const val RECORDING = 1
    const val PROCESSING = 2
    const val ERROR = 3
}

private const val TAG = "ExternalSpeechSession"
private const val STATE_IDLE = ExternalSpeechSessionState.IDLE
private const val STATE_RECORDING = ExternalSpeechSessionState.RECORDING
private const val STATE_PROCESSING = ExternalSpeechSessionState.PROCESSING
private const val STATE_ERROR = ExternalSpeechSessionState.ERROR
private const val LOCAL_MODEL_READY_WAIT_MAX_MS = 60_000L
private const val LOCAL_MODEL_READY_WAIT_CONSUMED = -1L

private inline fun safe(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "callback failed", t)
    }
}

internal class ExternalSpeechSession(
    private val id: Int,
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: ExternalSpeechCallbacks
) : StreamingAsrEngine.Listener,
    BackupAsrStatusListener {
    var engine: StreamingAsrEngine? = null
    private var autoStopSuppression: AutoCloseable? = null

    // 统计：录音起止与耗时（用于历史记录展示）
    private var sessionStartUptimeMs: Long = 0L
    private var sessionStartTotalUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L
    private var lastRequestDurationMs: Long? = null
    private var lastPostprocPreview: String? = null
    private var vendor: AsrVendor? = null
    private var processingStartUptimeMs: Long = 0L
    private var processingEndUptimeMs: Long = 0L
    private var localModelWaitStartUptimeMs: Long = 0L
    private val localModelReadyWaitMs = AtomicLong(0L)
    private var localModelReadyWaitJob: Job? = null
    private var pcmBytesForStats: Long = 0L
    private val sessionJob = SupervisorJob()
    private val sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)
    private val processingTimeoutLock = Any()
    private val parallelEngineFactory = AsrParallelEngineFactory()
    private val directMicrophoneEngineFactory = AsrDirectMicrophoneEngineFactory()
    private val pushPcmEngineFactory = AsrPushPcmEngineFactory()

    @Volatile private var processingTimeoutJob: Job? = null

    @Volatile private var finished: Boolean = false

    @Volatile private var canceled: Boolean = false

    @Volatile private var hasAsrPartial: Boolean = false

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

    private fun cancelLocalModelReadyWait() {
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed", t)
        } finally {
            localModelReadyWaitJob = null
        }
    }

    private fun cancelProcessingTimeout() {
        val job = synchronized(processingTimeoutLock) {
            val current = processingTimeoutJob
            processingTimeoutJob = null
            current
        }
        if (job != null) {
            try {
                job.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Cancel processing timeout failed", t)
            }
        }
    }

    private fun markLocalModelProcessingStartIfNeeded() {
        val vendorSnapshot = vendor ?: return
        if (!isLocalAsrVendor(vendorSnapshot)) return
        if (localModelWaitStartUptimeMs != 0L) return

        val startMs = if (processingStartUptimeMs >
            0L
        ) {
            processingStartUptimeMs
        } else {
            SystemClock.uptimeMillis()
        }
        localModelWaitStartUptimeMs = startMs
        localModelReadyWaitMs.set(0L)

        // 已就绪：无需等待
        if (isLocalAsrReady(prefs)) return

        cancelLocalModelReadyWait()
        localModelReadyWaitJob = sessionScope.launch {
            val ok =
                awaitLocalAsrReady(
                    prefs,
                    pollIntervalMs = 10L,
                    maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS
                )
            if (!ok) return@launch
            if (canceled) return@launch
            val readyAt = SystemClock.uptimeMillis()
            if (readyAt >= startMs) {
                localModelReadyWaitMs.compareAndSet(0L, (readyAt - startMs).coerceAtLeast(0L))
            }
        }
    }

    private fun onRequestDuration(ms: Long) {
        val waitMs = localModelReadyWaitMs.getAndSet(LOCAL_MODEL_READY_WAIT_CONSUMED)
        val adjusted = if (waitMs > 0L && ms > waitMs) ms - waitMs else ms
        lastRequestDurationMs = adjusted
        // 仅对首次“等待模型就绪”的请求做一次扣减，避免后续分段请求被重复扣除
        cancelLocalModelReadyWait()
    }

    private fun computeProcMsForStats(): Long {
        val fromEngine = lastRequestDurationMs
        if (fromEngine != null) return fromEngine
        val start = processingStartUptimeMs
        val end = processingEndUptimeMs
        if (start <= 0L || end <= 0L || end < start) return 0L
        val total = (end - start).coerceAtLeast(0L)
        val wait = localModelReadyWaitMs.get().coerceAtLeast(0L)
        return (total - wait).coerceAtLeast(0L)
    }

    private fun popTotalElapsedMsForStats(): Long {
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
        sessionStartTotalUptimeMs = if (engine?.isRunning == true) now else 0L
        return elapsed
    }

    private fun resolveFinalVendorForRecord(): AsrVendor {
        val e = engine
        return when (e) {
            is BackupAwareAsrEngine -> if (e.wasLastResultFromBackup()) e.backupVendor else e.primaryVendor
            else -> vendor ?: try {
                prefs.asrVendor
            } catch (_: Throwable) {
                AsrVendor.Volc
            }
        }
    }

    private fun scheduleProcessingTimeoutIfNeeded() {
        val audioMs = lastAudioMsForStats
        val backupEngine = engine as? BackupAwareAsrEngine
        val primaryVendor = backupEngine?.primaryVendor ?: vendor
        val backupVendor = backupEngine?.backupVendor
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = audioMs,
            primaryVendor = primaryVendor,
            primaryStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(primaryVendor, audioMs),
            backupStrategy = backupEngine?.backupStrategy,
            backupVendor = backupVendor,
            backupStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(backupVendor, audioMs),
            sensitivityTier = safeBackupSensitivityTier(),
            primaryStreaming = backupEngine?.primaryStreamingForSwitchPlan ?: true
        )
        synchronized(processingTimeoutLock) {
            if (processingTimeoutJob != null) return
            processingTimeoutJob = sessionScope.launch {
                val shouldDeferForLocalModel =
                    backupEngine == null && (vendor?.let { isLocalAsrVendor(it) } ?: false)
                if (shouldDeferForLocalModel) {
                    // 本地模型：将超时计时起点推移到“模型加载完成”之后，避免首次加载期间误触发超时
                    val ok =
                        awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                    if (!ok) {
                        Log.w(
                            TAG,
                            "awaitLocalAsrReady returned false, fallback to immediate timeout countdown"
                        )
                    }
                    if (canceled || finished) return@launch
                }
                delay(timeoutMs)
                if (canceled || finished) return@launch

                val msg = try {
                    context.getString(R.string.error_asr_timeout)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to get timeout string", t)
                    "timeout"
                }
                Log.w(TAG, "Processing timeout fired (audioMs=$audioMs, timeoutMs=$timeoutMs)")
                finished = true
                try {
                    engine?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine stop failed on processing timeout", t)
                }
                safe {
                    callbacks.onError(id, 408, msg)
                    callbacks.onState(id, STATE_ERROR, msg)
                }
                try {
                    callbacks.onSessionDone(id)
                } catch (t: Throwable) {
                    Log.w(TAG, "remove session on timeout failed", t)
                } finally {
                    try {
                        sessionJob.cancel()
                    } catch (t: Throwable) {
                        Log.w(TAG, "sessionJob.cancel failed on timeout", t)
                    }
                }
            }
        }
    }

    fun prepare(): Boolean {
        // 完全跟随应用内当前设置：供应商与是否流式均以 Prefs 为准
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        this.vendor = primaryVendor
        engine = parallelEngineFactory.createOrNull(
            context = context,
            scope = CoroutineScope(sessionJob + Dispatchers.Main),
            prefs = prefs,
            listener = this,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = false,
            onPrimaryRequestDuration = ::onRequestDuration
        ) ?: directMicrophoneEngineFactory.createOrNull(
            context = context,
            scope = CoroutineScope(Dispatchers.Main),
            prefs = prefs,
            listener = this,
            vendor = primaryVendor,
            preferences = prefs.asrEngineModePreferencesSnapshot(),
            source = AsrEngineConstructionSource.ExternalIntegration,
            onRequestDuration = ::onRequestDuration
        )
        return engine != null
    }

    fun preparePushPcm(): Boolean {
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        this.vendor = primaryVendor
        engine = parallelEngineFactory.createOrNull(
            context = context,
            scope = CoroutineScope(sessionJob + Dispatchers.Main),
            prefs = prefs,
            listener = this,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = true,
            onPrimaryRequestDuration = ::onRequestDuration
        ) ?: pushPcmEngineFactory.createOrNull(
            context = context,
            scope = CoroutineScope(Dispatchers.Main),
            prefs = prefs,
            listener = this,
            vendor = primaryVendor,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = prefs.asrEngineModePreferencesSnapshot(),
            source = AsrEngineConstructionSource.ExternalIntegration,
            onRequestDuration = ::onRequestDuration
        )
        return engine != null
    }

    fun start() {
        safe { callbacks.onState(id, STATE_RECORDING, "recording") }
        try {
            sessionStartUptimeMs = SystemClock.uptimeMillis()
            sessionStartTotalUptimeMs = sessionStartUptimeMs
            // 新会话开始时重置上次请求耗时，避免串台（流式模式不会更新此值）
            lastRequestDurationMs = null
            lastAudioMsForStats = 0L
            lastPostprocPreview = null
            processingStartUptimeMs = 0L
            processingEndUptimeMs = 0L
            localModelWaitStartUptimeMs = 0L
            localModelReadyWaitMs.set(0L)
            pcmBytesForStats = 0L
            cancelLocalModelReadyWait()
            canceled = false
            hasAsrPartial = false
            finished = false
            cancelProcessingTimeout()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to mark session start", t)
        }
        ensureAutoStopSuppressed()
        engine?.let { startedEngine ->
            preloadLocalAsrForImmediateUse(context, prefs)
            startedEngine.start()
        }
    }

    fun stop() {
        if (canceled || finished) return
        releaseAutoStopSuppression()
        // 记录一次会话录音时长（用于超时与统计）；部分引擎 stop() 不会回调 onStopped（如外部推流的本地流式），因此这里也做一次兜底快照。
        if (sessionStartUptimeMs > 0L) {
            try {
                if (lastAudioMsForStats == 0L) {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(
                        0
                    )
                    lastAudioMsForStats = dur
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on stop()", t)
            } finally {
                sessionStartUptimeMs = 0L
            }
        }
        if (processingStartUptimeMs == 0L) {
            processingStartUptimeMs = SystemClock.uptimeMillis()
        }
        markLocalModelProcessingStartIfNeeded()
        scheduleProcessingTimeoutIfNeeded()
        engine?.stop()
        safe { callbacks.onState(id, STATE_PROCESSING, "processing") }
    }

    fun cancel() {
        canceled = true
        finished = true
        releaseAutoStopSuppression()
        cancelLocalModelReadyWait()
        cancelProcessingTimeout()
        try {
            engine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Engine stop failed on cancel", t)
        }
        safe { callbacks.onState(id, STATE_IDLE, "canceled") }
        try {
            sessionJob.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "sessionJob.cancel failed on cancel", t)
        }
    }

    fun onPcmFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
        val e = engine
        if (e !is com.brycewg.asrkb.asr.ExternalPcmConsumer) return

        if (sampleRate > 0 && channels > 0 && pcm.isNotEmpty()) {
            pcmBytesForStats += pcm.size.toLong()
            val denom = sampleRate.toLong() * channels.toLong() * 2L
            if (denom > 0L) {
                lastAudioMsForStats = (pcmBytesForStats * 1000L / denom).coerceAtLeast(0L)
            }
        }
        try {
            e.appendPcm(pcm, sampleRate, channels)
        } catch (t: Throwable) {
            Log.w(TAG, "appendPcm failed for sid=$id", t)
        }
    }

    override fun onFinal(text: String) {
        if (canceled || finished) return
        finished = true
        releaseAutoStopSuppression()
        cancelProcessingTimeout()
        processingEndUptimeMs = SystemClock.uptimeMillis()
        // 若尚未收到 onStopped，则以当前时间近似计算一次时长
        if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
            try {
                val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                lastAudioMsForStats = dur
                sessionStartUptimeMs = 0L
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on final", t)
            }
        }
        prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
            engine = engine,
            fallbackPrimaryVendor = vendor ?: AsrVendor.Volc,
            audioMs = lastAudioMsForStats,
            requestMs = lastRequestDurationMs
        )
        val doAi = try {
            prefs.postProcessEnabled && prefs.hasLlmKeys()
        } catch (
            _: Throwable
        ) {
            false
        }
        if (doAi) {
            if (!hasAsrPartial && text.isNotEmpty()) {
                hasAsrPartial = true
                safe { callbacks.onPartial(id, text) }
            }
            // 执行带 AI 的完整后处理链（IO 在线程内切换）
            CoroutineScope(Dispatchers.Main).launch {
                if (canceled) return@launch
                val typewriterEnabled = try {
                    prefs.postprocTypewriterEnabled
                } catch (
                    _: Throwable
                ) {
                    true
                }
                var postprocCommitted = false
                var lastPostprocTarget: String? = null
                val typewriter = if (typewriterEnabled) {
                    TypewriterTextAnimator(
                        scope = this,
                        onEmit = emit@{ typed ->
                            if (canceled || postprocCommitted) return@emit
                            if (typed.isEmpty() || typed == lastPostprocPreview) return@emit
                            lastPostprocPreview = typed
                            safe { callbacks.onPartial(id, typed) }
                        },
                        frameDelayMs = 35L,
                        idleStopDelayMs = 1200L,
                        normalTargetFrames = 18,
                        normalMaxStep = 6,
                        rushTargetFrames = 8,
                        rushMaxStep = 24
                    )
                } else {
                    null
                }
                val onStreamingUpdate: (String) -> Unit = onStreamingUpdate@{ streamed ->
                    if (canceled || postprocCommitted) return@onStreamingUpdate
                    if (streamed.isEmpty() ||
                        streamed == lastPostprocTarget
                    ) {
                        return@onStreamingUpdate
                    }
                    lastPostprocTarget = streamed
                    if (typewriter != null) {
                        typewriter.submit(streamed)
                    } else {
                        if (streamed.isEmpty() ||
                            streamed == lastPostprocPreview
                        ) {
                            return@onStreamingUpdate
                        }
                        lastPostprocPreview = streamed
                        safe { callbacks.onPartial(id, streamed) }
                    }
                }
                var aiUsed = false
                var aiPostMs = 0L
                var aiPostStatus = com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.NONE
                val out = try {
                    val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                        context,
                        prefs,
                        text,
                        onStreamingUpdate = onStreamingUpdate
                    )
                    aiUsed = (res.usedAi && res.ok)
                    aiPostMs = if (res.attempted) res.llmMs else 0L
                    aiPostStatus = when {
                        res.attempted && aiUsed -> com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.SUCCESS
                        res.attempted -> com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.FAILED
                        else -> com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.NONE
                    }

                    val processed = res.text
                    val finalOut = processed.ifBlank {
                        // AI 返回空：回退到简单后处理（包含正则/繁体）
                        try {
                            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                                context,
                                prefs,
                                text
                            )
                        } catch (_: Throwable) {
                            text
                        }
                    }
                    if (typewriter != null && aiUsed && finalOut.isNotEmpty()) {
                        typewriter.submit(finalOut, rush = true)
                        val finalLen = finalOut.length
                        val t0 = SystemClock.uptimeMillis()
                        while (!canceled &&
                            (SystemClock.uptimeMillis() - t0) < 2_000L &&
                            typewriter.currentText().length != finalLen
                        ) {
                            delay(20)
                        }
                    }
                    finalOut
                } catch (t: Throwable) {
                    Log.w(TAG, "applyWithAi failed, fallback to simple", t)
                    aiUsed = false
                    aiPostMs = 0L
                    aiPostStatus = com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.FAILED
                    try {
                        com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                    } catch (_: Throwable) {
                        text
                    }
                } finally {
                    postprocCommitted = true
                    typewriter?.cancel()
                }
                if (canceled) return@launch
                // 记录使用统计与识别历史（来源标记为 external；尊重开关）
                try {
                    val audioMs = lastAudioMsForStats
                    val totalElapsedMs = popTotalElapsedMsForStats()
                    val procMs = computeProcMsForStats()
                    val chars = try {
                        com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out)
                    } catch (
                        _: Throwable
                    ) {
                        out.length
                    }
                    val vendorForRecord = resolveFinalVendorForRecord()
                    AnalyticsManager.recordAsrEvent(
                        context = context,
                        vendorId = vendorForRecord.id,
                        audioMs = audioMs,
                        procMs = procMs,
                        source = "external",
                        aiProcessed = aiUsed,
                        charCount = chars
                    )
                    if (!prefs.disableUsageStats) {
                        prefs.recordUsageCommit(
                            "external",
                            vendorForRecord,
                            audioMs,
                            chars,
                            procMs
                        )
                    }
                    if (!prefs.disableAsrHistory) {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                timestamp = System.currentTimeMillis(),
                                text = out,
                                vendorId = vendorForRecord.id,
                                audioMs = audioMs,
                                totalElapsedMs = totalElapsedMs,
                                procMs = procMs,
                                source = "external",
                                aiProcessed = aiUsed,
                                aiPostMs = aiPostMs,
                                aiPostStatus = aiPostStatus,
                                charCount = chars
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add ASR history (external, ai)", e)
                }
                if (canceled) return@launch
                safe { callbacks.onFinal(id, out) }
                safe { callbacks.onState(id, STATE_IDLE, "final") }
                try {
                    callbacks.onSessionDone(id)
                } catch (
                    t: Throwable
                ) {
                    Log.w(TAG, "remove session on final failed", t)
                }
            }
        } else {
            if (canceled) return
            // 应用简单末处理：去尾标点和预置替换
            val out = try {
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
            } catch (t: Throwable) {
                Log.w(TAG, "applySimple failed, fallback to raw text", t)
                text
            }
            // 记录使用统计与识别历史（来源标记为 external；尊重开关）
            try {
                val audioMs = lastAudioMsForStats
                val totalElapsedMs = popTotalElapsedMsForStats()
                val procMs = computeProcMsForStats()
                val chars = try {
                    com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out)
                } catch (
                    _: Throwable
                ) {
                    out.length
                }
                val vendorForRecord = resolveFinalVendorForRecord()
                AnalyticsManager.recordAsrEvent(
                    context = context,
                    vendorId = vendorForRecord.id,
                    audioMs = audioMs,
                    procMs = procMs,
                    source = "external",
                    aiProcessed = false,
                    charCount = chars
                )
                if (!prefs.disableUsageStats) {
                    prefs.recordUsageCommit("external", vendorForRecord, audioMs, chars, procMs)
                }
                if (!prefs.disableAsrHistory) {
                    val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                    store.add(
                        com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                            timestamp = System.currentTimeMillis(),
                            text = out,
                            vendorId = vendorForRecord.id,
                            audioMs = audioMs,
                            totalElapsedMs = totalElapsedMs,
                            procMs = procMs,
                            source = "external",
                            aiProcessed = false,
                            charCount = chars
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add ASR history (external, simple)", e)
            }
            safe { callbacks.onFinal(id, out) }
            safe { callbacks.onState(id, STATE_IDLE, "final") }
            try {
                callbacks.onSessionDone(id)
            } catch (
                t: Throwable
            ) {
                Log.w(TAG, "remove session on final failed", t)
            }
        }
    }

    override fun onError(message: String) {
        if (canceled || finished) return
        finished = true
        releaseAutoStopSuppression()
        processingEndUptimeMs = SystemClock.uptimeMillis()
        cancelLocalModelReadyWait()
        cancelProcessingTimeout()
        safe {
            callbacks.onError(id, 500, message)
            callbacks.onState(id, STATE_ERROR, message)
        }
        try {
            callbacks.onSessionDone(id)
        } catch (
            t: Throwable
        ) {
            Log.w(TAG, "remove session on error failed", t)
        }
    }

    override fun onPartial(text: String) {
        if (canceled || finished) return
        if (text.isNotEmpty()) {
            hasAsrPartial = true
            safe { callbacks.onPartial(id, text) }
        }
    }

    override fun onStopped() {
        if (canceled || finished) return
        // 计算一次会话录音时长
        if (sessionStartUptimeMs > 0L) {
            try {
                val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                lastAudioMsForStats = dur
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to compute audio duration on stop", t)
            } finally {
                sessionStartUptimeMs = 0L
            }
        }
        if (processingStartUptimeMs == 0L) {
            processingStartUptimeMs = SystemClock.uptimeMillis()
        }
        markLocalModelProcessingStartIfNeeded()
        safe { callbacks.onState(id, STATE_PROCESSING, "processing") }
        scheduleProcessingTimeoutIfNeeded()
    }

    override fun onAmplitude(amplitude: Float) {
        if (canceled || finished) return
        safe { callbacks.onAmplitude(id, amplitude) }
    }

    override fun onBackupAsrLoading(backupVendor: AsrVendor) {
        if (canceled || finished) return
        safe { callbacks.onState(id, STATE_PROCESSING, context.getString(R.string.status_backup_asr_loading)) }
    }

    override fun onBackupAsrRecognizing(backupVendor: AsrVendor) {
        if (canceled || finished) return
        safe {
            callbacks.onState(
                id,
                STATE_PROCESSING,
                context.getString(R.string.status_backup_asr_recognizing)
            )
        }
    }

    private fun safeBackupSensitivityTier(): Int = try {
        prefs.backupAsrTimeoutSensitivity
    } catch (_: Throwable) {
        1
    }
}

