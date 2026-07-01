/**
 * 悬浮键盘拖动把手位置判定。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

internal enum class FloatingDragHandlePlacement {
    TOP,
    BOTTOM
}

internal fun resolveFloatingDragHandlePlacement(
    windowY: Int,
    panelHeight: Int,
    screenHeight: Int,
    bottomThresholdPx: Int
): FloatingDragHandlePlacement {
    if (panelHeight <= 0 || screenHeight <= 0 || bottomThresholdPx <= 0) {
        return FloatingDragHandlePlacement.BOTTOM
    }
    val bottomGap = screenHeight.toLong() - windowY.toLong() - panelHeight.toLong()
    return if (bottomGap < bottomThresholdPx) {
        FloatingDragHandlePlacement.TOP
    } else {
        FloatingDragHandlePlacement.BOTTOM
    }
}
