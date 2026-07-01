/**
 * 悬浮键盘拖动把手位置判定测试。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingDragHandlePlacementTest {
    @Test
    fun nearBottomUsesTopHandle() {
        val placement = resolveFloatingDragHandlePlacement(
            windowY = 650,
            panelHeight = 300,
            screenHeight = 1000,
            bottomThresholdPx = 100
        )

        assertEquals(FloatingDragHandlePlacement.TOP, placement)
    }

    @Test
    fun exactThresholdKeepsBottomHandle() {
        val placement = resolveFloatingDragHandlePlacement(
            windowY = 600,
            panelHeight = 300,
            screenHeight = 1000,
            bottomThresholdPx = 100
        )

        assertEquals(FloatingDragHandlePlacement.BOTTOM, placement)
    }

    @Test
    fun outsideThresholdKeepsBottomHandle() {
        val placement = resolveFloatingDragHandlePlacement(
            windowY = 500,
            panelHeight = 300,
            screenHeight = 1000,
            bottomThresholdPx = 100
        )

        assertEquals(FloatingDragHandlePlacement.BOTTOM, placement)
    }

    @Test
    fun invalidDimensionsKeepBottomHandle() {
        assertEquals(
            FloatingDragHandlePlacement.BOTTOM,
            resolveFloatingDragHandlePlacement(
                windowY = 650,
                panelHeight = 0,
                screenHeight = 1000,
                bottomThresholdPx = 100
            )
        )
        assertEquals(
            FloatingDragHandlePlacement.BOTTOM,
            resolveFloatingDragHandlePlacement(
                windowY = 650,
                panelHeight = 300,
                screenHeight = 0,
                bottomThresholdPx = 100
            )
        )
        assertEquals(
            FloatingDragHandlePlacement.BOTTOM,
            resolveFloatingDragHandlePlacement(
                windowY = 650,
                panelHeight = 300,
                screenHeight = 1000,
                bottomThresholdPx = 0
            )
        )
    }
}
