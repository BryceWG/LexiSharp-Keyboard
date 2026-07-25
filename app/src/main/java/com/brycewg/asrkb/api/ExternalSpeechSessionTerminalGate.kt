// Arbitrates terminal delivery for one external speech session.
package com.brycewg.asrkb.api

import java.util.concurrent.atomic.AtomicBoolean

internal class ExternalSpeechSessionTerminalGate {
    private val finished = AtomicBoolean(false)

    val isFinished: Boolean
        get() = finished.get()

    fun reset() {
        finished.set(false)
    }

    fun tryFinish(): Boolean = finished.compareAndSet(false, true)

    fun markFinished() {
        finished.set(true)
    }
}
