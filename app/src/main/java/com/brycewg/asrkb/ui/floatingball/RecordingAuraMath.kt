package com.brycewg.asrkb.ui.floatingball

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Pure math for the floating ball recording aura and expanded recording window. */
internal object RecordingAuraMath {
    const val DEFAULT_ENVELOPE_SMOOTHING = 0.28f
    const val PEAK_ACTIVITY_FLOOR = 0.20f
    const val PEAK_DIRECTION_EPSILON = 0.006f
    const val PEAK_SHARP_RISE_DELTA = 0.10f
    const val PEAK_MIN_INTERVAL_MS = 280L
    const val PEAK_MAX_ACTIVE = 3
    const val EXPANDED_WINDOW_SCALE = 1.6f
    const val HALO_BREATH_PERIOD_MS = 1500L
    const val HALO_RADIUS_SCALE_MIN = 1.12f
    const val HALO_RADIUS_SCALE_MAX = 1.34f
    const val HALO_ALPHA_MIN = 58
    const val HALO_ALPHA_MAX = 118

    data class PeakState(
        val previousLevel: Float = 0f,
        val wasRising: Boolean = false,
        val lastEmitUptimeMs: Long = Long.MIN_VALUE
    )

    data class PeakDecision(
        val emit: Boolean,
        val state: PeakState,
        val emitStrength: Float = 0f
    )

    data class ExpandedWindow(
        val windowSizePx: Int,
        val windowX: Int,
        val windowY: Int,
        val logicalX: Int,
        val logicalY: Int,
        val logicalInsetPx: Int
    )

    data class HaloFrame(
        val radiusScale: Float,
        val alpha: Int
    )

    fun smoothEnvelope(
        current: Float,
        target: Float,
        smoothing: Float = DEFAULT_ENVELOPE_SMOOTHING
    ): Float {
        val alpha = smoothing.coerceIn(0f, 1f)
        val safeCurrent = current.coerceIn(0f, 1f)
        val safeTarget = target.coerceIn(0f, 1f)
        return (safeCurrent + (safeTarget - safeCurrent) * alpha).coerceIn(0f, 1f)
    }

    fun decidePeakEmission(
        level: Float,
        nowUptimeMs: Long,
        activePeakCount: Int,
        state: PeakState,
        activityFloor: Float = PEAK_ACTIVITY_FLOOR,
        directionEpsilon: Float = PEAK_DIRECTION_EPSILON,
        sharpRiseDelta: Float = PEAK_SHARP_RISE_DELTA,
        minIntervalMs: Long = PEAK_MIN_INTERVAL_MS,
        maxActive: Int = PEAK_MAX_ACTIVE
    ): PeakDecision {
        val safeLevel = level.coerceIn(0f, 1f)
        val previousLevel = state.previousLevel.coerceIn(0f, 1f)
        val delta = safeLevel - previousLevel

        val isRising = delta > directionEpsilon
        val isFalling = delta < -directionEpsilon
        val nextWasRising = when {
            isRising -> true
            isFalling -> false
            else -> state.wasRising
        }

        val localPeak = state.wasRising && isFalling && previousLevel >= activityFloor
        val sharpRise = delta >= sharpRiseDelta &&
            safeLevel >= activityFloor &&
            previousLevel <= activityFloor * 0.4f
        val intervalElapsed = state.lastEmitUptimeMs == Long.MIN_VALUE ||
            nowUptimeMs - state.lastEmitUptimeMs >= minIntervalMs
        val hasCapacity = activePeakCount < maxActive
        val shouldEmit = (localPeak || sharpRise) && intervalElapsed && hasCapacity
        val emitStrength = when {
            localPeak -> previousLevel
            sharpRise -> safeLevel
            else -> 0f
        }

        return PeakDecision(
            emit = shouldEmit,
            emitStrength = if (shouldEmit) emitStrength else 0f,
            state = PeakState(
                previousLevel = safeLevel,
                wasRising = nextWasRising,
                lastEmitUptimeMs = if (shouldEmit) nowUptimeMs else state.lastEmitUptimeMs
            )
        )
    }

    fun expandedWindowForLogicalBall(
        logicalX: Int,
        logicalY: Int,
        logicalSizePx: Int,
        screenWidthPx: Int,
        screenHeightPx: Int,
        scale: Float = EXPANDED_WINDOW_SCALE
    ): ExpandedWindow {
        val logicalSize = logicalSizePx.coerceAtLeast(1)
        val windowSize = (logicalSize * scale).roundToInt().coerceAtLeast(logicalSize)
        val inset = logicalInsetForWindow(logicalSize, windowSize)
        val maxLogicalX = (screenWidthPx - logicalSize).coerceAtLeast(0)
        val maxLogicalY = (screenHeightPx - logicalSize).coerceAtLeast(0)
        val safeLogicalX = logicalX.coerceIn(0, maxLogicalX)
        val safeLogicalY = logicalY.coerceIn(0, maxLogicalY)
        return ExpandedWindow(
            windowSizePx = windowSize,
            windowX = safeLogicalX - inset,
            windowY = safeLogicalY - inset,
            logicalX = safeLogicalX,
            logicalY = safeLogicalY,
            logicalInsetPx = inset
        )
    }

    fun expandedWindowPositionForLogical(
        logicalX: Int,
        logicalY: Int,
        logicalSizePx: Int,
        expandedWindowSizePx: Int
    ): Pair<Int, Int> {
        val inset = logicalInsetForWindow(logicalSizePx, expandedWindowSizePx)
        return (logicalX - inset) to (logicalY - inset)
    }

    fun logicalInsetForWindow(logicalSizePx: Int, expandedWindowSizePx: Int): Int = ((expandedWindowSizePx - logicalSizePx).coerceAtLeast(0) / 2)
        .coerceAtLeast(0)

    fun haloFrame(
        nowUptimeMs: Long,
        startedAtUptimeMs: Long,
        periodMs: Long = HALO_BREATH_PERIOD_MS
    ): HaloFrame {
        val safePeriod = periodMs.coerceAtLeast(1L)
        val elapsed = (nowUptimeMs - startedAtUptimeMs).coerceAtLeast(0L)
        val phase = (elapsed % safePeriod).toDouble() / safePeriod.toDouble()
        val pulse = ((1.0 - cos(2.0 * PI * phase)) / 2.0).coerceIn(0.0, 1.0)
        val radiusScale = HALO_RADIUS_SCALE_MIN +
            (HALO_RADIUS_SCALE_MAX - HALO_RADIUS_SCALE_MIN) * pulse.toFloat()
        val alpha = (HALO_ALPHA_MIN + (HALO_ALPHA_MAX - HALO_ALPHA_MIN) * pulse)
            .roundToInt()
            .coerceIn(0, 255)
        return HaloFrame(
            radiusScale = radiusScale,
            alpha = alpha
        )
    }
}
