package com.brycewg.asrkb.imebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeBridgeClipboardSyncServiceTest {
    @Test
    fun `activation accepts only the enabled current calling IME`() {
        assertEquals(ImeBridgeClipboardSyncContract.RESULT_OK, validate())
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_CALLER_REJECTED,
            validate(callers = setOf("other.ime"))
        )
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_IME_MISMATCH,
            validate(currentIme = "other.ime")
        )
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_SYNC_DISABLED,
            validate(syncEnabled = false)
        )
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_BRIDGE_UNAVAILABLE,
            validate(bridgeEnabled = false)
        )
    }

    @Test
    fun `activation rejects malformed protocol and identity`() {
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_PROTOCOL_MISMATCH,
            validate(protocolVersion = 2)
        )
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_BAD_REQUEST,
            validate(sessionId = "")
        )
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_BAD_REQUEST,
            validate(targetIme = "")
        )
    }

    private fun validate(
        protocolVersion: Int = 1,
        sessionId: String = "session-1",
        targetIme: String = "third.party.ime",
        callers: Set<String> = setOf(targetIme),
        currentIme: String? = targetIme,
        syncEnabled: Boolean = true,
        bridgeEnabled: Boolean = true
    ): Int = validateBridgeActivation(
        protocolVersion,
        sessionId,
        targetIme,
        callers,
        currentIme,
        syncEnabled,
        bridgeEnabled
    )
}
