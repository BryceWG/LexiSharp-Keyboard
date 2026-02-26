package com.brycewg.asrkb.ime

import android.view.MotionEvent
import android.view.View
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal class MicGestureController(
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val actionHandler: KeyboardActionHandler,
    private val performKeyHaptic: (View?) -> Unit,
    private val checkAsrReady: () -> Boolean,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val isAiEditPanelVisible: () -> Boolean,
    private val onLockedBySwipeChanged: () -> Unit,
) {
    private enum class GestureState {
        None,
        PendingCancel,
        PendingSend,
        PendingLock,
    }

    private var state: GestureState = GestureState.None

    fun bindMicButton() {
        views.btnMic?.setOnClickListener { v ->
            val locked = actionHandler.isMicLockedBySwipe()
            if (!prefs.micTapToggleEnabled && !locked) return@setOnClickListener
            performKeyHaptic(v)
            if (locked) {
                safeLog(
                    event = "mic_click_locked",
                    data = mapOf(
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    ),
                )
                actionHandler.handleLockedMicTap()
                return@setOnClickListener
            }
            if (!checkAsrReady()) return@setOnClickListener
            safeLog(
                event = "mic_click",
                data = mapOf(
                    "tapToggle" to true,
                    "state" to actionHandler.getCurrentState()::class.java.simpleName,
                    "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    "aiPanel" to isAiEditPanelVisible(),
                ),
            )
            if (isAiEditPanelVisible()) {
                actionHandler.handleAiEditClick(inputConnectionProvider())
            } else {
                actionHandler.handleMicTapToggle()
            }
        }

        views.btnMic?.setOnTouchListener { v, event ->
            if (prefs.micTapToggleEnabled) return@setOnTouchListener false
            if (isAiEditPanelVisible()) {
                handleAiEditMicTouch(v, event)
            } else {
                handleMainMicTouch(v, event)
            }
        }
    }

    fun bindOverlayButtons() {
        views.btnGestureCancel?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleMicGestureCancel()
        }
        views.btnGestureSend?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleMicGestureSend()
        }
    }

    fun resetPressedState() {
        state = GestureState.None
        updatePressedState(GestureState.None)
    }

    private fun handleAiEditMicTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performKeyHaptic(v)
                if (!checkAsrReady()) {
                    safeLog(
                        event = "mic_down_blocked",
                        data = mapOf(
                            "tapToggle" to false,
                            "aiPanel" to true,
                            "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        ),
                    )
                    v.performClick()
                    return true
                }
                safeLog(
                    event = "ai_mic_down",
                    data = mapOf(
                        "tapToggle" to false,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    ),
                )
                actionHandler.handleAiEditClick(inputConnectionProvider())
                return true
            }
            MotionEvent.ACTION_UP -> {
                safeLog(
                    event = "ai_mic_up",
                    data = mapOf(
                        "tapToggle" to false,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    ),
                )
                if (actionHandler.getCurrentState() is KeyboardState.AiEditListening) {
                    actionHandler.handleAiEditClick(inputConnectionProvider())
                }
                v.performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                safeLog(
                    event = "ai_mic_cancel",
                    data = mapOf(
                        "tapToggle" to false,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                    ),
                )
                if (actionHandler.getCurrentState() is KeyboardState.AiEditListening) {
                    actionHandler.handleAiEditClick(inputConnectionProvider())
                }
                v.performClick()
                return true
            }
            else -> return false
        }
    }

    private fun handleMainMicTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                performKeyHaptic(v)
                if (!checkAsrReady()) {
                    safeLog(
                        event = "mic_down_blocked",
                        data = mapOf(
                            "tapToggle" to false,
                            "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        ),
                    )
                    v.performClick()
                    return true
                }
                state = GestureState.None
                safeLog(
                    event = "mic_down",
                    data = mapOf(
                        "tapToggle" to false,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    ),
                )
                actionHandler.handleMicPressDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = when {
                    isPointInsideView(event.rawX, event.rawY, views.btnGestureCancel) -> GestureState.PendingCancel
                    isPointInsideView(event.rawX, event.rawY, views.btnGestureSend) -> GestureState.PendingSend
                    isPointInsideView(event.rawX, event.rawY, views.btnExtCenter2) -> GestureState.PendingLock
                    else -> GestureState.None
                }
                if (target != state) {
                    state = target
                    updatePressedState(target)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val curr = state
                state = GestureState.None
                updatePressedState(GestureState.None)
                when (curr) {
                    GestureState.PendingCancel -> {
                        performKeyHaptic(v)
                        actionHandler.handleMicGestureCancel()
                        v.performClick()
                        return true
                    }
                    GestureState.PendingSend -> {
                        performKeyHaptic(v)
                        actionHandler.handleMicGestureSend()
                        v.performClick()
                        return true
                    }
                    GestureState.PendingLock -> {
                        performKeyHaptic(v)
                        actionHandler.handleMicSwipeLock()
                        onLockedBySwipeChanged()
                        return true
                    }
                    else -> {
                        safeLog(
                            event = "mic_up",
                            data = mapOf(
                                "tapToggle" to false,
                                "state" to actionHandler.getCurrentState()::class.java.simpleName,
                                "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                            ),
                        )
                        actionHandler.handleMicPressUp(false)
                        v.performClick()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                safeLog(
                    event = "mic_cancel",
                    data = mapOf(
                        "tapToggle" to false,
                        "state" to actionHandler.getCurrentState()::class.java.simpleName,
                        "running" to (actionHandler.getCurrentState() is KeyboardState.Listening),
                    ),
                )
                state = GestureState.None
                updatePressedState(GestureState.None)
                actionHandler.handleMicPressUp(false)
                v.performClick()
                return true
            }
            else -> return false
        }
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, target: View?): Boolean {
        if (target == null || target.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val left = loc[0]
        val top = loc[1]
        val right = left + target.width
        val bottom = top + target.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun updatePressedState(state: GestureState) {
        views.btnGestureCancel?.isPressed = state == GestureState.PendingCancel
        views.btnGestureSend?.isPressed = state == GestureState.PendingSend
        views.btnExtCenter2?.isPressed = state == GestureState.PendingLock
    }

    private fun safeLog(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.log(category = "ime", event = event, data = data)
        } catch (t: Throwable) {
            android.util.Log.w("MicGestureController", "DebugLogManager.log failed: $event", t)
        }
    }
}
