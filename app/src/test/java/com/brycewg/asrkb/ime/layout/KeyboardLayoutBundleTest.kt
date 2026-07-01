/**
 * 自定义键盘布局 bundle 同步逻辑的回归测试。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutBundleTest {
    @Test
    fun syncedGridSizeAppliesToEveryPanel() {
        val bundle = testBundle()

        val synced = bundle.withSyncedGridSize(
            sourcePanel = KeyboardLayoutPanel.Main,
            gridSize = GridSize(cols = 8, rows = 5),
            updatedAt = 42L
        )

        assertEquals(GridSize(8, 5), synced.main.gridSize)
        assertEquals(GridSize(8, 5), synced.aiEdit.gridSize)
        assertEquals(GridSize(8, 5), synced.recording.gridSize)
        assertEquals(42L, synced.main.updatedAt)
        assertEquals(42L, synced.aiEdit.updatedAt)
        assertEquals(42L, synced.recording.updatedAt)
    }

    @Test
    fun syncedGridSizeKeepsEachPanelWithinAllowedRange() {
        val bundle = testBundle()

        val synced = bundle.withSyncedGridSize(
            sourcePanel = KeyboardLayoutPanel.Recording,
            gridSize = GridSize(cols = 3, rows = 2),
            updatedAt = 42L
        )

        assertEquals(GridSize(4, 3), synced.main.gridSize)
        assertEquals(GridSize(4, 3), synced.aiEdit.gridSize)
        assertEquals(GridSize(4, 2), synced.recording.gridSize)
    }

    @Test
    fun syncedGridSizeDropsBlocksOutsideNewGrid() {
        val bundle = testBundle(
            mainBlocks = listOf(
                BlockInstance("inside", BlockPlacement(0f, 0f, 1f, 1f)),
                BlockInstance("outside", BlockPlacement(6f, 3f, 1f, 1f))
            )
        )

        val synced = bundle.withSyncedGridSize(
            sourcePanel = KeyboardLayoutPanel.Main,
            gridSize = GridSize(cols = 5, rows = 3),
            updatedAt = 42L
        )

        assertEquals(listOf("inside"), synced.main.blocks.map { it.defId })
        assertTrue(synced.aiEdit.blocks.all { it.placement.withinGrid(synced.aiEdit.gridSize) })
        assertTrue(synced.recording.blocks.all { it.placement.withinGrid(synced.recording.gridSize) })
    }

    private fun testBundle(
        mainBlocks: List<BlockInstance> = listOf(BlockInstance("main", BlockPlacement(0f, 0f, 1f, 1f))),
        aiEditBlocks: List<BlockInstance> = listOf(BlockInstance("ai", BlockPlacement(0f, 0f, 1f, 1f))),
        recordingBlocks: List<BlockInstance> = listOf(BlockInstance("rec", BlockPlacement(0f, 0f, 1f, 1f)))
    ): KeyboardLayoutBundle = KeyboardLayoutBundle(
        main = testLayout(KeyboardLayoutPanel.Main, mainBlocks),
        aiEdit = testLayout(KeyboardLayoutPanel.AiEdit, aiEditBlocks),
        recording = testLayout(KeyboardLayoutPanel.Recording, recordingBlocks)
    )

    private fun testLayout(
        panel: KeyboardLayoutPanel,
        blocks: List<BlockInstance>
    ): KeyboardLayout = KeyboardLayout(
        id = panel.id,
        name = panel.id,
        panel = panel,
        gridSize = GridSize(7, 4),
        blocks = blocks,
        createdAt = 1L,
        updatedAt = 1L
    )
}
