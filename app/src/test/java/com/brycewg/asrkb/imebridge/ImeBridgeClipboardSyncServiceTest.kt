package com.brycewg.asrkb.imebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            validate(protocolVersion = 3)
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

    @Test
    fun `native IME activation does not require the hook bridge switch`() {
        assertEquals(
            ImeBridgeClipboardSyncContract.RESULT_OK,
            validate(protocolVersion = 2, bridgeEnabled = false)
        )
    }

    @Test
    fun `stale rejection only clears the matching bridge session`() {
        assertTrue(
            isSameBridgeSession("session-1", "third.party.ime", "session-1", "third.party.ime")
        )
        assertFalse(
            isSameBridgeSession("session-2", "third.party.ime", "session-1", "third.party.ime")
        )
        assertFalse(
            isSameBridgeSession("session-1", "other.ime", "session-1", "third.party.ime")
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
