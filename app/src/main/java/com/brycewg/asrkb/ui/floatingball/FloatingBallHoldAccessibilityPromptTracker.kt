// Defers accessibility settings navigation until a hold gesture ends without moving.
package com.brycewg.asrkb.ui.floatingball

internal class FloatingBallHoldAccessibilityPromptTracker {
    private var pending: Boolean = false

    fun markPending() {
        pending = true
    }

    fun consumeOnRelease(): Boolean {
        val shouldOpen = pending
        pending = false
        return shouldOpen
    }

    fun clear() {
        pending = false
    }
}
