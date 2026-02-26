package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 并行主备 ASR 引擎：
 * - 录音只采集一次（AudioCaptureManager）
 * - 同步推送 PCM 给主用与备用两个引擎（均以 externalPcmMode/Push-PCM 方式构建）
 * - 以“主用是否在阈值内产生终止事件（onFinal/onError）”作为切换依据：
 *   - 主用先给出非空 onFinal：直接采用主用
 *   - 主用超时或失败（onError/空 onFinal）：尝试采用备用结果
 */
class ParallelAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    val primaryVendor: AsrVendor,
    val backupVendor: AsrVendor,
    private val onPrimaryRequestDuration: ((Long) -> Unit)? = null,
    private val externalPcmInput: Boolean = false,
) : StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "ParallelAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val CHUNK_MS = 200
        private const val PRIMARY_SWITCH_RATIO_BALANCED = 0.75
        private const val PRIMARY_SWITCH_RATIO_SENSITIVE = 0.5
        private const val PRIMARY_SWITCH_MIN_MS = 6_000L
        private const val PRIMARY_SWITCH_MAX_MS = 15_000L
        private const val PRIMARY_SWITCH_NONSTREAM_SOFT_MAX_BALANCED_MS = 25_000L
        private const val PRIMARY_SWITCH_NONSTREAM_SOFT_MAX_SENSITIVE_MS = 18_000L
    }

    private enum class Source { PRIMARY, BACKUP }

    private sealed class Terminal {
        data class Final(val text: String) : Terminal()
        data class Error(val message: String) : Terminal()
    }

    override val isRunning: Boolean
        get() = running.get()

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val terminalDelivered = AtomicBoolean(false)

    private val stateLock = Any()

    private var audioJob: Job? = null
    private var primaryTimeoutJob: Job? = null

    @Volatile private var startUptimeMs: Long = 0L
    private val audioBytes = AtomicLong(0L)

    @Volatile private var stoppedNotified: Boolean = false

    @Volatile private var primaryTimedOut: Boolean = false

    @Volatile private var primaryTerminal: Terminal? = null

    @Volatile private var backupTerminal: Terminal? = null

    @Volatile private var lastFinalFromBackup: Boolean = false

    fun wasLastResultFromBackup(): Boolean = lastFinalFromBackup

    private val primaryListener = EngineListener(Source.PRIMARY, forwardLocalModelUi = true)
    private val backupListener = EngineListener(Source.BACKUP, forwardLocalModelUi = false)

    private var primaryEngine: StreamingAsrEngine? = null
    private var backupEngine: StreamingAsrEngine? = null
    private var primaryConsumer: ExternalPcmConsumer? = null
    private var backupConsumer: ExternalPcmConsumer? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return

        stopRequested.set(false)
        terminalDelivered.set(false)
        stoppedNotified = false
        primaryTimedOut = false
        primaryTerminal = null
        backupTerminal = null
        lastFinalFromBackup = false
        audioBytes.set(0L)
        primaryTimeoutJob?.cancel()
        primaryTimeoutJob = null

        startUptimeMs = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }

        primaryEngine = buildPushPcmEngine(primaryVendor, primaryListener, onPrimaryRequestDuration)
        backupEngine = buildPushPcmEngine(backupVendor, backupListener, onRequestDuration = null)
        primaryConsumer = primaryEngine as? ExternalPcmConsumer
        backupConsumer = backupEngine as? ExternalPcmConsumer

        if (primaryEngine == null && backupEngine == null) {
            running.set(false)
            try {
                listener.onError(context.getString(R.string.error_recognize_failed_with_reason, "No engine available"))
            } catch (t: Throwable) {
                Log.w(TAG, "notify no engine available failed", t)
            }
            return
        }

        try {
            primaryEngine?.start()
        } catch (t: Throwable) {
            Log.e(TAG, "primary start failed", t)
            onTerminal(Source.PRIMARY, Terminal.Error(t.message ?: "primary start failed"))
        }
        try {
            backupEngine?.start()
        } catch (t: Throwable) {
            Log.e(TAG, "backup start failed", t)
            onTerminal(Source.BACKUP, Terminal.Error(t.message ?: "backup start failed"))
        }

        if (!externalPcmInput) {
            startAudioCapture()
        }
    }

    override fun stop() {
        if (stopRequested.getAndSet(true)) return

        running.set(false)
        if (!terminalDelivered.get()) {
            notifyStoppedIfNeeded()
        }

        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed", t)
        } finally {
            audioJob = null
        }

        try {
            primaryEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "primary stop failed", t)
        }
        try {
            backupEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "backup stop failed", t)
        }

        schedulePrimaryTimeoutIfNeeded()
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!externalPcmInput) return
        if (!running.get()) return
        if (terminalDelivered.get()) return
        if (sampleRate != SAMPLE_RATE || channels != CHANNELS) return

        audioBytes.addAndGet(pcm.size.toLong())
        try {
            listener.onAmplitude(calculateNormalizedAmplitude(pcm))
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed (externalPcmInput)", t)
        }
        try {
            primaryConsumer?.appendPcm(pcm, sampleRate, channels)
        } catch (t: Throwable) {
            Log.w(TAG, "primary appendPcm failed (externalPcmInput)", t)
        }
        try {
            backupConsumer?.appendPcm(pcm, sampleRate, channels)
        } catch (t: Throwable) {
            Log.w(TAG, "backup appendPcm failed (externalPcmInput)", t)
        }
    }

    private fun cleanupAfterTerminal() {
        stopRequested.set(true)
        running.set(false)
        try {
            primaryTimeoutJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel primaryTimeoutJob failed in cleanupAfterTerminal", t)
        } finally {
            primaryTimeoutJob = null
        }
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed in cleanupAfterTerminal", t)
        } finally {
            audioJob = null
        }
        try {
            primaryEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "primary stop failed in cleanupAfterTerminal", t)
        }
        try {
            backupEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "backup stop failed in cleanupAfterTerminal", t)
        }
    }

    private fun startAudioCapture() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = SAMPLE_RATE,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                chunkMillis = CHUNK_MS,
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                fatalCaptureError(context.getString(R.string.error_record_permission_denied))
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                try {
                    VadDetector(context, SAMPLE_RATE, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to create VAD detector", t)
                    null
                }
            } else {
                null
            }

            try {
                audioManager.startCapture().collect { chunk ->
                    if (!isActive || !running.get()) return@collect
                    if (terminalDelivered.get()) return@collect

                    try {
                        listener.onAmplitude(calculateNormalizedAmplitude(chunk))
                    } catch (t: Throwable) {
                        Log.w(TAG, "notify amplitude failed", t)
                    }
                    audioBytes.addAndGet(chunk.size.toLong())

                    try {
                        primaryConsumer?.appendPcm(chunk, SAMPLE_RATE, CHANNELS)
                    } catch (t: Throwable) {
                        Log.w(TAG, "primary appendPcm failed", t)
                    }
                    try {
                        backupConsumer?.appendPcm(chunk, SAMPLE_RATE, CHANNELS)
                    } catch (t: Throwable) {
                        Log.w(TAG, "backup appendPcm failed", t)
                    }

                    if (vadDetector?.shouldStop(chunk, chunk.size) == true) {
                        Log.d(TAG, "VAD silence detected, stopping session")
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Audio capture cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio capture failed", t)
                    fatalCaptureError(context.getString(R.string.error_audio_error, t.message ?: ""))
                }
            } finally {
                try {
                    vadDetector?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "VAD release failed", t)
                }
            }
        }
    }

    private fun fatalCaptureError(message: String) {
        onTerminal(Source.PRIMARY, Terminal.Error(message))
        onTerminal(Source.BACKUP, Terminal.Error(message))
    }

    private fun notifyStoppedIfNeeded() {
        if (stoppedNotified) return
        stoppedNotified = true
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notify onStopped failed", t)
        }
    }

    private fun schedulePrimaryTimeoutIfNeeded() {
        if (primaryEngine == null || backupEngine == null) return
        if (terminalDelivered.get()) return

        val bytesAudioMs = audioMsFromBytes(audioBytes.get())
        val audioMs = if (bytesAudioMs > 0L) {
            bytesAudioMs
        } else {
            val t0 = startUptimeMs
            val t1 = try {
                SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            if (t0 > 0L && t1 >= t0) (t1 - t0) else 0L
        }

        val baseTimeoutMs = AsrTimeoutCalculator.calculateTimeoutMs(audioMs)
        val sensitivityTier = try {
            prefs.backupAsrTimeoutSensitivity
        } catch (_: Throwable) {
            1
        }
        val switchTimeoutMs = calculatePrimarySwitchTimeoutMs(baseTimeoutMs, isPrimaryStreamingForSwitch(), sensitivityTier)

        try {
            primaryTimeoutJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel primaryTimeoutJob failed", t)
        }
        primaryTimeoutJob = scope.launch {
            delay(switchTimeoutMs)
            synchronized(stateLock) {
                if (terminalDelivered.get()) return@synchronized
                if (primaryTerminal == null) {
                    primaryTimedOut = true
                    Log.w(TAG, "Primary timeout fired (audioMs=$audioMs, switchTimeoutMs=$switchTimeoutMs)")
                }
                tryResolveLocked()
            }
        }
        Log.d(
            TAG,
            "Primary timeout scheduled: audioMs=$audioMs, baseTimeoutMs=$baseTimeoutMs, switchTimeoutMs=$switchTimeoutMs",
        )
    }

    private fun calculatePrimarySwitchTimeoutMs(baseTimeoutMs: Long, primaryStreaming: Boolean, sensitivityTier: Int): Long {
        val ratio = when (sensitivityTier.coerceIn(0, 2)) {
            0 -> 1.0
            2 -> PRIMARY_SWITCH_RATIO_SENSITIVE
            else -> PRIMARY_SWITCH_RATIO_BALANCED
        }

        var timeoutMs = (baseTimeoutMs.toDouble() * ratio).toLong().coerceAtLeast(0L)

        if (primaryStreaming) {
            timeoutMs = timeoutMs.coerceIn(PRIMARY_SWITCH_MIN_MS, PRIMARY_SWITCH_MAX_MS)
        } else {
            val minMs = when (sensitivityTier.coerceIn(0, 2)) {
                2 -> 5_000L
                1 -> 6_000L
                else -> 0L
            }
            val softMaxMs = when (sensitivityTier.coerceIn(0, 2)) {
                2 -> PRIMARY_SWITCH_NONSTREAM_SOFT_MAX_SENSITIVE_MS
                1 -> PRIMARY_SWITCH_NONSTREAM_SOFT_MAX_BALANCED_MS
                else -> Long.MAX_VALUE
            }
            timeoutMs = timeoutMs.coerceAtLeast(minMs).coerceAtMost(softMaxMs)
        }

        return timeoutMs
    }

    private fun audioMsFromBytes(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val denom = SAMPLE_RATE.toLong() * CHANNELS.toLong() * 2L
        if (denom <= 0L) return 0L
        return (bytes * 1000L / denom).coerceAtLeast(0L)
    }

    private fun isPrimaryStreamingForSwitch(): Boolean {
        return when (primaryVendor) {
            AsrVendor.Volc -> prefs.volcStreamingEnabled
            AsrVendor.DashScope -> prefs.isDashStreamingModelSelected()
            AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
            AsrVendor.ElevenLabs -> prefs.elevenStreamingEnabled
            AsrVendor.Paraformer -> true
            else -> false
        }
    }

    private fun onTerminal(source: Source, t: Terminal) {
        val shouldStopCapture = synchronized(stateLock) {
            if (terminalDelivered.get()) return
            when (source) {
                Source.PRIMARY -> primaryTerminal = t
                Source.BACKUP -> backupTerminal = t
            }
            tryResolveLocked()
            terminalDelivered.get()
        }
        if (shouldStopCapture) {
            cleanupAfterTerminal()
        }
    }

    private fun tryResolveLocked() {
        if (terminalDelivered.get()) return

        val p = primaryTerminal
        val b = backupTerminal
        val hasPrimary = primaryEngine != null
        val hasBackup = backupEngine != null

        // 无主用：直接采用备用
        if (!hasPrimary) {
            when (b) {
                is Terminal.Final -> deliverFinalLocked(b.text, Source.BACKUP)
                is Terminal.Error -> deliverErrorLocked(b.message)
                null -> Unit
            }
            return
        }

        // 主用有结果（非空）直接采用
        val pFinal = p as? Terminal.Final
        if (pFinal != null && pFinal.text.isNotBlank()) {
            deliverFinalLocked(pFinal.text, Source.PRIMARY)
            return
        }

        // 没有备用：主用终止即交付
        if (!hasBackup) {
            when (p) {
                is Terminal.Final -> deliverFinalLocked(p.text, Source.PRIMARY)
                is Terminal.Error -> deliverErrorLocked(p.message)
                null -> Unit
            }
            return
        }

        val pFailed = when (p) {
            is Terminal.Error -> true
            is Terminal.Final -> p.text.isBlank()
            null -> false
        }

        // 主用失败：尽快尝试采用备用（无需等待主用超时阈值）
        if (pFailed) {
            val bFinal = b as? Terminal.Final
            when {
                bFinal != null && bFinal.text.isNotBlank() -> deliverFinalLocked(bFinal.text, Source.BACKUP)
                b is Terminal.Error -> deliverErrorLocked(b.message)
                bFinal != null -> deliverFinalLocked(bFinal.text, Source.BACKUP)
                else -> Unit
            }
            return
        }

        // 主用未终止但已超时：切换到备用
        if (primaryTimedOut) {
            when (b) {
                is Terminal.Final -> deliverFinalLocked(b.text, Source.BACKUP)
                is Terminal.Error -> deliverErrorLocked(b.message)
                null -> Unit
            }
            return
        }

        // 否则：继续等待主用（备用结果缓存，不立即提交）
    }

    private fun deliverFinalLocked(text: String, from: Source) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        lastFinalFromBackup = (from == Source.BACKUP)
        try {
            primaryTimeoutJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel primaryTimeoutJob failed on deliverFinal", t)
        } finally {
            primaryTimeoutJob = null
        }
        try {
            listener.onFinal(text)
        } catch (t: Throwable) {
            Log.e(TAG, "notify final failed", t)
        }
    }

    private fun deliverErrorLocked(message: String) {
        if (!terminalDelivered.compareAndSet(false, true)) return
        try {
            primaryTimeoutJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel primaryTimeoutJob failed on deliverError", t)
        } finally {
            primaryTimeoutJob = null
        }
        try {
            listener.onError(message)
        } catch (t: Throwable) {
            Log.e(TAG, "notify error failed", t)
        }
    }

    private inner class EngineListener(
        private val source: Source,
        private val forwardLocalModelUi: Boolean,
    ) : StreamingAsrEngine.Listener, SenseVoiceFileAsrEngine.LocalModelLoadUi {

        override fun onFinal(text: String) {
            onTerminal(source, Terminal.Final(text))
        }

        override fun onError(message: String) {
            onTerminal(source, Terminal.Error(message))
        }

        override fun onPartial(text: String) {
            if (source != Source.PRIMARY) return
            try {
                listener.onPartial(text)
            } catch (t: Throwable) {
                Log.w(TAG, "notify partial failed", t)
            }
        }

        override fun onLocalModelLoadStart() {
            if (!forwardLocalModelUi) return
            val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi ?: return
            try {
                ui.onLocalModelLoadStart()
            } catch (t: Throwable) {
                Log.w(TAG, "forward loadStart failed", t)
            }
        }

        override fun onLocalModelLoadDone() {
            if (!forwardLocalModelUi) return
            val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi ?: return
            try {
                ui.onLocalModelLoadDone()
            } catch (t: Throwable) {
                Log.w(TAG, "forward loadDone failed", t)
            }
        }
    }

    private fun buildPushPcmEngine(
        vendor: AsrVendor,
        engineListener: StreamingAsrEngine.Listener,
        onRequestDuration: ((Long) -> Unit)?,
    ): StreamingAsrEngine? {
        val hasKeys = try {
            when (vendor) {
                AsrVendor.SiliconFlow -> prefs.hasSfKeys()
                else -> prefs.hasVendorKeys(vendor)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read keys for vendor=$vendor", t)
            false
        }
        if (!hasKeys) return null

        return when (vendor) {
            AsrVendor.Volc -> if (prefs.volcStreamingEnabled) {
                VolcStreamAsrEngine(context, scope, prefs, engineListener, externalPcmMode = true)
            } else {
                if (prefs.volcFileStandardEnabled) {
                    GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        engineListener,
                        VolcStandardFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration),
                    )
                } else {
                    GenericPushFileAsrAdapter(
                        context,
                        scope,
                        prefs,
                        engineListener,
                        VolcFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration),
                    )
                }
            }
            AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(
                context,
                scope,
                prefs,
                engineListener,
                onRequestDuration = onRequestDuration,
            ).let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            AsrVendor.ElevenLabs -> if (prefs.elevenStreamingEnabled) {
                ElevenLabsStreamAsrEngine(context, scope, prefs, engineListener, externalPcmMode = true)
            } else {
                ElevenLabsFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                    .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            }
            AsrVendor.OpenAI -> OpenAiFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            AsrVendor.DashScope -> if (prefs.isDashStreamingModelSelected()) {
                DashscopeStreamAsrEngine(context, scope, prefs, engineListener, externalPcmMode = true)
            } else {
                DashscopeFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                    .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            }
            AsrVendor.Gemini -> GeminiFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            AsrVendor.Soniox -> if (prefs.sonioxStreamingEnabled) {
                SonioxStreamAsrEngine(context, scope, prefs, engineListener, externalPcmMode = true)
            } else {
                SonioxFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                    .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            }
            AsrVendor.Zhipu -> ZhipuFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            AsrVendor.SenseVoice -> if (prefs.svPseudoStreamEnabled) {
                SenseVoicePushPcmPseudoStreamAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
            } else {
                SenseVoiceFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                    .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            }
            AsrVendor.FunAsrNano -> FunAsrNanoFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            AsrVendor.Telespeech -> if (prefs.tsPseudoStreamEnabled) {
                TelespeechPushPcmPseudoStreamAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
            } else {
                TelespeechFileAsrEngine(context, scope, prefs, engineListener, onRequestDuration = onRequestDuration)
                    .let { GenericPushFileAsrAdapter(context, scope, prefs, engineListener, it) }
            }
            AsrVendor.Paraformer -> ParaformerStreamAsrEngine(context, scope, prefs, engineListener, externalPcmMode = true)
        }
    }
}
