// Tests recording ownership for floating-ball hold gestures.
package com.brycewg.asrkb.ui.floatingball

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBallHoldRecordingTrackerTest {
    @Test
    fun releaseStopsOnlyRecordingStartedByCurrentHoldGesture() {
        val tracker = FloatingBallHoldRecordingTracker()

        assertFalse(tracker.consumeStopOnRelease(isRecordingActive = true))

        tracker.markStartResult(started = true, isRecordingActive = true)
        assertTrue(tracker.consumeStopOnRelease(isRecordingActive = true))
        assertFalse(tracker.consumeStopOnRelease(isRecordingActive = true))
    }

    @Test
    fun failedStartDoesNotOwnAnotherRecording() {
        val tracker = FloatingBallHoldRecordingTracker()

        tracker.markStartResult(started = false, isRecordingActive = true)

        assertFalse(tracker.consumeCancelForGesture(isRecordingActive = true))
    }

    @Test
    fun processingVisualStateDoesNotClearActiveGestureOwnership() {
        val tracker = FloatingBallHoldRecordingTracker()
        tracker.markStartResult(started = true, isRecordingActive = true)

        tracker.onRecordingActivityChanged(isRecordingActive = true)

        assertTrue(tracker.consumeStopOnRelease(isRecordingActive = true))
    }

    @Test
    fun inactiveEngineDoesNotTriggerDuplicateStop() {
        val tracker = FloatingBallHoldRecordingTracker()
        tracker.markStartResult(started = true, isRecordingActive = true)

        assertFalse(tracker.consumeStopOnRelease(isRecordingActive = false))
    }

    @Test
    fun menuOrMoveConsumesCancellationOnlyOnce() {
        val tracker = FloatingBallHoldRecordingTracker()
        tracker.markStartResult(started = true, isRecordingActive = true)

        assertTrue(tracker.consumeCancelForGesture(isRecordingActive = true))
        assertFalse(tracker.consumeCancelForGesture(isRecordingActive = true))
    }
}
