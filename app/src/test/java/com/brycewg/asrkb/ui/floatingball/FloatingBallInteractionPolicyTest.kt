// Tests floating-ball recording and long-hold movement mode decisions.
package com.brycewg.asrkb.ui.floatingball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBallInteractionPolicyTest {
    @Test
    fun tapStopsAnExistingRecordingInBothRecordingModes() {
        assertEquals(
            FloatingBallRecordingTapAction.StopRecording,
            resolveFloatingBallRecordingTapAction(
                isRecording = true,
                holdToRecordEnabled = false
            )
        )
        assertEquals(
            FloatingBallRecordingTapAction.StopRecording,
            resolveFloatingBallRecordingTapAction(
                isRecording = true,
                holdToRecordEnabled = true
            )
        )
    }

    @Test
    fun idleTapStartsOnlyInTapRecordingMode() {
        assertEquals(
            FloatingBallRecordingTapAction.StartRecording,
            resolveFloatingBallRecordingTapAction(
                isRecording = false,
                holdToRecordEnabled = false
            )
        )
        assertEquals(
            FloatingBallRecordingTapAction.None,
            resolveFloatingBallRecordingTapAction(
                isRecording = false,
                holdToRecordEnabled = true
            )
        )
    }

    @Test
    fun holdRecordingDoesNotScheduleLongHoldMove() {
        assertFalse(
            shouldScheduleFloatingLongHoldMove(
                holdToRecordEnabled = true,
                directMoveEnabled = true
            )
        )
    }

    @Test
    fun holdRecordingStartsImmediatelyOnActionDown() {
        assertTrue(
            shouldStartFloatingHoldRecordingOnDown(
                holdToRecordEnabled = true,
                isMoveMode = false
            )
        )
        assertFalse(
            shouldStartFloatingHoldRecordingOnDown(
                holdToRecordEnabled = false,
                isMoveMode = false
            )
        )
        assertFalse(
            shouldStartFloatingHoldRecordingOnDown(
                holdToRecordEnabled = true,
                isMoveMode = true
            )
        )
    }

    @Test
    fun holdPressStopsExistingRecordingAndCancelsProcessing() {
        assertEquals(
            FloatingBallHoldPressAction.StopRecording,
            resolveFloatingBallHoldPressAction(
                isRecording = true,
                isProcessing = false,
                isEdgeHandleVisible = false
            )
        )
        assertEquals(
            FloatingBallHoldPressAction.CancelProcessing,
            resolveFloatingBallHoldPressAction(
                isRecording = false,
                isProcessing = true,
                isEdgeHandleVisible = false
            )
        )
    }

    @Test
    fun tapModePreservesExistingLongHoldMoveScheduling() {
        assertFalse(
            shouldScheduleFloatingLongHoldMove(
                holdToRecordEnabled = false,
                directMoveEnabled = true
            )
        )
        assertTrue(
            shouldScheduleFloatingLongHoldMove(
                holdToRecordEnabled = false,
                directMoveEnabled = false
            )
        )
    }

    @Test
    fun holdMovementUsesThresholdThenChoosesMenuOrDirectMove() {
        assertEquals(
            FloatingBallHoldMoveAction.None,
            resolveFloatingBallHoldMoveAction(
                movementExceeded = false,
                menuThresholdExceeded = false,
                movingTowardScreenCenter = true,
                directMoveEnabled = true
            )
        )
        assertEquals(
            FloatingBallHoldMoveAction.OpenMenu,
            resolveFloatingBallHoldMoveAction(
                movementExceeded = true,
                menuThresholdExceeded = true,
                movingTowardScreenCenter = true,
                directMoveEnabled = true
            )
        )
        assertEquals(
            FloatingBallHoldMoveAction.MoveBall,
            resolveFloatingBallHoldMoveAction(
                movementExceeded = true,
                menuThresholdExceeded = false,
                movingTowardScreenCenter = false,
                directMoveEnabled = true
            )
        )
        assertEquals(
            FloatingBallHoldMoveAction.None,
            resolveFloatingBallHoldMoveAction(
                movementExceeded = true,
                menuThresholdExceeded = false,
                movingTowardScreenCenter = false,
                directMoveEnabled = false
            )
        )
        assertEquals(
            FloatingBallHoldMoveAction.None,
            resolveFloatingBallHoldMoveAction(
                movementExceeded = true,
                menuThresholdExceeded = false,
                movingTowardScreenCenter = true,
                directMoveEnabled = true
            )
        )
    }
}
