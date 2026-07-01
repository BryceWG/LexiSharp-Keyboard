// X-ASR tail-drain JVM regression tests.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XAsrStreamAsrEngineTest {
    @Test
    fun tailDrainStopsAfterConfiguredChunkCount() {
        assertFalse(xAsrShouldStopAfterTailDrainChunk(1, 2))
        assertTrue(xAsrShouldStopAfterTailDrainChunk(2, 2))
        assertTrue(xAsrShouldStopAfterTailDrainChunk(3, 2))
    }
}
