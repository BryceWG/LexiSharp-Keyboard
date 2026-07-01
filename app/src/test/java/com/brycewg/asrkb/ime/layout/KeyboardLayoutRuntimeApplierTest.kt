/**
 * 自定义键盘运行时布局计算的回归测试。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyboardLayoutRuntimeApplierTest {
    @Test
    fun scaledMicBoundsKeepsWideMicRectangular() {
        val bounds = KeyboardLayoutRuntimeApplier.scaledMicBounds(
            left = 0,
            top = 0,
            width = 300,
            height = 200
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(300, bounds.width)
        assertEquals(200, bounds.height)
        assertNotEquals(bounds.width, bounds.height)
    }

    @Test
    fun scaledMicBoundsKeepsTallMicRectangular() {
        val bounds = KeyboardLayoutRuntimeApplier.scaledMicBounds(
            left = 0,
            top = 0,
            width = 200,
            height = 300
        )

        assertEquals(0, bounds.left)
        assertEquals(0, bounds.top)
        assertEquals(200, bounds.width)
        assertEquals(300, bounds.height)
        assertNotEquals(bounds.width, bounds.height)
    }

    @Test
    fun scaledMicBoundsKeepsSquareMicFullSize() {
        val bounds = KeyboardLayoutRuntimeApplier.scaledMicBounds(
            left = 20,
            top = 30,
            width = 200,
            height = 200
        )

        assertEquals(20, bounds.left)
        assertEquals(30, bounds.top)
        assertEquals(200, bounds.width)
        assertEquals(200, bounds.height)
    }
}
