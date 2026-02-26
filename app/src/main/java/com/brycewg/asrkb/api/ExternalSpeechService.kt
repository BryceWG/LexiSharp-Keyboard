package com.brycewg.asrkb.api

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.aidl.SpeechConfig
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.AsrTimeoutCalculator
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeStreamAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsStreamAsrEngine
import com.brycewg.asrkb.asr.FunAsrNanoFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.GenericPushFileAsrAdapter
import com.brycewg.asrkb.asr.OpenAiFileAsrEngine
import com.brycewg.asrkb.asr.ParaformerStreamAsrEngine
import com.brycewg.asrkb.asr.ParallelAsrEngine
import com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine
import com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine
import com.brycewg.asrkb.asr.SonioxFileAsrEngine
import com.brycewg.asrkb.asr.SonioxStreamAsrEngine
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.TelespeechFileAsrEngine
import com.brycewg.asrkb.asr.VadAutoStopGuard
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.VolcStandardFileAsrEngine
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.ZhipuFileAsrEngine
import com.brycewg.asrkb.asr.awaitLocalAsrReady
import com.brycewg.asrkb.asr.isLocalAsrReady
import com.brycewg.asrkb.asr.isLocalAsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.TypewriterTextAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 对外导出的语音服务（Binder 手写协议，兼容 AIDL 生成的代理）。
 * - 接口描述符需与 AIDL 一致：com.brycewg.asrkb.aidl.IExternalSpeechService。
 * - 方法顺序与 AIDL 保持一致，以匹配事务码。
 */
