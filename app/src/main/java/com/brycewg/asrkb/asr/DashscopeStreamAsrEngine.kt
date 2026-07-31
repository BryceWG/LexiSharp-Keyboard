package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DashScope Qwen3-ASR-Flash 实时流式引擎（SDK）。
 *
 * - 使用 OmniRealtimeConversation + OmniRealtimeCallback 实现。
 * - 模型：支持 Qwen3 Realtime，以及共用 Recognition 协议的 Fun-ASR / Qwen-Audio 3.0。
 * - 每 ~100ms 发送一帧 PCM（16kHz/16bit/mono），Base64 编码。
 * - 使用手动模式（enableTurnDetection=false），用户停止时调用 commit() 触发最终识别。
 * - text 事件的 text+stash 字段从录音开始持续累积，用于实时预览。
 * - 支持 language 和 corpusText 参数提升识别准确度。
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
        private const val MODEL_QWEN3 = Prefs.DASH_MODEL_QWEN3_REALTIME
        private const val WS_URL_CN = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
        private const val WS_URL_INTL = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime"
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

    private var conversation: OmniRealtimeConversation? = null
    private var recognizer: Recognition? = null
    private var useRecognitionProtocol: Boolean = false
    private var selectedModel: String = Prefs.DASH_MODEL_QWEN3_REALTIME

    // 用于识别结果
    // currentTurnText: 当前已确定的文本（来自 text 事件的 text 字段，用于实时预览）
    // currentTurnStash: 当前未确定的中间文本（来自 text 事件的 stash 字段，用于实时预览）
    // finalTranscript: 用户停止后，由 commit() 触发的最终完整识别结果
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

    @Volatile private var convReady: Boolean = false

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

        selectedModel = prefs.dashAsrModel
        useRecognitionProtocol = DashScopePrefsCompat.isRecognitionStreamingModel(selectedModel)

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
                if (useRecognitionProtocol) {
                    startRecognitionStreaming(selectedModel)
                    return@launch
                }

                // 根据地域选择 WebSocket URL
                val wsUrl = if (prefs.dashRegion.equals(
                        "intl",
                        ignoreCase = true
                    )
                ) {
                    WS_URL_INTL
                } else {
                    WS_URL_CN
                }
                prepareApiLog(
                    wsUrl = wsUrl,
                    model = MODEL_QWEN3,
                    requestStructure = "SDK WebSocket session; config=modalities, turn_detection, transcription; audio=base64 pcm frames"
                )

                // 构建 OmniRealtimeParam
                val param = OmniRealtimeParam.builder()
                    .model(MODEL_QWEN3)
                    .apikey(prefs.dashApiKey)
                    .url(wsUrl)
                    .build()

                // 构建 OmniRealtimeTranscriptionParam
                val transcriptionParam = OmniRealtimeTranscriptionParam()
                transcriptionParam.setInputSampleRate(sampleRate)
                transcriptionParam.setInputAudioFormat("pcm")
                // 可选：设置语言以提升准确度
                prefs.getDashLanguages().firstOrNull()?.let(transcriptionParam::setLanguage)
                // 可选：设置语料文本
                val corpus = prefs.dashPrompt
                if (corpus.isNotBlank()) {
                    transcriptionParam.setCorpusText(corpus)
                }

                // 构建 OmniRealtimeConfig（关闭服务端 VAD，使用手动模式）
                // 手动模式下：text 事件仍实时返回用于预览，用户停止时调用 commit() 触发最终识别
                val config = OmniRealtimeConfig.builder()
                    .modalities(listOf(OmniRealtimeModality.TEXT))
                    .enableTurnDetection(false) // 关闭服务端 VAD
                    .transcriptionConfig(transcriptionParam)
                    .build()

                // 创建回调
                val callback = object : OmniRealtimeCallback() {
                    override fun onOpen() {
                        Log.d(TAG, "WebSocket opened, updating session config")
                        try {
                            conversation?.updateSession(config)
                            convReady = true
                            // 冲刷预缓冲
                            flushPrebuffer()
                        } catch (t: Throwable) {
                            Log.e(TAG, "updateSession failed", t)
                        }
                    }

                    override fun onEvent(message: JsonObject) {
                        handleServerEvent(message)
                    }

                    override fun onClose(code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closed: $code $reason")
                        if (running.get()) {
                            recordApiLogOnce(success = false, code = code, error = reason)
                            // 非预期关闭
                            running.set(false)
                            try {
                                listener.onError(
                                    context.getString(
                                        R.string.error_recognize_failed_with_reason,
                                        reason
                                    )
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "notify error failed", t)
                            }
                        }
                    }
                }

                // 创建并连接（使用构造函数，与官方示例一致）
                val conv = OmniRealtimeConversation(param, callback)
                conversation = conv
                convReady = false
                conv.connect()

                // 建立连接后开始推送音频（仅非外部模式）
                if (!externalPcmMode) {
                    startCaptureAndSend()
                }
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
        conversation = null

        convReady = false
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

        convReady = true
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
     * 处理服务端事件
     */
    private fun handleServerEvent(message: JsonObject) {
        val eventType = message.get("type")?.asString ?: return

        when (eventType) {
            "session.created" -> {
                Log.d(TAG, "Session created")
            }
            "session.updated" -> {
                Log.d(TAG, "Session updated")
            }
            "input_audio_buffer.speech_started" -> {
                Log.d(TAG, "Speech started")
            }
            "input_audio_buffer.speech_stopped" -> {
                Log.d(TAG, "Speech stopped")
            }
            "input_audio_buffer.committed" -> {
                Log.d(TAG, "Audio committed")
            }
            "conversation.item.created" -> {
                Log.d(TAG, "Conversation item created")
            }
            "conversation.item.input_audio_transcription.text" -> {
                // 实时识别结果（用于预览）
                // text: 已确定的文本（完整，非增量）
                // stash: 尚未确定的中间文本
                val text = message.get("text")?.asString ?: ""
                val stash = message.get("stash")?.asString ?: ""

                if (running.get()) {
                    // 更新当前文本（用于实时预览）
                    currentTurnText = text
                    currentTurnStash = stash

                    // 实时预览 = text + stash
                    val preview = currentTurnText + currentTurnStash
                    if (preview.isNotEmpty()) {
                        try {
                            listener.onPartial(preview)
                        } catch (t: Throwable) {
                            Log.e(TAG, "notify partial failed", t)
                        }
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                // 最终识别结果（由 commit() 触发，手动模式下只会有一次）
                val transcript = message.get("transcript")?.asString ?: ""
                Log.d(TAG, "Transcription completed: $transcript")

                // 保存最终结果（用于 stop() 时返回）
                finalTranscript = transcript

                finalResultDeferred?.complete(transcript)

                // 通知 UI 最终结果
                if (finalDelivered.compareAndSet(false, true)) {
                    recordApiLogOnce(success = true)
                    try {
                        listener.onFinal(transcript)
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify final failed", t)
                    }
                }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                // 识别失败
                val error = message.getAsJsonObject("error")
                val errorMsg = error?.get("message")?.asString ?: "Transcription failed"
                Log.e(TAG, "Transcription failed: $errorMsg")
                running.set(false)
                if (!finalDelivered.get()) {
                    recordApiLogOnce(success = false, error = errorMsg)
                    try {
                        listener.onError(
                            context.getString(R.string.error_recognize_failed_with_reason, errorMsg)
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
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
            "error" -> {
                // 通用错误
                val error = message.getAsJsonObject("error")
                val errorMsg = error?.get("message")?.asString ?: "Unknown error"
                Log.e(TAG, "Server error: $errorMsg")
                if (running.get()) {
                    running.set(false)
                    recordApiLogOnce(success = false, error = errorMsg)
                    try {
                        listener.onError(
                            context.getString(R.string.error_recognize_failed_with_reason, errorMsg)
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
                try {
                    audioJob?.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel audio job after server error failed", t)
                }
                audioJob = null
                finalResultDeferred?.complete(null)
                safeClose()
            }
            else -> {
                Log.d(TAG, "Unknown event: $eventType")
            }
        }
    }

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

    /**
     * 发送音频帧（Base64 编码）
     */
    private fun sendAudioFrame(audioChunk: ByteArray) {
        if (useRecognitionProtocol) {
            try {
                recognizer?.sendAudioFrame(ByteBuffer.wrap(audioChunk))
            } catch (t: Throwable) {
                Log.e(TAG, "sendAudioFrame failed", t)
            }
            return
        }
        try {
            val base64Audio = Base64.encodeToString(audioChunk, Base64.NO_WRAP)
            conversation?.appendAudio(base64Audio)
        } catch (t: Throwable) {
            Log.e(TAG, "appendAudio failed", t)
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

        if (!convReady) {
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

                if (useRecognitionProtocol) {
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
                } else {
                    // 调用 commit() 触发最终识别（手动模式必需）
                    // completed 事件会在回调中调用 listener.onFinal()
                    try {
                        Log.d(TAG, "Calling commit() to trigger final recognition")
                        conversation?.commit()
                    } catch (t: Throwable) {
                        Log.w(TAG, "commit() failed", t)
                        // 如果 commit 失败，使用当前预览作为最终结果
                        val fallbackText = (currentTurnText + currentTurnStash).trim()
                        if (finalDelivered.compareAndSet(false, true)) {
                            recordApiLogOnce(success = false, error = "commit failed: ${t.message.orEmpty()}")
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
                    if (!convReady) {
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
        convReady = false
        try {
            conversation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "conversation close failed", t)
        } finally {
            conversation = null
        }

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
