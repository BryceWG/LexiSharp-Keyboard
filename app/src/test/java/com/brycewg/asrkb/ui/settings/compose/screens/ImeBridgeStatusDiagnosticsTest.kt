package com.brycewg.asrkb.ui.settings.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBridgeStatusDiagnosticsTest {
    @Test
    fun shouldQueryStatusWhenEitherBridgeFeatureIsEnabled() {
        assertFalse(shouldQueryImeBridgeStatus(textInsertionEnabled = false, pcmRecordingEnabled = false))
        assertTrue(shouldQueryImeBridgeStatus(textInsertionEnabled = true, pcmRecordingEnabled = false))
        assertTrue(shouldQueryImeBridgeStatus(textInsertionEnabled = false, pcmRecordingEnabled = true))
        assertTrue(shouldQueryImeBridgeStatus(textInsertionEnabled = true, pcmRecordingEnabled = true))
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
