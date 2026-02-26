package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用的“推送 PCM” -> 非流式识别 适配器。
 *
 * 用法：传入具体供应商的 File 引擎实例（其实现 PcmBatchRecognizer），本适配器将：
 * - start(): 标记运行中；
 * - appendPcm(): 校验 16k/单声道并累计，同时回调振幅；
 * - stop(): onStopped -> 一次性提交 PCM 给底层引擎识别；
 *
 * 目的：将“推送 PCM”的通用部分抽象出来，避免在每个供应商重复粘贴聚合/回调逻辑。
 */
class GenericPushFileAsrAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val recognizer: PcmBatchRecognizer,
) : StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "PushFileAdapter"
    }

    private val running = AtomicBoolean(false)
    private val bos = ByteArrayOutputStream()

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notify stopped failed", t)
        }
        val data = bos.toByteArray()
        bos.reset()
        if (data.isEmpty()) {
            try {
                listener.onError(context.getString(R.string.error_audio_empty))
            } catch (_: Throwable) {}
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                    context = context,
                    prefs = prefs,
                    pcm = data,
                    sampleRate = 16000,
                )
                recognizer.recognizeFromPcm(denoised)
            } catch (t: Throwable) {
                Log.e(TAG, "recognizeFromPcm failed", t)
                try {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                } catch (_: Throwable) { }
            }
        }
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!isRunning) return
        if (sampleRate != 16000 || channels != 1) {
            Log.w(TAG, "ignore frame: sr=$sampleRate ch=$channels")
            return
        }
        try {
            listener.onAmplitude(calculateNormalizedAmplitude(pcm))
        } catch (t: Throwable) {
            Log.w(TAG, "amp cb failed", t)
        }
        try {
            bos.write(pcm)
        } catch (t: Throwable) {
            Log.e(TAG, "buffer write failed", t)
        }
    }
}