class ExternalSpeechService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val sessions = ConcurrentHashMap<Int, Session>()

    @Volatile private var nextId: Int = 1

    override fun onBind(intent: Intent?): IBinder? = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_SVC)
                    return true
                }
                TRANSACTION_START_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    // 开关与权限检查：仅要求开启外部联动
                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    // 联通测试：当 vendorId == "mock" 时，无需录音权限，直接回调固定内容并结束
                    if (cfg?.vendorId == "mock") {
                        val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                        safe { cb.onState(sid, STATE_RECORDING, "recording") }
                        safe { cb.onPartial(sid, "【联通测试中】……") }
                        safe { cb.onFinal(sid, "说点啥外部AIDL联通成功（mock）") }
                        safe { cb.onState(sid, STATE_IDLE, "final") }
                        reply?.apply {
                            writeNoException()
                            writeInt(sid)
                        }
                        return true
                    }

                    val permOk = ContextCompat.checkSelfPermission(
                        this@ExternalSpeechService,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!permOk) {
                        safe { cb.onError(-1, 401, "record permission denied") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-4)
                        }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-2)
                        }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb)
                    if (!s.prepare()) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply {
                        writeNoException()
                        writeInt(sid)
                    }
                    return true
                }
                TRANSACTION_STOP_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_CANCEL_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.cancel()
                    sessions.remove(sid)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_IS_RECORDING -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val r = sessions[sid]?.engine?.isRunning == true
                    reply?.apply {
                        writeNoException()
                        writeInt(if (r) 1 else 0)
                    }
                    return true
                }
                TRANSACTION_IS_ANY_RECORDING -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val r = sessions.values.any { it.engine?.isRunning == true }
                    reply?.apply {
                        writeNoException()
                        writeInt(if (r) 1 else 0)
                    }
                    return true
                }
                TRANSACTION_GET_VERSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    reply?.apply {
                        writeNoException()
                        writeString(com.brycewg.asrkb.BuildConfig.VERSION_NAME)
                    }
                    return true
                }
                // ================= 推送 PCM 模式 =================
                TRANSACTION_START_PCM_SESSION -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply {
                            writeNoException()
                            writeInt(-3)
                        }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-2)
                        }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb)
                    if (!s.preparePushPcm()) {
                        reply?.apply {
                            writeNoException()
                            writeInt(-5)
                        }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply {
                        writeNoException()
                        writeInt(sid)
                    }
                    return true
                }
                TRANSACTION_WRITE_PCM -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val sr = data.readInt()
                    val ch = data.readInt()
                    sessions[sid]?.onPcmFrame(bytes, sr, ch)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_FINISH_PCM -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class Session(
        private val id: Int,
        private val context: Context,
        private val prefs: Prefs,
        private val cb: CallbackProxy,
    ) : StreamingAsrEngine.Listener {
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

            val startMs = if (processingStartUptimeMs > 0L) processingStartUptimeMs else SystemClock.uptimeMillis()
            localModelWaitStartUptimeMs = startMs
            localModelReadyWaitMs.set(0L)

            // 已就绪：无需等待
            if (isLocalAsrReady(prefs)) return

            cancelLocalModelReadyWait()
            localModelReadyWaitJob = sessionScope.launch {
                val ok = awaitLocalAsrReady(prefs, pollIntervalMs = 10L, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
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
                is ParallelAsrEngine -> if (e.wasLastResultFromBackup()) e.backupVendor else e.primaryVendor
                else -> vendor ?: try {
                    prefs.asrVendor
                } catch (_: Throwable) {
                    AsrVendor.Volc
                }
            }
        }

        private fun scheduleProcessingTimeoutIfNeeded() {
            val audioMs = lastAudioMsForStats
            val baseTimeoutMs = AsrTimeoutCalculator.calculateTimeoutMs(audioMs)
            val timeoutMs = if (engine is ParallelAsrEngine) baseTimeoutMs + 2_000L else baseTimeoutMs
            synchronized(processingTimeoutLock) {
                if (processingTimeoutJob != null) return
                processingTimeoutJob = sessionScope.launch {
                    val usingBackupEngine = engine is ParallelAsrEngine
                    val shouldDeferForLocalModel = !usingBackupEngine && (vendor?.let { isLocalAsrVendor(it) } ?: false)
                    if (shouldDeferForLocalModel) {
                        // 本地模型：将超时计时起点推移到“模型加载完成”之后，避免首次加载期间误触发超时
                        val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                        if (!ok) {
                            Log.w(TAG, "awaitLocalAsrReady returned false, fallback to immediate timeout countdown")
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
                        cb.onError(id, 408, msg)
                        cb.onState(id, STATE_ERROR, msg)
                    }
                    try {
                        (context as? ExternalSpeechService)?.onSessionDone(id)
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
            val streamingPref = resolveStreamingBySettings(primaryVendor)
            val backupEnabled = shouldUseBackupAsr(primaryVendor, backupVendor)
            engine = if (backupEnabled) {
                ParallelAsrEngine(
                    context = context,
                    scope = CoroutineScope(sessionJob + Dispatchers.Main),
                    prefs = prefs,
                    listener = this,
                    primaryVendor = primaryVendor,
                    backupVendor = backupVendor,
                    onPrimaryRequestDuration = ::onRequestDuration,
                )
            } else {
                buildEngine(primaryVendor, streamingPref)
            }
            return engine != null
        }

        fun preparePushPcm(): Boolean {
            val primaryVendor = prefs.asrVendor
            val backupVendor = prefs.backupAsrVendor
            this.vendor = primaryVendor
            val streamingPref = resolveStreamingBySettings(primaryVendor)
            val backupEnabled = shouldUseBackupAsr(primaryVendor, backupVendor)
            engine = if (backupEnabled) {
                ParallelAsrEngine(
                    context = context,
                    scope = CoroutineScope(sessionJob + Dispatchers.Main),
                    prefs = prefs,
                    listener = this,
                    primaryVendor = primaryVendor,
                    backupVendor = backupVendor,
                    onPrimaryRequestDuration = ::onRequestDuration,
                    externalPcmInput = true,
                )
            } else {
                buildPushPcmEngine(primaryVendor, streamingPref)
            }
            return engine != null
        }

        fun start() {
            safe { cb.onState(id, STATE_RECORDING, "recording") }
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
            engine?.start()
        }

        fun stop() {
            if (canceled || finished) return
            releaseAutoStopSuppression()
            // 记录一次会话录音时长（用于超时与统计）；部分引擎 stop() 不会回调 onStopped（如外部推流的本地流式），因此这里也做一次兜底快照。
            if (sessionStartUptimeMs > 0L) {
                try {
                    if (lastAudioMsForStats == 0L) {
                        val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
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
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
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
            safe { cb.onState(id, STATE_IDLE, "canceled") }
            try {
                sessionJob.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "sessionJob.cancel failed on cancel", t)
            }
        }

        fun onPcmFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
            val e = engine
            if (e !is com.brycewg.asrkb.asr.ExternalPcmConsumer) return

            if (sampleRate == 16000 && channels == 1 && pcm.isNotEmpty()) {
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

        private fun resolveStreamingBySettings(vendor: AsrVendor): Boolean {
            return when (vendor) {
                AsrVendor.Volc -> prefs.volcStreamingEnabled
                AsrVendor.DashScope -> prefs.isDashStreamingModelSelected()
                AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
                // 本地 sherpa-onnx：Paraformer 仅流式；SenseVoice/FunASR Nano/TeleSpeech 仅非流式
                AsrVendor.Paraformer -> true
                AsrVendor.SenseVoice, AsrVendor.FunAsrNano, AsrVendor.Telespeech -> false
                AsrVendor.ElevenLabs -> prefs.elevenStreamingEnabled
                // 其他云厂商（OpenAI/Gemini/SiliconFlow/Zhipu）仅非流式
                AsrVendor.OpenAI, AsrVendor.Gemini, AsrVendor.SiliconFlow, AsrVendor.Zhipu -> false
            }
        }

        private fun buildEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    VolcStreamAsrEngine(context, scope, prefs, this)
                } else {
                    VolcFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    )
                }
                AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.ElevenLabs -> if (streamingPreferred) {
                    ElevenLabsStreamAsrEngine(context, scope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    )
                }
                AsrVendor.OpenAI -> OpenAiFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.DashScope -> if (streamingPreferred) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    )
                }
                AsrVendor.Gemini -> GeminiFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.Soniox -> if (streamingPreferred) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    )
                }
                AsrVendor.Zhipu -> ZhipuFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.SenseVoice -> SenseVoiceFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.FunAsrNano -> FunAsrNanoFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.Telespeech -> TelespeechFileAsrEngine(
                    context,
                    scope,
                    prefs,
                    this,
                    onRequestDuration = ::onRequestDuration,
                )
                AsrVendor.Paraformer -> ParaformerStreamAsrEngine(context, scope, prefs, this)
            }
        }

        private fun buildPushPcmEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    VolcStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    if (prefs.volcFileStandardEnabled) {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context,
                            scope,
                            prefs,
                            this,
                            com.brycewg.asrkb.asr.VolcStandardFileAsrEngine(
                                context,
                                scope,
                                prefs,
                                this,
                                onRequestDuration = ::onRequestDuration,
                            ),
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context,
                            scope,
                            prefs,
                            this,
                            com.brycewg.asrkb.asr.VolcFileAsrEngine(
                                context,
                                scope,
                                prefs,
                                this,
                                onRequestDuration = ::onRequestDuration,
                            ),
                        )
                    }
                }
                // 阿里 DashScope：依据设置走流式或非流式
                AsrVendor.DashScope -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.DashscopeStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        this,
                        com.brycewg.asrkb.asr.DashscopeFileAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        ),
                    )
                }
                // Soniox：依据设置走流式或非流式
                AsrVendor.Soniox -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.SonioxStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        this,
                        com.brycewg.asrkb.asr.SonioxFileAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        ),
                    )
                }
                // 其他云厂商：仅非流式（若供应商另行支持流式则走对应分支）
                AsrVendor.ElevenLabs -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.ElevenLabsStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        this,
                        com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        ),
                    )
                }
                AsrVendor.OpenAI -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context,
                    scope,
                    prefs,
                    this,
                    com.brycewg.asrkb.asr.OpenAiFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    ),
                )
                AsrVendor.Gemini -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context,
                    scope,
                    prefs,
                    this,
                    com.brycewg.asrkb.asr.GeminiFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    ),
                )
                AsrVendor.SiliconFlow -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context,
                    scope,
                    prefs,
                    this,
                    com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    ),
                )
                AsrVendor.Zhipu -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context,
                    scope,
                    prefs,
                    this,
                    com.brycewg.asrkb.asr.ZhipuFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = ::onRequestDuration,
                    ),
                )
                // 本地：Paraformer 固定流式
                AsrVendor.Paraformer -> com.brycewg.asrkb.asr.ParaformerStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                // SenseVoice：支持伪流式（VAD 分片预览 + 整段离线识别）
                AsrVendor.SenseVoice -> {
                    if (prefs.svPseudoStreamEnabled) {
                        com.brycewg.asrkb.asr.SenseVoicePushPcmPseudoStreamAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context,
                            scope,
                            prefs,
                            this,
                            com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine(
                                context,
                                scope,
                                prefs,
                                this,
                                onRequestDuration = ::onRequestDuration,
                            ),
                        )
                    }
                }
                // FunASR Nano：整段离线识别（算力开销高，不支持伪流式预览）
                AsrVendor.FunAsrNano -> {
                    // FunASR Nano 算力开销高：不支持伪流式预览，仅保留整段离线识别
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        this,
                        com.brycewg.asrkb.asr.FunAsrNanoFileAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        ),
                    )
                }
                // TeleSpeech：支持伪流式（VAD 分片预览 + 整段离线识别）
                AsrVendor.Telespeech -> {
                    if (prefs.tsPseudoStreamEnabled) {
                        com.brycewg.asrkb.asr.TelespeechPushPcmPseudoStreamAsrEngine(
                            context,
                            scope,
                            prefs,
                            this,
                            onRequestDuration = ::onRequestDuration,
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context,
                            scope,
                            prefs,
                            this,
                            com.brycewg.asrkb.asr.TelespeechFileAsrEngine(
                                context,
                                scope,
                                prefs,
                                this,
                                onRequestDuration = ::onRequestDuration,
                            ),
                        )
                    }
                }
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
            val doAi = try {
                prefs.postProcessEnabled && prefs.hasLlmKeys()
            } catch (_: Throwable) {
                false
            }
            if (doAi) {
                if (!hasAsrPartial && text.isNotEmpty()) {
                    hasAsrPartial = true
                    safe { cb.onPartial(id, text) }
                }
                // 执行带 AI 的完整后处理链（IO 在线程内切换）
                CoroutineScope(Dispatchers.Main).launch {
                    if (canceled) return@launch
                    val typewriterEnabled = try {
                        prefs.postprocTypewriterEnabled
                    } catch (_: Throwable) {
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
                                safe { cb.onPartial(id, typed) }
                            },
                            frameDelayMs = 35L,
                            idleStopDelayMs = 1200L,
                            normalTargetFrames = 18,
                            normalMaxStep = 6,
                            rushTargetFrames = 8,
                            rushMaxStep = 24,
                        )
                    } else {
                        null
                    }
                    val onStreamingUpdate: (String) -> Unit = onStreamingUpdate@{ streamed ->
                        if (canceled || postprocCommitted) return@onStreamingUpdate
                        if (streamed.isEmpty() || streamed == lastPostprocTarget) return@onStreamingUpdate
                        lastPostprocTarget = streamed
                        if (typewriter != null) {
                            typewriter.submit(streamed)
                        } else {
                            if (streamed.isEmpty() || streamed == lastPostprocPreview) return@onStreamingUpdate
                            lastPostprocPreview = streamed
                            safe { cb.onPartial(id, streamed) }
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
                            onStreamingUpdate = onStreamingUpdate,
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
                                    text,
                                )
                            } catch (_: Throwable) {
                                text
                            }
                        }
                        if (typewriter != null && aiUsed && finalOut.isNotEmpty()) {
                            typewriter.submit(finalOut, rush = true)
                            val finalLen = finalOut.length
                            val t0 = SystemClock.uptimeMillis()
                            while (!canceled && (SystemClock.uptimeMillis() - t0) < 2_000L &&
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
                        } catch (_: Throwable) {
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
                            charCount = chars,
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
                                    aiProcessed = aiUsed,
                                    aiPostMs = aiPostMs,
                                    aiPostStatus = aiPostStatus,
                                    charCount = chars,
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add ASR history (external, ai)", e)
                    }
                    if (canceled) return@launch
                    safe { cb.onFinal(id, out) }
                    safe { cb.onState(id, STATE_IDLE, "final") }
                    try {
                        (context as? ExternalSpeechService)?.onSessionDone(id)
                    } catch (t: Throwable) {
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
                    } catch (_: Throwable) {
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
                        charCount = chars,
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
                                charCount = chars,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add ASR history (external, simple)", e)
                }
                safe { cb.onFinal(id, out) }
                safe { cb.onState(id, STATE_IDLE, "final") }
                try {
                    (context as? ExternalSpeechService)?.onSessionDone(id)
                } catch (t: Throwable) {
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
                cb.onError(id, 500, message)
                cb.onState(id, STATE_ERROR, message)
            }
            try {
                (context as? ExternalSpeechService)?.onSessionDone(id)
            } catch (t: Throwable) {
                Log.w(TAG, "remove session on error failed", t)
            }
        }

        override fun onPartial(text: String) {
            if (canceled || finished) return
            if (text.isNotEmpty()) {
                hasAsrPartial = true
                safe { cb.onPartial(id, text) }
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
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
            scheduleProcessingTimeoutIfNeeded()
        }

        override fun onAmplitude(amplitude: Float) {
            if (canceled || finished) return
            safe { cb.onAmplitude(id, amplitude) }
        }
    }

    private class CallbackProxy(private val remote: IBinder?) {
        fun onState(sessionId: Int, state: Int, msg: String) {
            transact(CB_ON_STATE) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(state)
                data.writeString(msg)
            }
        }
        fun onPartial(sessionId: Int, text: String) {
            transact(CB_ON_PARTIAL) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onFinal(sessionId: Int, text: String) {
            transact(CB_ON_FINAL) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onError(sessionId: Int, code: Int, message: String) {
            transact(CB_ON_ERROR) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(code)
                data.writeString(message)
            }
        }
        fun onAmplitude(sessionId: Int, amp: Float) {
            transact(CB_ON_AMPLITUDE) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeFloat(amp)
            }
        }

        private inline fun transact(code: Int, fill: (Parcel) -> Unit) {
            val b = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                fill(data)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Log.w(TAG, "callback transact failed: code=$code", t)
            } finally {
                try {
                    data.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "data.recycle failed", t)
                }
                try {
                    reply.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "reply.recycle failed", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ExternalSpeechSvc"

        // 与 AIDL 生成的 Stub 保持一致的描述符与事务号
        private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val TRANSACTION_START_SESSION = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_STOP_SESSION = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_CANCEL_SESSION = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_IS_RECORDING = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_IS_ANY_RECORDING = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_GET_VERSION = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_START_PCM_SESSION = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_WRITE_PCM = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val TRANSACTION_FINISH_PCM = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
        private const val CB_ON_STATE = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CB_ON_PARTIAL = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CB_ON_FINAL = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CB_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CB_ON_AMPLITUDE = IBinder.FIRST_CALL_TRANSACTION + 4

        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1
        private const val STATE_PROCESSING = 2
        private const val STATE_ERROR = 3

        private const val LOCAL_MODEL_READY_WAIT_MAX_MS = 60_000L
        private const val LOCAL_MODEL_READY_WAIT_CONSUMED = -1L

        private inline fun safe(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "callback failed", t)
            }
        }
    }

    // 统一的会话清理入口：在 onFinal/onError 触发后移除，避免内存泄漏
    private fun onSessionDone(sessionId: Int) {
        try {
            sessions.remove(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "sessions.remove failed for id=$sessionId", t)
        }
    }
}
