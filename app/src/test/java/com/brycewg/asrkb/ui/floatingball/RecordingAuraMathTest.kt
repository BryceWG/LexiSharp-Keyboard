package com.brycewg.asrkb.ui.floatingball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAuraMathTest {
    @Test
    fun smoothEnvelopeMovesTowardTargetAndClampsInput() {
        assertEquals(0.28f, RecordingAuraMath.smoothEnvelope(0f, 1f), 0.0001f)
        assertEquals(0.4816f, RecordingAuraMath.smoothEnvelope(0.28f, 1f), 0.0001f)
        assertEquals(0.72f, RecordingAuraMath.smoothEnvelope(1f, -1f), 0.0001f)
        assertEquals(1f, RecordingAuraMath.smoothEnvelope(2f, 2f), 0.0001f)
    }

    @Test
    fun peakEmissionFiresOnLocalMaximumAboveActivityFloor() {
        var state = RecordingAuraMath.PeakState()

        var decision = RecordingAuraMath.decidePeakEmission(
            level = 0.10f,
            nowUptimeMs = 0L,
            activePeakCount = 0,
            state = state
        )
        assertFalse(decision.emit)
        state = decision.state

        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.24f,
            nowUptimeMs = 20L,
            activePeakCount = 0,
            state = state
        )
        assertFalse(decision.emit)
        state = decision.state

        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.34f,
            nowUptimeMs = 40L,
            activePeakCount = 0,
            state = state
        )
        assertFalse(decision.emit)
        state = decision.state

        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.28f,
            nowUptimeMs = 60L,
            activePeakCount = 0,
            state = state
        )
        assertTrue(decision.emit)
        assertEquals(0.34f, decision.emitStrength, 0.0001f)
        state = decision.state

        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.40f,
            nowUptimeMs = 120L,
            activePeakCount = 1,
            state = state
        )
        assertFalse(decision.emit)
    }

    @Test
    fun peakEmissionFiresOnSharpRiseWithoutWaitingForFall() {
        val decision = RecordingAuraMath.decidePeakEmission(
            level = 0.32f,
            nowUptimeMs = 100L,
            activePeakCount = 0,
            state = RecordingAuraMath.PeakState()
        )

        assertTrue(decision.emit)
        assertEquals(0.32f, decision.emitStrength, 0.0001f)
    }

    @Test
    fun peakEmissionHonorsThrottleAndActiveLimit() {
        var state = RecordingAuraMath.PeakState(previousLevel = 0.22f, wasRising = true)

        var decision = RecordingAuraMath.decidePeakEmission(
            level = 0.18f,
            nowUptimeMs = 1000L,
            activePeakCount = 0,
            state = state
        )
        assertTrue(decision.emit)
        state = decision.state

        state = RecordingAuraMath.decidePeakEmission(
            level = 0.12f,
            nowUptimeMs = 1100L,
            activePeakCount = 1,
            state = state
        ).state
        state = RecordingAuraMath.decidePeakEmission(
            level = 0.26f,
            nowUptimeMs = 1120L,
            activePeakCount = 1,
            state = state
        ).state
        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.20f,
            nowUptimeMs = 1140L,
            activePeakCount = 1,
            state = state
        )
        assertFalse(decision.emit)
        state = decision.state

        state = RecordingAuraMath.decidePeakEmission(
            level = 0.12f,
            nowUptimeMs = 1290L,
            activePeakCount = 1,
            state = state
        ).state
        state = RecordingAuraMath.decidePeakEmission(
            level = 0.26f,
            nowUptimeMs = 1310L,
            activePeakCount = 1,
            state = state
        ).state
        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.20f,
            nowUptimeMs = 1330L,
            activePeakCount = RecordingAuraMath.PEAK_MAX_ACTIVE,
            state = state
        )
        assertFalse(decision.emit)

        decision = RecordingAuraMath.decidePeakEmission(
            level = 0.20f,
            nowUptimeMs = 1330L,
            activePeakCount = RecordingAuraMath.PEAK_MAX_ACTIVE - 1,
            state = state
        )
        assertTrue(decision.emit)
    }

    @Test
    fun haloFrameBreathesByTimeOnly() {
        val period = RecordingAuraMath.HALO_BREATH_PERIOD_MS
        val start = 10_000L

        val low = RecordingAuraMath.haloFrame(
            nowUptimeMs = start,
            startedAtUptimeMs = start
        )
        val high = RecordingAuraMath.haloFrame(
            nowUptimeMs = start + period / 2,
            startedAtUptimeMs = start
        )
        val nextLow = RecordingAuraMath.haloFrame(
            nowUptimeMs = start + period,
            startedAtUptimeMs = start
        )

        assertEquals(RecordingAuraMath.HALO_RADIUS_SCALE_MIN, low.radiusScale, 0.0001f)
        assertEquals(RecordingAuraMath.HALO_ALPHA_MIN, low.alpha)
        assertEquals(RecordingAuraMath.HALO_RADIUS_SCALE_MAX, high.radiusScale, 0.0001f)
        assertEquals(RecordingAuraMath.HALO_ALPHA_MAX, high.alpha)
        assertEquals(low.radiusScale, nextLow.radiusScale, 0.0001f)
        assertEquals(low.alpha, nextLow.alpha)
    }

    @Test
    fun expandedWindowKeepsLogicalCenterWhenUnclamped() {
        val expanded = RecordingAuraMath.expandedWindowForLogicalBall(
            logicalX = 200,
            logicalY = 300,
            logicalSizePx = 100,
            screenWidthPx = 1000,
            screenHeightPx = 800
        )

        assertEquals(160, expanded.windowSizePx)
        assertEquals(30, expanded.logicalInsetPx)
        assertEquals(170, expanded.windowX)
        assertEquals(270, expanded.windowY)
        assertEquals(250, expanded.windowX + expanded.logicalInsetPx + 50)
        assertEquals(350, expanded.windowY + expanded.logicalInsetPx + 50)
    }

    @Test
    fun expandedWindowUsesFloorInsetForOddExtraSpace() {
        val expanded = RecordingAuraMath.expandedWindowForLogicalBall(
            logicalX = 200,
            logicalY = 300,
            logicalSizePx = 101,
            screenWidthPx = 1000,
            screenHeightPx = 800
        )

        assertEquals(162, expanded.windowSizePx)
        assertEquals(30, expanded.logicalInsetPx)
        assertEquals(170, expanded.windowX)
        assertEquals(270, expanded.windowY)
        assertEquals(200, expanded.windowX + expanded.logicalInsetPx)
        assertEquals(300, expanded.windowY + expanded.logicalInsetPx)

        val (windowX, windowY) = RecordingAuraMath.expandedWindowPositionForLogical(
            logicalX = 200,
            logicalY = 300,
            logicalSizePx = 101,
            expandedWindowSizePx = 162
        )
        assertEquals(170, windowX)
        assertEquals(270, windowY)
    }

    @Test
    fun expandedWindowAllowsHorizontalOverflowButClampsLogicalBall() {
        val left = RecordingAuraMath.expandedWindowForLogicalBall(
            logicalX = -76,
            logicalY = 20,
            logicalSizePx = 100,
            screenWidthPx = 1000,
            screenHeightPx = 800
        )
        assertEquals(0, left.logicalX)
        assertEquals(-30, left.windowX)

        val right = RecordingAuraMath.expandedWindowForLogicalBall(
            logicalX = 980,
            logicalY = 20,
            logicalSizePx = 100,
            screenWidthPx = 1000,
            screenHeightPx = 800
        )
        assertEquals(900, right.logicalX)
        assertEquals(870, right.windowX)
    }

    @Test
    fun expandedWindowClampsBottomLogicalBallIntoScreen() {
        val bottom = RecordingAuraMath.expandedWindowForLogicalBall(
            logicalX = 400,
            logicalY = 760,
            logicalSizePx = 100,
            screenWidthPx = 1000,
            screenHeightPx = 800
        )

        assertEquals(700, bottom.logicalY)
        assertEquals(670, bottom.windowY)
        assertEquals(800, bottom.windowY + bottom.logicalInsetPx + 100)
    }
}
