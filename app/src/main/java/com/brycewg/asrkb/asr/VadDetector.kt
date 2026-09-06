package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

data class VadTuning(
    val name: String,
    val threshold: Float,
    val minSilenceDuration: Float,
    val minSpeechDuration: Float = 0.25f,
    val windowSize: Int = 256,
    val speechHangoverMs: Int = 250,
    val initialDebounceMs: Int = 2000
) {
    companion object {
        const val LEVELS: Int = 10

        val Default = tuningForLevel(Prefs.DEFAULT_SILENCE_SENSITIVITY)

        val ConservativeFilter = VadTuning(
            name = "conservative_filter",
            threshold = 0.40f,
            minSilenceDuration = 0.55f,
            minSpeechDuration = 0.25f,
            windowSize = 256,
            speechHangoverMs = 300,
            initialDebounceMs = 3000
        )

        fun tuningForLevel(level: Int): VadTuning {
            val lvl = level.coerceIn(1, LEVELS)
            val ratio = (lvl - 1).toFloat() / (LEVELS - 1).toFloat()
            val conservativeRatio = 1f - ratio
            return VadTuning(
                name = "level_$lvl",
                threshold = 0.28f + 0.22f * ratio,
                minSilenceDuration = 0.30f + 0.30f * conservativeRatio,
                minSpeechDuration = 0.25f,
                windowSize = 256,
                speechHangoverMs = (250 + 300 * conservativeRatio).toInt(),
                initialDebounceMs = (1400 + 1800 * conservativeRatio).toInt()
            )
        }
    }
}

/**
 * 基于 Ten VAD（sherpa-onnx）的语音活动检测与判停器。
 *
 * 相比基于音量阈值的 SilenceDetector，VAD 使用 AI 模型进行语音/静音判断，
 * 能够更准确地区分语音、呼吸声、环境噪音，减少误判。
 *
 * ## 工作原理
 * - 将 PCM 音频块转换为归一化的 FloatArray
 * - 调用 sherpa-onnx Vad 模型进行实时语音活动检测
 * - 累计连续非语音时长，超过窗口阈值时触发判停
 *
 *
 * @param context Android Context，用于访问 AssetManager
 * @param sampleRate 音频采样率（Hz），必须与 PCM 数据一致
 * @param windowMs 连续非语音的时长阈值（毫秒），超过此值判定为静音
 * @param sensitivityLevel 判停灵敏度档位（1-10），值越大越容易判定为无人说话。
 * @param tuning 内部 VAD 参数；默认按 sensitivityLevel 映射，也可由空音频过滤等内部路径显式指定。
 */
