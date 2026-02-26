package com.brycewg.asrkb.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.asr.AsrErrorMessageMapper
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BaseFileAsrEngine
import com.brycewg.asrkb.asr.BluetoothRouteManager
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
import com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured
import com.brycewg.asrkb.asr.preloadTelespeechIfConfigured
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
    private val prefs: Prefs,
) : StreamingAsrEngine.Listener, SenseVoiceFileAsrEngine.LocalModelLoadUi {

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

    // 当前会话状态
    private var currentState: KeyboardState = KeyboardState.Idle

    // 音频焦点请求句柄
    private var audioFocusRequest: AudioFocusRequest? = null

    // ASR 请求耗时记录
    private var lastRequestDurationMs: Long? = null

    // 统计/历史：本次会话主/备供应商快照（避免设置变更导致 vendorId 串台）
    private var sessionPrimaryVendor: AsrVendor = try {
        prefs.asrVendor
    } catch (_: Throwable) {
        AsrVendor.Volc
    }
    private var lastFinalVendorForStats: AsrVendor? = null

    // 本地模型：Processing 阶段等待“模型就绪”的耗时（用于将处理耗时统计从模型就绪开始）
    private var sessionSeq: Long = 0L
    private var localModelWaitStartUptimeMs: Long = 0L
    private val localModelReadyWaitMs = AtomicLong(0L)
    private var localModelReadyWaitJob: Job? = null

    // 会话录音时长统计（毫秒）
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L

    // 统计/历史：端到端耗时起点（从开始录音到最终提交完成）
    private var sessionStartTotalUptimeMs: Long = 0L

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

    fun peekLastFinalVendorForStats(): AsrVendor {
        return lastFinalVendorForStats ?: sessionPrimaryVendor
    }

    /**
     * 构建符合当前配置的 ASR 引擎
     */
    fun buildEngine(): StreamingAsrEngine? {
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val backupEnabled = shouldUseBackupAsr(primaryVendor, backupVendor)
        if (backupEnabled) {
            return ParallelAsrEngine(
                context = context,
                scope = scope,
                prefs = prefs,
                listener = this,
                primaryVendor = primaryVendor,
                backupVendor = backupVendor,
                onPrimaryRequestDuration = ::onRequestDuration,
            )
        }
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(context, scope, prefs, this)
                } else {
                    if (prefs.volcFileStandardEnabled) {
                        VolcStandardFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                    } else {
                        VolcFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                    }
                }
            } else {
                null
            }

            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else {
                null
            }

            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                if (prefs.elevenStreamingEnabled) {
                    ElevenLabsStreamAsrEngine(context, scope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else {
                null
            }

            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else {
                null
            }

            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                if (prefs.isDashStreamingModelSelected()) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else {
                null
            }

            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else {
                null
            }

            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            } else {
                null
            }

            AsrVendor.Zhipu -> if (prefs.hasZhipuKeys()) {
                ZhipuFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            } else {
                null
            }

            AsrVendor.SenseVoice -> {
                if (prefs.svPseudoStreamEnabled) {
                    // 本地 SenseVoice：伪流式模式（VAD 分片预览 + 整段离线识别）
                    SenseVoicePseudoStreamAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                } else {
                    // 本地 SenseVoice：传统文件识别模式
                    SenseVoiceFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            }
            AsrVendor.FunAsrNano -> {
                // 本地 FunASR Nano：算力开销高，不支持伪流式预览，仅保留整段离线识别
                FunAsrNanoFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
            }
            AsrVendor.Telespeech -> {
                if (prefs.tsPseudoStreamEnabled) {
                    // 本地 TeleSpeech：伪流式模式（VAD 分片预览 + 整段离线识别）
                    TelespeechPseudoStreamAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                } else {
                    // 本地 TeleSpeech：传统文件识别模式
                    TelespeechFileAsrEngine(context, scope, prefs, this, ::onRequestDuration)
                }
            }
            AsrVendor.Paraformer -> {
                ParaformerStreamAsrEngine(context, scope, prefs, this)
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

    /**
     * 确保引擎与当前模式匹配（用于模式切换时避免重建引擎）
     */
    fun ensureEngineMatchesMode(): StreamingAsrEngine? {
        if (!prefs.hasAsrKeys()) return null

        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val backupEnabled = shouldUseBackupAsr(primaryVendor, backupVendor)
        if (backupEnabled) {
            val current = asrEngine
            val matched = when (current) {
                is ParallelAsrEngine -> if (current.primaryVendor == primaryVendor && current.backupVendor == backupVendor) {
                    current
                } else {
                    null
                }
                else -> null
            }
            val engine = matched ?: buildEngine()
            if (engine != null && engine !== asrEngine) {
                asrEngine?.stop()
                asrEngine = engine
            }
            return asrEngine
        }

        val current = asrEngine
        val matched = when (prefs.asrVendor) {
            AsrVendor.Volc -> {
                when (current) {
                    is VolcStreamAsrEngine -> if (prefs.volcStreamingEnabled) current else null
                    is VolcStandardFileAsrEngine -> if (!prefs.volcStreamingEnabled && prefs.volcFileStandardEnabled) current else null
                    is VolcFileAsrEngine -> if (!prefs.volcStreamingEnabled && !prefs.volcFileStandardEnabled) current else null
                    else -> null
                }
            }
            AsrVendor.SiliconFlow -> if (current is SiliconFlowFileAsrEngine) current else null
            AsrVendor.ElevenLabs -> when (current) {
                is ElevenLabsFileAsrEngine -> if (!prefs.elevenStreamingEnabled) current else null
                is ElevenLabsStreamAsrEngine -> if (prefs.elevenStreamingEnabled) current else null
                else -> null
            }
            AsrVendor.OpenAI -> if (current is OpenAiFileAsrEngine) current else null
            AsrVendor.DashScope -> when (current) {
                is DashscopeFileAsrEngine -> if (!prefs.isDashStreamingModelSelected()) current else null
                is DashscopeStreamAsrEngine -> if (prefs.isDashStreamingModelSelected()) current else null
                else -> null
            }
            AsrVendor.Gemini -> if (current is GeminiFileAsrEngine) current else null

            AsrVendor.Soniox -> when (current) {
                is SonioxFileAsrEngine -> if (!prefs.sonioxStreamingEnabled) current else null
                is SonioxStreamAsrEngine -> if (prefs.sonioxStreamingEnabled) current else null
                else -> null
            }

            AsrVendor.Zhipu -> if (current is ZhipuFileAsrEngine) current else null

            AsrVendor.SenseVoice -> when (current) {
                is SenseVoicePseudoStreamAsrEngine -> if (prefs.svPseudoStreamEnabled) current else null
                is SenseVoiceFileAsrEngine -> if (!prefs.svPseudoStreamEnabled) current else null
                else -> null
            }
            AsrVendor.FunAsrNano -> when (current) {
                is FunAsrNanoFileAsrEngine -> current
                else -> null
            }
            AsrVendor.Telespeech -> when (current) {
                is TelespeechPseudoStreamAsrEngine -> if (prefs.tsPseudoStreamEnabled) current else null
                is TelespeechFileAsrEngine -> if (!prefs.tsPseudoStreamEnabled) current else null
                else -> null
            }
            AsrVendor.Paraformer -> when (current) {
                is ParaformerStreamAsrEngine -> current
                else -> null
            }
        }

        val engine = matched ?: buildEngine()
        if (engine != null && engine !== asrEngine) {
            asrEngine?.stop()
            asrEngine = engine
        }
        return asrEngine
    }

    /**
     * 重新构建引擎（设置改变时使用）
     */
    fun rebuildEngine() {
        asrEngine = buildEngine()
    }

    /**
     * 启动 ASR 录音
     * @param state 启动时的键盘状态
     */
    fun startRecording(state: KeyboardState) {
        currentState = state
        try {
            sessionPrimaryVendor = prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot vendors on startRecording", t)
        } finally {
            lastFinalVendorForStats = null
        }
        sessionSeq++
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
            val eng = ensureEngineMatchesMode()
            DebugLogManager.log(
                category = "asr",
                event = "start",
                data = mapOf(
                    "vendor" to prefs.asrVendor.name,
                    "engine" to (eng?.javaClass?.simpleName ?: "null"),
                    "state" to state::class.java.simpleName,
                    "duckMedia" to prefs.duckMediaOnRecordEnabled,
                ),
            )
        } catch (_: Throwable) { }
        // 开始录音前根据设置决定是否请求短时独占音频焦点（音频避让）
        if (prefs.duckMediaOnRecordEnabled) {
            requestTransientAudioFocus()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }
        // 若为本地 SenseVoice / FunASR Nano / TeleSpeech，在录音触发时后台开始加载模型
        try {
            if (
                prefs.asrVendor == AsrVendor.SenseVoice ||
                prefs.asrVendor == AsrVendor.FunAsrNano ||
                prefs.asrVendor == AsrVendor.Telespeech
            ) {
                val prepared = try {
                    when (prefs.asrVendor) {
                        AsrVendor.SenseVoice -> com.brycewg.asrkb.asr.isSenseVoicePrepared()
                        AsrVendor.FunAsrNano -> com.brycewg.asrkb.asr.isFunAsrNanoPrepared()
                        AsrVendor.Telespeech -> com.brycewg.asrkb.asr.isTelespeechPrepared()
                        else -> false
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to check local model prepared state", t)
                    false
                }
                if (!prepared) {
                    try {
                        when (prefs.asrVendor) {
                            AsrVendor.FunAsrNano -> com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(
                                context,
                                prefs,
                                onLoadStart = { onLocalModelLoadStart() },
                                onLoadDone = { onLocalModelLoadDone() },
                                suppressToastOnStart = true,
                                forImmediateUse = true,
                            )
                            AsrVendor.Telespeech -> com.brycewg.asrkb.asr.preloadTelespeechIfConfigured(
                                context,
                                prefs,
                                onLoadStart = { onLocalModelLoadStart() },
                                onLoadDone = { onLocalModelLoadDone() },
                                suppressToastOnStart = true,
                                forImmediateUse = true,
                            )
                            else -> com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                                context,
                                prefs,
                                onLoadStart = { onLocalModelLoadStart() },
                                onLoadDone = { onLocalModelLoadDone() },
                                suppressToastOnStart = true,
                                forImmediateUse = true,
                            )
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to trigger local model preload on startRecording", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Local model preload guard failed", t)
        }
        asrEngine?.start()
        try {
            DebugLogManager.log(
                category = "asr",
                event = "start_state",
                data = mapOf(
                    "engine" to (asrEngine?.javaClass?.simpleName ?: "null"),
                    "running" to (asrEngine?.isRunning == true),
                ),
            )
        } catch (_: Throwable) { }
        // 录音期间保持耳机路由
        try {
            BluetoothRouteManager.onRecordingStarted(context)
        } catch (t: Throwable) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t)
        }
    }

    /**
     * 停止 ASR 录音
     */
    fun stopRecording() {
        snapshotAudioDurationIfPossible()
        markLocalModelProcessingStartIfNeeded()
        asrEngine?.stop()
        try {
            DebugLogManager.log(
                category = "asr",
                event = "stop",
                data = mapOf(
                    "state" to currentState::class.java.simpleName,
                    "engineRunning" to (asrEngine?.isRunning == true),
                ),
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
        } catch (t: Throwable) {
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
    fun peekLastAudioMsForStats(): Long {
        return lastAudioMsForStats
    }

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
        asrEngine?.stop()
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed on cleanup", t)
        }
        localModelReadyWaitJob = null
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

    override fun onFinal(text: String) {
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
                    "len" to text.length,
                    "state" to currentState::class.java.simpleName,
                ),
            )
        } catch (_: Throwable) { }
        listener?.onAsrFinal(text, currentState)
    }

    override fun onPartial(text: String) {
        // 若引擎已停止（用户已松手），忽略后续中间结果，避免重复追加
        if (!isRunning()) {
            Log.d(TAG, "onPartial ignored: engine stopped")
            return
        }
        Log.d(TAG, "onPartial: text='$text'")
        listener?.onAsrPartial(text)
    }

    override fun onError(message: String) {
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
                    "state" to currentState::class.java.simpleName,
                    "msgType" to if (friendlyMessage != null) "friendly" else "raw",
                ),
            )
        } catch (_: Throwable) { }
        listener?.onAsrError(friendlyMessage ?: message)
    }

    override fun onStopped() {
        Log.d(TAG, "onStopped: state=$currentState")
        markLocalModelProcessingStartIfNeeded()
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
                    "audioMs" to ms,
                    "state" to currentState::class.java.simpleName,
                ),
            )
        } catch (_: Throwable) { }
        listener?.onAsrStopped()
    }

    override fun onAmplitude(amplitude: Float) {
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

    private fun markLocalModelProcessingStartIfNeeded() {
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

        val seq = sessionSeq
        try {
            localModelReadyWaitJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Cancel local model wait job failed", t)
        }
        localModelReadyWaitJob = scope.launch(Dispatchers.Default) {
            val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
            if (!ok) return@launch
            if (sessionSeq != seq) return@launch
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

    private fun onRequestDuration(ms: Long) {
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
