package com.brycewg.asrkb.ime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

class CornerBadgeDrawable(
    private val text: String,
    private val textSizePx: Float,
    private val color: Int,
    private val paddingPx: Float = 6f
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@CornerBadgeDrawable.color
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
    }

    override fun draw(canvas: Canvas) {
        if (text.isEmpty()) return
        val b: Rect = bounds
        val fm = paint.fontMetrics
        // 中上位置：水平居中，靠近按键上边缘（留一点内边距）
        val x = b.exactCenterX()
        val y = (b.top + paddingPx) - fm.ascent
        // 降低不透明度以避免喧宾夺主
        val oldAlpha = paint.alpha
        paint.alpha = (oldAlpha * 0.7f).toInt()
        canvas.drawText(text, x, y, paint)
        paint.alpha = oldAlpha
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }
}
