// Tests deferred accessibility navigation for floating-ball hold gestures.
package com.brycewg.asrkb.ui.floatingball

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBallHoldAccessibilityPromptTrackerTest {
    @Test
    fun missingAccessibilityPromptIsDeferredUntilRelease() {
        val tracker = FloatingBallHoldAccessibilityPromptTracker()

        tracker.markPending()

        assertTrue(tracker.consumeOnRelease())
        assertFalse(tracker.consumeOnRelease())
    }

    @Test
    fun movingOrCancellingGestureDiscardsPendingPrompt() {
        val tracker = FloatingBallHoldAccessibilityPromptTracker()

        tracker.markPending()
        tracker.clear()

        assertFalse(tracker.consumeOnRelease())
    }
}
