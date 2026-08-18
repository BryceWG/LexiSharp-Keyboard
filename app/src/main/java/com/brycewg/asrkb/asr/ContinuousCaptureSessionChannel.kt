package com.brycewg.asrkb.asr

import java.util.WeakHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.selects.select

/** 解除已结束会话对持续录音分发协程的等待。 */
private val continuousCaptureSessionEnds =
    WeakHashMap<Channel<ByteArray>, CompletableDeferred<Unit>>()

internal suspend fun Channel<ByteArray>.sendWhileSessionActive(chunk: ByteArray) {
    val ended = sessionEndSignal()
    try {
        select<Unit> {
            onSend(chunk) { }
            ended.onAwait { }
        }
    } catch (_: ClosedSendChannelException) {
        // Channel already closed; drop this frame.
    }
}

internal fun Channel<ByteArray>.closeSessionDispatch(cause: Throwable? = null) {
    sessionEndSignal().complete(Unit)
    close(cause)
}

private fun Channel<ByteArray>.sessionEndSignal(): CompletableDeferred<Unit> =
    synchronized(continuousCaptureSessionEnds) {
        continuousCaptureSessionEnds.getOrPut(this) { CompletableDeferred() }
    }
