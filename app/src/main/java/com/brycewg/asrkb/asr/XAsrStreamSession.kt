// X-ASR single-writer streaming session: one FIFO owns the sherpa stream.
package com.brycewg.asrkb.asr

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface XAsrStreamSink {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun isReady(): Boolean
    fun decode()
    fun getResultText(): String?
    fun inputFinished()
    fun release()
}

/**
 * 单写者 X-ASR 推理会话。所有 PCM / Finish / Cancel 进入同一 FIFO，
 * 由一个协程独占 sherpa stream。Finish 是屏障：之前的 PCM 必须送入模型，之后的丢弃。
 */
internal class XAsrStreamSession(
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16000,
    private val frameMs: Int = 200,
    private val useItn: Boolean = false,
    private val nowMs: () -> Long,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val logDiag: (event: String, data: Map<String, Any?>) -> Unit = { _, _ -> },
    private val processorDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val events = Channel<XAsrStreamEvent>(Channel.UNLIMITED)
    private val sinkReady = CompletableDeferred<XAsrStreamSink>()
    private val finishRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val closedForEnqueue = AtomicBoolean(false)
    private val acceptedPcmBytes = AtomicInteger(0)
    private val droppedAfterFinish = AtomicInteger(0)
    private val droppedOverflow = AtomicInteger(0)
    private val queuedPcmBytes = AtomicInteger(0)

    @Volatile private var processorJob: Job? = null
    @Volatile private var lastEmittedText: String? = null
    private var lastEmitUptimeMs: Long = 0L

    fun start() {
        if (processorJob != null) return
        logDiag("xasr_session_start", emptyMap())
        processorJob = scope.launch(processorDispatcher) {
            runLoop()
        }
    }

    fun attachSink(sink: XAsrStreamSink) {
        if (!sinkReady.complete(sink)) {
            try {
                sink.release()
            } catch (t: Throwable) {
                Log.e(TAG, "release unused sink failed", t)
            }
        }
    }

    fun enqueuePcm(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (closedForEnqueue.get()) {
            droppedAfterFinish.addAndGet(bytes.size)
            return false
        }
        if (!sinkReady.isCompleted) {
            val pending = queuedPcmBytes.get() - acceptedPcmBytes.get()
            if (pending + bytes.size > MAX_PREBUFFER_BYTES) {
                droppedOverflow.addAndGet(bytes.size)
                return false
            }
        }
        val result = events.trySend(XAsrStreamEvent.Pcm(bytes.copyOf()))
        if (result.isSuccess) {
            queuedPcmBytes.addAndGet(bytes.size)
            return true
        }
        droppedAfterFinish.addAndGet(bytes.size)
        return false
    }

    fun finish() {
        if (cancelRequested.get()) return
        if (!finishRequested.compareAndSet(false, true)) return
        events.trySend(XAsrStreamEvent.Finish)
        closeEvents()
        logDiag(
            "xasr_finish",
            mapOf(
                "queuedBytes" to queuedPcmBytes.get(),
                "droppedBytes" to droppedAfterFinish.get(),
                "overflowBytes" to droppedOverflow.get()
            )
        )
    }

    fun cancel() {
        cancelRequested.set(true)
        events.trySend(XAsrStreamEvent.Cancel)
        closeEvents()
        val sinkAttached = sinkReady.isCompleted
        if (!sinkAttached) {
            sinkReady.cancel()
        }
        // 没有 sink 时无法 finalize；已在 finalize 的 Finish 不要打断。
        if (!finishRequested.get() || !sinkAttached) {
            processorJob?.cancel()
        }
    }

    suspend fun awaitCompletion() {
        processorJob?.join()
    }

    private fun closeEvents() {
        closedForEnqueue.set(true)
        events.close()
    }

    private suspend fun runLoop() {
        val sink = try {
            sinkReady.await()
        } catch (_: Throwable) {
            return
        }
        var finalized = false
        try {
            for (event in events) {
                when (event) {
                    is XAsrStreamEvent.Pcm -> acceptPcm(sink, event.bytes)
                    XAsrStreamEvent.Finish -> {
                        finalized = true
                        emitFinal(sink)
                        return
                    }
                    XAsrStreamEvent.Cancel -> return
                }
            }
            if (!cancelRequested.get() && finishRequested.get()) {
                finalized = true
                emitFinal(sink)
            }
        } finally {
            if (!finalized) {
                withContext(NonCancellable) {
                    releaseSink(sink)
                }
            }
        }
    }

    private fun acceptPcm(sink: XAsrStreamSink, bytes: ByteArray) {
        val floats = xAsrPcmToFloatArray(bytes)
        if (floats.isEmpty()) return
        sink.acceptWaveform(floats, sampleRate)
        acceptedPcmBytes.addAndGet(bytes.size)
        // Finish 之后 FIFO 里剩下的真实 PCM 必须解完，不能再用实时路径的 8 次上限。
        val maxLoops = if (finishRequested.get()) FINALIZE_DECODE_MAX_LOOPS else LIVE_DECODE_MAX_LOOPS
        var loops = 0
        while (sink.isReady() && loops < maxLoops) {
            sink.decode()
            loops++
        }
        if (finishRequested.get() || cancelRequested.get()) return
        val partial = sink.getResultText()
        if (partial.isNullOrBlank()) return
        val now = nowMs()
        val normalized = formatXAsrText(partial, useItn)
        val needEmit = (now - lastEmitUptimeMs) >= frameMs && normalized != lastEmittedText
        if (!needEmit) return
        try {
            onPartial(normalized)
        } catch (t: Throwable) {
            Log.e(TAG, "notify partial failed", t)
        }
        lastEmitUptimeMs = now
        lastEmittedText = normalized
    }

    private suspend fun emitFinal(sink: XAsrStreamSink) {
        val result = withContext(NonCancellable) {
            finalizeAndRelease(sink)
        }
        logDiag(
            "xasr_final",
            mapOf(
                "empty" to result.text.isEmpty(),
                "acceptedBytes" to acceptedPcmBytes.get(),
                "droppedBytes" to droppedAfterFinish.get(),
                "overflowBytes" to droppedOverflow.get(),
                "queueDrained" to (acceptedPcmBytes.get() == queuedPcmBytes.get()),
                "decodeLoops" to result.decodeLoops,
                "decodeComplete" to result.decodeComplete,
                "padChunks" to result.padChunks
            )
        )
        try {
            onFinal(result.text)
        } catch (t: Throwable) {
            Log.e(TAG, "notify final failed", t)
        }
    }

    private fun finalizeAndRelease(sink: XAsrStreamSink): XAsrFinalizeResult {
        val chunkSamples = ((sampleRate * MODEL_CHUNK_MS) / 1000).coerceAtLeast(1)
        var loops = 0
        var decodeComplete = true
        // 先把真实 PCM 解完，再按模型 chunk 补 right-context；完成条件是 isReady==false，不是墙钟。
        val caughtUp = decodeWhileReady(sink, loops)
        loops = caughtUp.loops
        decodeComplete = decodeComplete && caughtUp.complete
        repeat(TAIL_PAD_CHUNKS) {
            sink.acceptWaveform(FloatArray(chunkSamples), sampleRate)
            val padded = decodeWhileReady(sink, loops)
            loops = padded.loops
            decodeComplete = decodeComplete && padded.complete
        }
        sink.inputFinished()
        val flushed = decodeWhileReady(sink, loops)
        loops = flushed.loops
        decodeComplete = decodeComplete && flushed.complete
        val text = try {
            formatXAsrText(sink.getResultText().orEmpty(), useItn)
        } catch (t: Throwable) {
            Log.e(TAG, "getResultText failed", t)
            ""
        }
        releaseSink(sink)
        return XAsrFinalizeResult(
            text = text,
            decodeLoops = loops,
            decodeComplete = decodeComplete,
            padChunks = TAIL_PAD_CHUNKS
        )
    }

    private fun decodeWhileReady(sink: XAsrStreamSink, loopsUsed: Int): XAsrDecodeDrain {
        var loops = loopsUsed
        while (loops < FINALIZE_DECODE_MAX_LOOPS) {
            if (!sink.isReady()) return XAsrDecodeDrain(loops = loops, complete = true)
            sink.decode()
            loops++
        }
        return XAsrDecodeDrain(loops = loops, complete = false)
    }

    private fun releaseSink(sink: XAsrStreamSink) {
        try {
            sink.release()
        } catch (t: Throwable) {
            Log.e(TAG, "releaseStream failed", t)
        }
    }

    private companion object {
        private const val TAG = "XAsrStreamSession"
        private const val LIVE_DECODE_MAX_LOOPS = 8
        private const val MAX_PREBUFFER_BYTES = 384 * 1024
        private const val MODEL_CHUNK_MS = 480
        // 补齐当前 chunk + 一个 right-context chunk；数量由模型结构决定，与设备快慢无关。
        private const val TAIL_PAD_CHUNKS = 2
        private const val FINALIZE_DECODE_MAX_LOOPS = 512
    }
}

private data class XAsrDecodeDrain(
    val loops: Int,
    val complete: Boolean
)

private data class XAsrFinalizeResult(
    val text: String,
    val decodeLoops: Int,
    val decodeComplete: Boolean,
    val padChunks: Int
)

private sealed class XAsrStreamEvent {
    class Pcm(val bytes: ByteArray) : XAsrStreamEvent()
    data object Finish : XAsrStreamEvent()
    data object Cancel : XAsrStreamEvent()
}

internal fun xAsrPcmToFloatArray(src: ByteArray, len: Int = src.size): FloatArray {
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
