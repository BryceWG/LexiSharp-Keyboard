package com.brycewg.asrkb.ime

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/**
 * 退格手势处理器：封装退格键的复杂手势逻辑
 *
 * 支持的手势：
 * - 点击：删除一个字符
 * - 长按：重复删除
 * - 向上/向左滑动：清空所有文本
 * - 向下滑动：撤销最近一次操作
 * - 清空后向下滑动：恢复清空前的文本
 */
class BackspaceGestureHandler(
    private val inputHelper: InputConnectionHelper,
) {
    // 回调接口
    interface Listener {
        fun onSingleDelete()
        fun onClearAll()
        fun onUndo()
        fun onVibrateRequest()
    }

    private var listener: Listener? = null

    // 手势状态
    private var startX: Float = 0f
    private var startY: Float = 0f
    private var isPressed: Boolean = false
    private var clearedInGesture: Boolean = false

    // 单次手势内去抖标记：避免在 MOVE 过程中重复触发撤销/恢复
    private var undoTriggeredInGesture: Boolean = false
    private var restoredAfterClearInGesture: Boolean = false

    // 清空发生时的参考坐标：用于在同一手势中判定“清空后向下滑动恢复”
    private var clearRefX: Float = 0f
    private var clearRefY: Float = 0f

    // 长按重复删除状态
    private var longPressStarted: Boolean = false
    private var longPressStarter: Runnable? = null
    private var repeatRunnable: Runnable? = null

    // 快照：用于清空后恢复
    private var gestureSnapshot: UndoSnapshot? = null

    fun setListener(l: Listener) {
        listener = l
    }

    /**
     * 处理触摸事件
     * @param view 触摸的视图
     * @param event 触摸事件
     * @param ic InputConnection 实例
     * @return 是否消费了事件
     */
    fun handleTouchEvent(view: View, event: MotionEvent, ic: InputConnection?): Boolean {
        if (ic == null) return false

        val slop = ViewConfiguration.get(view.context).scaledTouchSlop

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onActionDown(view, event, ic)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val absDx = abs(dx)
                val absDy = abs(dy)

                // 向上/向左滑动：清空所有文本（要求方向占优，减少误触）
                if (!clearedInGesture && (
                        (dy <= -slop && absDy >= absDx) ||
                            (dx <= -slop && absDx >= absDy)
                        )
                ) {
                    // 记录清空时的参考坐标（用于后续“清空后下滑恢复”的判定）
                    clearRefX = event.x
                    clearRefY = event.y
                    onSwipeToClear(view, ic)
                    return true
                }

                // 向下滑动：执行撤销（要求方向占优）
                if (!clearedInGesture && !undoTriggeredInGesture && dy >= slop && absDy >= absDx) {
                    onSwipeToUndo(view)
                    undoTriggeredInGesture = true
                    return true
                }

                // 清空后向下滑动：相对清空点的位移判断（要求方向占优）
                if (clearedInGesture && !restoredAfterClearInGesture) {
                    val dxSinceClear = event.x - clearRefX
                    val dySinceClear = event.y - clearRefY
                    if (dySinceClear >= slop && abs(dySinceClear) >= abs(dxSinceClear)) {
                        onRestoreAfterClear(view, ic)
                        restoredAfterClearInGesture = true
                        return true
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val isTap = abs(dx) < slop && abs(dy) < slop &&
                    !clearedInGesture && !longPressStarted

                onActionUp(view, isTap, event.actionMasked == MotionEvent.ACTION_UP)
                return true
            }

            else -> return false
        }
    }

    private fun onActionDown(view: View, event: MotionEvent, ic: InputConnection) {
        // 请求震动反馈
        listener?.onVibrateRequest()

        // 记录起始位置
        startX = event.x
        startY = event.y
        clearedInGesture = false
        undoTriggeredInGesture = false
        restoredAfterClearInGesture = false
        clearRefX = startX
        clearRefY = startY
        isPressed = true
        longPressStarted = false

        // 取消之前的计时器
        cancelTimers(view)

        // 捕获撤销快照（用于清空后恢复）
        gestureSnapshot = inputHelper.captureUndoSnapshot(ic)

        // 启动长按重复删除计时器
        scheduleLongPress(view)

        // 标记按下以触发 state_pressed，从而切换按压态图标
        try {
            view.isPressed = true
        } catch (e: Throwable) {
            android.util.Log.w("BackspaceGestureHandler", "Failed to set pressed=true", e)
        }
    }

    private fun onSwipeToClear(view: View, ic: InputConnection) {
        // 取消长按计时器
        cancelTimers(view)

        // 先通知监听方保存撤销快照（需在清空之前保存）
        // 这样全局下滑撤回会恢复到清空前的内容，而不是空文本
        listener?.onClearAll()

        // 再执行清空
        inputHelper.clearAllText(ic, gestureSnapshot)
        clearedInGesture = true
        listener?.onVibrateRequest()

        // 离开按压态
        try {
            view.isPressed = false
        } catch (e: Throwable) {
            android.util.Log.w("BackspaceGestureHandler", "Failed to set pressed=false (clear)", e)
        }
    }

    private fun onSwipeToUndo(view: View) {
        // 取消长按计时器
        cancelTimers(view)

        // 执行撤销
        listener?.onVibrateRequest()
        listener?.onUndo()

        // 离开按压态
        try {
            view.isPressed = false
        } catch (e: Throwable) {
            android.util.Log.w("BackspaceGestureHandler", "Failed to set pressed=false (undo)", e)
        }
    }

    private fun onRestoreAfterClear(view: View, ic: InputConnection) {
        val snapshot = gestureSnapshot
        if (snapshot != null) {
            inputHelper.restoreSnapshot(ic, snapshot)
        }
        clearedInGesture = false
        listener?.onVibrateRequest()
    }

    private fun onActionUp(view: View, isTap: Boolean, isUpAction: Boolean) {
        isPressed = false

        // 取消所有计时器
        cancelTimers(view)

        // 如果是单击，执行单次删除
        if (isTap && isUpAction) {
            listener?.onSingleDelete()
        }

        // 清理快照
        gestureSnapshot = null
        clearedInGesture = false
        undoTriggeredInGesture = false
        restoredAfterClearInGesture = false
    }

    private fun scheduleLongPress(view: View) {
        val starter = Runnable {
            if (!isPressed || clearedInGesture) return@Runnable
            longPressStarted = true

            // 执行首次删除
            listener?.onSingleDelete()

            // 启动重复删除
            val rep = object : Runnable {
                override fun run() {
                    if (!isPressed || clearedInGesture) return
                    listener?.onSingleDelete()
                    view.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                }
            }
            repeatRunnable = rep
            view.postDelayed(rep, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
        longPressStarter = starter
        view.postDelayed(starter, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelTimers(view: View) {
        longPressStarter?.let { view.removeCallbacks(it) }
        longPressStarter = null
        repeatRunnable?.let { view.removeCallbacks(it) }
        repeatRunnable = null

        // 释放按压态
        try {
            view.isPressed = false
        } catch (e: Throwable) {
            android.util.Log.w("BackspaceGestureHandler", "Failed to set pressed=false (up)", e)
        }
    }
}
