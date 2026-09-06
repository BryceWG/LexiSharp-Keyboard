/**
 * 将 bridge PCM 状态机接入现有 ExternalSpeechSession Push PCM 编排。
 *
 * 归属模块：imebridge
 */
package com.brycewg.asrkb.imebridge

import android.content.Context
import com.brycewg.asrkb.api.ExternalSpeechCallbacks
import com.brycewg.asrkb.api.ExternalSpeechSession
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class ImeBridgePcmExternalSessionFactory(
    private val pushPcmSessionFactory: BridgePushPcmSessionFactory,
    private val backfill: ImeBridgePcmBackfill
) : BridgePcmSessionFactory {
    constructor(
        context: Context,
        prefs: Prefs,
        bridgeClient: ImeBridgeClient = ImeBridgeClient(context)
    ) : this(
        pushPcmSessionFactory = ExternalSpeechBridgePushPcmSessionFactory(context, prefs),
        backfill = ImeBridgeClientPcmBackfill(bridgeClient)
    )

    override fun create(config: BridgePcmSessionConfig, onEnded: (String) -> Unit): BridgePcmSession? {
        val lifecycle = BridgePcmSessionLifecycle()
        val callbacks = ImeBridgePcmExternalCallbacks(
            bridgeSessionId = config.sessionId,
            supportsComposingPreview = config.supportsComposingPreview,
            backfill = backfill,
            lifecycle = lifecycle,
            onEnded = { onEnded(config.sessionId) }
        )
        val pushPcmSession = pushPcmSessionFactory.create(callbacks) ?: return null
        return BackfilledBridgePcmSession(pushPcmSession, backfill, config.sessionId, lifecycle)
    }
}

internal fun interface BridgePushPcmSessionFactory {
    fun create(callbacks: ExternalSpeechCallbacks): BridgePcmSession?
}

internal interface ImeBridgePcmBackfill {
    fun beginSession(sessionId: String): ImeBridgeResult
    fun setComposingText(sessionId: String, text: String): ImeBridgeResult
    fun insertText(sessionId: String, text: String): ImeBridgeResult
    fun finishComposingText(sessionId: String): ImeBridgeResult
    fun cancelSession(sessionId: String): ImeBridgeResult
}

internal class ImeBridgeClientPcmBackfill(
    private val bridgeClient: ImeBridgeClient
) : ImeBridgePcmBackfill {
    override fun beginSession(sessionId: String): ImeBridgeResult = bridgeClient.beginSession(sessionId)

    override fun setComposingText(sessionId: String, text: String): ImeBridgeResult = bridgeClient.setComposingText(text, sessionId = sessionId)

    override fun insertText(sessionId: String, text: String): ImeBridgeResult = bridgeClient.insertText(text, sessionId = sessionId)

    override fun finishComposingText(sessionId: String): ImeBridgeResult = bridgeClient.finishComposingText(sessionId = sessionId)

    override fun cancelSession(sessionId: String): ImeBridgeResult = bridgeClient.cancelSession(sessionId)
}

private class ExternalSpeechBridgePushPcmSessionFactory(
    private val context: Context,
    private val prefs: Prefs
) : BridgePushPcmSessionFactory {
    private val nextExternalSessionId = AtomicInteger(1)

    override fun create(callbacks: ExternalSpeechCallbacks): BridgePcmSession? {
        val externalSessionId = nextExternalSessionId.getAndIncrement()
        val externalSession = ExternalSpeechSession(
            externalSessionId,
            context,
            prefs,
            callbacks
        )
        if (!externalSession.preparePushPcm()) return null
        return ExternalSpeechBridgePcmSession(externalSession)
    }
}

private class ExternalSpeechBridgePcmSession(
    private val externalSession: ExternalSpeechSession
) : BridgePcmSession {
    override fun start() {
        externalSession.start()
    }

    override fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
        externalSession.onPcmFrame(pcm, sampleRate, channels)
    }

    override fun finish() {
        externalSession.stop()
    }

    override fun cancel() {
        externalSession.cancel()
    }
}

