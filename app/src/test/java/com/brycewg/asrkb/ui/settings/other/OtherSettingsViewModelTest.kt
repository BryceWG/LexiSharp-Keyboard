package com.brycewg.asrkb.ui.settings.other

import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OtherSettingsViewModelTest {
    @Test
    fun automaticReceive_observesPersistedRuntimeProbeResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = Prefs(ApplicationProvider.getApplicationContext()).apply {
                syncClipboardEnabled = true
                syncClipboardReceiveMode = ClipboardSyncReceiveMode.POLLING
                syncClipboardRealtimeSupported = null
            }
            val viewModel = OtherSettingsViewModel(prefs)
            runCurrent()

            prefs.syncClipboardRealtimeSupported = true
            prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.REALTIME
            advanceTimeBy(250L)
            runCurrent()

            assertEquals(ClipboardSyncReceiveMode.REALTIME, viewModel.syncClipboardState.value.receiveMode)
            assertFalse(viewModel.syncClipboardState.value.detectingReceiveMode)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
