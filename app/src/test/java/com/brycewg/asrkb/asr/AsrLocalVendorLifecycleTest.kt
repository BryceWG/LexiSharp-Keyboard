// Tests local ASR supplier lifecycle routing without touching real model files.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrLocalVendorLifecycleTest {
    @Test
    fun productionLifecycleExistsForEveryLocalSupplierOnly() {
        val localVendors = setOf(
            AsrVendor.SenseVoice,
            AsrVendor.FunAsrNano,
            AsrVendor.Qwen3Asr,
            AsrVendor.Parakeet,
            AsrVendor.FireRedAsr,
            AsrVendor.XAsr
        )

        assertEquals(localVendors, AsrLocalVendorLifecycles.all().map { it.vendor }.toSet())
        AsrVendor.entries.forEach { vendor ->
            val lifecycle = AsrLocalVendorLifecycles.lifecycleFor(vendor)
            val descriptorLifecycle = AsrVendorRegistry.descriptorFor(vendor).localLifecycle
            if (vendor in localVendors) {
                assertSame("lifecycle for $vendor", lifecycle, descriptorLifecycle)
                assertTrue("local vendor for $vendor", AsrLocalVendorLifecycles.isLocalVendor(vendor))
            } else {
                assertNull("online lifecycle for $vendor", lifecycle)
                assertNull("online descriptor lifecycle for $vendor", descriptorLifecycle)
                assertFalse("online vendor for $vendor", AsrLocalVendorLifecycles.isLocalVendor(vendor))
            }
        }
    }

    @Test
    fun fakeRegistryRoutesPreloadAndUnloadHooks() {
        val lifecycle = FakeLifecycle(vendor = AsrVendor.SenseVoice)
        val registry = AsrLocalVendorLifecycleRegistry(listOf(lifecycle))
        val request = AsrLocalVendorPreloadRequest.forTest(
            suppressToastOnStart = true,
            forImmediateUse = true
        )

        assertTrue(registry.preload(AsrVendor.SenseVoice, request))
        assertFalse(registry.preload(AsrVendor.OpenAI, request))
        assertTrue(registry.unload(AsrVendor.SenseVoice))
        assertFalse(registry.unload(AsrVendor.OpenAI))

        assertEquals(listOf(request), lifecycle.preloadRequests)
        assertEquals(1, lifecycle.unloadCalls)
        assertTrue(lifecycle.preloadRequests.single().suppressToastOnStart)
        assertTrue(lifecycle.preloadRequests.single().forImmediateUse)
    }

    @Test
    fun fakeRegistryKeepsPreparedAndReadyDistinct() {
        val lifecycle = FakeLifecycle(
            vendor = AsrVendor.XAsr,
            prepared = true,
            ready = false
        )
        val registry = AsrLocalVendorLifecycleRegistry(listOf(lifecycle))

        assertTrue(registry.isPrepared(AsrVendor.XAsr))
        assertFalse(registry.isReady(AsrVendor.XAsr))
        assertFalse(registry.isPrepared(AsrVendor.DashScope))
        assertFalse(registry.isReady(AsrVendor.DashScope))
    }

    private class FakeLifecycle(
        override val vendor: AsrVendor,
        private val prepared: Boolean = false,
        private val ready: Boolean = false,
        private val modelStatus: LocalModelCheck<*> = LocalModelCheck.Missing
    ) : AsrLocalVendorLifecycle {
        val preloadRequests = mutableListOf<AsrLocalVendorPreloadRequest>()
        var unloadCalls = 0

        override fun preload(request: AsrLocalVendorPreloadRequest) {
            preloadRequests += request
        }

        override fun unload() {
            unloadCalls++
        }

        override fun isPrepared(): Boolean = prepared

        override fun isReady(): Boolean = ready

        override fun modelStatus(context: Context, prefs: Prefs): LocalModelCheck<*> = modelStatus
    }
}
