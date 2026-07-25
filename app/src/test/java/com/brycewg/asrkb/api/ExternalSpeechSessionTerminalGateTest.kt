package com.brycewg.asrkb.api

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSpeechSessionTerminalGateTest {
    @Test
    fun concurrentTerminalEventsHaveExactlyOneWinner() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            repeat(100) {
                val gate = ExternalSpeechSessionTerminalGate()
                val barrier = CyclicBarrier(3)
                val results = List(3) {
                    executor.submit<Boolean> {
                        barrier.await()
                        gate.tryFinish()
                    }
                }

                assertEquals(1, results.count { it.get(1, TimeUnit.SECONDS) })
                assertTrue(gate.isFinished)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun resetAllowsTheNextSessionToFinish() {
        val gate = ExternalSpeechSessionTerminalGate()

        assertTrue(gate.tryFinish())
        assertFalse(gate.tryFinish())
        gate.reset()
        assertTrue(gate.tryFinish())
    }

    @Test
    fun forcedFinishBlocksLaterTerminalEvents() {
        val gate = ExternalSpeechSessionTerminalGate()

        gate.markFinished()

        assertFalse(gate.tryFinish())
    }
}
