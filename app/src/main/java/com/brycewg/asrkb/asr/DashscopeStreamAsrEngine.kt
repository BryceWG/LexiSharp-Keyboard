package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DashScope 实时流式 ASR 引擎（SDK）。
 *
 * - 模型：使用共用 Recognition 协议的 Fun-ASR / Qwen-Audio 3.0。
 * - 每 ~100ms 发送一帧 PCM（16kHz/16bit/mono）。
 * - text 事件的 text+stash 字段从录音开始持续累积，用于实时预览。
 * - 支持 language_hints 和 vocabulary_id 等识别参数。
 */
class DashscopeStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    AudioFrameSinkOwner {

    override var audioFrameSink: AudioFrameSink? = null

    companion object {
        private const val TAG = "DashscopeStreamAsrEngine"
        private const val WS_URL_INFER_CN = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        private const val WS_URL_INFER_INTL = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference"
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L
    }

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var controlJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var recognizer: Recognition? = null
    private var selectedModel: String = Prefs.DEFAULT_DASH_MODEL

    // 用于识别结果
    // currentTurnText: 当前已确定的文本（来自 text 事件的 text 字段，用于实时预览）
    // currentTurnStash: 当前未确定的中间文本（来自 text 事件的 stash 字段，用于实时预览）
    // finalTranscript: 用户停止后，由 stop() 触发的最终完整识别结果
    private var currentTurnText: String = ""
    private var currentTurnStash: String = ""
    private var finalTranscript: String? = null
    private var finalResultDeferred: CompletableDeferred<String?>? = null
    private val finalDelivered = AtomicBoolean(false)
    private var apiLogSession: ApiCallLogger.Session? = null

    override val isRunning: Boolean
        get() = running.get()

    private val prebuffer = java.util.ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

    @Volatile private var recognizerReady: Boolean = false

