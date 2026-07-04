// X-ASR local streaming engine and sherpa-onnx reflection adapter.
package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun xAsrShouldStopAfterTailDrainChunk(chunkIndex: Int, maxChunks: Int): Boolean {
    return maxChunks > 0 && chunkIndex >= maxChunks
}

/**
 * 基于 sherpa-onnx OnlineRecognizer 的本地 X-ASR 流式识别引擎。
 * - 反射调用 sherpa-onnx Kotlin API，避免编译期强耦合。
 * - 录音分片（默认200ms），送入在线流；每次分片后尽可能 decode，并节流发送 partial。
 * - 停止时先读取少量真实尾部 PCM，再 inputFinished + 完整 decode 输出最终结果。
 */
class XAsrStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine,
    ExternalPcmConsumer {

    companion object {
        private const val TAG = "XAsrStreamAsrEngine"
        private const val FRAME_MS = 200
        private const val CAPTURE_TAIL_DRAIN_CHUNKS = 2
        private const val CAPTURE_TAIL_DRAIN_TIMEOUT_MS = 900L
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finalizeOnce = AtomicBoolean(false)
    private val closeSilently = AtomicBoolean(false)
    private val captureTailDrainRequested = AtomicBoolean(false)
    private val captureTailDrainChunks = AtomicInteger(0)

    @Volatile private var useItnForSession: Boolean = false

    private var audioJob: Job? = null
    private val mgr = XAsrOnnxManager.getInstance()

    @Volatile private var currentStream: Any? = null
    private val streamMutex = Mutex()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

    // 预缓冲：在模型加载/流未创建前缓存音频，避免首字延迟过长
    private val prebufferMutex = Mutex()
    private val prebuffer = ArrayDeque<ByteArray>()
    private var prebufferBytes: Int = 0
    private val maxPrebufferBytes: Int = 384 * 1024 // ~12s @16kHz s16le mono

    private var lastEmitUptimeMs: Long = 0L
    private var lastEmittedText: String? = null
    private val loggedAudioBytes = AtomicInteger(0)

    @Volatile private var streamLog: LocalAsrCallLogger.Session? = null

    @Volatile private var loadLog: LocalAsrCallLogger.Session? = null

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        closing.set(false)
        finalizeOnce.set(false)
        closeSilently.set(false)
        captureTailDrainRequested.set(false)
        captureTailDrainChunks.set(0)
        loggedAudioBytes.set(0)
        streamLog = LocalAsrCallLogger.startInference(
            prefs = prefs,
            vendor = AsrVendor.XAsr,
            source = if (externalPcmMode) "external_pcm_stream" else "streaming",
            audioBytes = 0,
            sampleRate = sampleRate
        )
        // 外部推流模式下不检查录音权限
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val msg = context.getString(R.string.error_record_permission_denied)
                failStreamLog(msg)
                listener.onError(msg)
                return
            }
        }

        if (!mgr.isOnnxAvailable()) {
            val msg = context.getString(R.string.error_local_asr_not_ready)
            failStreamLog(msg)
            listener.onError(msg)
            return
        }

        useItnForSession = try {
            prefs.xAsrUseItn
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read xAsrUseItn", t)
            false
        }
        running.set(true)
        externalVadInputLeveler.reset()
        lastEmitUptimeMs = 0L
        lastEmittedText = null

        // 非外部模式才启动采集；外部模式下由 appendPcm 注入
        if (!externalPcmMode) startCapture()
        scope.launch(Dispatchers.Default) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val filesCheck = checkXAsrModelFiles(context, java.io.File(base, "x_asr"))
            val files = (filesCheck as? LocalModelCheck.Ready)?.value
            if (files == null) {
                val msg = localModelErrorMessage(
                    context,
                    filesCheck,
                    R.string.error_x_asr_model_missing
                )
                failStreamLog(msg)
                listener.onError(msg)
                running.set(false)
                return@launch
            }

            val keepMinutes = prefs.xAsrKeepAliveMinutes
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val ok = mgr.prepare(
                tokens = files.tokens.absolutePath,
                encoder = files.encoder.absolutePath,
                decoder = files.decoder.absolutePath,
                joiner = files.joiner.absolutePath,
                numThreads = prefs.xAsrNumThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.XAsr,
                        source = "streaming_load"
                    )
                    notifyLoadUi(true)
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    notifyLoadUi(false)
                }
            )
            if (!ok) {
                Log.w(TAG, "X-ASR prepare() failed")
                loadLog?.failure("prepare returned false")
                loadLog = null
                failStreamLog("X-ASR prepare returned false")
                running.set(false)
                return@launch
            }

            val stream = mgr.createStreamOrNull()
            if (stream == null) {
                val msg = context.getString(R.string.error_local_asr_not_ready)
                failStreamLog(msg)
                listener.onError(msg)
                return@launch
            }
            currentStream = stream

            // 冲刷预缓冲
            drainPrebufferTo(stream)

            // 若在准备期间已调用 stop()，此处直接做最终解码
            if (closing.get() && finalizeOnce.compareAndSet(false, true)) {
                if (closeSilently.get()) {
                    try {
                        releaseStreamSilently(stream)
                    } catch (
                        t: Throwable
                    ) {
                        Log.e(TAG, "releaseStreamSilently failed", t)
                    }
                    failStreamLog("Stream released silently")
                } else {
                    val finalText = try {
                        finalizeAndRelease(stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "finalizeAndRelease failed", t)
                        failStreamLog(t.message ?: "finalizeAndRelease failed")
                        ""
                    }
                    completeStreamLog(finalText)
                    try {
                        listener.onFinal(finalText)
                    } catch (
                        t: Throwable
                    ) {
                        Log.e(TAG, "notify final failed", t)
                    }
                }
                closing.set(false)
                running.set(false)
                closeSilently.set(false)
            }
        }
    }

    // ========== ExternalPcmConsumer（外部推流） ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get() && currentStream == null && !closing.get()) return
        if (sampleRate != 16000 || channels != 1) return
        if (pcm.isNotEmpty()) {
            loggedAudioBytes.addAndGet(pcm.size)
        }
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (
            _: Throwable
        ) { }
        val s = currentStream
        if (s == null) {
            scope.launch { appendPrebuffer(pcm) }
        } else {
            scope.launch { deliverChunk(s, pcm, pcm.size) }
        }
    }

    override fun stop() {
        running.set(false)
        closing.set(true)
        val jobToJoin = audioJob
        val s = currentStream
        val drainCaptureTail = !externalPcmMode && s != null && jobToJoin?.isActive == true
        if (drainCaptureTail) {
            captureTailDrainChunks.set(0)
            captureTailDrainRequested.set(true)
        } else {
            jobToJoin?.cancel()
        }
        audioJob = null

        if (s == null) {
            return
        }

        if (finalizeOnce.compareAndSet(false, true)) {
            scope.launch(Dispatchers.Default) {
                val finalText = try {
                    awaitCaptureJobStopped(jobToJoin, drainCaptureTail)
                    finalizeAndRelease(s)
                } catch (t: Throwable) {
                    Log.e(TAG, "finalizeAndRelease failed", t)
                    failStreamLog(t.message ?: "finalizeAndRelease failed")
                    ""
                }
                completeStreamLog(finalText)
                try {
                    listener.onFinal(finalText)
                } catch (
                    t: Throwable
                ) {
                    Log.e(TAG, "notify final failed", t)
                }
                closing.set(false)
            }
        }
    }

    private suspend fun awaitCaptureJobStopped(job: Job?, drainTail: Boolean) {
        if (job == null) return
        if (drainTail) {
            val stopped = withTimeoutOrNull(CAPTURE_TAIL_DRAIN_TIMEOUT_MS) {
                job.join()
                true
            } == true
            if (stopped) return
        }
        job.cancel()
        job.join()
    }

    private fun notifyLoadUi(start: Boolean) {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi) ?: return
        if (start) ui.onLocalModelLoadStart() else ui.onLocalModelLoadDone()
    }

    private fun completeStreamLog(finalText: String) {
        val trimmed = finalText.trim()
        val response = "resultChars=${trimmed.length}; empty=${trimmed.isEmpty()}; audioBytes=${loggedAudioBytes.get()}"
        if (trimmed.isEmpty()) {
            streamLog?.failure("Empty ASR result")
        } else {
            streamLog?.success(response)
        }
        streamLog = null
    }

    private fun failStreamLog(message: String) {
        streamLog?.failure(message)
        streamLog = null
    }

    private fun startCapture() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = FRAME_MS
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                val msg = context.getString(R.string.error_record_permission_denied)
                failStreamLog(msg)
                listener.onError(msg)
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
                Log.d(TAG, "Starting audio capture for X-ASR with chunk=${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get() && currentStream == null) return@collect
                    if (audioChunk.isEmpty()) return@collect
                    val tailDrainRequestedAtChunkStart = captureTailDrainRequested.get()
                    val tailDrainChunkIndex = if (tailDrainRequestedAtChunkStart) {
                        captureTailDrainChunks.incrementAndGet()
                    } else {
                        0
                    }
                    if (audioChunk.isNotEmpty()) {
                        loggedAudioBytes.addAndGet(audioChunk.size)
                    }

                    val leveled = vadInputLeveler.process(audioChunk)

                    // Calculate and send audio amplitude (for waveform animation)
                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    val shouldStopForVad = vadDetector?.shouldStop(
                        leveled.leveledPcm,
                        leveled.leveledPcm.size
                    ) == true
                    val s = currentStream
                    if (s == null) {
                        withContext(NonCancellable) {
                            appendPrebuffer(audioChunk)
                        }
                    } else {
                        withContext(NonCancellable) {
                            deliverChunk(s, audioChunk, audioChunk.size)
                        }
                    }

                    if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }

                    // VAD 自动判停：当前块必须先进入模型，避免尾段在停止前被丢弃。
                    if (shouldStopForVad) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (
                            t: Throwable
                        ) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }
                    if (
                        tailDrainRequestedAtChunkStart &&
                        xAsrShouldStopAfterTailDrainChunk(
                            tailDrainChunkIndex,
                            CAPTURE_TAIL_DRAIN_CHUNKS
                        )
                    ) {
                        throw CancellationException("X-ASR capture stopped after tail drain")
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    val msg = if (isLikelyMicInUseError(t)) {
                        context.getString(R.string.asr_error_mic_in_use)
                    } else {
                        context.getString(R.string.error_audio_error, t.message ?: "")
                    }
                    failStreamLog(msg)
                    try {
                        listener.onError(msg)
                    } catch (
                        err: Throwable
                    ) {
                        Log.e(TAG, "notify error failed", err)
                    }

                    // 录音被系统中断：静默释放（不再回调 onFinal），避免后续 stop() 触发 JNI 竞态
                    closeSilently.set(true)
                    running.set(false)
                    closing.set(true)
                    val s = currentStream
                    if (s != null && finalizeOnce.compareAndSet(false, true)) {
                        scope.launch(Dispatchers.Default) {
                            try {
                                releaseStreamSilently(s)
                            } catch (releaseErr: Throwable) {
                                Log.e(TAG, "releaseStreamSilently failed", releaseErr)
                            } finally {
                                closeSilently.set(false)
                                closing.set(false)
                            }
                        }
                    }
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

    private fun isLikelyMicInUseError(t: Throwable): Boolean {
        fun matchOne(msg: String?): Boolean {
            val m = msg?.lowercase() ?: return false
            if (m.contains("audiorecord read error")) {
                val code = m.substringAfter("audiorecord read error:", "").trim().toIntOrNull()
                return code == -3 || code == -6 || code == -2
            }
            if (m.contains("failed to start recording") || m.contains("startrecording")) return true
            if (m.contains("error reading audio data") || m.contains("audiorecord")) return true
            return false
        }
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (matchOne(cur.message)) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    private suspend fun deliverChunk(stream: Any, bytes: ByteArray, len: Int) {
        if (!running.get() && !closing.get()) return
        if (currentStream !== stream) return
        val floats = pcmToFloatArray(bytes, len)
        if (floats.isEmpty()) return

        var partial: String? = null
        streamMutex.withLock {
            if (currentStream !== stream) return
            mgr.acceptWaveform(stream, floats, sampleRate)
            var loops = 0
            while (mgr.isReady(stream) && loops < 8) {
                mgr.decode(stream)
                loops++
            }
            partial = mgr.getResultText(stream)
        }

        // 节流发送 partial
        val now = SystemClock.uptimeMillis()
        if (!partial.isNullOrBlank() && running.get() && !closing.get()) {
            val normalized = formatXAsrText(partial, useItnForSession)
            val needEmit = (now - lastEmitUptimeMs) >= FRAME_MS && normalized != lastEmittedText
            if (needEmit) {
                try {
                    listener.onPartial(normalized)
                } catch (
                    t: Throwable
                ) {
                    Log.e(TAG, "notify partial failed", t)
                }
                lastEmitUptimeMs = now
                lastEmittedText = normalized
            }
        }
    }

    private suspend fun finalizeAndRelease(stream: Any): String {
        var text: String? = null
        streamMutex.withLock {
            if (currentStream !== stream) return@withLock
            val tailSamples = ((sampleRate * 0.6).toInt()).coerceAtLeast(1)
            val tail = FloatArray(tailSamples)
            mgr.acceptWaveform(stream, tail, sampleRate)
            mgr.inputFinished(stream)

            val startUptimeMs = SystemClock.uptimeMillis()
            val maxUptimeMs = startUptimeMs + 2500L
            var loops = 0
            while (loops < 512 && SystemClock.uptimeMillis() < maxUptimeMs) {
                if (!mgr.isReady(stream)) break
                mgr.decode(stream)
                loops++
            }
            text = mgr.getResultText(stream)
            try {
                mgr.releaseStream(stream)
            } catch (
                t: Throwable
            ) {
                Log.e(TAG, "releaseStream failed", t)
            }
            currentStream = null
        }
        val out = formatXAsrText(text.orEmpty(), useItnForSession)
        if (out.isEmpty()) return out
        return out
    }

    private suspend fun releaseStreamSilently(stream: Any) {
        streamMutex.withLock {
            if (currentStream !== stream) return
            try {
                mgr.releaseStream(stream)
            } catch (
                t: Throwable
            ) {
                Log.e(TAG, "releaseStream failed", t)
            }
            currentStream = null
        }
    }

    private suspend fun appendPrebuffer(bytes: ByteArray) {
        prebufferMutex.withLock {
            if (bytes.isEmpty()) return@withLock
            while (prebufferBytes + bytes.size > maxPrebufferBytes && prebuffer.isNotEmpty()) {
                val rm = prebuffer.removeFirst()
                prebufferBytes -= rm.size
            }
            prebuffer.addLast(bytes.copyOf())
            prebufferBytes += bytes.size
        }
    }

    private suspend fun drainPrebufferTo(stream: Any) {
        val list = mutableListOf<ByteArray>()
        prebufferMutex.withLock {
            if (prebuffer.isEmpty()) return
            list.addAll(prebuffer)
            prebuffer.clear()
            prebufferBytes = 0
        }
        for (b in list) {
            deliverChunk(stream, b, b.size)
        }
    }

    private fun pcmToFloatArray(src: ByteArray, len: Int): FloatArray {
        if (len <= 1) return FloatArray(0)
        val n = len / 2
        val out = FloatArray(n)
        var i = 0
        var offset = 0
        while (i < n) {
            val s = (src[offset + 1].toInt() shl 8) or (src[offset].toInt() and 0xFF)
            var f = s / 32768.0f
            if (f > 1f) {
                f = 1f
            } else if (f < -1f) {
                f = -1f
            }
            out[i] = f
            i++
            offset += 2
        }
        return out
    }
}

/**
 * 查找 X-ASR 480ms 模型目录：tokens.txt、encoder-480ms、decoder-480ms、joiner-480ms 必须同目录。
 */
fun findXAsrModelDir(root: java.io.File?): java.io.File? = findXAsrModelFiles(root)?.dir

internal data class XAsrModelFiles(
    val dir: java.io.File,
    val tokens: java.io.File,
    val encoder: java.io.File,
    val decoder: java.io.File,
    val joiner: java.io.File
)

internal fun findXAsrModelFiles(root: java.io.File?): XAsrModelFiles? = (checkXAsrModelFiles(root) as? LocalModelCheck.Ready)?.value

internal fun checkXAsrModelFiles(root: java.io.File?): LocalModelCheck<XAsrModelFiles> = checkXAsrModelFilesInternal(context = null, root = root)

internal fun checkXAsrModelFiles(
    context: Context,
    root: java.io.File?
): LocalModelCheck<XAsrModelFiles> = checkXAsrModelFilesInternal(context = context.applicationContext, root = root)

private fun checkXAsrModelFilesInternal(
    context: Context?,
    root: java.io.File?
): LocalModelCheck<XAsrModelFiles> {
    if (root == null || !root.exists() || !root.isDirectory) return LocalModelCheck.Missing

    fun filesIn(dir: java.io.File): LocalModelCheck<XAsrModelFiles> {
        val files = XAsrModelFiles(
            dir = dir,
            tokens = java.io.File(dir, "tokens.txt"),
            encoder = java.io.File(dir, "encoder-480ms.onnx"),
            decoder = java.io.File(dir, "decoder-480ms.onnx"),
            joiner = java.io.File(dir, "joiner-480ms.onnx")
        )
        val check = if (context != null) {
            requireModelFilesCached(
                context,
                files.tokens to LocalModelSpecs.XAsr.tokens,
                files.encoder to LocalModelSpecs.XAsr.encoder,
                files.decoder to LocalModelSpecs.XAsr.decoder,
                files.joiner to LocalModelSpecs.XAsr.joiner
            )
        } else {
            requireModelFiles(
                files.tokens to LocalModelSpecs.XAsr.tokens,
                files.encoder to LocalModelSpecs.XAsr.encoder,
                files.decoder to LocalModelSpecs.XAsr.decoder,
                files.joiner to LocalModelSpecs.XAsr.joiner
            )
        }
        return when (check) {
            is LocalModelCheck.IntegrityError -> LocalModelCheck.IntegrityError(check.fileName)
            LocalModelCheck.Missing -> LocalModelCheck.Missing
            is LocalModelCheck.Ready -> LocalModelCheck.Ready(files)
        }
    }

    val direct = filesIn(root)
    if (direct is LocalModelCheck.Ready || direct is LocalModelCheck.IntegrityError) return direct
    val subs = root.listFiles() ?: return LocalModelCheck.Missing
    subs.forEach { child ->
        if (child.isDirectory && child.name != "__MACOSX" && !child.name.startsWith(".")) {
            val nested = checkXAsrModelFilesInternal(context, child)
            if (nested is LocalModelCheck.Ready || nested is LocalModelCheck.IntegrityError) return nested
        }
    }
    return LocalModelCheck.Missing
}

/**
 * 释放 X-ASR 识别器（供设置页或切换供应商时手工卸载）
 */
fun unloadXAsrRecognizer() {
    LocalModelLoadCoordinator.cancel()
    XAsrOnnxManager.getInstance().unload()
}

// 判断是否已有缓存的本地 X-ASR 识别器（已加载或正在加载中）
fun isXAsrPrepared(): Boolean {
    val manager = XAsrOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

private const val X_ASR_CJK_PUNCT = "，。！？；：、（）《》〈〉【】「」『』“”‘’"
private const val X_ASR_ASCII_PUNCT_NO_LEADING_SPACE = ",.!?;:%)]}"

private fun formatXAsrText(text: String, useItn: Boolean): String {
    var out = normalizeXAsrCjkSpacing(text.trim())
    if (useItn && out.isNotEmpty()) {
        out = normalizeXAsrCjkSpacing(ChineseItn.normalize(out))
    }
    return out
}

private fun normalizeXAsrCjkSpacing(text: String): String {
    if (text.isEmpty()) return text
    val chars = text.toCharArray()
    val out = StringBuilder(text.length)
    var i = 0
    while (i < chars.size) {
        val ch = chars[i]
        if (ch.isWhitespace()) {
            val prev = out.lastOrNull()
            var j = i + 1
            while (j < chars.size && chars[j].isWhitespace()) {
                j++
            }
            val next = chars.getOrNull(j)
            val dropCjkSpace = prev != null &&
                next != null &&
                isXAsrCjkOrPunct(prev) &&
                isXAsrCjkOrPunct(next)
            val dropBeforeAsciiPunct = next != null &&
                X_ASR_ASCII_PUNCT_NO_LEADING_SPACE.indexOf(next) >= 0
            if (!dropCjkSpace && !dropBeforeAsciiPunct) {
                var k = i
                while (k < j) {
                    out.append(chars[k])
                    k++
                }
            }
            i = j
            continue
        }
        out.append(ch)
        i++
    }
    return out.toString()
}

private fun isXAsrCjkOrPunct(ch: Char): Boolean = isXAsrCjk(ch) || X_ASR_CJK_PUNCT.indexOf(ch) >= 0

private fun isXAsrCjk(ch: Char): Boolean = ch in '\u3400'..'\u4DBF' ||
    ch in '\u4E00'..'\u9FFF' ||
    ch in '\uF900'..'\uFAFF'

// ===== 反射式在线识别管理器 =====

private class ReflectiveOnlineStream(val instance: Any) {
    private val cls = instance.javaClass
    private val acceptWaveformMethod: Method? = try {
        cls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
    } catch (_: Throwable) {
        null
    }
    private val inputFinishedMethod: Method? = try {
        cls.getMethod("inputFinished")
    } catch (_: Throwable) {
        null
    }
    private val releaseMethod: Method? = try {
        cls.getMethod("release")
    } catch (_: Throwable) {
        null
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            (acceptWaveformMethod ?: throw NoSuchMethodException("acceptWaveform"))
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "acceptWaveform reflection failed", t)
        }
    }

    fun inputFinished() {
        try {
            (inputFinishedMethod ?: throw NoSuchMethodException("inputFinished")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "inputFinished failed", t)
        }
    }

    fun release() {
        try {
            (releaseMethod ?: throw NoSuchMethodException("release")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "release failed", t)
        }
    }
}

