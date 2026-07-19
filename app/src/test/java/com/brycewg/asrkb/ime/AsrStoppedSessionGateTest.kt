package com.brycewg.asrkb.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrStoppedSessionGateTest {
    @Test
    fun sameSession_isDeliveredOnlyOnce() {
        val gate = AsrStoppedSessionGate()

        assertTrue(gate.tryDeliver(1L))
        assertFalse(gate.tryDeliver(1L))
    }

    @Test
    fun nextSession_canDeliverStoppedAgain() {
        val gate = AsrStoppedSessionGate()

        assertTrue(gate.tryDeliver(1L))
        assertTrue(gate.tryDeliver(2L))
        assertFalse(gate.tryDeliver(2L))
    }

    @Test
    fun inactiveSession_isNeverDelivered() {
        assertFalse(AsrStoppedSessionGate().tryDeliver(0L))
    }
}
