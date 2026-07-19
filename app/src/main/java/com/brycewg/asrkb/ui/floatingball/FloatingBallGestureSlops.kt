// Resolves floating-ball gesture thresholds without depending on Android touch events.
package com.brycewg.asrkb.ui.floatingball

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FloatingBallGestureSlops(
    val moveActivationPx: Int,
    val menuSelectionPx: Int,
    val directMovePx: Int
)

internal fun resolveFloatingBallGestureSlops(
    density: Float,
    scaledTouchSlop: Int,
    holdToRecordEnabled: Boolean,
    directMoveEnabled: Boolean
): FloatingBallGestureSlops {
    val touchSlop = dpToPx(4, density)
    val directMoveSlop = maxOf(dpToPx(8, density), scaledTouchSlop)
    val protectedMoveSlop = maxOf(dpToPx(32, density), scaledTouchSlop)
    val protectedMenuSlop = maxOf(dpToPx(48, density), scaledTouchSlop)

    if (holdToRecordEnabled) {
        return FloatingBallGestureSlops(
            moveActivationPx = protectedMoveSlop,
            menuSelectionPx = protectedMenuSlop,
            directMovePx = protectedMoveSlop
        )
    }

    return FloatingBallGestureSlops(
        moveActivationPx = if (directMoveEnabled) directMoveSlop else touchSlop,
        menuSelectionPx = touchSlop,
        directMovePx = directMoveSlop
    )
}

private fun dpToPx(value: Int, density: Float): Int =
    (value * density).roundToInt().coerceAtLeast(1)

internal fun exceedsFloatingBallMoveSlop(dx: Int, dy: Int, slop: Int): Boolean {
    val distanceSquared = dx.toDouble() * dx + dy.toDouble() * dy
    val slopSquared = slop.toDouble() * slop
    return distanceSquared > slopSquared
}

internal fun shouldStartFloatingBallMenuSelection(dx: Int, dy: Int, slop: Int): Boolean {
    val absDx = abs(dx)
    val absDy = abs(dy)
    return absDx > slop && absDx >= absDy
}

internal fun shouldStartFloatingBallMenuTowardCenter(
    dx: Int,
    dy: Int,
    slop: Int,
    downX: Float,
    downY: Float,
    screenWidth: Int,
    screenHeight: Int
): Boolean {
    if (!exceedsFloatingBallMoveSlop(dx, dy, slop)) return false
    return isFloatingBallMovementTowardCenter(
        dx = dx,
        dy = dy,
        downX = downX,
        downY = downY,
        screenWidth = screenWidth,
        screenHeight = screenHeight
    )
}

internal fun isFloatingBallMovementTowardCenter(
    dx: Int,
    dy: Int,
    downX: Float,
    downY: Float,
    screenWidth: Int,
    screenHeight: Int
): Boolean {
    if (screenWidth <= 0 || screenHeight <= 0) return false

    val towardCenterX = screenWidth / 2f - downX
    val towardCenterY = screenHeight / 2f - downY
    val dot = dx.toDouble() * towardCenterX + dy.toDouble() * towardCenterY
    if (dot <= 0.0) return false

    val movementLengthSquared = dx.toDouble() * dx + dy.toDouble() * dy
    val centerLengthSquared =
        towardCenterX.toDouble() * towardCenterX + towardCenterY.toDouble() * towardCenterY
    if (movementLengthSquared <= 0.0 || centerLengthSquared <= 0.0) return false

    // Require the movement to stay within 45 degrees of the direction toward screen center.
    return dot * dot >= 0.5 * movementLengthSquared * centerLengthSquared
}
