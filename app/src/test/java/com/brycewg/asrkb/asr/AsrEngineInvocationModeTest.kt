// Tests the ASR invocation vocabulary used by the future engine factory.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrEngineInvocationModeTest {
    @Test
    fun modesCoverCurrentConstructionScenarios() {
        val directPaths = listOf(
            CurrentAsrConstructionPath.AppDirectMicrophone,
            CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone,
            CurrentAsrConstructionPath.ExternalDirectMicrophone
        )

        directPaths.forEach { path ->
            assertSame(
                "Direct microphone mode should cover $path",
                AsrEngineInvocationMode.DirectMicrophoneCapture,
                modeForCurrentPath(path)
            )
        }
        assertSame(
            AsrEngineInvocationMode.PushPcm,
            modeForCurrentPath(CurrentAsrConstructionPath.ExternalPushPcm)
        )
        assertSame(
            AsrEngineInvocationMode.RecordingTest,
            modeForCurrentPath(CurrentAsrConstructionPath.RecordingTestPushPcm)
        )
        assertEquals(
            listOf(
                AsrEngineInvocationMode.ParallelPrimary,
                AsrEngineInvocationMode.ParallelBackup
            ),
            modesForParallelPath(CurrentAsrConstructionPath.ParallelDirectLeg)
        )
        assertEquals(
            listOf(
                AsrEngineInvocationMode.ParallelPrimary,
                AsrEngineInvocationMode.ParallelBackup
            ),
            modesForParallelPath(CurrentAsrConstructionPath.ParallelPushPcmLeg)
        )
    }

    @Test
    fun audioInputDistinguishesOwnedMicrophoneFromPushedPcm() {
        assertTrue(AsrEngineInvocationMode.DirectMicrophoneCapture.ownsMicrophoneCapture)
        assertFalse(AsrEngineInvocationMode.DirectMicrophoneCapture.consumesPushedPcm)

        val pushedModes = AsrEngineInvocationMode.entries
            .filterNot { it == AsrEngineInvocationMode.DirectMicrophoneCapture }

        pushedModes.forEach { mode ->
            assertFalse("$mode should not own microphone capture", mode.ownsMicrophoneCapture)
            assertTrue("$mode should consume pushed PCM", mode.consumesPushedPcm)
        }
    }

    @Test
    fun parallelModesCapturePrimaryBackupDifferences() {
        val primary = AsrEngineInvocationMode.ParallelPrimary
        val backup = AsrEngineInvocationMode.ParallelBackup

        assertEquals(AsrEngineParallelRole.Primary, primary.parallelRole)
        assertEquals(AsrEngineParallelRole.Backup, backup.parallelRole)
        assertTrue(primary.reportsRequestDuration)
        assertFalse(backup.reportsRequestDuration)
        assertTrue(primary.requiresConfigurationValidation)
        assertTrue(backup.requiresConfigurationValidation)
        assertEquals(AsrConfigurationValidation.FactoryRequired, primary.configurationValidation)
        assertEquals(AsrConfigurationValidation.FactoryRequired, backup.configurationValidation)
    }

    @Test
    fun allModesKeepProviderSpecificValidationInsideFactorySeam() {
        val modes = AsrEngineInvocationMode.entries

        modes.forEach { mode ->
            assertEquals(
                "$mode should leave provider-specific config checks to the factory seam",
                AsrConfigurationValidation.FactoryRequired,
                mode.configurationValidation
            )
            assertTrue("$mode should require generic configuration validation", mode.requiresConfigurationValidation)
        }
        assertNull(AsrEngineInvocationMode.DirectMicrophoneCapture.parallelRole)
        assertNull(AsrEngineInvocationMode.PushPcm.parallelRole)
        assertNull(AsrEngineInvocationMode.RecordingTest.parallelRole)
        assertTrue(AsrEngineInvocationMode.DirectMicrophoneCapture.reportsRequestDuration)
        assertTrue(AsrEngineInvocationMode.PushPcm.reportsRequestDuration)
        assertFalse(AsrEngineInvocationMode.RecordingTest.reportsRequestDuration)
    }

    private fun modeForCurrentPath(path: CurrentAsrConstructionPath): AsrEngineInvocationMode =
        when (path) {
            CurrentAsrConstructionPath.AppDirectMicrophone,
            CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone,
            CurrentAsrConstructionPath.ExternalDirectMicrophone ->
                AsrEngineInvocationMode.DirectMicrophoneCapture
            CurrentAsrConstructionPath.ExternalPushPcm ->
                AsrEngineInvocationMode.PushPcm
            CurrentAsrConstructionPath.RecordingTestPushPcm ->
                AsrEngineInvocationMode.RecordingTest
            CurrentAsrConstructionPath.ParallelDirectLeg,
            CurrentAsrConstructionPath.ParallelPushPcmLeg ->
                error("Parallel paths build both primary and backup modes")
        }

    private fun modesForParallelPath(path: CurrentAsrConstructionPath): List<AsrEngineInvocationMode> =
        when (path) {
            CurrentAsrConstructionPath.ParallelDirectLeg,
            CurrentAsrConstructionPath.ParallelPushPcmLeg -> listOf(
                AsrEngineInvocationMode.ParallelPrimary,
                AsrEngineInvocationMode.ParallelBackup
            )
            else -> error("$path is not a parallel construction path")
        }
}
