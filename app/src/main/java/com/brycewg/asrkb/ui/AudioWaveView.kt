package com.brycewg.asrkb.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.Choreographer
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * 录音波形视图：根据实时音频能量绘制滚动柱状波形。
 * - 通过 [setLevel] 注入 0..1 的RMS电平
 * - [start] 开启帧驱动滚动， [stop] 停止
 */
class AudioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private var running = false

    // UI 参数
    private val barWidthPx = dp(4f)
    private val barGapPx = dp(2f)
    private val barRadiusPx = dp(2f)
    private val maxBars: Int
        get() {
            val w = width.coerceAtLeast(1)
            val unit = (barWidthPx + barGapPx)
            return max(8, (w / unit).toInt())
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resolvePrimaryColor()
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ColorUtils.setAlphaComponent(resolvePrimaryColor(), (255 * 0.35f).toInt())
    }

    // 数据缓冲：滚动条高（0..1），右侧为最新
    private var levels: FloatArray = FloatArray(64)

    // 电平平滑：目标与显示值
    private var targetLevel: Float = 0f
    private var displayLevel: Float = 0f

    // 帧率控制
    private var lastFrameNs: Long = 0L

    fun start() {
        if (running) return
        running = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        try { choreographer.removeFrameCallback(this) } catch (_: Throwable) {}
        // 渐隐清空
        targetLevel = 0f
        displayLevel = 0f
        for (i in levels.indices) levels[i] = 0f
        invalidate()
    }

    /** 注入新的音频能量（0..1） */
    fun setLevel(l: Float) {
        targetLevel = l.coerceIn(0f, 1f)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNs == 0L) 0f else (frameTimeNanos - lastFrameNs) / 1_000_000f
        lastFrameNs = frameTimeNanos

        // 指数平滑靠近目标（~20ms 时间常数）
        val alpha = when {
            dt <= 0 -> 0.25f
            else -> (1 - Math.exp((-dt / 20.0)).toFloat()).coerceIn(0.05f, 0.6f)
        }
        displayLevel = lerp(displayLevel, targetLevel, alpha)

        // 滚动缓冲：左移一格，末尾填入当前显示电平
        ensureLevelCapacity()
        for (i in 0 until levels.size - 1) {
            levels[i] = levels[i + 1]
        }
        levels[levels.lastIndex] = displayLevel

        invalidate()
        choreographer.postFrameCallback(this)
    }

    private fun ensureLevelCapacity() {
        val need = maxBars
        if (levels.size != need) {
            val old = levels
            levels = FloatArray(need)
            val copy = min(old.size, levels.size)
            System.arraycopy(old, max(0, old.size - copy), levels, levels.size - copy, copy)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerY = h / 2f
        val unit = (barWidthPx + barGapPx)
        val count = maxBars
        // 居中显示柱组，避免只在左或右留空导致边缘视觉不齐
        val startX = (w - count * unit) * 0.5f

        for (i in 0 until count) {
            val level = levels.getOrElse(i) { 0f }
            // 感知增益：提升中低幅度的可见度
            val boosted = Math.pow(level.toDouble(), 0.6).toFloat() // 0.0..1.0
            // 将电平映射到高度：提高最小高度到 15% 并增益 85%
            val norm = (0.05f + 0.85f * boosted).coerceIn(0f, 1f)
            val barH = h * norm
            val cx = startX + i * unit + barWidthPx / 2f
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            val p = if (level < 0.15f) dimPaint else paint
            canvas.drawRoundRect(
                cx - barWidthPx / 2f,
                top,
                cx + barWidthPx / 2f,
                bottom,
                barRadiusPx,
                barRadiusPx,
                p
            )
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun resolvePrimaryColor(): Int {
        return try {
            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        } catch (_: Throwable) {
            0xFF2196F3.toInt() // fallback 蓝色
        }
    }
}