private class ReflectiveOnlineRecognizer(private val instance: Any, private val cls: Class<*>) {
    private val createStreamMethod: Method = cls.getMethod("createStream", String::class.java)
    private val decodeMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val isReadyMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val getResultMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val resultTextMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val releaseMethod: Method? = try {
        cls.getMethod("release")
    } catch (_: Throwable) {
        null
    }

    fun createStream(): ReflectiveOnlineStream {
        val s = createStreamMethod.invoke(instance, "") as Any
        return ReflectiveOnlineStream(s)
    }

    fun isReady(stream: ReflectiveOnlineStream): Boolean {
        val streamClass = stream.instance.javaClass
        val method = isReadyMethodCache.getOrPut(streamClass) {
            cls.getMethod("isReady", streamClass)
        }
        return method.invoke(instance, stream.instance) as Boolean
    }

    fun decode(stream: ReflectiveOnlineStream) {
        val streamClass = stream.instance.javaClass
        val method = decodeMethodCache.getOrPut(streamClass) {
            cls.getMethod("decode", streamClass)
        }
        method.invoke(instance, stream.instance)
    }

    fun getResultText(stream: ReflectiveOnlineStream): String? {
        val streamClass = stream.instance.javaClass
        val getResultMethod = getResultMethodCache.getOrPut(streamClass) {
            cls.getMethod("getResult", streamClass)
        }
        val res = getResultMethod.invoke(instance, stream.instance)
        return try {
            val resultClass = res.javaClass
            val textMethod = resultTextMethodCache.getOrPut(resultClass) {
                resultClass.getMethod("getText")
            }
            textMethod.invoke(res) as? String
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "getResultText getter not found", t)
            null
        }
    }

    fun release() {
        try {
            (releaseMethod ?: throw NoSuchMethodException("release")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "release failed", t)
        }
    }
}

