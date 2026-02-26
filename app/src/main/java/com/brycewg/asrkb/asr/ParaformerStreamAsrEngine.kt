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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于 sherpa-onnx OnlineRecognizer 的本地 Paraformer 流式识别引擎。
 * - 反射调用 sherpa-onnx Kotlin API，避免编译期强耦合。
 * - 录音分片（默认200ms），送入在线流；每次分片后尽可能 decode，并节流发送 partial。
 * - 停止时写入尾部静音 + inputFinished + 完整 decode 输出最终结果。
 */
class ParaformerStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false,
) : StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "ParaformerStreamAsrEngine"
        private const val FRAME_MS = 200
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val finalizeOnce = AtomicBoolean(false)
    private val closeSilently = AtomicBoolean(false)

    @Volatile private var useItnForSession: Boolean = false
    private var audioJob: Job? = null
    private val mgr = ParaformerOnnxManager.getInstance()

    @Volatile private var currentStream: Any? = null
    private val streamMutex = Mutex()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 预缓冲：在模型加载/流未创建前缓存音频，避免首字延迟过长
    private val prebufferMutex = Mutex()
    private val prebuffer = ArrayDeque<ByteArray>()
    private var prebufferBytes: Int = 0
    private val maxPrebufferBytes: Int = 384 * 1024 // ~12s @16kHz s16le mono

    private var lastEmitUptimeMs: Long = 0L
    private var lastEmittedText: String? = null

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        closing.set(false)
        finalizeOnce.set(false)
        closeSilently.set(false)
        // 外部推流模式下不检查录音权限
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                return
            }
        }

        if (!mgr.isOnnxAvailable()) {
            listener.onError(context.getString(R.string.error_local_asr_not_ready))
            return
        }

        useItnForSession = try {
            prefs.pfUseItn
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read pfUseItn", t)
            false
        }

        // 若通用标点模型未安装，给出一次性提示（不阻断识别）
        try {
            SherpaPunctuationManager.maybeWarnModelMissing(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to warn punctuation model missing", t)
        }

        running.set(true)
        lastEmitUptimeMs = 0L
        lastEmittedText = null

        // 非外部模式才启动采集；外部模式下由 appendPcm 注入
        if (!externalPcmMode) startCapture()
        scope.launch(Dispatchers.Default) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = java.io.File(base, "paraformer")
            val isTri = prefs.pfModelVariant.startsWith("trilingual")
            val group = if (isTri) java.io.File(root, "trilingual") else java.io.File(root, "bilingual")
            val dir = findPfModelDir(group)
            if (dir == null) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            val int8 = prefs.pfModelVariant.contains("int8")
            val enc = if (int8) java.io.File(dir, "encoder.int8.onnx") else java.io.File(dir, "encoder.onnx")
            val dec = if (int8) java.io.File(dir, "decoder.int8.onnx") else java.io.File(dir, "decoder.onnx")
            if (!enc.exists() || !dec.exists()) {
                listener.onError(context.getString(R.string.error_paraformer_model_missing))
                running.set(false)
                return@launch
            }

            val keepMinutes = prefs.pfKeepAliveMinutes
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val ok = mgr.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                numThreads = prefs.pfNumThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = { notifyLoadUi(true) },
                onLoadDone = { notifyLoadUi(false) },
            )
            if (!ok) {
                Log.w(TAG, "Paraformer prepare() failed")
                return@launch
            }

            val stream = mgr.createStreamOrNull()
            if (stream == null) {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
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
                    } catch (t: Throwable) {
                        Log.e(TAG, "releaseStreamSilently failed", t)
                    }
                } else {
                    val finalText = try {
                        finalizeAndRelease(stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "finalizeAndRelease failed", t)
                        ""
                    }
                    try {
                        listener.onFinal(finalText)
                    } catch (t: Throwable) {
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
        try {
            listener.onAmplitude(com.brycewg.asrkb.asr.calculateNormalizedAmplitude(pcm))
        } catch (_: Throwable) { }
        val s = currentStream
        if (s == null) {
            scope.launch { appendPrebuffer(pcm) }
        } else {
            scope.launch { deliverChunk(s, pcm, pcm.size) }
        }
    }

    override fun stop() {
        if (!running.get() && currentStream == null) {
            // 尚在加载阶段：仅标记关闭并停止采集
            closing.set(true)
            audioJob?.cancel()
            audioJob = null
            return
        }
        running.set(false)
        closing.set(true)
        audioJob?.cancel()
        audioJob = null

        val s = currentStream
        if (s != null && finalizeOnce.compareAndSet(false, true)) {
            scope.launch(Dispatchers.Default) {
                val finalText = try {
                    finalizeAndRelease(s)
                } catch (t: Throwable) {
                    Log.e(TAG, "finalizeAndRelease failed", t)
                    ""
                }
                try {
                    listener.onFinal(finalText)
                } catch (t: Throwable) {
                    Log.e(TAG, "notify final failed", t)
                }
                closing.set(false)
            }
        }
    }

    private fun notifyLoadUi(start: Boolean) {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi) ?: return
        if (start) ui.onLocalModelLoadStart() else ui.onLocalModelLoadDone()
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
                chunkMillis = chunkMillis,
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                VadDetector(context, sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            } else {
                null
            }

            try {
                Log.d(TAG, "Starting audio capture for Paraformer with chunk=${chunkMillis}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get() && currentStream == null) return@collect

                    // Calculate and send audio amplitude (for waveform animation)
                    try {
                        val amplitude = com.brycewg.asrkb.asr.calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    // VAD 自动判停
                    if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }

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
                    try {
                        listener.onError(msg)
                    } catch (err: Throwable) {
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
            val trimmed = partial!!.trim()
            val normalized = if (useItnForSession) ChineseItn.normalize(trimmed) else trimmed
            val needEmit = (now - lastEmitUptimeMs) >= FRAME_MS && normalized != lastEmittedText
            if (needEmit) {
                try {
                    listener.onPartial(normalized)
                } catch (t: Throwable) {
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
            } catch (t: Throwable) {
                Log.e(TAG, "releaseStream failed", t)
            }
            currentStream = null
        }
        var out = text?.trim().orEmpty()
        if (out.isEmpty()) return out
        if (useItnForSession) {
            out = ChineseItn.normalize(out)
        }
        out = try {
            SherpaPunctuationManager.getInstance().addOfflinePunctuation(context, out)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply offline punctuation", t)
            out
        }
        return out
    }

    private suspend fun releaseStreamSilently(stream: Any) {
        streamMutex.withLock {
            if (currentStream !== stream) return
            try {
                mgr.releaseStream(stream)
            } catch (t: Throwable) {
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
        val bb = ByteBuffer.wrap(src, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }
}

/**
 * 查找 Paraformer 模型目录：在 root 下寻找包含 tokens.txt 且存在 encoder/decoder(onxx/int8.onnx) 的目录。
 */
fun findPfModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    fun validDir(d: java.io.File): Boolean {
        if (!d.exists() || !d.isDirectory) return false
        val tokens = java.io.File(d, "tokens.txt")
        if (!tokens.exists()) return false
        val e1 = java.io.File(d, "encoder.onnx")
        val d1 = java.io.File(d, "decoder.onnx")
        val e2 = java.io.File(d, "encoder.int8.onnx")
        val d2 = java.io.File(d, "decoder.int8.onnx")
        return (e1.exists() && d1.exists()) || (e2.exists() && d2.exists())
    }
    if (validDir(root)) return root
    val subs = root.listFiles() ?: return null
    subs.forEach { f -> if (f.isDirectory && validDir(f)) return f }
    return null
}

/**
 * 释放 Paraformer 识别器（供设置页或切换供应商时手工卸载）
 */
fun unloadParaformerRecognizer() {
    LocalModelLoadCoordinator.cancel()
    ParaformerOnnxManager.getInstance().unload()
}

// 判断是否已有缓存的本地 Paraformer 识别器（已加载或正在加载中）
fun isParaformerPrepared(): Boolean {
    val manager = ParaformerOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

// ===== 反射式在线识别管理器 =====

private class ReflectiveOnlineStream(val instance: Any) {
    private val cls = instance.javaClass

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            cls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "acceptWaveform reflection failed", t)
        }
    }

    fun inputFinished() {
        try {
            cls.getMethod("inputFinished").invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "inputFinished failed", t)
        }
    }

    fun release() {
        try {
            cls.getMethod("release").invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "release failed", t)
        }
    }
}

