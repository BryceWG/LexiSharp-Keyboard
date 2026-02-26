package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * SenseVoice（本地离线）推 PCM 伪流式引擎：
 * - AIDL writePcm 推流；
 * - 定时分片做离线预览（onPartial）；
 * - finishPcm/stop 时对整段音频做一次离线识别（onFinal）。
 */
class SenseVoicePushPcmPseudoStreamAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
) : PushPcmPseudoStreamAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    companion object {
        private const val TAG = "SvPushPcmPseudo"
    }

    private val delegate = SenseVoicePseudoStreamDelegate(
        context = context,
        scope = scope,
        prefs = prefs,
        listener = listener,
        sampleRate = sampleRate,
        onRequestDuration = onRequestDuration,
        tag = TAG,
    )

    private val sessionIdGenerator = AtomicLong(0L)

    @Volatile
    private var activeSessionId: Long = 0L

    @Volatile
    private var finishingSessionId: Long = 0L

    override fun start() {
        val wasRunning = isRunning
        super.start()
        if (wasRunning || !isRunning) return
        val sessionId = sessionIdGenerator.incrementAndGet()
        activeSessionId = sessionId
        finishingSessionId = sessionId
        delegate.onSessionStart(sessionId)
    }

    override fun stop() {
        finishingSessionId = activeSessionId
        super.stop()
    }

    override fun ensureReady(): Boolean {
        return delegate.ensureReady()
    }

    override fun onSegmentBoundary(pcmSegment: ByteArray) {
        delegate.onSegmentBoundary(activeSessionId, pcmSegment)
    }

    override suspend fun onSessionFinished(fullPcm: ByteArray) {
        delegate.onSessionFinished(finishingSessionId, fullPcm)
    }
}
