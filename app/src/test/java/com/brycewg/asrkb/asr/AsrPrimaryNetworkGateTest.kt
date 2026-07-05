// Tests primary ASR network preflight decisions without Android framework dependencies.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrPrimaryNetworkGateTest {
    @Test
    fun offlinePrimaryWithNoNetworkProducesImmediateFailoverError() {
        assertEquals(
            AsrBackupArbitrationEvent.PrimaryError(
                message = AsrPrimaryNetworkGate.NO_NETWORK_PRIMARY_ERROR,
                strategy = AsrPrimaryErrorStrategy.ImmediateFailover
            ),
            AsrPrimaryNetworkGate.preflightEvent(
                primaryVendor = AsrVendor.OpenAI,
                networkAvailable = false
            )
        )
    }

    @Test
    fun onlinePrimaryWithNetworkDoesNotProduceGateEvent() {
        assertNull(
            AsrPrimaryNetworkGate.preflightEvent(
                primaryVendor = AsrVendor.OpenAI,
                networkAvailable = true
            )
        )
    }

    @Test
    fun localPrimaryWithNoNetworkDoesNotProduceGateEvent() {
        assertNull(
            AsrPrimaryNetworkGate.preflightEvent(
                primaryVendor = AsrVendor.SenseVoice,
                networkAvailable = false
            )
        )
    }
}