private class ReflectiveOnlineRecognizer(private val instance: Any, private val cls: Class<*>) {
    fun createStream(): ReflectiveOnlineStream {
        val s = cls.getMethod("createStream", String::class.java).invoke(instance, "") as Any
        return ReflectiveOnlineStream(s)
    }

    fun isReady(stream: ReflectiveOnlineStream): Boolean {
        return cls.getMethod("isReady", stream.instance.javaClass)
            .invoke(instance, stream.instance) as Boolean
    }

    fun decode(stream: ReflectiveOnlineStream) {
        cls.getMethod("decode", stream.instance.javaClass)
            .invoke(instance, stream.instance)
    }

    fun getResultText(stream: ReflectiveOnlineStream): String? {
        val res = cls.getMethod("getResult", stream.instance.javaClass)
            .invoke(instance, stream.instance)
        return try {
            res.javaClass.getMethod("getText").invoke(res) as? String
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "getResultText getter not found", t)
            null
        }
    }

    fun release() {
        try {
            cls.getMethod("release").invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "release failed", t)
        }
    }
}

class ParaformerOnnxManager private constructor() {
    companion object {
        private const val TAG = "ParaformerOnnxManager"

        @Volatile private var instance: ParaformerOnnxManager? = null
        fun getInstance(): ParaformerOnnxManager = instance ?: synchronized(this) {
            instance ?: ParaformerOnnxManager().also { instance = it }
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

    @Volatile private var clsOnlineParaformerModelConfig: Class<*>? = null

    @Volatile private var clsFeatureConfig: Class<*>? = null

    @Volatile private var unloadJob: Job? = null

    // 最近一次配置与流计数：用于保留/卸载
    @Volatile private var lastKeepAliveMs: Long = 0L

    @Volatile private var lastAlwaysKeep: Boolean = false
    private val activeStreams = AtomicInteger(0)

    @Volatile private var pendingUnload: Boolean = false

    fun isOnnxAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            true
        } catch (t: Throwable) {
            Log.d(TAG, "sherpa-onnx online not available", t)
            false
        }
    }

