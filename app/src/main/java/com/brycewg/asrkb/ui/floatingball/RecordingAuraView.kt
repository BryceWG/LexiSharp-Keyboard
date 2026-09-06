package com.brycewg.asrkb.ui.floatingball

import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.sqrt

/** Draws the non-interactive halo and peak rings around the floating ball while recording. */
internal class RecordingAuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class PeakWave(val startedAtMs: Long, val strength: Float)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringInterpolator: TimeInterpolator = DecelerateInterpolator()
    private var auraColor: Int = 0
    private var active = false
    private var envelope = 0f
    private var startedAtMs: Long = 0L
    private var peakState = RecordingAuraMath.PeakState()
    private val peakWaves = mutableListOf<PeakWave>()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun setAuraColor(color: Int) {
        auraColor = color
        invalidate()
    }

    fun start() {
        active = true
        startedAtMs = SystemClock.uptimeMillis()
        envelope = 0f
        peakState = RecordingAuraMath.PeakState()
        peakWaves.clear()
        animate().cancel()
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
        visibility = VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(AURA_ENTER_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        invalidate()
    }

    fun stop() {
        animate().cancel()
        active = false
        startedAtMs = 0L
        envelope = 0f
        peakState = RecordingAuraMath.PeakState()
        peakWaves.clear()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        visibility = INVISIBLE
        invalidate()
    }

    fun updateLevel(level: Float, nowUptimeMs: Long = SystemClock.uptimeMillis()) {
        if (!active) return
        envelope = level.coerceIn(0f, 1f)
        trimExpiredPeaks(nowUptimeMs)
        val decision = RecordingAuraMath.decidePeakEmission(
            level = envelope,
            nowUptimeMs = nowUptimeMs,
            activePeakCount = peakWaves.size,
            state = peakState
        )
        peakState = decision.state
        if (decision.emit) {
            peakWaves += PeakWave(nowUptimeMs, decision.emitStrength)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width <= 0 || height <= 0 || auraColor == 0) return

        val now = SystemClock.uptimeMillis()
        trimExpiredPeaks(now)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f
        val ballRadius = maxRadius / RecordingAuraMath.EXPANDED_WINDOW_SCALE

        drawHalo(canvas, cx, cy, ballRadius, maxRadius, now)
        drawPeakWaves(canvas, cx, cy, ballRadius, maxRadius, now)
        postInvalidateOnAnimation()
    }

    private fun drawHalo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ballRadius: Float,
        maxRadius: Float,
        nowUptimeMs: Long
    ) {
        val frame = RecordingAuraMath.haloFrame(
            nowUptimeMs = nowUptimeMs,
            startedAtUptimeMs = startedAtMs
        )
        val haloRadius = ballRadius * frame.radiusScale
        haloPaint.color = applyAlpha(auraColor, frame.alpha)
        canvas.drawCircle(cx, cy, haloRadius.coerceAtMost(maxRadius), haloPaint)
    }

    private fun drawPeakWaves(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ballRadius: Float,
        maxRadius: Float,
        nowUptimeMs: Long
    ) {
        val iterator = peakWaves.iterator()
        while (iterator.hasNext()) {
            val wave = iterator.next()
            val rawProgress = ((nowUptimeMs - wave.startedAtMs).toFloat() / PEAK_DURATION_MS)
                .coerceIn(0f, 1f)
            if (rawProgress >= 1f) {
                iterator.remove()
                continue
            }
            val progress = ringInterpolator.getInterpolation(rawProgress)
            val radius = ballRadius * (1.02f + 0.50f * progress)
            val fade = sqrt((1f - rawProgress).coerceIn(0f, 1f))
            val alpha = (160f * fade * wave.strength.coerceIn(0.45f, 1f)).toInt()
                .coerceIn(0, 180)
            ringPaint.color = applyAlpha(auraColor, alpha)
            ringPaint.strokeWidth = (resources.displayMetrics.density * (2.2f + 1.2f * wave.strength))
                .coerceAtLeast(1f)
            canvas.drawCircle(cx, cy, radius.coerceAtMost(maxRadius), ringPaint)
        }
    }

    private fun trimExpiredPeaks(nowUptimeMs: Long) {
        peakWaves.removeAll { nowUptimeMs - it.startedAtMs >= PEAK_DURATION_MS }
    }

    private fun applyAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private companion object {
        private const val PEAK_DURATION_MS = 760L
        private const val AURA_ENTER_MS = 180L
    }
}
