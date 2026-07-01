/**
 * 自定义键盘布局的数据模型与校验规则。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class KeyboardLayoutPanel(val id: String) {
    Main("main"),
    AiEdit("ai_edit"),
    Recording("recording");

    companion object {
        fun fromId(id: String?): KeyboardLayoutPanel = values().firstOrNull { it.id == id } ?: Main
    }
}

enum class ButtonViewKind {
    Icon,
    Text,
    Status,
    Punctuation,
    Gesture,
    External
}

data class GridSize(
    val cols: Int,
    val rows: Int
) {
    fun coerceForPanel(panel: KeyboardLayoutPanel): GridSize {
        val colsRange = if (panel == KeyboardLayoutPanel.Recording) 4..10 else 4..10
        val rowsRange = if (panel == KeyboardLayoutPanel.Recording) 2..6 else 3..6
        return GridSize(cols.coerceIn(colsRange), rows.coerceIn(rowsRange))
    }
}

data class BlockPlacement(
    val col: Float,
    val row: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = col + width
    val bottom: Float get() = row + height

    fun overlaps(other: BlockPlacement): Boolean = col < other.right && right > other.col && row < other.bottom && bottom > other.row

    fun withinGrid(grid: GridSize): Boolean = col >= 0f && row >= 0f && right <= grid.cols && bottom <= grid.rows

    fun snapped(): BlockPlacement = copy(
        col = col.snapHalf(),
        row = row.snapHalf(),
        width = width.snapHalf().coerceAtLeast(1f),
        height = height.snapHalf().coerceAtLeast(1f)
    )
}

data class BlockDef(
    val id: String,
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int?,
    val viewKind: ButtonViewKind,
    val allowedSizes: List<BlockSize>,
    val defaultSize: BlockSize,
    val required: Boolean = false,
    val maxInstances: Int = 1,
    val viewId: Int? = null,
    val extensionActionId: String? = null
)

data class BlockSize(
    val width: Float,
    val height: Float
) {
    override fun toString(): String = "${width.formatHalf()}x${height.formatHalf()}"
}

data class BlockInstance(
    val defId: String,
    val placement: BlockPlacement,
    val config: Map<String, String> = emptyMap()
)

data class KeyboardLayout(
    val id: String,
    val name: String,
    val panel: KeyboardLayoutPanel,
    val gridSize: GridSize,
    val blocks: List<BlockInstance>,
    val createdAt: Long,
    val updatedAt: Long
)

data class KeyboardLayoutBundle(
    val main: KeyboardLayout,
    val aiEdit: KeyboardLayout,
    val recording: KeyboardLayout
) {
    fun layoutFor(panel: KeyboardLayoutPanel): KeyboardLayout = when (panel) {
        KeyboardLayoutPanel.Main -> main
        KeyboardLayoutPanel.AiEdit -> aiEdit
        KeyboardLayoutPanel.Recording -> recording
    }

    fun withLayout(layout: KeyboardLayout): KeyboardLayoutBundle = when (layout.panel) {
        KeyboardLayoutPanel.Main -> copy(main = layout)
        KeyboardLayoutPanel.AiEdit -> copy(aiEdit = layout)
        KeyboardLayoutPanel.Recording -> copy(recording = layout)
    }

    fun withSyncedGridSize(
        sourcePanel: KeyboardLayoutPanel,
        gridSize: GridSize,
        updatedAt: Long = System.currentTimeMillis()
    ): KeyboardLayoutBundle {
        val sourceGrid = gridSize.coerceForPanel(sourcePanel)
        return KeyboardLayoutBundle(
            main = main.withGridSize(sourceGrid, updatedAt),
            aiEdit = aiEdit.withGridSize(sourceGrid, updatedAt),
            recording = recording.withGridSize(sourceGrid, updatedAt)
        )
    }

    private fun KeyboardLayout.withGridSize(gridSize: GridSize, updatedAt: Long): KeyboardLayout {
        val nextGrid = gridSize.coerceForPanel(panel)
        return copy(
            gridSize = nextGrid,
            blocks = blocks.filter { it.placement.withinGrid(nextGrid) },
            updatedAt = updatedAt
        )
    }
}

sealed class LayoutError {
    data class UnknownBlock(val defId: String) : LayoutError()
    data class MissingRequired(val defId: String) : LayoutError()
    data class OutOfBounds(val defId: String) : LayoutError()
    data class Overlap(val a: String, val b: String) : LayoutError()
    data class ExceedsMaxInstances(val defId: String, val max: Int) : LayoutError()
    data class InvalidSize(val defId: String) : LayoutError()
}

fun validateLayout(layout: KeyboardLayout, registry: BlockDefRegistry): List<LayoutError> {
    val errors = mutableListOf<LayoutError>()
    val defs = registry.defsFor(layout.panel)

    defs.filter { it.required }.forEach { def ->
        if (layout.blocks.none { it.defId == def.id }) {
            errors += LayoutError.MissingRequired(def.id)
        }
    }

    layout.blocks.forEach { block ->
        val def = registry.get(layout.panel, block.defId) ?: registry.get(block.defId)
        if (def == null) {
            errors += LayoutError.UnknownBlock(block.defId)
            return@forEach
        }
        val placement = block.placement.snapped()
        if (!placement.withinGrid(layout.gridSize)) {
            errors += LayoutError.OutOfBounds(block.defId)
        }
        val size = BlockSize(placement.width, placement.height)
        if (def.allowedSizes.none { it.width == size.width && it.height == size.height }) {
            errors += LayoutError.InvalidSize(block.defId)
        }
    }

    for (i in layout.blocks.indices) {
        for (j in i + 1 until layout.blocks.size) {
            if (layout.blocks[i].placement.snapped().overlaps(layout.blocks[j].placement.snapped())) {
                errors += LayoutError.Overlap(layout.blocks[i].defId, layout.blocks[j].defId)
            }
        }
    }

    layout.blocks.groupBy { it.defId }.forEach { (defId, instances) ->
        val def = registry.get(layout.panel, defId) ?: registry.get(defId) ?: return@forEach
        if (instances.size > def.maxInstances) {
            errors += LayoutError.ExceedsMaxInstances(defId, def.maxInstances)
        }
    }

    return errors
}

internal fun Float.snapHalf(): Float = (kotlin.math.round(this * 2f) / 2f)

internal fun Float.formatHalf(): String {
    val rounded = snapHalf()
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}