    fun unload() {
        pendingUnload = true
        scope.launch {
            tryUnloadIfIdle()
        }
    }

    fun isPrepared(): Boolean = cachedRecognizer != null
	
    fun isPreparing(): Boolean = preparing

    private fun invokeCallbackSafely(name: String, callback: (() -> Unit)?) {
        if (callback == null) return
        try {
            callback()
        } catch (t: Throwable) {
            Log.e(TAG, "$name callback failed", t)
        }
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
        unloadJob?.cancel()
        if (alwaysKeep) return
        if (keepAliveMs <= 0L) {
            unload()
            return
        }
        unloadJob = scope.launch {
            delay(keepAliveMs)
            unload()
        }
    }

    private fun initClasses() {
        if (clsOnlineRecognizer == null) {
            clsOnlineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            clsOnlineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            clsOnlineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            clsOnlineParaformerModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            Log.d(TAG, "Initialized reflection classes for online recognizer")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean {
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
            true
        } catch (t: Throwable) {
            try {
                val m = target.javaClass.getMethod(
                    "set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    value?.javaClass ?: Any::class.java,
                )
                m.invoke(target, value)
                true
            } catch (t2: Throwable) {
                Log.w(TAG, "Failed to set field '$name'", t2)
                false
            }
        }
    }

    private data class RecognizerConfig(
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val numThreads: Int,
        val provider: String = "cpu",
        val sampleRate: Int = 16000,
        val featureDim: Int = 80,
        val debug: Boolean = false,
    ) {
        fun toCacheKey(): String = listOf(tokens, encoder, decoder, numThreads, provider, sampleRate, featureDim, debug).joinToString("|")
    }

    private fun buildModelConfig(tokens: String, encoder: String, decoder: String, numThreads: Int, provider: String, debug: Boolean): Any {
        val para = clsOnlineParaformerModelConfig!!.getDeclaredConstructor(String::class.java, String::class.java)
            .newInstance(encoder, decoder)
        val model = clsOnlineModelConfig!!.getDeclaredConstructor().newInstance()
        // OnlineModelConfig: tokens/numThreads/provider/debug/paraformer
        trySetField(model, "tokens", tokens)
        trySetField(model, "numThreads", numThreads)
        trySetField(model, "provider", provider)
        trySetField(model, "debug", debug)
        trySetField(model, "paraformer", para)
        return model
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val model = buildModelConfig(config.tokens, config.encoder, config.decoder, config.numThreads, config.provider, config.debug)
        val feat = buildFeatureConfig(config.sampleRate, config.featureDim)
        val rec = clsOnlineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        // OnlineRecognizerConfig: modelConfig/featConfig/decodingMethod/enableEndpoint/maxActivePaths...
        trySetField(rec, "modelConfig", model)
        trySetField(rec, "featConfig", feat)
        trySetField(rec, "decodingMethod", "greedy_search")
        trySetField(rec, "enableEndpoint", true)
        trySetField(rec, "maxActivePaths", 4)
        return rec
    }

    private fun createRecognizer(recConfig: Any): Any {
        val ctor = clsOnlineRecognizer!!.getDeclaredConstructor(
            android.content.res.AssetManager::class.java,
            clsOnlineRecognizerConfig!!,
        )
        return ctor.newInstance(null, recConfig)
    }

    suspend fun prepare(
        tokens: String,
        encoder: String,
        decoder: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null,
    ): Boolean = mutex.withLock {
        try {
            pendingUnload = false
            unloadJob?.cancel()
            unloadJob = null
            initClasses()
            val config = RecognizerConfig(tokens, encoder, decoder, numThreads)

            val cached = cachedRecognizer
            val sameConfig = cachedConfig == config
            if (sameConfig && cached != null) {
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                return@withLock true
            }

            if (!sameConfig && cached != null && activeStreams.get() > 0) {
                Log.w(TAG, "prepare skipped: ${activeStreams.get()} active streams")
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
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
                true
            } catch (t: CancellationException) {
                if (newRecognizer != null) {
                    synchronized(runtimeLock) { newRecognizer!!.release() }
                }
                throw t
            } catch (t: Throwable) {
                if (newRecognizer != null) {
                    synchronized(runtimeLock) { newRecognizer!!.release() }
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

    fun isReady(stream: Any): Boolean {
        return try {
            synchronized(runtimeLock) {
                val r = cachedRecognizer
                if (r != null && stream is ReflectiveOnlineStream) r.isReady(stream) else false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "isReady failed", t)
            false
        }
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

    fun getResultText(stream: Any): String? {
        return try {
            synchronized(runtimeLock) {
                val r = cachedRecognizer
                if (r != null && stream is ReflectiveOnlineStream) r.getResultText(stream) else null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getResultText failed", t)
            null
        }
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

// 预加载：根据当前配置尝试构建本地 Paraformer 在线识别器，降低首次点击等待
fun preloadParaformerIfConfigured(
    context: android.content.Context,
    prefs: com.brycewg.asrkb.store.Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false,
) {
    try {
        val manager = ParaformerOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) return

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val root = java.io.File(base, "paraformer")
        val isTri = prefs.pfModelVariant.startsWith("trilingual")
        val group = if (isTri) java.io.File(root, "trilingual") else java.io.File(root, "bilingual")
        val dir = findPfModelDir(group) ?: return
        val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
        val int8 = prefs.pfModelVariant.contains("int8")
        val enc = if (int8) java.io.File(dir, "encoder.int8.onnx") else java.io.File(dir, "encoder.onnx")
        val dec = if (int8) java.io.File(dir, "decoder.int8.onnx") else java.io.File(dir, "decoder.onnx")
        if (!enc.exists() || !dec.exists()) return

        val keepMinutes = prefs.pfKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        val numThreads = prefs.pfNumThreads
        val key = "paraformer|tokens=$tokensPath|encoder=${enc.absolutePath}|decoder=${dec.absolutePath}|threads=$numThreads"

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                tokens = tokensPath,
                encoder = enc.absolutePath,
                decoder = dec.absolutePath,
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    if (!suppressToastOnStart) {
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(com.brycewg.asrkb.R.string.pf_loading_model),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    onLoadStart?.invoke()
                },
                onLoadDone = onLoadDone,
            )
            if (ok && !forImmediateUse) {
                val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                mainHandler.post {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.brycewg.asrkb.R.string.sv_model_ready_with_ms, dt),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                manager.scheduleUnloadIfIdle()
            }
        }
    } catch (t: Throwable) {
        Log.e("ParaformerPreload", "preloadParaformerIfConfigured failed", t)
    }
}