class XAsrOnnxManager private constructor() {
    companion object {
        private const val TAG = "XAsrOnnxManager"

        @Volatile private var instance: XAsrOnnxManager? = null
        fun getInstance(): XAsrOnnxManager = instance ?: synchronized(this) {
            instance ?: XAsrOnnxManager().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()
    private val runtimeLock = Any()

    @Volatile private var cachedConfig: RecognizerConfig? = null

    @Volatile private var cachedRecognizer: ReflectiveOnlineRecognizer? = null

    @Volatile private var preparing: Boolean = false

    @Volatile private var clsOnlineRecognizer: Class<*>? = null

    @Volatile private var clsOnlineRecognizerConfig: Class<*>? = null

    @Volatile private var clsOnlineModelConfig: Class<*>? = null

    @Volatile private var clsOnlineTransducerModelConfig: Class<*>? = null

    @Volatile private var clsFeatureConfig: Class<*>? = null

    @Volatile private var unloadJob: Job? = null

    // 最近一次配置与流计数：用于保留/卸载
    @Volatile private var lastKeepAliveMs: Long = 0L

    @Volatile private var lastAlwaysKeep: Boolean = false
    private val activeStreams = AtomicInteger(0)

    @Volatile private var pendingUnload: Boolean = false

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OnlineRecognizer"
    )

    fun unload() {
        pendingUnload = true
        scope.launch {
            tryUnloadIfIdle()
        }
    }

    fun isPrepared(): Boolean = cachedRecognizer != null
	
    fun isPreparing(): Boolean = preparing

    private fun invokeCallbackSafely(name: String, callback: (() -> Unit)?) {
        sherpaInvokeCallbackSafely(TAG, name, callback)
    }
	
    private suspend fun tryUnloadIfIdle() {
        mutex.withLock {
            if (!pendingUnload) return@withLock
            if (activeStreams.get() > 0) return@withLock
            try {
                synchronized(runtimeLock) {
                    cachedRecognizer?.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "unload failed", t)
            } finally {
                cachedRecognizer = null
                cachedConfig = null
                pendingUnload = false
            }
        }
    }

    private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
        unloadJob = sherpaScheduleAutoUnload(TAG, scope, unloadJob, keepAliveMs, alwaysKeep) {
            unload()
        }
    }