class VadDetector(
    private val context: Context,
    private val sampleRate: Int,
    private val windowMs: Int,
    sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY,
    private val tuning: VadTuning = VadTuning.tuningForLevel(sensitivityLevel)
) {
    /**
     * 单帧分析结果：
     * @param isSpeech 当前帧是否检测到语音
     * @param silenceStop 是否累计静音已达到停录阈值
     */
    data class FrameResult(val isSpeech: Boolean, val silenceStop: Boolean)

    companion object {
        private const val TAG = "VadDetector"

        private const val MAX_POOL_SIZE = 2

        private data class VadPoolKey(val sampleRate: Int, val tuning: VadTuning)

        private val poolLock = Any()

        private val vadPools = HashMap<VadPoolKey, ArrayDeque<Vad>>()

        private fun buildVadModelConfig(sampleRate: Int, tuning: VadTuning): VadModelConfig {
            val tenConfig = TenVadModelConfig(
                model = "vad/ten-vad.onnx",
                threshold = tuning.threshold,
                minSilenceDuration = tuning.minSilenceDuration,
                minSpeechDuration = tuning.minSpeechDuration,
                windowSize = tuning.windowSize
            )
            return VadModelConfig(
                tenVadModelConfig = tenConfig,
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        }

        private fun createVad(context: Context, sampleRate: Int, tuning: VadTuning): Vad = Vad(
            assetManager = context.assets,
            config = buildVadModelConfig(sampleRate, tuning)
        )

        private fun acquireFromPool(context: Context, sampleRate: Int, tuning: VadTuning): Vad {
            val key = VadPoolKey(sampleRate = sampleRate, tuning = tuning)

            val take = synchronized(poolLock) {
                vadPools[key]?.let { pool ->
                    if (pool.isEmpty()) null else pool.removeFirst()
                }
            }

            val vad = take ?: createVad(context, sampleRate, tuning)
            try {
                vad.reset()
                while (!vad.empty()) vad.pop()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to reset VAD after acquire", t)
            }
            return vad
        }

        private fun recycleToPool(key: VadPoolKey, vad: Vad) {
            try {
                vad.reset()
                while (!vad.empty()) vad.pop()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to reset VAD before pooling", t)
            }

            val pooled = synchronized(poolLock) {
                val pool = vadPools.getOrPut(key) { ArrayDeque() }
                if (pool.size >= MAX_POOL_SIZE) {
                    false
                } else {
                    pool.addLast(vad)
                    true
                }
            }
            if (pooled) return
            try {
                vad.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to release VAD", t)
            }
        }

        // 预加载 VAD（填充池，降低首次录音时的模型加载延迟）
        fun preload(
            context: Context,
            sampleRate: Int,
            sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY
        ) {
            try {
                val tuning = VadTuning.tuningForLevel(sensitivityLevel)
                val key = VadPoolKey(sampleRate = sampleRate, tuning = tuning)

                val alreadyReady = synchronized(poolLock) { vadPools[key]?.isNotEmpty() == true }
                if (alreadyReady) return

                val vad = createVad(context, sampleRate, tuning)
                recycleToPool(key, vad)
                Log.i(TAG, "VAD preloaded (sr=$sampleRate, tuning=${tuning.name})")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to preload global VAD", t)
            }
        }

        // 重建预加载池（用于灵敏度调整后“立即生效”）。
        fun rebuildGlobal(context: Context, sampleRate: Int, sensitivityLevel: Int) {
            try {
                val toRelease: List<Vad>
                synchronized(poolLock) {
                    toRelease = vadPools.values.flatMap { it.toList() }
                    vadPools.clear()
                }
                toRelease.forEach { v ->
                    try {
                        v.release()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to release pooled VAD during rebuild", t)
                    }
                }
                preload(context, sampleRate, sensitivityLevel)
                Log.i(
                    TAG,
                    "VAD pool rebuilt (sr=$sampleRate, tuning=${VadTuning.tuningForLevel(sensitivityLevel).name})"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to rebuild global VAD", t)
            }
        }
    }

    private var vad: Vad? = null
    private var silentMsAcc: Int = 0
    private val minSilenceDuration: Float
    private val threshold: Float
    private val speechHangoverMs: Int
    private var speechHangoverRemainingMs: Int = 0

    // 录音开始阶段的初期防抖（仅在首次检测到语音之前生效）
    private val initialDebounceMs: Int
    private var initialDebounceRemainingMs: Int = 0
    private var hasDetectedSpeech: Boolean = false
    private val poolKey: Companion.VadPoolKey

    init {
        poolKey = Companion.VadPoolKey(sampleRate = sampleRate, tuning = tuning)
        minSilenceDuration = tuning.minSilenceDuration
        threshold = tuning.threshold
        speechHangoverMs = tuning.speechHangoverMs
        initialDebounceMs = tuning.initialDebounceMs
        initialDebounceRemainingMs = initialDebounceMs

        try {
            initVad()
            Log.i(
                TAG,
                "VadDetector initialized: windowMs=$windowMs, tuning=${tuning.name}, minSilenceDuration=$minSilenceDuration, threshold=$threshold, hangoverMs=$speechHangoverMs, initialDebounceMs=$initialDebounceMs"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize VAD, will fallback to no detection", t)
        }
    }

    /**
     * 初始化 sherpa-onnx Vad
     */
    private fun initVad() {
        vad = acquireFromPool(context, sampleRate, tuning)
        Log.d(TAG, "Vad instance acquired")
    }

    /**
     * 处理音频帧，判断是否应停止录音。
     *
     * @param buf PCM 音频数据（16-bit LE）
     * @param len 有效数据长度（字节）
     * @return 如果连续非语音时长超过窗口阈值，返回 true
     */
    fun shouldStop(buf: ByteArray, len: Int): Boolean = analyzeFrame(buf, len).silenceStop

    fun isAvailable(): Boolean = vad != null

    /**
     * 对单帧音频进行 VAD 分析，返回“是否语音”与“是否触发静音停录”。
     *
     * - isSpeech：基于模型的当前帧语音判定；
     * - silenceStop：在综合初期防抖、挂起和平滑累计后，是否达到静音停录阈值。
     */
    fun analyzeFrame(buf: ByteArray, len: Int): FrameResult {
        val vad = this.vad ?: return FrameResult(isSpeech = false, silenceStop = false)

        try {
            val frameMs = if (sampleRate > 0) ((len / 2) * 1000) / sampleRate else 0

            // 1. 将 PCM ByteArray 转换为 FloatArray（归一化到 -1.0 ~ 1.0）
            val samples = pcmToFloatArray(buf, len)
            if (samples.isEmpty()) return FrameResult(isSpeech = false, silenceStop = false)

            // 2. 调用 Vad.acceptWaveform(FloatArray)
            vad.acceptWaveform(samples)

            // 3. 调用 Vad.isSpeechDetected(): Boolean
            val isSpeech = vad.isSpeechDetected()

            // 4. 清理已完成的语音片段队列，避免堆积；保留 VAD 内部时序状态以支持跨帧判定
            while (!vad.empty()) {
                vad.pop()
            }

            // 5. 初期防抖 + 语音挂起 平滑处理，再进行累计非语音时长
            if (isSpeech) {
                silentMsAcc = 0
                hasDetectedSpeech = true
                speechHangoverRemainingMs = speechHangoverMs
                return FrameResult(isSpeech = true, silenceStop = false)
            } else {
                // 初期防抖：尚未检测到语音前，不累计静音
                if (!hasDetectedSpeech && initialDebounceRemainingMs > 0) {
                    initialDebounceRemainingMs -= frameMs
                    if (initialDebounceRemainingMs < 0) initialDebounceRemainingMs = 0
                    return FrameResult(isSpeech = false, silenceStop = false)
                }

                // 语音挂起：检测到语音后的一小段时间内，不累计静音
                if (speechHangoverRemainingMs > 0) {
                    speechHangoverRemainingMs -= frameMs
                    if (speechHangoverRemainingMs < 0) speechHangoverRemainingMs = 0
                    // 挂起期内直接返回
                    return FrameResult(isSpeech = false, silenceStop = false)
                }

                silentMsAcc += frameMs
                if (silentMsAcc >= windowMs) {
                    Log.d(TAG, "Silence window reached: ${silentMsAcc}ms >= ${windowMs}ms")
                    return FrameResult(isSpeech = false, silenceStop = true)
                }
            }

            return FrameResult(isSpeech = false, silenceStop = false)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during VAD detection", t)
            return FrameResult(isSpeech = false, silenceStop = false)
        }
    }

    /**
     * 仅基于 VAD 判断当前帧是否包含语音，不参与静音累计与停录判定。
     *
     * 适用于“起说检测”等仅需语音活动信息的场景（例如畅说模式）。
     */
    fun isSpeechFrame(buf: ByteArray, len: Int): Boolean = try {
        analyzeFrame(buf, len).isSpeech
    } catch (t: Throwable) {
        Log.e(TAG, "Error during VAD speech frame detection", t)
        false
    }

    /**
     * 重置内部累计状态（不重新创建底层 VAD 实例）。
     *
     * 适用于同一会话内按“停顿”分片后继续使用当前检测器的场景。
     */
    fun reset() {
        silentMsAcc = 0
        speechHangoverRemainingMs = 0
        initialDebounceRemainingMs = initialDebounceMs
        hasDetectedSpeech = false
        try {
            vad?.reset()
            while (vad?.empty() == false) vad?.pop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset VAD", t)
        }
    }

    /**
     * 释放 VAD 资源
     */
    fun release() {
        val v = vad ?: return
        vad = null
        recycleToPool(poolKey, v)
    }

    /**
     * 将 PCM 16-bit LE ByteArray 转换为归一化的 FloatArray
     *
     * @param pcm PCM 音频数据
     * @param len 有效数据长度（字节）
     * @return 归一化的音频样本（-1.0 到 1.0）
     */
    private fun pcmToFloatArray(pcm: ByteArray, len: Int): FloatArray {
        if (len <= 0) return FloatArray(0)

        val numSamples = len / 2 // 16-bit = 2 bytes per sample
        val samples = FloatArray(numSamples)

        var i = 0
        var sampleIdx = 0
        while (i + 1 < len && sampleIdx < numSamples) {
            // Little Endian: 低字节在前
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val pcmValue = (hi shl 8) or lo // 0..65535

            // 转为有符号 -32768..32767
            val signed = if (pcmValue < 0x8000) pcmValue else pcmValue - 0x10000

            // 归一化到 -1.0 ~ 1.0
            // 使用 32768.0f 避免 -32768 除法溢出，并限制范围
            var normalized = signed / 32768.0f
            if (normalized > 1.0f) {
                normalized = 1.0f
            } else if (normalized < -1.0f) {
                normalized = -1.0f
            }

            samples[sampleIdx] = normalized

            i += 2
            sampleIdx++
        }

        return samples
    }
}

/**
 * 长按说话时的自动停录抑制器：用于绕过静音判停干预。
 */
object VadAutoStopGuard {
    private val suppressCount = AtomicInteger(0)

    fun acquire(): AutoCloseable {
        suppressCount.incrementAndGet()
        return AutoCloseable { release() }
    }

    fun release() {
        val remaining = suppressCount.decrementAndGet()
        if (remaining < 0) {
            suppressCount.set(0)
        }
    }

    fun isSuppressed(): Boolean = suppressCount.get() > 0
}

fun isVadAutoStopEnabled(context: Context, prefs: Prefs): Boolean {
    return try {
        if (VadAutoStopGuard.isSuppressed()) return false
        prefs.recordingAutoStopMode == Prefs.RecordingAutoStopMode.SILENCE
    } catch (t: Throwable) {
        Log.w("VadDetector", "Failed to read prefs for VAD auto-stop", t)
        false
    }
}
