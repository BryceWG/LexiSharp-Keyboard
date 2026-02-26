package com.brycewg.asrkb.ui.floating

import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floatingball.FloatingBallState
import com.brycewg.asrkb.ui.floatingball.FloatingBallStateMachine
import com.brycewg.asrkb.ui.floatingball.FloatingBallViewManager

internal class FloatingVisibilityCoordinator(
    private val prefs: Prefs,
    private val stateMachine: FloatingBallStateMachine,
    private val viewManager: FloatingBallViewManager,
    private val tag: String,
    private val hasOverlayPermission: () -> Boolean,
    private val isImeVisible: () -> Boolean,
    private val isForceVisibleActive: () -> Boolean,
    private val showBall: (String) -> Unit,
    private val hideBall: () -> Unit,
) {
    private var lastShowAttemptAt: Long = 0L
    private var lastShowAttemptSig: String? = null

    fun applyVisibility(src: String) {
        val enabledPref = try {
            prefs.floatingAsrEnabled
        } catch (_: Throwable) {
            false
        }
        val onlyWhenImeVisible = try {
            prefs.floatingSwitcherOnlyWhenImeVisible
        } catch (_: Throwable) {
            false
        }
        val imeVisible = isImeVisible()
        val overlayGranted = hasOverlayPermission()

        logShowAttemptDedup(
            src = src,
            enabled = enabledPref,
            overlayGranted = overlayGranted,
            imeVisible = imeVisible,
            onlyWhenImeVisible = onlyWhenImeVisible,
        )

        if (!enabledPref || !overlayGranted) {
            DebugLogManager.log("float", "show_skip", mapOf("reason" to "pref_or_permission"))
            hideBall()
            return
        }

        val completionActive = try {
            viewManager.isCompletionTickActive()
        } catch (_: Throwable) {
            false
        }
        val forceVisible = isForceVisibleActive()
        if (onlyWhenImeVisible && !imeVisible &&
            !stateMachine.isRecording && !stateMachine.isProcessing && !completionActive && !forceVisible
        ) {
            DebugLogManager.log("float", "show_skip", mapOf("reason" to "ime_not_visible"))
            hideBall()
            return
        }

        // 若上次处于错误态：仅在“重新出现”时复位为 Idle，避免重复播放错误动画
        val hasBallView = viewManager.getBallView() != null
        if (!hasBallView && stateMachine.isError) {
            try {
                stateMachine.transitionTo(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to reset state from Error to Idle before show", e)
            }
        }

        showBall(src)

        // 常驻模式下：Idle 且无交互保护时，根据 IME 可见性自动恢复“本体/把手”
        if (!onlyWhenImeVisible && stateMachine.isIdle && !completionActive && !forceVisible) {
            try {
                if (imeVisible) {
                    viewManager.animateRevealFromEdgeIfNeeded()
                } else {
                    viewManager.animateHideToEdgePartialIfNeeded()
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to apply edge reveal/hide in applyVisibility", e)
            }
        }
    }

    private fun logShowAttemptDedup(
        src: String,
        enabled: Boolean,
        overlayGranted: Boolean,
        imeVisible: Boolean,
        onlyWhenImeVisible: Boolean,
    ) {
        val sig = "$src|$enabled|$overlayGranted|$imeVisible|$onlyWhenImeVisible"
        val now = System.currentTimeMillis()
        if (now - lastShowAttemptAt < 300L && lastShowAttemptSig == sig) return

        try {
            DebugLogManager.log(
                category = "float",
                event = "show_attempt",
                data = mapOf(
                    "src" to src,
                    "enabled" to enabled,
                    "overlay" to overlayGranted,
                    "imeVisible" to imeVisible,
                    "onlyWhenImeVisible" to onlyWhenImeVisible,
                ),
            )
        } catch (_: Throwable) {
            // 仅用于调试统计，忽略记录失败
        } finally {
            lastShowAttemptAt = now
            lastShowAttemptSig = sig
        }
    }
}
