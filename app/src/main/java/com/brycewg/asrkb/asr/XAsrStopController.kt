// X-ASR stop latch: pin the session being stopped and ignore re-entrant stop().
package com.brycewg.asrkb.asr

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

internal data class XAsrArmedStop(
    val session: XAsrStreamSession?,
    val captureJob: Job?,
    val drained: CompletableDeferred<Boolean>,
    val waitForCapture: Boolean
)

internal class XAsrStopController {
    private val stopRequested = AtomicBoolean(false)

    @Volatile var session: XAsrStreamSession? = null
        private set

    @Volatile var captureJob: Job? = null

    @Volatile var captureDrained: CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { it.complete(true) }

    fun onNewSession(next: XAsrStreamSession): XAsrStreamSession? {
        val previous = session
        session = next
        stopRequested.set(false)
        return previous
    }

    fun tryBeginStop(externalPcmMode: Boolean): XAsrArmedStop? {
        if (!stopRequested.compareAndSet(false, true)) return null
        val stoppingSession = session
        val job = captureJob
        captureJob = null
        val drained = captureDrained
        return XAsrArmedStop(
            session = stoppingSession,
            captureJob = job,
            drained = drained,
            waitForCapture = !externalPcmMode && job != null
        )
    }
}
