package com.brycewg.asrkb.clipboard

import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemClipboardPortFactoryTest {
    @Test
    fun preferDirectWhenCurrentImeIsSelf() {
        assertEquals(
            SystemClipboardActor.DIRECT,
            resolveClipboardActor(
                selfPackage = "com.brycewg.asrkb",
                currentImePackage = "com.brycewg.asrkb",
                bridgeEnabled = true,
                bridgeStatus = successClipboardStatus(supportsClipboard = true)
            )
        )
    }

    @Test
    fun preferBridgeWhenThirdPartyImeSupportsClipboard() {
        assertEquals(
            SystemClipboardActor.BRIDGE,
            resolveClipboardActor(
                selfPackage = "com.brycewg.asrkb",
                currentImePackage = "com.example.third.ime",
                bridgeEnabled = true,
                bridgeStatus = successClipboardStatus(supportsClipboard = true)
            )
        )
    }

    @Test
    fun fallBackToDirectWhenBridgeDisabled() {
        assertEquals(
            SystemClipboardActor.DIRECT,
            resolveClipboardActor(
                selfPackage = "com.brycewg.asrkb",
                currentImePackage = "com.example.third.ime",
                bridgeEnabled = false,
                bridgeStatus = successClipboardStatus(supportsClipboard = true)
            )
        )
    }

    @Test
    fun fallBackToDirectWhenModuleTooOld() {
        assertEquals(
            SystemClipboardActor.DIRECT,
            resolveClipboardActor(
                selfPackage = "com.brycewg.asrkb",
                currentImePackage = "com.example.third.ime",
                bridgeEnabled = true,
                bridgeStatus = successClipboardStatus(supportsClipboard = false)
            )
        )
    }

    @Test
    fun fallBackToDirectWhenBridgeAbsent() {
        assertEquals(
            SystemClipboardActor.DIRECT,
            resolveClipboardActor(
                selfPackage = "com.brycewg.asrkb",
                currentImePackage = "com.example.third.ime",
                bridgeEnabled = true,
                bridgeStatus = ImeBridgeResult(
                    code = ImeBridgeContract.RESULT_NO_RECEIVER,
                    message = "no receiver",
                    targetPackage = "com.example.third.ime",
                    hasInputConnection = false,
                    isSensitiveField = false,
                    isImeWindowVisible = false,
                    supportsClipboard = false
                )
            )
        )
    }

    @Test
    fun bridgeStatusReportsClipboardCapability() {
        val status = successClipboardStatus(supportsClipboard = true)
        assertTrue(status.isSuccess)
        assertTrue(status.supportsClipboard)
    }
}

private fun successClipboardStatus(supportsClipboard: Boolean): ImeBridgeResult =
    ImeBridgeResult(
        code = ImeBridgeContract.RESULT_OK,
        message = "ready",
        targetPackage = "com.example.third.ime",
        hasInputConnection = true,
        isSensitiveField = false,
        isImeWindowVisible = true,
        supportsClipboard = supportsClipboard
    )
