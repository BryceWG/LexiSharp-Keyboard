// Tracks whether the active recording belongs to the current floating-ball hold gesture.
package com.brycewg.asrkb.ui.floatingball

internal class FloatingBallHoldRecordingTracker {
    private var gestureOwnsRecording: Boolean = false

    fun markStartResult(started: Boolean, isRecordingActive: Boolean) {
        gestureOwnsRecording = started && isRecordingActive
    }

    fun onRecordingActivityChanged(isRecordingActive: Boolean) {
        if (!isRecordingActive) {
            gestureOwnsRecording = false
        }
    }

    fun consumeStopOnRelease(isRecordingActive: Boolean): Boolean =
        consumeIfOwned(isRecordingActive)

    fun consumeCancelForGesture(isRecordingActive: Boolean): Boolean =
        consumeIfOwned(isRecordingActive)

    fun clear() {
        gestureOwnsRecording = false
    }

    private fun consumeIfOwned(isRecordingActive: Boolean): Boolean {
        val shouldAct = gestureOwnsRecording && isRecordingActive
        gestureOwnsRecording = false
        return shouldAct
    }
}
