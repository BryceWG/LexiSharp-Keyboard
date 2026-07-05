// Tests backup ASR policy decisions without constructing ASR engines.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBackupPolicyTest {
    @Test
    fun disabledBackupReturnsFalseWithoutCheckingAvailability() {
        val shouldUse = shouldUseBackupAsr(
            input(
                backupEnabled = false,
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        assertFalse(shouldUse)
    }

    @Test
    fun samePrimaryAndBackupVendorReturnsFalseWithoutCheckingAvailability() {
        val shouldUse = shouldUseBackupAsr(
            input(
                backupEnabled = true,
                primaryVendor = AsrVendor.OpenAI,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        assertFalse(shouldUse)
    }

    @Test
    fun configuredOnlineBackupReturnsTrue() {
        val shouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenRouter,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { vendor -> vendor == AsrVendor.OpenRouter },
                    localModelReadiness = { error("online backup should not check local model readiness") }
                )
            )
        )

        assertTrue(shouldUse)
    }

    @Test
    fun configuredOnlineBackupDecisionUsesParallel() {
        val decision = resolveBackupAsrDecision(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { vendor -> vendor == AsrVendor.OpenAI },
                    localModelReadiness = { error("online backup should not check local model readiness") }
                )
            )
        )

        assertSame(AsrBackupPolicyDecision.UseParallel, decision)
    }

    @Test
    fun readyLocalBackupDecisionUsesLazyLocalBackupByDefault() {
        val decision = resolveBackupAsrDecision(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online key fields") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertSame(AsrBackupPolicyDecision.UseLazyLocalBackup, decision)
    }

    @Test
    fun readyLocalBackupDecisionUsesParallelWhenResidencyIsResident() {
        val decision = resolveBackupAsrDecision(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                localBackupResidency = BackupAsrLocalResidency.Resident,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not check online key fields") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertSame(AsrBackupPolicyDecision.UseParallel, decision)
    }

    @Test
    fun unconfiguredOnlineBackupReturnsFalse() {
        val shouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.StepAudio,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { false },
                    localModelReadiness = { error("online backup should not check local model readiness") }
                )
            )
        )

        assertFalse(shouldUse)
    }

    @Test
    fun siliconFlowBackupUsesFreeOrKeylessConfigurationPath() {
        val freeOrKeylessShouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SiliconFlow,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { vendor ->
                        isOnlineAsrVendorConfigured(
                            vendor,
                            AsrOnlineConfigurationChecks(
                                hasSfKeys = { true },
                                hasVendorKeys = { error("SiliconFlow should use hasSfKeys") }
                            )
                        )
                    },
                    localModelReadiness = { error("online backup should not check local model readiness") }
                )
            )
        )
        val missingConfigurationShouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SiliconFlow,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { vendor ->
                        isOnlineAsrVendorConfigured(
                            vendor,
                            AsrOnlineConfigurationChecks(
                                hasSfKeys = { false },
                                hasVendorKeys = { error("SiliconFlow should use hasSfKeys") }
                            )
                        )
                    },
                    localModelReadiness = { error("online backup should not check local model readiness") }
                )
            )
        )

        assertTrue(freeOrKeylessShouldUse)
        assertFalse(missingConfigurationShouldUse)
    }

    @Test
    fun missingLocalModelBackupReturnsFalse() {
        val shouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.FunAsrNano,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not use online key fields") },
                    localModelReadiness = { false }
                )
            )
        )

        assertFalse(shouldUse)
    }

    @Test
    fun unavailableLocalBackupDecisionUsesPrimaryOnly() {
        val decision = resolveBackupAsrDecision(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.FunAsrNano,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not use online key fields") },
                    localModelReadiness = { false }
                )
            )
        )

        assertSame(AsrBackupPolicyDecision.UsePrimaryOnly, decision)
    }

    @Test
    fun readyLocalModelBackupReturnsTrue() {
        val shouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                availabilityChecks = availabilityChecks(
                    onlineConfiguration = { error("local backup should not use online key fields") },
                    localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
                )
            )
        )

        assertTrue(shouldUse)
    }

    @Test
    fun availabilityCheckerExceptionReturnsFalse() {
        val shouldUse = shouldUseBackupAsr(
            input(
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.OpenAI,
                availabilityChecks = throwingChecks()
            )
        )

        assertFalse(shouldUse)
    }

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

    private fun throwingChecks(): AsrBackupAvailabilityChecks =
        AsrBackupAvailabilityChecks { error("availability should not be required") }
}