    private fun initClasses() {
        if (clsOnlineRecognizer == null) {
            clsOnlineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            clsOnlineRecognizerConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            clsOnlineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            clsOnlineTransducerModelConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            Log.d(TAG, "Initialized reflection classes for online recognizer")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    private data class RecognizerConfig(
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val numThreads: Int,
        val provider: String = "cpu",
        val modelType: String = "zipformer2",
        val sampleRate: Int = 16000,
        val featureDim: Int = 80,
        val debug: Boolean = false
    ) {
        fun toCacheKey(): String = listOf(
            tokens,
            encoder,
            decoder,
            joiner,
            numThreads,
            provider,
            modelType,
            sampleRate,
            featureDim,
            debug
        ).joinToString("|")
    }

    private fun buildModelConfig(
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        numThreads: Int,
        provider: String,
        modelType: String,
        debug: Boolean
    ): Any {
        val transducer = clsOnlineTransducerModelConfig!!.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java
        )
            .newInstance(encoder, decoder, joiner)
        val model = clsOnlineModelConfig!!.getDeclaredConstructor().newInstance()
        // Android sherpa-onnx exposes Python from_transducer(...) through OnlineModelConfig.transducer.
        trySetField(model, "tokens", tokens)
        trySetField(model, "numThreads", numThreads)
        trySetField(model, "provider", provider)
        trySetField(model, "modelType", modelType)
        trySetField(model, "debug", debug)
        trySetField(model, "transducer", transducer)
        return model
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val model =
            buildModelConfig(
                config.tokens,
                config.encoder,
                config.decoder,
                config.joiner,
                config.numThreads,
                config.provider,
                config.modelType,
                config.debug
            )
        val feat = buildFeatureConfig(config.sampleRate, config.featureDim)
        val rec = clsOnlineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        // OnlineRecognizerConfig: modelConfig/featConfig/decodingMethod/enableEndpoint/maxActivePaths...
        trySetField(rec, "modelConfig", model)
        trySetField(rec, "featConfig", feat)
        trySetField(rec, "decodingMethod", "greedy_search")
        trySetField(rec, "enableEndpoint", false)
        trySetField(rec, "maxActivePaths", 4)
        return rec
    }

    private fun createRecognizer(recConfig: Any): Any {
        val ctor = clsOnlineRecognizer!!.getDeclaredConstructor(
            android.content.res.AssetManager::class.java,
            clsOnlineRecognizerConfig!!
        )
        return ctor.newInstance(null, recConfig)
    }

    suspend fun prepare(
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            pendingUnload = false
            unloadJob?.cancel()
            unloadJob = null
            initClasses()
            val config = RecognizerConfig(tokens, encoder, decoder, joiner, numThreads)

            val cached = cachedRecognizer
            val sameConfig = cachedConfig == config
            if (sameConfig && cached != null) {
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock true
            }

            if (!sameConfig && cached != null && activeStreams.get() > 0) {
                Log.w(TAG, "prepare skipped: ${activeStreams.get()} active streams")
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock true
            }

            preparing = true
            var newRecognizer: ReflectiveOnlineRecognizer? = null
            try {
                invokeCallbackSafely("onLoadStart", onLoadStart)
                currentCoroutineContext().ensureActive()

                val recConfig = buildRecognizerConfig(config)
                currentCoroutineContext().ensureActive()
                val inst = synchronized(runtimeLock) { createRecognizer(recConfig) }
                newRecognizer = ReflectiveOnlineRecognizer(inst, clsOnlineRecognizer!!)

                currentCoroutineContext().ensureActive()
                val oldRecognizer = cachedRecognizer
                synchronized(runtimeLock) { cachedRecognizer = newRecognizer }
                cachedConfig = config
                newRecognizer = null

                invokeCallbackSafely("onLoadDone", onLoadDone)
                if (oldRecognizer != null) {
                    synchronized(runtimeLock) { oldRecognizer.release() }
                }

                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                true
            } catch (t: CancellationException) {
                val toRelease = newRecognizer
                if (toRelease != null) {
                    synchronized(runtimeLock) { toRelease.release() }
                }
                throw t
            } catch (t: Throwable) {
                val toRelease = newRecognizer
                if (toRelease != null) {
                    synchronized(runtimeLock) { toRelease.release() }
                }
                throw t
            } finally {
                preparing = false
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "prepare failed", t)
            false
        }
    }