private class BackfilledBridgePcmSession(
    private val pushPcmSession: BridgePcmSession,
    private val backfill: ImeBridgePcmBackfill,
    private val bridgeSessionId: String,
    private val lifecycle: BridgePcmSessionLifecycle
) : BridgePcmSession {
    override fun start() {
        val beginResult = backfill.beginSession(bridgeSessionId)
        if (!beginResult.isSuccess) {
            lifecycle.endBeforeRecording()
            runCatching { pushPcmSession.cancel() }
            throw ImeBridgePcmBeginFailedException(beginResult)
        }
        lifecycle.markRecording()
        pushPcmSession.start()
    }

    override fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!lifecycle.isRecording()) return
        pushPcmSession.writeFrame(pcm, sampleRate, channels)
    }

    override fun finish() {
        if (!lifecycle.markFinishing()) return
        pushPcmSession.finish()
    }

    override fun cancel() {
        if (!lifecycle.cancelIfActive()) return
        pushPcmSession.cancel()
        backfill.cancelSession(bridgeSessionId)
    }
}

private class BridgePcmSessionLifecycle {
    private val state = AtomicReference(State.Created)

    fun isRecording(): Boolean = state.get() == State.Recording

    fun acceptsCallback(): Boolean {
        val current = state.get()
        return current == State.Recording || current == State.Finishing
    }

    fun markRecording(): Boolean = state.compareAndSet(State.Created, State.Recording)

    fun endBeforeRecording(): Boolean = state.compareAndSet(State.Created, State.Ended)

    fun markFinishing(): Boolean = state.compareAndSet(State.Recording, State.Finishing)

    fun cancelIfActive(): Boolean {
        while (true) {
            val current = state.get()
            if (current != State.Recording && current != State.Finishing) return false
            if (state.compareAndSet(current, State.Ended)) return true
        }
    }

    fun endFromCallback(): Boolean {
        while (true) {
            val current = state.get()
            if (current == State.Created || current == State.Ended) return false
            if (state.compareAndSet(current, State.Ended)) return true
        }
    }

    private enum class State {
        Created,
        Recording,
        Finishing,
        Ended
    }
}

private class ImeBridgePcmBeginFailedException(result: ImeBridgeResult) :
    IllegalStateException(
        "bridge begin failed: code=${result.code} message=${result.message.take(120)}"
    )

private class ImeBridgePcmExternalCallbacks(
    private val bridgeSessionId: String,
    private val supportsComposingPreview: Boolean,
    private val backfill: ImeBridgePcmBackfill,
    private val lifecycle: BridgePcmSessionLifecycle,
    private val onEnded: () -> Unit
) : ExternalSpeechCallbacks {
    override fun onState(sessionId: Int, state: Int, message: String) = Unit

    override fun onPartial(sessionId: Int, text: String) {
        if (lifecycle.acceptsCallback() && supportsComposingPreview && text.isNotEmpty()) {
            backfill.setComposingText(bridgeSessionId, text)
        }
    }

    override fun onFinal(sessionId: Int, text: String) {
        if (!lifecycle.endFromCallback()) return
        if (text.isNotEmpty()) {
            backfill.insertText(bridgeSessionId, text)
        } else {
            backfill.cancelSession(bridgeSessionId)
        }
        onEnded()
    }

    override fun onError(sessionId: Int, code: Int, message: String) {
        if (!lifecycle.endFromCallback()) return
        backfill.cancelSession(bridgeSessionId)
        onEnded()
    }

    override fun onAmplitude(sessionId: Int, amplitude: Float) = Unit

    override fun onSessionDone(sessionId: Int) {
        if (!lifecycle.endFromCallback()) return
        backfill.cancelSession(bridgeSessionId)
        onEnded()
    }
}
