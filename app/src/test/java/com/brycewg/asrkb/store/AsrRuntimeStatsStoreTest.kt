// Tests device-local ASR runtime statistics used as future timeout baselines.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.AsrVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrRuntimeStatsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun requestSamplesRemainInsufficientUntilMinimumWindowIsReached() {
        val prefs = Prefs(context)

        repeat(4) { index ->
            prefs.recordAsrRuntimeRequest(
                vendor = AsrVendor.OpenAI,
                audioMs = 1_000L,
                requestMs = 2_000L + index,
                timestampMs = 10_000L + index
            )
        }

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L
        )

        assertEquals(4, snapshot.requestSampleCount)
        assertFalse(snapshot.hasEnoughRequestSamples)
        assertNull(snapshot.p50RequestMs)
        assertNull(snapshot.p90RequestMs)
        assertNull(snapshot.slowRequestMs)
    }

    @Test
    fun snapshotNormalizesSamplesToTargetAudioDuration() {
        val prefs = Prefs(context)
        listOf(
            1_000L to 2_000L,
            2_000L to 4_000L,
            3_000L to 6_000L,
            4_000L to 8_000L,
            5_000L to 10_000L
        ).forEachIndexed { index, (audioMs, requestMs) ->
            prefs.recordAsrRuntimeRequest(
                vendor = AsrVendor.OpenAI,
                audioMs = audioMs,
                requestMs = requestMs,
                timestampMs = 20_000L + index
            )
        }

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 3_000L
        )

        assertTrue(snapshot.hasEnoughRequestSamples)
        assertEquals(5, snapshot.requestSampleCount)
        assertEquals(6_000L, snapshot.p50RequestMs)
        assertEquals(6_000L, snapshot.p90RequestMs)
        assertEquals(6_000L, snapshot.slowRequestMs)
    }

    @Test
    fun snapshotClipsExtremeOutlierBeforeEstimatingPercentiles() {
        val prefs = Prefs(context)
        listOf(2_000L, 2_100L, 2_200L, 2_300L, 60_000L).forEachIndexed { index, requestMs ->
            prefs.recordAsrRuntimeRequest(
                vendor = AsrVendor.DashScope,
                audioMs = 1_000L,
                requestMs = requestMs,
                timestampMs = 30_000L + index
            )
        }

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.DashScope,
            targetAudioMs = 1_000L
        )

        assertTrue(snapshot.hasEnoughRequestSamples)
        assertEquals(2_200L, snapshot.p50RequestMs)
        assertEquals(2_300L, snapshot.p90RequestMs)
        assertEquals(2_300L, snapshot.slowRequestMs)
    }

    @Test
    fun requestSamplesArePrunedToRecentWindow() {
        val prefs = Prefs(context)

        repeat(70) { index ->
            prefs.recordAsrRuntimeRequest(
                vendor = AsrVendor.Soniox,
                audioMs = 1_000L,
                requestMs = 1_000L + index,
                timestampMs = 40_000L + index
            )
        }

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.Soniox,
            targetAudioMs = 1_000L
        )

        assertEquals(64, snapshot.requestSampleCount)
        assertEquals(1_038L, snapshot.p50RequestMs)
        assertEquals(1_063L, snapshot.p90RequestMs)
    }

    @Test
    fun localModelLoadDurationsAppearInSnapshot() {
        val prefs = Prefs(context)

        listOf(1_000L, 1_200L, 1_400L).forEachIndexed { index, loadMs ->
            prefs.recordAsrRuntimeLoad(
                vendor = AsrVendor.SenseVoice,
                loadMs = loadMs,
                timestampMs = 50_000L + index
            )
        }

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.SenseVoice,
            targetAudioMs = 1_000L
        )

        assertEquals(3, snapshot.loadSampleCount)
        assertEquals(1_400L, snapshot.latestLoadMs)
        assertEquals(1_200L, snapshot.p50LoadMs)
        assertEquals(1_400L, snapshot.p90LoadMs)
    }

    @Test
    fun corruptedJsonFallsBackToEmptyStatsAndCanBeReplaced() {
        val prefs = Prefs(context)
        prefs.setPrefString(KEY_ASR_RUNTIME_STATS_JSON, "{bad json")

        val empty = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L
        )
        assertEquals(0, empty.requestSampleCount)
        assertFalse(empty.hasEnoughRequestSamples)

        prefs.recordAsrRuntimeRequest(
            vendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L,
            timestampMs = 60_000L
        )

        val repaired = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L
        )
        assertEquals(1, repaired.requestSampleCount)
    }

    @Test
    fun runtimeStatsIgnoreDisableUsageStatsPrivacyToggle() {
        val prefs = Prefs(context)
        prefs.disableUsageStats = true

        prefs.recordAsrRuntimeRequest(
            vendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L,
            timestampMs = 70_000L
        )

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L
        )
        assertEquals(1, snapshot.requestSampleCount)
    }
}
