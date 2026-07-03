// Tests ASR supplier availability classification without touching real Android model files.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrVendorAvailabilityTest {
    @Test
    fun missingLocalModelIsNotReadyEvenIfOnlineKeyCheckWouldPass() {
        var onlineCalls = 0

        val readiness = checkAsrVendorAvailability(
            vendor = AsrVendor.FunAsrNano,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = {
                    onlineCalls++
                    true
                },
                localModelReadiness = { false }
            )
        )

        assertSame(AsrVendor.FunAsrNano, readiness.vendor)
        assertEquals(AsrVendorAvailabilityClassification.LocalModelReadiness, readiness.classification)
        assertTrue(readiness.isConstructible)
        assertNull(readiness.onlineConfigured)
        assertEquals(false, readiness.localModelReady)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isUsable)
        assertEquals(0, onlineCalls)
    }

    @Test
    fun readyLocalModelIsReady() {
        val readiness = checkAsrVendorAvailability(
            vendor = AsrVendor.SenseVoice,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { error("local supplier should not use online configuration") },
                localModelReadiness = { vendor -> vendor == AsrVendor.SenseVoice }
            )
        )

        assertEquals(AsrVendorAvailabilityClassification.LocalModelReadiness, readiness.classification)
        assertNull(readiness.onlineConfigured)
        assertEquals(true, readiness.localModelReady)
        assertTrue(readiness.isReady)
        assertTrue(readiness.isUsable)
    }

    @Test
    fun configuredOnlineSupplierIsReady() {
        val readiness = checkAsrVendorAvailability(
            vendor = AsrVendor.OpenRouter,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { vendor -> vendor == AsrVendor.OpenRouter },
                localModelReadiness = { error("online supplier should not use local model readiness") }
            )
        )

        assertEquals(AsrVendorAvailabilityClassification.OnlineConfiguration, readiness.classification)
        assertEquals(true, readiness.onlineConfigured)
        assertNull(readiness.localModelReady)
        assertTrue(readiness.isReady)
        assertTrue(readiness.isUsable)
    }

    @Test
    fun unconfiguredOnlineSupplierIsNotReady() {
        val readiness = checkAsrVendorAvailability(
            vendor = AsrVendor.Zhipu,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { false },
                localModelReadiness = { error("online supplier should not use local model readiness") }
            )
        )

        assertEquals(AsrVendorAvailabilityClassification.OnlineConfiguration, readiness.classification)
        assertEquals(false, readiness.onlineConfigured)
        assertNull(readiness.localModelReady)
        assertFalse(readiness.isReady)
        assertFalse(readiness.isUsable)
    }

    @Test
    fun siliconFlowKeepsFreeOrKeylessConfigurationPath() {
        val freeOrKeyConfigured = isOnlineAsrVendorConfigured(
            AsrVendor.SiliconFlow,
            AsrOnlineConfigurationChecks(
                hasSfKeys = { true },
                hasVendorKeys = { error("SiliconFlow should use hasSfKeys") }
            )
        )
        val missingSfConfiguration = isOnlineAsrVendorConfigured(
            AsrVendor.SiliconFlow,
            AsrOnlineConfigurationChecks(
                hasSfKeys = { false },
                hasVendorKeys = { error("SiliconFlow should use hasSfKeys") }
            )
        )

        assertTrue(freeOrKeyConfigured)
        assertFalse(missingSfConfiguration)
    }

    @Test
    fun otherOnlineSuppliersKeepVendorKeyHelperPath() {
        val checkedVendors = mutableListOf<AsrVendor>()

        val openRouterConfigured = isOnlineAsrVendorConfigured(
            AsrVendor.OpenRouter,
            AsrOnlineConfigurationChecks(
                hasSfKeys = { error("OpenRouter should use hasVendorKeys") },
                hasVendorKeys = { vendor ->
                    checkedVendors += vendor
                    vendor == AsrVendor.OpenRouter
                }
            )
        )
        val stepAudioConfigured = isOnlineAsrVendorConfigured(
            AsrVendor.StepAudio,
            AsrOnlineConfigurationChecks(
                hasSfKeys = { error("StepAudio should use hasVendorKeys") },
                hasVendorKeys = { vendor ->
                    checkedVendors += vendor
                    vendor == AsrVendor.OpenRouter
                }
            )
        )

        assertTrue(openRouterConfigured)
        assertFalse(stepAudioConfigured)
        assertEquals(listOf(AsrVendor.OpenRouter, AsrVendor.StepAudio), checkedVendors)
    }

    @Test
    fun registryDescriptorsExposeConsistentAvailabilityClassificationAndChecks() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val isLocal = AsrVendorCapability.LocalRecognition in descriptor.capabilities
            assertEquals(
                "classification for ${descriptor.vendor}",
                if (isLocal) {
                    AsrVendorAvailabilityClassification.LocalModelReadiness
                } else {
                    AsrVendorAvailabilityClassification.OnlineConfiguration
                },
                descriptor.availabilityClassification
            )
            assertEquals(
                "classifier for ${descriptor.vendor}",
                descriptor.availabilityClassification,
                classifyAsrVendorAvailability(descriptor.vendor)
            )
        }

        val checkers = AsrVendorAvailabilityCheckers(
            onlineConfiguration = { vendor -> vendor == AsrVendor.OpenAI },
            localModelReadiness = { vendor -> vendor == AsrVendor.XAsr }
        )

        assertTrue(AsrVendorRegistry.descriptorFor(AsrVendor.OpenAI).checkAvailability(checkers).isUsable)
        assertFalse(AsrVendorRegistry.descriptorFor(AsrVendor.Gemini).checkAvailability(checkers).isUsable)
        assertTrue(AsrVendorRegistry.descriptorFor(AsrVendor.XAsr).checkAvailability(checkers).isUsable)
        assertFalse(AsrVendorRegistry.descriptorFor(AsrVendor.Parakeet).checkAvailability(checkers).isUsable)
    }
}
