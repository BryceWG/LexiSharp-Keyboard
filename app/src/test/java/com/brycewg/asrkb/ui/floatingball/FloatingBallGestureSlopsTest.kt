// Tests floating-ball gesture thresholds for tap and hold-to-record modes.
package com.brycewg.asrkb.ui.floatingball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBallGestureSlopsTest {
    @Test
    fun tapModePreservesExistingThresholdsWithoutDirectMove() {
        val slops = resolveFloatingBallGestureSlops(
            density = 2f,
            scaledTouchSlop = 20,
            holdToRecordEnabled = false,
            directMoveEnabled = false
        )

        assertEquals(8, slops.moveActivationPx)
        assertEquals(8, slops.menuSelectionPx)
        assertEquals(20, slops.directMovePx)
    }

    @Test
    fun tapModeDirectMoveUsesExistingSystemAwareThreshold() {
        val slops = resolveFloatingBallGestureSlops(
            density = 2f,
            scaledTouchSlop = 20,
            holdToRecordEnabled = false,
            directMoveEnabled = true
        )

        assertEquals(20, slops.moveActivationPx)
        assertEquals(8, slops.menuSelectionPx)
    }

    @Test
    fun holdModeUsesThirtyTwoDpForMoveAndFortyEightDpForMenu() {
        val slops = resolveFloatingBallGestureSlops(
            density = 2f,
            scaledTouchSlop = 20,
            holdToRecordEnabled = true,
            directMoveEnabled = true
        )

        assertEquals(64, slops.moveActivationPx)
        assertEquals(96, slops.menuSelectionPx)
        assertEquals(64, slops.directMovePx)
    }

    @Test
    fun holdModeRespectsLargerSystemTouchSlop() {
        val slops = resolveFloatingBallGestureSlops(
            density = 1f,
            scaledTouchSlop = 32,
            holdToRecordEnabled = true,
            directMoveEnabled = false
        )

        assertEquals(32, slops.moveActivationPx)
        assertEquals(48, slops.menuSelectionPx)
    }

    @Test
    fun protectedMenuRequiresHorizontalMovementBeyondThreshold() {
        assertFalse(shouldStartFloatingBallMenuSelection(dx = 24, dy = 0, slop = 24))
        assertFalse(shouldStartFloatingBallMenuSelection(dx = 25, dy = 26, slop = 24))
        assertTrue(shouldStartFloatingBallMenuSelection(dx = 25, dy = 20, slop = 24))
    }

    @Test
    fun protectedMoveUsesRadialDistance() {
        assertFalse(exceedsFloatingBallMoveSlop(dx = 22, dy = 22, slop = 32))
        assertFalse(exceedsFloatingBallMoveSlop(dx = 32, dy = 0, slop = 32))
        assertTrue(exceedsFloatingBallMoveSlop(dx = 23, dy = 23, slop = 32))
        assertTrue(exceedsFloatingBallMoveSlop(dx = 32, dy = 32, slop = 32))
        assertTrue(exceedsFloatingBallMoveSlop(dx = 33, dy = 0, slop = 32))
        assertTrue(exceedsFloatingBallMoveSlop(dx = 0, dy = -33, slop = 32))
        assertTrue(
            exceedsFloatingBallMoveSlop(
                dx = Int.MAX_VALUE,
                dy = Int.MAX_VALUE,
                slop = Int.MAX_VALUE
            )
        )
    }

    @Test
    fun menuThresholdUsesRadialDistanceBeforeDirectionCheck() {
        assertTrue(
            shouldStartFloatingBallMenuTowardCenter(
                dx = 35,
                dy = 35,
                slop = 48,
                downX = 0f,
                downY = 0f,
                screenWidth = 1000,
                screenHeight = 1000
            )
        )
    }

    @Test
    fun menuGestureMustMoveTowardScreenCenter() {
        assertTrue(
            shouldStartFloatingBallMenuTowardCenter(
                dx = 49,
                dy = 0,
                slop = 48,
                downX = 0f,
                downY = 500f,
                screenWidth = 1000,
                screenHeight = 1000
            )
        )
        assertFalse(
            shouldStartFloatingBallMenuTowardCenter(
                dx = -49,
                dy = 0,
                slop = 48,
                downX = 0f,
                downY = 500f,
                screenWidth = 1000,
                screenHeight = 1000
            )
        )
        assertTrue(
            shouldStartFloatingBallMenuTowardCenter(
                dx = -49,
                dy = 0,
                slop = 48,
                downX = 1000f,
                downY = 500f,
                screenWidth = 1000,
                screenHeight = 1000
            )
        )
        assertTrue(
            shouldStartFloatingBallMenuTowardCenter(
                dx = 0,
                dy = -49,
                slop = 48,
                downX = 500f,
                downY = 1000f,
                screenWidth = 1000,
                screenHeight = 1000
            )
        )
    }
}