    override fun start() {
        if (running.get()) return
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                return
            }
        }
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return
        }

        selectedModel = DashScopePrefsCompat.normalizeDashAsrModel(prefs.dashAsrModel)

        running.set(true)
        externalVadInputLeveler.reset()
        currentTurnText = ""
        currentTurnStash = ""
        finalTranscript = null
        finalResultDeferred = null
        finalDelivered.set(false)
        apiLogSession = null

        // 在 IO 线程启动 SDK 识别并随后启动采集
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            try {
                startRecognitionStreaming(selectedModel)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start DashScope streaming recognition", t)
                recordApiLogOnce(success = false, error = t.message.orEmpty())
                try {
                    listener.onError(
                        context.getString(
                            R.string.error_recognize_failed_with_reason,
                            t.message ?: ""
                        )
                    )
                } catch (notifyError: Throwable) {
                    Log.e(TAG, "notify error failed", notifyError)
                }
                running.set(false)
                safeClose()
            }
        }
    }

    private fun startRecognitionStreaming(model: String) {
        // Fun-ASR 与 Qwen-Audio 3.0 使用 Recognition SDK 和 inference endpoint。
        val wsUrl = if (prefs.dashRegion.equals(
                "intl",
                ignoreCase = true
            )
        ) {
            WS_URL_INFER_INTL
        } else {
            WS_URL_INFER_CN
        }
        prepareApiLog(
            wsUrl = wsUrl,
            model = model,
            requestStructure = "SDK WebSocket recognition; format=pcm, sample_rate=16000, language_hints?, semantic_punctuation_enabled?"
        )
        try {
            Constants.baseWebsocketApiUrl = wsUrl
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set baseWebsocketApiUrl", t)
        }

        val param = buildDashRecognitionParam(
            model = model,
            apiKey = prefs.dashApiKey,
            sampleRate = sampleRate,
            languages = prefs.getDashLanguages(),
            semanticPunctuationEnabled = prefs.dashFunAsrSemanticPunctEnabled
        )
        val rec = Recognition()
        recognizer = rec
        recognizerReady = false
        val callback = object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                handleRecognitionEvent(result)
            }

            override fun onComplete() {
                handleRecognitionComplete()
            }

            override fun onError(e: Exception) {
                handleRecognitionError(e)
            }
        }

        rec.call(param, callback)

        recognizerReady = true
        flushPrebuffer()
        if (!externalPcmMode) {
            startCaptureAndSend()
        }
    }

    private fun handleRecognitionEvent(result: RecognitionResult) {
        val sentenceText = result.getSentence()?.getText().orEmpty()
        if (sentenceText.isBlank()) return

        val isEnd = result.isSentenceEnd
        if (isEnd) {
            currentTurnText = appendSentence(currentTurnText, sentenceText)
            currentTurnStash = ""
        } else {
            currentTurnStash = sentenceText
        }

        if (!running.get()) return
        val preview = (currentTurnText + currentTurnStash).trim()
        if (preview.isNotEmpty()) {
            try {
                listener.onPartial(preview)
            } catch (t: Throwable) {
                Log.e(TAG, "notify partial failed", t)
            }
        }
    }

    private fun handleRecognitionComplete() {
        val finalText = (currentTurnText + currentTurnStash).trim()
        finalTranscript = finalText
        finalResultDeferred?.complete(finalText)

        if (finalDelivered.compareAndSet(false, true)) {
            recordApiLogOnce(success = true)
            try {
                listener.onFinal(finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "notify final failed", t)
            }
        }
    }

    private fun handleRecognitionError(e: Exception) {
        val msg = e.message ?: "Recognition error"
        Log.e(TAG, "DashScope Recognition streaming error: $msg", e)
        recordApiLogOnce(success = false, error = msg)
        if (running.get()) {
            running.set(false)
            if (!finalDelivered.get()) {
                try {
                    listener.onError(
                        context.getString(R.string.error_recognize_failed_with_reason, msg)
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "notify error failed", t)
                }
            }
        }
        finalResultDeferred?.complete(null)
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job after failure failed", t)
        }
        audioJob = null
        safeClose()
    }

    private fun appendSentence(existing: String, sentence: String): String {
        val s = sentence.trim()
        if (s.isEmpty()) return existing
        val cur = existing.trim()
        if (cur.isEmpty()) return s
        val last = cur.last()
        val first = s.first()
        val needsSpace = last.isAsciiLetterOrDigit() && first.isAsciiLetterOrDigit()
        return if (needsSpace) "$cur $s" else cur + s
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

    /**
     * 冲刷预缓冲区
     */
    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b ->
            sendAudioFrame(b)
        }
    }

    /** 发送 PCM 音频帧。 */
    private fun sendAudioFrame(audioChunk: ByteArray) {
        try {
            recognizer?.sendAudioFrame(ByteBuffer.wrap(audioChunk))
        } catch (t: Throwable) {
            Log.e(TAG, "sendAudioFrame failed", t)
        }
    }

    // ========== ExternalPcmConsumer（外部推流） ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != 16000 || channels != 1) return
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed", t)
        }

        if (!recognizerReady) {
            synchronized(prebufferLock) { prebuffer.addLast(pcm.copyOf()) }
        } else {
            // 先冲刷预缓冲
            flushPrebuffer()
            sendAudioFrame(pcm)
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        // 先取消音频采集，然后调用 commit() 触发最终识别
        scope.launch(Dispatchers.IO) {
            val resultDeferred = CompletableDeferred<String?>()
            finalResultDeferred = resultDeferred
            try {
                // 通知 UI：录音阶段结束，可复位麦克风按钮
                try {
                    listener.onStopped()
                } catch (t: Throwable) {
                    Log.e(TAG, "notify stopped failed", t)
                }

                // 取消音频采集协程，触发 AudioRecord 释放
                try {
                    audioJob?.cancel()
                    // 等待音频采集协程完全结束，确保 AudioRecord 被完全释放
                    audioJob?.join()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel/join audio job failed", t)
                }
                audioJob = null

                // Recognition 协议：调用 stop() 触发最终回调（onComplete）
                try {
                    Log.d(TAG, "Calling recognizer.stop() to trigger final recognition")
                    recognizer?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "recognizer.stop() failed", t)
                    val fallbackText = (currentTurnText + currentTurnStash).trim()
                    if (finalDelivered.compareAndSet(false, true)) {
                        recordApiLogOnce(success = false, error = "recognizer.stop failed: ${t.message.orEmpty()}")
                        try {
                            listener.onFinal(fallbackText)
                        } catch (notifyError: Throwable) {
                            Log.e(TAG, "notify final fallback failed", notifyError)
                        }
                    }
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(fallbackText)
                    }
                }

                // 等待 completed 事件返回或超时
                val awaited = withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { resultDeferred.await() }
                if (awaited == null && finalDelivered.compareAndSet(false, true)) {
                    recordApiLogOnce(success = false, error = "final result timeout")
                    // 超时后使用当前文本作为兜底结果
                    val fallbackText = (finalTranscript ?: (currentTurnText + currentTurnStash)).trim()
                    try {
                        listener.onFinal(fallbackText)
                    } catch (notifyError: Throwable) {
                        Log.e(TAG, "notify final timeout fallback failed", notifyError)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stop cleanup failed", t)
            } finally {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(finalTranscript)
                }
                finalResultDeferred = null
                safeClose()
            }
        }
    }

    private fun startCaptureAndSend() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 100 // 建议 100ms 左右
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis,
                audioFrameSinkProvider = { audioFrameSink }
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                VadDetector(
                    context,
                    sampleRate,
                    prefs.autoStopSilenceWindowMs,
                    prefs.autoStopSilenceSensitivity
                )
            } else {
                null
            }
            val maxDurationLimiter = RecordingDurationLimiter.fromPrefs(
                prefs = prefs,
                sampleRate = sampleRate
            )
            val vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

            try {
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    val leveled = vadInputLeveler.process(audioChunk)

                    // Calculate and send audio amplitude (for waveform animation)
                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    // 客户端 VAD 自动停止（可选，与服务端 VAD 独立）
                    if (vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true) {
                        Log.d(TAG, "Client VAD: silence detected, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (
                            t: Throwable
                        ) {
                            Log.e(TAG, "notify stopped failed", t)
                        }
                        stop()
                        return@collect
                    }

                    // 发送音频
                    if (!recognizerReady) {
                        synchronized(prebufferLock) { prebuffer.addLast(audioChunk.copyOf()) }
                    } else {
                        flushPrebuffer()
                        sendAudioFrame(audioChunk)
                    }

                    if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (t: Throwable) {
                            Log.e(TAG, "notify stopped failed", t)
                        }
                        stop()
                        return@collect
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
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

    private fun safeClose() {
        recognizerReady = false

        try {
            recognizer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "recognizer stop failed", t)
        } finally {
            recognizer = null
        }
    }

    private fun prepareApiLog(
        wsUrl: String,
        model: String,
        requestStructure: String
    ) {
        val meta = ApiCallLogger.meta(
            category = "ASR",
            vendor = "dashscope",
            model = model,
            requestStructure = requestStructure
        )
        apiLogSession = ApiCallLogger.startSdkWebSocket(wsUrl, meta)
    }

    private fun recordApiLogOnce(
        success: Boolean,
        code: Int = 0,
        error: String = ""
    ) {
        apiLogSession?.complete(success = success, code = code, error = error)
    }
}

internal fun buildDashRecognitionParam(
    model: String,
    apiKey: String,
    sampleRate: Int,
    languages: List<String>,
    semanticPunctuationEnabled: Boolean
): RecognitionParam {
    val builder = RecognitionParam.builder()
        .model(model)
        .apiKey(apiKey)
        .format("pcm")
        .sampleRate(sampleRate)
    val normalizedLanguages = DashScopePrefsCompat.parseDashLanguages(languages.joinToString(","))
    val languageHints = if (DashScopePrefsCompat.isQwenAudioModel(model)) {
        normalizedLanguages
    } else {
        normalizedLanguages.take(1)
    }
    if (languageHints.isNotEmpty()) {
        builder.parameter("language_hints", languageHints.toTypedArray())
    }
    if (model.equals(Prefs.DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true)) {
        builder.parameter("semantic_punctuation_enabled", semanticPunctuationEnabled)
    }
    return builder.build()
}
