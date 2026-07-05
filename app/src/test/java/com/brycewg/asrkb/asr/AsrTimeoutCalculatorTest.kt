// Tests ASR timeout calculation with runtime statistics snapshots.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.AsrRuntimeVendorSnapshot
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrTimeoutCalculatorTest {
    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun processingTimeoutFallsBackToStaticTimeoutWhenSamplesAreInsufficient() {
        val snapshot = snapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 30_000L,
            requestSampleCount = 4,
            slowRequestMs = 28_000L
        )

        val timeoutMs = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 30_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = snapshot
        )

        assertEquals(22_000L, timeoutMs)
    }

    @Test
    fun processingTimeoutUsesDynamicSlowBaselinePlusSafetyMargin() {
        val timeoutMs = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 30_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 30_000L,
                slowRequestMs = 18_000L
            )
        )

        assertEquals(20_000L, timeoutMs)
    }

    @Test
    fun dynamicProcessingTimeoutIsClampedToVendorStaticBounds() {
        val tooFast = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 5_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 5_000L,
                slowRequestMs = 1_500L
            )
        )
        val tooSlow = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 5_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 5_000L,
                slowRequestMs = 90_000L
            )
        )

        assertEquals(10_000L, tooFast)
        assertEquals(40_000L, tooSlow)
    }

    @Test
    fun backupSwitchPlanUsesStaticFallbackWhenSamplesAreInsufficient() {
        val sensitive = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 2,
            primaryStatsSnapshot = null
        )
        val balanced = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 1,
            primaryStatsSnapshot = null
        )
        val relaxed = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            primaryStatsSnapshot = null
        )

        assertEquals(3_000L, sensitive.switchDeadlineMs)
        assertEquals(5_000L, balanced.switchDeadlineMs)
        assertEquals(8_000L, relaxed.switchDeadlineMs)
        assertEquals(true, sensitive.usedStaticFallback)
    }

    @Test
    fun backupSwitchPlanStaticFallbackAddsAudioLengthAndModeAdjustments() {
        val onlineFileMediumAudio = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 10_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = false,
            sensitivityTier = 2
        )
        val localStreamingLongAudio = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 30_000L,
            primaryVendor = AsrVendor.XAsr,
            primaryStreaming = true,
            sensitivityTier = 1
        )
        val localFileExtraLongAudio = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 70_000L,
            primaryVendor = AsrVendor.FunAsrNano,
            primaryStreaming = false,
            sensitivityTier = 0
        )

        assertEquals(5_000L, onlineFileMediumAudio.switchDeadlineMs)
        assertEquals(9_000L, localStreamingLongAudio.switchDeadlineMs)
        assertEquals(16_000L, localFileExtraLongAudio.switchDeadlineMs)
    }

    @Test
    fun backupSwitchPlanUsesP50P90DerivedBaselinesWhenSamplesAreEnough() {
        val snapshot = snapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L,
            p50RequestMs = 6_000L,
            p90RequestMs = 14_000L
        )

        val relaxed = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            primaryStatsSnapshot = snapshot
        )
        val balanced = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 1,
            primaryStatsSnapshot = snapshot
        )
        val sensitive = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 2,
            primaryStatsSnapshot = snapshot
        )

        assertEquals(14_000L, relaxed.switchDeadlineMs)
        assertEquals(11_000L, balanced.switchDeadlineMs)
        assertEquals(8_000L, sensitive.switchDeadlineMs)
        assertEquals(false, sensitive.usedStaticFallback)
    }

    @Test
    fun backupSwitchPlanClampsExtremeDynamicBaselines() {
        val tooFast = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 2,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 1_000L,
                p50RequestMs = 300L,
                p90RequestMs = 900L
            )
        )
        val tooSlow = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 1_000L,
                p50RequestMs = 100_000L,
                p90RequestMs = 120_000L
            )
        )

        assertEquals(3_000L, tooFast.switchDeadlineMs)
        assertEquals(40_000L, tooSlow.switchDeadlineMs)
    }

    @Test
    fun backupSwitchPlanExposesLazyBackupStartInputs() {
        val sensitive = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 2,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 1_000L,
                p90LoadMs = 5_000L
            )
        )
        val relaxed = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 1_000L,
                p90LoadMs = 5_000L
            )
        )

        assertEquals(3_000L, sensitive.switchDeadlineMs)
        assertEquals(1_500L, sensitive.lazyBackupStartAtMs)
        assertEquals(5_000L, sensitive.lazyEstimatedBackupReadyMs)
        assertEquals(1.0, sensitive.lazyResidencyFactor, 0.0)
        assertEquals(5_750L, relaxed.lazyBackupStartAtMs)
        assertEquals(0.45, relaxed.lazyResidencyFactor, 0.0)
    }

    @Test
    fun backupSwitchPlanUsesP90ThenLatestThenFallbackForLazyBackupReadyEstimate() {
        val p90Preferred = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 1_000L,
                latestLoadMs = 8_000L,
                p90LoadMs = 3_000L
            )
        )
        val latestFallback = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 0,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 1_000L,
                latestLoadMs = 2_000L,
                p90LoadMs = null
            )
        )
        val conservativeFallback = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            sensitivityTier = 2,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = null
        )

        assertEquals(3_000L, p90Preferred.lazyEstimatedBackupReadyMs)
        assertEquals(6_650L, p90Preferred.lazyBackupStartAtMs)
        assertEquals(2_000L, latestFallback.lazyEstimatedBackupReadyMs)
        assertEquals(7_100L, latestFallback.lazyBackupStartAtMs)
        assertEquals(1_500L, conservativeFallback.lazyEstimatedBackupReadyMs)
        assertEquals(1_500L, conservativeFallback.lazyBackupStartAtMs)
    }

    @Test
    fun runtimeSnapshotNormalizationChangesDynamicTimeoutForTargetAudioDuration() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        repeat(5) { index ->
            prefs.recordAsrRuntimeRequest(
                vendor = AsrVendor.OpenAI,
                audioMs = 10_000L,
                requestMs = 100_000L,
                timestampMs = 10_000L + index
            )
        }

        val shortTimeout = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 1_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 1_000L)
        )
        val longTimeout = AsrTimeoutCalculator.calculateProcessingTimeoutMs(
            audioMs = 5_000L,
            vendor = AsrVendor.OpenAI,
            statsSnapshot = prefs.getAsrRuntimeStatsSnapshot(AsrVendor.OpenAI, 5_000L)
        )

        assertEquals(12_000L, shortTimeout)
        assertEquals(40_000L, longTimeout)
    }

    @Test
    fun parallelProcessingTimeoutKeepsBackupMargin() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 5_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 5_000L,
                slowRequestMs = 18_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseParallel,
            backupVendor = AsrVendor.DashScope,
            backupStatsSnapshot = null,
            sensitivityTier = 1
        )

        assertEquals(22_000L, timeoutMs)
    }

    @Test
    fun parallelProcessingTimeoutUsesSlowerBackupDynamicBudgetPlusMargin() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 5_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 5_000L,
                slowRequestMs = 12_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseParallel,
            backupVendor = AsrVendor.DashScope,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.DashScope,
                targetAudioMs = 5_000L,
                slowRequestMs = 30_000L
            ),
            sensitivityTier = 1
        )

        assertEquals(34_000L, timeoutMs)
    }

    @Test
    fun parallelProcessingTimeoutLetsResidentLocalBackupFinishShortAudio() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = null,
            backupStrategy = AsrParallelEngineDecision.UseParallel,
            backupVendor = AsrVendor.FunAsrNano,
            backupStatsSnapshot = null,
            sensitivityTier = 1
        )

        assertEquals(17_000L, timeoutMs)
    }

    @Test
    fun parallelProcessingTimeoutFallsBackToBackupStaticProfileWhenSnapshotIsMissing() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 50_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 50_000L,
                slowRequestMs = 12_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseParallel,
            backupVendor = AsrVendor.DashScope,
            backupStatsSnapshot = null,
            sensitivityTier = 1
        )

        assertEquals(32_000L, timeoutMs)
    }

    @Test
    fun parallelProcessingTimeoutUsesDefaultStaticProfileWhenBackupVendorIsMissing() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 50_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 50_000L,
                slowRequestMs = 12_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseParallel,
            backupVendor = null,
            backupStatsSnapshot = null,
            sensitivityTier = 1
        )

        assertEquals(32_000L, timeoutMs)
    }

    @Test
    fun lazyLocalBackupProcessingTimeoutCoversDeadlineLoadInferenceAndMargin() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = null,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupVendor = AsrVendor.SenseVoice,
            backupStatsSnapshot = null,
            sensitivityTier = 2,
            primaryStreaming = true
        )

        assertEquals(16_500L, timeoutMs)
    }

    @Test
    fun lazyLocalBackupProcessingTimeoutUsesPrimaryStreamingModeInDeadlinePlan() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 1_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = null,
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupVendor = AsrVendor.SenseVoice,
            backupStatsSnapshot = null,
            sensitivityTier = 2,
            primaryStreaming = false
        )

        assertEquals(17_500L, timeoutMs)
    }

    @Test
    fun lazyLocalBackupProcessingTimeoutUsesDynamicSwitchDeadlinePlan() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 10_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 10_000L,
                slowRequestMs = null,
                p50RequestMs = 20_000L,
                p90RequestMs = 20_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupVendor = AsrVendor.SenseVoice,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 10_000L,
                p90LoadMs = 5_000L
            ),
            sensitivityTier = 1
        )

        assertEquals(41_000L, timeoutMs)
    }

    @Test
    fun lazyLocalBackupProcessingTimeoutCoversLoadAndWholeBackupInferenceWithAbsoluteCap() {
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = 10_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 10_000L,
                slowRequestMs = 16_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupVendor = AsrVendor.FunAsrNano,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.FunAsrNano,
                targetAudioMs = 10_000L,
                slowRequestMs = 130_000L,
                p90LoadMs = 50_000L
            ),
            sensitivityTier = 1
        )

        assertEquals(120_000L, timeoutMs)
    }

    @Test
    fun lazyLocalBackupStartPlanStartsEarlierWhenMeasuredLoadWouldMissDeadline() {
        val plan = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = 10_000L,
            primaryVendor = AsrVendor.OpenAI,
            primaryStreaming = true,
            primaryStatsSnapshot = snapshot(
                vendor = AsrVendor.OpenAI,
                targetAudioMs = 10_000L,
                p50RequestMs = 20_000L,
                p90RequestMs = 20_000L
            ),
            backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
            backupStatsSnapshot = snapshot(
                vendor = AsrVendor.SenseVoice,
                targetAudioMs = 10_000L,
                p90LoadMs = 5_000L
            ),
            sensitivityTier = 1
        )

        assertEquals(20_000L, plan.switchDeadlineMs)
        assertEquals(16_250L, plan.lazyBackupStartAtMs)
    }

    private fun snapshot(
        vendor: AsrVendor,
        targetAudioMs: Long,
        requestSampleCount: Int = 5,
        slowRequestMs: Long? = null,
        p50RequestMs: Long? = slowRequestMs,
        p90RequestMs: Long? = slowRequestMs,
        p90LoadMs: Long? = null,
        latestLoadMs: Long? = p90LoadMs
    ): AsrRuntimeVendorSnapshot = AsrRuntimeVendorSnapshot(
        vendorId = vendor.id,
        targetAudioMs = targetAudioMs,
        requestSampleCount = requestSampleCount,
        hasEnoughRequestSamples = requestSampleCount >= 5,
        p50RequestMs = p50RequestMs,
        p90RequestMs = p90RequestMs,
        slowRequestMs = slowRequestMs,
        loadSampleCount = if (latestLoadMs == null && p90LoadMs == null) 0 else 5,
        latestLoadMs = latestLoadMs,
        p50LoadMs = p90LoadMs,
        p90LoadMs = p90LoadMs
    )
}
