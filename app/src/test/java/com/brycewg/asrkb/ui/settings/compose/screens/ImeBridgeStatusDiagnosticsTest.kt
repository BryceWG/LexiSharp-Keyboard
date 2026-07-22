package com.brycewg.asrkb.ui.settings.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBridgeStatusDiagnosticsTest {
    @Test
    fun shouldQueryStatusOnlyWhenMasterSwitchEnabled() {
        assertFalse(shouldQueryImeBridgeStatus(bridgeEnabled = false))
        assertTrue(shouldQueryImeBridgeStatus(bridgeEnabled = true))
    }

    @Test
    fun classifyLastFailureForMicrophonePermission() {
        assertEquals(
            ImeBridgeFailureKind.MicrophonePermission,
            classifyImeBridgeRecordingFailure("audio record failed")
        )
        assertEquals(
            ImeBridgeFailureKind.MicrophonePermission,
            classifyImeBridgeRecordingFailure("missing RECORD_AUDIO permission")
        )
    }

    @Test
    fun classifyLastFailureForInjectionUnsupported() {
        assertEquals(
            ImeBridgeFailureKind.InjectionUnsupported,
            classifyImeBridgeRecordingFailure("unsupported ime window root")
        )
        assertEquals(
            ImeBridgeFailureKind.InjectionUnsupported,
            classifyImeBridgeRecordingFailure("attach failed: IllegalStateException")
        )
    }

    @Test
    fun classifyLastFailureKeepsUnknownMessagesGeneric() {
        assertEquals(
            ImeBridgeFailureKind.Other,
            classifyImeBridgeRecordingFailure("bridge unavailable")
        )
        assertEquals(
            ImeBridgeFailureKind.Other,
            classifyImeBridgeRecordingFailure(null)
        )
    }
}