    suspend fun createStreamOrNull(): Any? = mutex.withLock {
        try {
            pendingUnload = false
            unloadJob?.cancel()
            unloadJob = null
            val r = cachedRecognizer ?: return@withLock null
            val s = synchronized(runtimeLock) { r.createStream() }
            activeStreams.incrementAndGet()
            s
        } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t)
            null
        }
    }

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int) {
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.acceptWaveform(samples, sampleRate)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveform failed", t)
        }
    }

    fun inputFinished(stream: Any) {
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.inputFinished()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inputFinished failed", t)
        }
    }

    fun isReady(stream: Any): Boolean = try {
        synchronized(runtimeLock) {
            val r = cachedRecognizer
            if (r != null && stream is ReflectiveOnlineStream) r.isReady(stream) else false
        }
    } catch (t: Throwable) {
        Log.e(TAG, "isReady failed", t)
        false
    }

    fun decode(stream: Any) {
        try {
            synchronized(runtimeLock) {
                val r = cachedRecognizer
                if (r != null && stream is ReflectiveOnlineStream) r.decode(stream)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        }
    }

    fun getResultText(stream: Any): String? = try {
        synchronized(runtimeLock) {
            val r = cachedRecognizer
            if (r != null && stream is ReflectiveOnlineStream) r.getResultText(stream) else null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "getResultText failed", t)
        null
    }

    fun releaseStream(stream: Any?) {
        if (stream == null) return
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.release()
            }
            activeStreams.updateAndGet { if (it > 0) it - 1 else 0 }
            scheduleUnloadIfIdle()
        } catch (t: Throwable) {
            Log.e(TAG, "releaseStream failed", t)
        }
    }

    fun scheduleUnloadIfIdle() {
        if (activeStreams.get() <= 0) {
            if (pendingUnload) {
                scope.launch { tryUnloadIfIdle() }
            } else {
                scheduleAutoUnload(lastKeepAliveMs, lastAlwaysKeep)
            }
        }
    }
}

