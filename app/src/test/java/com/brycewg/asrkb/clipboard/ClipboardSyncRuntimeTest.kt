package com.brycewg.asrkb.clipboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime 编排 Direct/Bridge 会话与 realtime。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardSyncRuntimeTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun activateDirect_whenEnabledAndScreenOn_startsSession() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.POLLING)

        controller.activateSession()

        assertTrue(fake.started)
        assertEquals(ClipboardSyncRuntimePhase.POLLING, controller.phase)
    }

    @Test
    fun activateDirect_whenScreenOff_doesNotStart() {
        val fake = FakeRuntimeSession()
        val controller = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { ClipboardSyncReceiveMode.POLLING },
            isScreenInteractive = { false },
            session = fake,
            scope = testScope
        )

        controller.activateSession()

        assertFalse(fake.started)
        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
    }

    @Test
    fun deactivateDirect_stopsSession() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.POLLING)
        controller.activateSession()
        assertTrue(fake.started)

        controller.deactivateSession()

        assertTrue(fake.stopped)
        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
    }

    @Test
    fun syncDisabled_stopsSessionPromptly() {
        var enabled = true
        val fake = FakeRuntimeSession()
        val controller = ClipboardSyncRuntime(
            syncEnabled = { enabled },
            receiveMode = { ClipboardSyncReceiveMode.POLLING },
            isScreenInteractive = { true },
            session = fake,
            scope = testScope
        )
        controller.activateSession()

        enabled = false
        controller.notifyConfigChanged()

        assertTrue(fake.stopped)
        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
    }

    @Test
    fun configChanged_whileActive_invalidatesReceivePath() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.POLLING)
        controller.activateSession()
        fake.invalidated = false

        controller.notifyConfigChanged()

        assertTrue(fake.invalidated)
        assertTrue(fake.started)
        assertEquals(ClipboardSyncRuntimePhase.POLLING, controller.phase)
    }

    @Test
    fun screenOff_whileActive_stopsAndRequiresNextImeShow() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.POLLING)
        controller.activateSession()

        controller.onScreenOff()
        assertTrue(fake.stopped)
        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)

        fake.reset()
        controller.onScreenOn()
        assertFalse(fake.started)

        controller.activateSession()
        assertTrue(fake.started)
    }

    @Test
    fun uploadOnly_mode_startsObserverWithoutPolling() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.OFF)

        controller.activateSession()

        assertTrue(fake.started)
        assertFalse(fake.pollingRequested)
        assertEquals(ClipboardSyncRuntimePhase.UPLOAD_ONLY, controller.phase)
    }

    @Test
    fun realtimeMode_startsRealtimeClient_notImmediateFailStub() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = false
        )

        controller.activateSession()

        assertTrue(fake.realtimeStarted)
        assertEquals(ClipboardSyncRuntimePhase.CONNECTING, controller.phase)

        fake.emitRealtimeConnected()
        assertEquals(ClipboardSyncRuntimePhase.REALTIME, controller.phase)
        assertFalse(fake.realtimeStopped)
        assertFalse(fake.pollingRequested)
        assertEquals(1, fake.catchUpCount)
    }

    @Test
    fun realtimeProfile_appliesPayloadWithoutHttpCatchUp() {
        val fake = FakeRuntimeSession()
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.REALTIME)
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.resetEffects()
        val profileJson = """{"text":"remote","type":"Text"}"""

        fake.emitRemoteProfile(profileJson)

        assertEquals(listOf(profileJson), fake.appliedProfiles)
        assertEquals(0, fake.catchUpCount)
    }

    @Test
    fun invalidRealtimeProfile_fallsBackToHttpCatchUp() {
        val fake = FakeRuntimeSession().apply { applySucceeds = false }
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.REALTIME)
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.resetEffects()

        fake.emitRemoteProfile("""{"type":"Text"}""")

        assertEquals(1, fake.catchUpCount)
    }

    @Test
    fun failedRealtimeProfile_afterCredentialsChange_doesNotCatchUp() {
        val fake = FakeRuntimeSession().apply { deferApplyResult = true }
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.REALTIME)
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.resetEffects()
        fake.emitRemoteProfile("""{"text":"stale","type":"Text"}""")

        controller.notifyConfigChanged()
        fake.completeDeferredApply(false)

        assertEquals(0, fake.catchUpCount)
    }

    @Test
    fun failedRealtimeProfile_afterStop_doesNotCatchUp() {
        val fake = FakeRuntimeSession().apply { deferApplyResult = true }
        val controller = controller(fake, receiveMode = ClipboardSyncReceiveMode.REALTIME)
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.resetEffects()
        fake.emitRemoteProfile("""{"text":"stale","type":"Text"}""")

        controller.deactivateSession()
        fake.completeDeferredApply(false)

        assertEquals(0, fake.catchUpCount)
    }

    @Test
    fun unsupportedRealtime_usesPollingFallback() {
        val fake = FakeRuntimeSession()
        val runtime = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { ClipboardSyncReceiveMode.REALTIME },
            isScreenInteractive = { true },
            session = fake,
            scope = testScope
        )

        runtime.activateSession()
        fake.emitRealtimeDisconnected(RealtimeUnavailableException(404))

        assertTrue(fake.pollingRequested)
        assertEquals(ClipboardSyncRuntimePhase.POLLING_FALLBACK, runtime.phase)
    }

    @Test
    fun keepBackground_screenOff_doesNotStopRealtime() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.reset()

        controller.onScreenOff()

        assertTrue(fake.paused)
        assertFalse(fake.realtimeStopped)
        assertEquals(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT, controller.phase)

        fake.reset()
        controller.onScreenOn()
        assertTrue(fake.resumed)
        assertEquals(ClipboardSyncRuntimePhase.REALTIME, controller.phase)
    }

    @Test
    fun keepBackground_disconnectThenScreenOn_resumesAndCatchesUpAfterReconnect() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.emitRealtimeDisconnected()
        assertTrue(fake.pollingRequested)
        controller.onScreenOff()
        fake.resetEffects()
        val pollingStopCount = fake.pollingStopCount

        controller.onScreenOn()
        assertEquals(pollingStopCount + 1, fake.pollingStopCount)
        fake.emitRealtimeConnected()

        assertTrue(fake.started)
        assertEquals(1, fake.catchUpCount)
        assertEquals(ClipboardSyncRuntimePhase.REALTIME, controller.phase)
    }

    @Test
    fun keepBackground_fallbackReconnectWhileScreenOff_stopsPollingOnScreenOn() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.emitRealtimeDisconnected()
        assertTrue(fake.pollingRequested)
        controller.onScreenOff()
        fake.resetEffects()

        controller.onNetworkAvailable()
        fake.emitRealtimeConnected()

        assertEquals(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT, controller.phase)
        assertEquals(0, fake.startCount)
        assertEquals(0, fake.catchUpCount)
        assertFalse(fake.resumed)

        controller.onScreenOn()

        assertTrue(fake.resumed)
        assertFalse(fake.pollingRequested)
        assertEquals(1, fake.catchUpCount)
        assertEquals(ClipboardSyncRuntimePhase.REALTIME, controller.phase)
    }

    @Test
    fun unsupportedRealtimeWhileScreenOff_defersPollingUntilScreenOn() {
        val fake = FakeRuntimeSession()
        val runtime = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { ClipboardSyncReceiveMode.REALTIME },
            keepBackgroundRealtimeEnabled = { true },
            isScreenInteractive = { true },
            session = fake,
            scope = testScope
        )
        runtime.activateSession()
        runtime.onScreenOff()
        fake.resetEffects()

        fake.emitRealtimeDisconnected(RealtimeUnavailableException(404))

        assertEquals(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT, runtime.phase)
        assertEquals(0, fake.startCount)

        runtime.onScreenOn()
        assertEquals(ClipboardSyncRuntimePhase.POLLING_FALLBACK, runtime.phase)
        assertTrue(fake.pollingRequested)
    }

    @Test
    fun bridgeActivateQueuedAfterScreenOff_doesNotWakeDormantRuntime() {
        var interactive = true
        val fake = FakeRuntimeSession()
        val controller = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { ClipboardSyncReceiveMode.REALTIME },
            keepBackgroundRealtimeEnabled = { true },
            isScreenInteractive = { interactive },
            session = fake,
            scope = testScope
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        interactive = false
        controller.onScreenOff()
        fake.resetEffects()

        controller.activateSession()

        assertEquals(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT, controller.phase)
        assertFalse(fake.resumed)
        assertEquals(0, fake.startCount)
    }

    @Test
    fun keepBackground_directHide_doesNotDeactivate() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        fake.reset()

        controller.deactivateSession()

        assertFalse(fake.stopped)
        assertFalse(fake.realtimeStopped)
        assertEquals(ClipboardSyncRuntimePhase.REALTIME, controller.phase)
    }

    @Test
    fun forceDeactivate_closesBackgroundRealtimeForRuntimeSwitch() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        controller.deactivateSession()
        fake.reset()

        controller.forceDeactivateSession()

        assertTrue(fake.stopped)
        assertTrue(fake.realtimeStopped)
        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
    }

    @Test
    fun hiddenDirect_configDisablesBackground_doesNotRebuildWithoutActor() {
        var receiveMode = ClipboardSyncReceiveMode.REALTIME
        var keepBackground = true
        val fake = FakeRuntimeSession()
        val controller = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { receiveMode },
            keepBackgroundRealtimeEnabled = { keepBackground },
            isScreenInteractive = { true },
            session = fake,
            scope = testScope
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        controller.deactivateSession()
        fake.resetEffects()

        keepBackground = false
        receiveMode = ClipboardSyncReceiveMode.POLLING
        controller.notifyConfigChanged()

        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
        assertEquals(0, fake.startCount)
        assertTrue(fake.stopped)
    }

    @Test
    fun queuedActivateAfterInactiveScreenOff_doesNotAuthorizeLaterConfigChange() {
        var interactive = true
        val fake = FakeRuntimeSession()
        val controller = ClipboardSyncRuntime(
            syncEnabled = { true },
            receiveMode = { ClipboardSyncReceiveMode.POLLING },
            isScreenInteractive = { interactive },
            session = fake,
            scope = testScope
        )
        controller.activateSession()
        interactive = false
        controller.onScreenOff()
        controller.activateSession()
        controller.activateSession()
        interactive = true
        fake.resetEffects()

        controller.notifyConfigChanged()

        assertEquals(ClipboardSyncRuntimePhase.INACTIVE, controller.phase)
        assertEquals(0, fake.startCount)
    }

    @Test
    fun configChangedWhileDormant_defersRebuildUntilScreenOn() {
        val fake = FakeRuntimeSession()
        val controller = controller(
            fake,
            receiveMode = ClipboardSyncReceiveMode.REALTIME,
            keepBackground = true
        )
        controller.activateSession()
        fake.emitRealtimeConnected()
        controller.onScreenOff()
        fake.resetEffects()

        controller.notifyConfigChanged()

        assertEquals(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT, controller.phase)
        assertEquals(0, fake.startCount)
        assertFalse(fake.resumed)

        controller.onScreenOn()

        assertTrue(fake.started)
        assertTrue(fake.startCount > 0)
        assertTrue(fake.realtimeStarted)
    }

    private fun controller(
        fake: FakeRuntimeSession,
        receiveMode: ClipboardSyncReceiveMode,
        keepBackground: Boolean = false
    ) = ClipboardSyncRuntime(
        syncEnabled = { true },
        receiveMode = { receiveMode },
        keepBackgroundRealtimeEnabled = { keepBackground },
        isScreenInteractive = { true },
        session = fake,
        scope = testScope
    )

    private class FakeRuntimeSession : ClipboardSyncRuntimeSession {
        var started = false
        var stopped = false
        var pollingRequested = false
        var invalidated = false
        var paused = false
        var resumed = false
        var realtimeStarted = false
        var realtimeStopped = false
        var realtimeStartCount = 0
        var pollingStopCount = 0
        var catchUpCount = 0
        val appliedProfiles = mutableListOf<String>()
        var applySucceeds = true
        var deferApplyResult = false
        private var deferredApplyCallback: ((Boolean) -> Boolean)? = null
        var startCount = 0
        private var realtimeListener: SyncClipboardSignalRClient.Listener? = null

        override fun updateListener(listener: SyncClipboardManager.Listener?) = Unit

        override fun start(pollingEnabled: Boolean) {
            startCount += 1
            started = true
            stopped = false
            pollingRequested = pollingEnabled
            paused = false
        }

        override fun stop() {
            stopped = true
            started = false
            pollingRequested = false
            paused = false
            stopRealtime()
        }

        override fun invalidateReceivePath() {
            invalidated = true
            stopRealtime()
        }

        override fun catchUpPull() {
            catchUpCount += 1
        }

        override fun stopPolling() {
            pollingStopCount += 1
            pollingRequested = false
        }

        override fun applyRemoteProfile(profileJson: String, onResult: (Boolean) -> Boolean) {
            appliedProfiles += profileJson
            if (deferApplyResult) {
                deferredApplyCallback = onResult
            } else {
                val shouldFallback = onResult(applySucceeds)
                if (!applySucceeds && shouldFallback) catchUpPull()
            }
        }

        override fun downloadFile(entryId: String): Boolean = false

        override fun pauseClipboardSideEffects() {
            paused = true
        }

        override fun resumeClipboardSideEffects() {
            paused = false
            resumed = true
        }

        override fun startRealtime(listener: SyncClipboardSignalRClient.Listener) {
            realtimeStartCount += 1
            realtimeStarted = true
            realtimeStopped = false
            realtimeListener = listener
        }

        override fun stopRealtime() {
            realtimeStopped = true
            realtimeListener = null
        }

        fun emitRealtimeConnected() {
            realtimeListener?.onConnected()
        }

        fun emitRealtimeDisconnected(error: Throwable? = null) {
            realtimeListener?.onDisconnected(error)
        }

        fun emitRemoteProfile(profileJson: String?) {
            realtimeListener?.onRemoteProfileChanged(profileJson)
        }

        fun completeDeferredApply(applied: Boolean) {
            val shouldFallback = deferredApplyCallback?.invoke(applied) == true
            if (!applied && shouldFallback) catchUpPull()
            deferredApplyCallback = null
        }

        fun resetEffects() {
            resumed = false
            catchUpCount = 0
            startCount = 0
        }

        fun reset() {
            started = false
            stopped = false
            pollingRequested = false
            invalidated = false
            paused = false
            resumed = false
            realtimeStarted = false
            realtimeStopped = false
            pollingStopCount = 0
            catchUpCount = 0
            startCount = 0
        }
    }
}
