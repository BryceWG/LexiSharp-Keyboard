// Guards that device-local ASR runtime stats never enter settings backup.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.AsrVendor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrRuntimeStatsBackupGuardTest {
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
    fun runtimeStatsAreNotExported() {
        val prefs = Prefs(context)
        prefs.recordAsrRuntimeRequest(
            vendor = AsrVendor.OpenAI,
            audioMs = 1_000L,
            requestMs = 2_000L,
            timestampMs = 1L
        )

        val exported = JSONObject(prefs.exportJsonString())

        assertFalse(exported.has(KEY_ASR_RUNTIME_STATS_JSON))
    }

    @Test
    fun runtimeStatsAreNotImportedFromBackupPayload() {
        val prefs = Prefs(context)
        val payload = JSONObject()
            .put("_version", 1)
            .put(KEY_ASR_RUNTIME_STATS_JSON, """{"vendors":{"openai":{"requestSamples":[{"audioMs":1000,"requestMs":2000,"timestampMs":1}]}}}""")
            .toString()

        assertTrue(prefs.importJsonString(payload))

        val snapshot = prefs.getAsrRuntimeStatsSnapshot(
            vendor = AsrVendor.OpenAI,
            targetAudioMs = 1_000L
        )
        assertEquals(0, snapshot.requestSampleCount)
    }
}