// 预加载：根据当前配置尝试构建本地 X-ASR 在线识别器，降低首次点击等待
fun preloadXAsrIfConfigured(
    context: android.content.Context,
    prefs: com.brycewg.asrkb.store.Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = XAsrOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.XAsr,
                "preload",
                context.getString(com.brycewg.asrkb.R.string.error_local_asr_not_ready)
            )
            return
        }

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val filesCheck = checkXAsrModelFiles(context, java.io.File(base, "x_asr"))
        val files = (filesCheck as? LocalModelCheck.Ready)?.value
        if (files == null) {
            val msg = localModelErrorMessage(
                context,
                filesCheck,
                com.brycewg.asrkb.R.string.error_x_asr_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.XAsr,
                "preload",
                msg
            )
            return
        }

        val keepMinutes = prefs.xAsrKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        val numThreads = prefs.xAsrNumThreads
        val key = listOf(
            "x_asr",
            "tokens=${files.tokens.absolutePath}",
            "encoder=${files.encoder.absolutePath}",
            "decoder=${files.decoder.absolutePath}",
            "joiner=${files.joiner.absolutePath}",
            "threads=$numThreads"
        ).joinToString("|")

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                tokens = files.tokens.absolutePath,
                encoder = files.encoder.absolutePath,
                decoder = files.decoder.absolutePath,
                joiner = files.joiner.absolutePath,
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.XAsr,
                        source = "preload"
                    )
                    if (!suppressToastOnStart) {
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(com.brycewg.asrkb.R.string.x_asr_loading_model),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    onLoadStart?.invoke()
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    onLoadDone?.invoke()
                }
            )
            if (!ok) {
                loadLog?.failure("prepare returned false")
                loadLog = null
            }
            if (ok && !forImmediateUse) {
                val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                mainHandler.post {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.brycewg.asrkb.R.string.sv_model_ready_with_ms, dt),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                manager.scheduleUnloadIfIdle()
            }
        }
    } catch (t: Throwable) {
        Log.e("X-ASRPreload", "preloadXAsrIfConfigured failed", t)
    }
}
