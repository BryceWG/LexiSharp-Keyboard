// Tests the ParallelAsrEngine factory-level policy decision without Android engine construction.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrParallelEngineFactoryTest {
    private val factory = AsrParallelEngineFactory()

    @Test
    fun backupDisabledPlansPrimaryOnlyWithoutCheckingAvailability() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                backupEnabled = false,
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        assertPrimaryOnly(plan)
    }

    @Test
    fun samePrimaryAndBackupPlansPrimaryOnlyWithoutCheckingAvailability() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.OpenAI,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        assertPrimaryOnly(plan)
    }

    @Test
    fun unconfiguredBackupPlansPrimaryOnly() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.StepAudio,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { false },
                    localModelReadiness = { error("online backup should not check local readiness") }
                )
            )
        )

        assertPrimaryOnly(plan)
    }

    @Test
    fun configuredBackupPlansParallelEngine() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { vendor -> vendor == AsrVendor.OpenAI },
                    localModelReadiness = { error("online backup should not check local readiness") }
                )
            )
        )

        assertParallel(plan)
        assertSame(AsrVendor.Volc, plan.primaryVendor)
        assertSame(AsrVendor.OpenAI, plan.backupVendor)
    }

    @Test
    fun readyLocalBackupDefaultsToLazyLocalBackupPlan() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online configuration") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertLazyLocalBackup(plan)
        assertSame(AsrVendor.Volc, plan.primaryVendor)
        assertSame(AsrVendor.SenseVoice, plan.backupVendor)
    }

    @Test
    fun readyLocalBackupWithResidentModePlansParallelEngine() {
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                localBackupResidency = BackupAsrLocalResidency.Resident,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online configuration") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertParallel(plan)
        assertSame(AsrVendor.Volc, plan.primaryVendor)
        assertSame(AsrVendor.SenseVoice, plan.backupVendor)
    }

    @Test
    fun localBackupReadinessIsDelegatedToPolicySeam() {
        val missingLocalPlan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online configuration") },
                    localModelReadiness = { false }
                )
            )
        )
        val readyLocalPlan = factory.resolvePlan(
            backupPolicyInput = input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online configuration") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertPrimaryOnly(missingLocalPlan)
        assertLazyLocalBackup(readyLocalPlan)
    }

    @Test
    fun externalPcmInputFlagIsPreservedInPlan() {
        val plan = factory.resolvePlan(
            backupPolicyInput = configuredInput(
                primaryVendor = AsrVendor.Soniox,
                backupVendor = AsrVendor.OpenRouter
            ),
            externalPcmInput = true
        )

        assertParallel(plan)
        assertTrue(plan.externalPcmInput)
        assertSame(AsrVendor.Soniox, plan.primaryVendor)
        assertSame(AsrVendor.OpenRouter, plan.backupVendor)
    }

    @Test
    fun approvedPlanCreatesThroughConstructorSeamWithoutAndroidEngine() {
        val fakeEngine = FakeStreamingAsrEngine()
        var capturedPlan: AsrParallelEnginePlan? = null
        val seamFactory = AsrParallelEngineFactory(
            AsrParallelEngineConstructorTable { plan, _ ->
                capturedPlan = plan
                fakeEngine
            }
        )
        val plan = factory.resolvePlan(configuredInput())

        val engine = seamFactory.createPlanned(plan) {
            throw AssertionError("real ParallelAsrEngine should not be constructed in this test")
        }

        assertSame(fakeEngine, engine)
        assertEquals(plan, capturedPlan)
    }

    @Test
    fun rejectedPlanDoesNotInvokeConstructorSeam() {
        val seamFactory = AsrParallelEngineFactory(
            AsrParallelEngineConstructorTable { _, _ ->
                error("constructor seam should not be invoked for a non-parallel plan")
            }
        )
        val plan = factory.resolvePlan(
            backupPolicyInput = input(
                backupEnabled = false,
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        val engine = seamFactory.createPlanned(plan) {
            error("engine factory should not be invoked for a non-parallel plan")
        }

        assertNull(engine)
    }

    private fun assertParallel(plan: AsrParallelEnginePlan) {
        assertSame(AsrParallelEngineDecision.UseParallel, plan.decision)
        assertTrue(plan.shouldUseParallel)
        assertTrue(plan.shouldUseBackupWrapper)
        assertEquals("ParallelAsrEngine", plan.engineClassName)
    }

    private fun assertLazyLocalBackup(plan: AsrParallelEnginePlan) {
        assertSame(AsrParallelEngineDecision.UseLazyLocalBackup, plan.decision)
        assertFalse(plan.shouldUseParallel)
        assertTrue(plan.shouldUseBackupWrapper)
        assertEquals("LazyLocalBackupAsrEngine", plan.engineClassName)
    }

    private fun assertPrimaryOnly(plan: AsrParallelEnginePlan) {
        assertSame(AsrParallelEngineDecision.UsePrimaryOnly, plan.decision)
        assertFalse(plan.shouldUseParallel)
        assertFalse(plan.shouldUseBackupWrapper)
        assertNull(plan.engineClassName)
    }

    private fun configuredInput(
        primaryVendor: AsrVendor = AsrVendor.Volc,
        backupVendor: AsrVendor = AsrVendor.OpenAI
    ): AsrBackupPolicyInput = input(
        primaryVendor = primaryVendor,
        backupVendor = backupVendor,
        availabilityChecks = availabilityChecks(
            onlineConfiguration = { true },
            localModelReadiness = { true }
        )
    )

    private fun input(
        backupEnabled: Boolean = true,
        primaryVendor: AsrVendor,
        backupVendor: AsrVendor,
        localBackupResidency: BackupAsrLocalResidency = BackupAsrLocalResidency.OnDemand,
        availabilityChecks: AsrBackupAvailabilityChecks
    ): AsrBackupPolicyInput = AsrBackupPolicyInput(
        backupEnabled = backupEnabled,
        primaryVendor = primaryVendor,
        backupVendor = backupVendor,
        localBackupResidency = localBackupResidency,
        availabilityChecks = availabilityChecks
    )

    private fun availabilityChecks(
        onlineConfiguration: (AsrVendor) -> Boolean,
        localModelReadiness: (AsrVendor) -> Boolean
    ): AsrBackupAvailabilityChecks = AsrBackupAvailabilityChecks { vendor ->
        checkAsrVendorAvailability(
            vendor = vendor,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = onlineConfiguration,
                localModelReadiness = localModelReadiness
            )
        )
    }

    private fun throwingChecks(): AsrBackupAvailabilityChecks = AsrBackupAvailabilityChecks { error("availability should not be required") }

    private class FakeStreamingAsrEngine : StreamingAsrEngine {
        override val isRunning: Boolean = false

        override fun start() = Unit

        override fun stop() = Unit
    }
}
