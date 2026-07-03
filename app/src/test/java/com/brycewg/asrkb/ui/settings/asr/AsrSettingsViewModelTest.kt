// Tests ASR settings ViewModel local model status guards.
package com.brycewg.asrkb.ui.settings.asr

import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LocalModelCheck
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrSettingsViewModelTest {
    @Test
    fun localVendorModelStatusReturnsMissingWhenStatusProviderThrows() {
        val loggedFailures = mutableListOf<Pair<AsrVendor, Throwable>>()
        val failure = IllegalStateException("status failed")

        val status = localVendorModelStatusOrMissing(
            vendor = AsrVendor.SenseVoice,
            modelStatus = { throw failure },
            logFailure = { vendor, throwable -> loggedFailures += vendor to throwable }
        )

        assertSame(LocalModelCheck.Missing, status)
        assertTrue(loggedFailures.single().first == AsrVendor.SenseVoice)
        assertSame(failure, loggedFailures.single().second)
    }
}
