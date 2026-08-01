package com.brycewg.asrkb.imebridge

import com.brycewg.asrkb.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeBridgeWarningTest {
    @Test
    fun mapsOnlyProtocolAndConnectionFailuresToWarnings() {
        assertEquals(
            R.string.toast_ime_bridge_protocol_mismatch,
            imeBridgeWarningMessageRes(
                ImeBridgeContract.RESULT_PROTOCOL_MISMATCH,
                warnOnFailure = true
            )
        )
        assertEquals(
            R.string.toast_ime_bridge_connection_failed,
            imeBridgeWarningMessageRes(ImeBridgeContract.RESULT_TIMEOUT, warnOnFailure = true)
        )
        assertNull(imeBridgeWarningMessageRes(ImeBridgeContract.RESULT_SENSITIVE_FIELD, true))
        assertNull(imeBridgeWarningMessageRes(ImeBridgeContract.RESULT_NO_RECEIVER, false))
    }

    @Test
    fun differentWarningsHaveIndependentCooldowns() {
        assertEquals(true, ImeBridgeWarningToast.tryAcquire(R.string.toast_ime_bridge_protocol_mismatch, 0L))
        assertEquals(false, ImeBridgeWarningToast.tryAcquire(R.string.toast_ime_bridge_protocol_mismatch, 1L))
        assertEquals(true, ImeBridgeWarningToast.tryAcquire(R.string.toast_ime_bridge_connection_failed, 1L))
    }

    @Test
    fun wasShownWithinReflectsActualToastAcquisition() {
        // 使用独立 key，避免与其他用例共享冷却状态
        val messageRes = 424242
        assertEquals(false, ImeBridgeWarningToast.wasShownWithin(messageRes, 0L, 5_000L))
        assertEquals(true, ImeBridgeWarningToast.tryAcquire(messageRes, 0L))
        assertEquals(true, ImeBridgeWarningToast.wasShownWithin(messageRes, 4_999L, 5_000L))
        assertEquals(false, ImeBridgeWarningToast.wasShownWithin(messageRes, 5_000L, 5_000L))
    }
}
