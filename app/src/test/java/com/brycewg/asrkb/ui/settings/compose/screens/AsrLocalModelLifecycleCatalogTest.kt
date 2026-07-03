// Tests settings local model specs are linked to ASR local vendor lifecycles.
package com.brycewg.asrkb.ui.settings.compose.screens

import com.brycewg.asrkb.asr.AsrLocalModelCatalog
import com.brycewg.asrkb.asr.AsrLocalVendorLifecycles
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.AsrVendorCapability
import com.brycewg.asrkb.asr.AsrVendorRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrLocalModelLifecycleCatalogTest {
    @Test
    fun localAsrVendorsHaveExactlyOneSpecLifecycleAndCatalogEntry() {
        val localVendors = localAsrVendors()
        val specsByVendor = AllAsrLocalModelSpecs.mapNotNull { spec ->
            spec.vendor?.let { vendor -> vendor to spec }
        }.groupBy({ it.first }, { it.second })
        val lifecyclesByVendor = AsrLocalVendorLifecycles.all().groupBy { it.vendor }
        val catalogByVendor = AsrLocalModelCatalog.all().groupBy { it.vendor }

        assertEquals(localVendors, specsByVendor.keys)
        assertEquals(localVendors, lifecyclesByVendor.keys)
        assertEquals(localVendors, catalogByVendor.keys)

        localVendors.forEach { vendor ->
            assertEquals("single spec for $vendor", 1, specsByVendor.getValue(vendor).size)
            assertEquals("single lifecycle for $vendor", 1, lifecyclesByVendor.getValue(vendor).size)
            assertEquals("single catalog entry for $vendor", 1, catalogByVendor.getValue(vendor).size)

            val entry = AsrLocalModelCatalog.entryFor(vendor)
            assertSame(lifecyclesByVendor.getValue(vendor).single(), entry?.lifecycle)
        }
    }

    @Test
    fun onlineVendorsAreExcludedFromLocalModelCatalog() {
        onlineAsrVendors().forEach { vendor ->
            assertNull("catalog entry for online $vendor", AsrLocalModelCatalog.entryFor(vendor))
            assertFalse(
                "local model spec for online $vendor",
                AllAsrLocalModelSpecs.any { it.vendor == vendor }
            )
        }
    }

    @Test
    fun punctuationModelRemainsSettingsOnlyAndNotAsrLifecycle() {
        assertSame(PunctuationModelSpec, AllAsrLocalModelSpecs.single { it.key == "punctuation" })
        assertNull(PunctuationModelSpec.vendor)
        assertFalse(AsrLocalModelCatalog.all().any { it.vendor.id == PunctuationModelSpec.key })
        assertFalse(AsrLocalVendorLifecycles.all().any { it.vendor.id == PunctuationModelSpec.key })
    }

    @Test
    fun clearInstalledAsrLocalModelDeletesBeforeUnloadingMatchingVendor() {
        val events = mutableListOf<String>()
        val result = clearInstalledAsrLocalModel(
            vendor = AsrVendor.FireRedAsr,
            deleteInstalled = { events += "delete" },
            unload = { vendor ->
                events += "unload:${vendor.id}"
                true
            }
        )

        assertTrue(result)
        assertEquals(listOf("delete", "unload:firered_asr"), events)
    }

    @Test
    fun clearInstalledAsrLocalModelReturnsFalseWhenUnloadFailsAfterDelete() {
        val events = mutableListOf<String>()
        val result = clearInstalledAsrLocalModel(
            vendor = AsrVendor.FireRedAsr,
            deleteInstalled = { events += "delete" },
            unload = { vendor ->
                events += "unload:${vendor.id}"
                false
            }
        )

        assertFalse(result)
        assertEquals(listOf("delete", "unload:firered_asr"), events)
    }

    private fun localAsrVendors(): Set<AsrVendor> =
        AsrVendorRegistry.descriptors
            .filter { AsrVendorCapability.LocalRecognition in it.capabilities }
            .map { it.vendor }
            .toSet()

    private fun onlineAsrVendors(): Set<AsrVendor> =
        AsrVendorRegistry.descriptors
            .filterNot { AsrVendorCapability.LocalRecognition in it.capabilities }
            .map { it.vendor }
            .toSet()
}
