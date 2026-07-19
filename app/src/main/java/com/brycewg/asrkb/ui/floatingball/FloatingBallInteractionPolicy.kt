// Defines mode-dependent floating-ball recording and movement decisions.
package com.brycewg.asrkb.ui.floatingball

internal enum class FloatingBallRecordingTapAction {
    StartRecording,
    StopRecording,
    None
}

internal enum class FloatingBallHoldPressAction {
    StartRecording,
    StopRecording,
    CancelProcessing,
    RevealEdge,
    None
}

internal enum class FloatingBallHoldMoveAction {
    None,
    OpenMenu,
    MoveBall
}

internal fun resolveFloatingBallRecordingTapAction(
    isRecording: Boolean,
    holdToRecordEnabled: Boolean
): FloatingBallRecordingTapAction = when {
    isRecording -> FloatingBallRecordingTapAction.StopRecording
    holdToRecordEnabled -> FloatingBallRecordingTapAction.None
    else -> FloatingBallRecordingTapAction.StartRecording
}

internal fun shouldScheduleFloatingLongHoldMove(
    holdToRecordEnabled: Boolean,
    directMoveEnabled: Boolean
): Boolean = !holdToRecordEnabled && !directMoveEnabled

internal fun shouldStartFloatingHoldRecordingOnDown(
    holdToRecordEnabled: Boolean,
    isMoveMode: Boolean
): Boolean = holdToRecordEnabled && !isMoveMode

internal fun resolveFloatingBallHoldPressAction(
    isRecording: Boolean,
    isProcessing: Boolean,
    isEdgeHandleVisible: Boolean
): FloatingBallHoldPressAction = when {
    isRecording -> FloatingBallHoldPressAction.StopRecording
    isProcessing -> FloatingBallHoldPressAction.CancelProcessing
    isEdgeHandleVisible -> FloatingBallHoldPressAction.RevealEdge
    else -> FloatingBallHoldPressAction.StartRecording
}

internal fun resolveFloatingBallHoldMoveAction(
    movementExceeded: Boolean,
    menuThresholdExceeded: Boolean,
    movingTowardScreenCenter: Boolean,
    directMoveEnabled: Boolean
): FloatingBallHoldMoveAction = when {
    movingTowardScreenCenter && menuThresholdExceeded -> FloatingBallHoldMoveAction.OpenMenu
    movingTowardScreenCenter -> FloatingBallHoldMoveAction.None
    !movementExceeded -> FloatingBallHoldMoveAction.None
    directMoveEnabled -> FloatingBallHoldMoveAction.MoveBall
    else -> FloatingBallHoldMoveAction.None
}
