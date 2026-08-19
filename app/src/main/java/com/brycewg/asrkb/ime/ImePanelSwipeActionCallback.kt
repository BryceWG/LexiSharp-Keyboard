/**
 * IME 列表面板滑动露出层：阈值、触感与圆角背景文案。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes
import kotlin.math.abs

internal data class ImePanelSwipeReveal(
    val backgroundColor: Int,
    val label: String
)

internal class ImePanelSwipeActionCallback(
    context: Context,
    private val swipeDirsAt: (Int) -> Int,
    private val revealAt: (position: Int, dX: Float) -> ImePanelSwipeReveal?,
    private val onThresholdReached: (View) -> Unit,
    private val onSwiped: (position: Int, direction: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val theme = BibiViewThemes.resolve(context, Prefs(context))
    private val density = context.resources.displayMetrics.density
    private val cornerRadiusPx = ClipboardPanelMetrics.itemRadiusDp(theme.panelRadiusDp) * density
    private val labelInsetPx = 16f * density
    private val backgroundRect = RectF()
    private val textBounds = Rect()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.onPrimary
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var thresholdArmed = false

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val pos = viewHolder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return 0
        return swipeDirsAt(pos)
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = SWIPE_THRESHOLD

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        thresholdArmed = false
        onSwiped(viewHolder.bindingAdapterPosition, direction)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        thresholdArmed = false
        super.clearView(recyclerView, viewHolder)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView
            updateThresholdHaptic(itemView, dX, isCurrentlyActive)
            val pos = viewHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val reveal = revealAt(pos, dX)
                if (reveal != null && dX != 0f) {
                    drawReveal(
                        canvas = c,
                        itemView = itemView,
                        revealWidth = dX,
                        fromStart = dX > 0f,
                        backgroundColor = reveal.backgroundColor,
                        label = reveal.label
                    )
                }
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun updateThresholdHaptic(itemView: View, dX: Float, isCurrentlyActive: Boolean) {
        if (!isCurrentlyActive) return
        val width = itemView.width
        if (width <= 0) return
        val nowCrossed = abs(dX) >= width * SWIPE_THRESHOLD
        if (nowCrossed && !thresholdArmed) {
            thresholdArmed = true
            onThresholdReached(itemView)
        } else if (!nowCrossed) {
            thresholdArmed = false
        }
    }

    private fun drawReveal(
        canvas: Canvas,
        itemView: View,
        revealWidth: Float,
        fromStart: Boolean,
        backgroundColor: Int,
        label: String
    ) {
        val width = abs(revealWidth)
        if (width <= 0f) return

        val top = itemView.top.toFloat()
        val bottom = itemView.bottom.toFloat()
        val itemLeft = itemView.left.toFloat()
        val itemRight = itemView.right.toFloat()
        val revealLeft = if (fromStart) itemLeft else itemRight + revealWidth
        val revealRight = if (fromStart) itemLeft + width else itemRight

        // 背景按整卡圆角绘制；裁剪内侧多伸一个圆角半径，填住卡片圆角与截断线之间的空隙。
        val bgClipLeft = if (fromStart) revealLeft else (revealLeft - cornerRadiusPx).coerceAtLeast(itemLeft)
        val bgClipRight = if (fromStart) (revealRight + cornerRadiusPx).coerceAtMost(itemRight) else revealRight
        var saveCount = canvas.save()
        canvas.clipRect(bgClipLeft, top, bgClipRight, bottom)
        backgroundRect.set(itemLeft, top, itemRight, bottom)
        backgroundPaint.color = backgroundColor
        canvas.drawRoundRect(backgroundRect, cornerRadiusPx, cornerRadiusPx, backgroundPaint)
        canvas.restoreToCount(saveCount)

        // 文案只裁到真实露出区，固定贴外侧，随滑动逐渐拉出。
        saveCount = canvas.save()
        canvas.clipRect(revealLeft, top, revealRight, bottom)
        val labelWidth = labelPaint.measureText(label)
        labelPaint.getTextBounds(label, 0, label.length, textBounds)
        val textX = if (fromStart) {
            itemLeft + labelInsetPx
        } else {
            itemRight - labelInsetPx - labelWidth
        }
        val textY = (top + bottom) / 2f - (textBounds.top + textBounds.bottom) / 2f
        canvas.drawText(label, textX, textY, labelPaint)
        canvas.restoreToCount(saveCount)
    }

    companion object {
        private const val SWIPE_THRESHOLD = 0.35f
    }
}
