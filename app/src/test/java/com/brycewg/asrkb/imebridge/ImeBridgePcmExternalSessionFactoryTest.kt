package com.brycewg.asrkb.imebridge

import com.brycewg.asrkb.api.ExternalSpeechCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImeBridgePcmExternalSessionFactoryTest {
    @Test
    fun pcmFramesReachPushPcmSession() {
        val fixture = Fixture()
        val session = fixture.createSession()
        val frame = byteArrayOf(1, 2, 3, 4)

        session.start()
        session.writeFrame(frame, 16000, 1)
        session.finish()

        val pushSession = fixture.pushFactory.sessions.single()
        assertEquals(1, pushSession.starts)
        assertEquals(1, pushSession.finishes)
        assertEquals(listOf(Frame(frame.toList(), 16000, 1)), pushSession.frames)
        assertEquals(listOf(Event.Begin("session-1")), fixture.backfill.events)
    }

    @Test
    fun partialUsesBridgeComposingPreviewWhenSupported() {
        val fixture = Fixture(supportsComposingPreview = true)
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onPartial(1, "hello")

        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.SetComposing("session-1", "hello")
            ),
            fixture.backfill.events
        )
    }

    @Test
    fun partialIsIgnoredWhenComposingPreviewUnsupported() {
        val fixture = Fixture(supportsComposingPreview = false)
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onPartial(1, "hello")

        assertEquals(listOf(Event.Begin("session-1")), fixture.backfill.events)
    }

    @Test
    fun finalUsesBridgeInsertAsSessionTerminalCommit() {
        val fixture = Fixture()
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onFinal(1, "done")

        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.Insert("session-1", "done")
            ),
            fixture.backfill.events
        )
        assertEquals(listOf("session-1"), fixture.endedSessions)
    }

    @Test
    fun finishAllowsAsrFinalToCommit() {
        val fixture = Fixture()
        val session = fixture.createSession()

        session.start()
        session.finish()
        fixture.pushFactory.callbacks.onFinal(1, "done")

        assertEquals(1, fixture.pushFactory.sessions.single().finishes)
        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.Insert("session-1", "done")
            ),
            fixture.backfill.events
        )
        assertEquals(listOf("session-1"), fixture.endedSessions)
    }

    @Test
    fun emptyFinalClearsBridgeSessionWithoutInsert() {
        val fixture = Fixture()
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onPartial(1, "preview")
        fixture.pushFactory.callbacks.onFinal(1, "")

        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.SetComposing("session-1", "preview"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertEquals(listOf("session-1"), fixture.endedSessions)
    }

    @Test
    fun asrErrorCancelsBridgeSessionAndEndsOnce() {
        val fixture = Fixture()
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onPartial(1, "preview")
        fixture.pushFactory.callbacks.onError(1, 500, "boom")
        fixture.pushFactory.callbacks.onSessionDone(1)

        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.SetComposing("session-1", "preview"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertEquals(listOf("session-1"), fixture.endedSessions)
    }

    @Test
    fun sessionDoneWithoutFinalClearsPreviewAndIgnoresLateFinal() {
        val fixture = Fixture()
        fixture.createSession().start()

        fixture.pushFactory.callbacks.onPartial(1, "preview")
        fixture.pushFactory.callbacks.onSessionDone(1)
        fixture.pushFactory.callbacks.onFinal(1, "late")

        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.SetComposing("session-1", "preview"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertEquals(listOf("session-1"), fixture.endedSessions)
    }

    @Test
    fun cancelCancelsPushPcmAndBridgeSession() {
        val fixture = Fixture()
        val session = fixture.createSession()

        session.start()
        session.cancel()

        assertEquals(1, fixture.pushFactory.sessions.single().cancels)
        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertTrue(fixture.endedSessions.isEmpty())
    }

    @Test
    fun cancelIsIdempotentAndIgnoresLateCallbacks() {
        val fixture = Fixture()
        val session = fixture.createSession()

        session.start()
        session.cancel()
        session.cancel()
        fixture.pushFactory.callbacks.onPartial(1, "late preview")
        fixture.pushFactory.callbacks.onFinal(1, "late final")
        fixture.pushFactory.callbacks.onError(1, 500, "late error")

        assertEquals(1, fixture.pushFactory.sessions.single().cancels)
        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertTrue(fixture.endedSessions.isEmpty())
    }

    @Test
    fun repeatedFinishDoesNotDuplicatePcmFinish() {
        val fixture = Fixture()
        val session = fixture.createSession()

        session.start()
        session.finish()
        session.finish()

        assertEquals(1, fixture.pushFactory.sessions.single().finishes)
    }

    @Test
    fun cancelAfterFinishFailureClearsBridgeSession() {
        val fixture = Fixture(failOnFinish = true)
        val session = fixture.createSession()

        session.start()
        try {
            session.finish()
            fail("expected finish failure")
        } catch (_: IllegalStateException) {
        }
        session.cancel()
        fixture.pushFactory.callbacks.onFinal(1, "late final")

        val pushSession = fixture.pushFactory.sessions.single()
        assertEquals(1, pushSession.finishes)
        assertEquals(1, pushSession.cancels)
        assertEquals(
            listOf(
                Event.Begin("session-1"),
                Event.Cancel("session-1")
            ),
            fixture.backfill.events
        )
        assertTrue(fixture.endedSessions.isEmpty())
    }

    @Test
    fun beginFailureDoesNotStartPushPcmOrBackfillText() {
        val fixture = Fixture(beginResult = bridgeFailure())
        val session = fixture.createSession()
        val frame = byteArrayOf(1, 2)

        try {
            session.start()
            fail("expected bridge begin failure")
        } catch (_: IllegalStateException) {
        }
        session.writeFrame(frame, 16000, 1)
        fixture.pushFactory.callbacks.onPartial(1, "late preview")
        fixture.pushFactory.callbacks.onFinal(1, "late final")
        session.finish()

        val pushSession = fixture.pushFactory.sessions.single()
        assertEquals(1, fixture.backfill.beginAttempts)
        assertEquals(0, pushSession.starts)
        assertEquals(0, pushSession.finishes)
        assertEquals(1, pushSession.cancels)
        assertTrue(pushSession.frames.isEmpty())
        assertTrue(fixture.backfill.events.isEmpty())
        assertTrue(fixture.endedSessions.isEmpty())
    }

    private class Fixture(
        private val supportsComposingPreview: Boolean = true,
        beginResult: ImeBridgeResult = ok(),
        failOnFinish: Boolean = false
    ) {
        val pushFactory = FakePushPcmSessionFactory(failOnFinish)
        val backfill = FakeBackfill(beginResult)
        val endedSessions = mutableListOf<String>()
        private val factory = ImeBridgePcmExternalSessionFactory(pushFactory, backfill)

        fun createSession(): BridgePcmSession =
            factory.create(
                BridgePcmSessionConfig(
                    sessionId = "session-1",
                    supportsComposingPreview = supportsComposingPreview
                )
            ) { endedSessions += it } ?: error("expected session")
    }

    private class FakePushPcmSessionFactory(
        private val failOnFinish: Boolean
    ) : BridgePushPcmSessionFactory {
        lateinit var callbacks: ExternalSpeechCallbacks
        val sessions = mutableListOf<FakePushPcmSession>()

        override fun create(callbacks: ExternalSpeechCallbacks): BridgePcmSession {
            this.callbacks = callbacks
            return FakePushPcmSession(failOnFinish).also { sessions += it }
        }
    }

    private class FakePushPcmSession(
        private val failOnFinish: Boolean
    ) : BridgePcmSession {
        var starts = 0
        var finishes = 0
        var cancels = 0
        val frames = mutableListOf<Frame>()

        override fun start() {
            starts += 1
        }

        override fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
            frames += Frame(pcm.toList(), sampleRate, channels)
        }

        override fun finish() {
            finishes += 1
            if (failOnFinish) error("finish failed")
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private class FakeBackfill(
        private val beginResult: ImeBridgeResult
    ) : ImeBridgePcmBackfill {
        val events = mutableListOf<Event>()
        var beginAttempts = 0

        override fun beginSession(sessionId: String): ImeBridgeResult {
            beginAttempts += 1
            if (beginResult.isSuccess) events += Event.Begin(sessionId)
            return beginResult
        }

        override fun setComposingText(sessionId: String, text: String): ImeBridgeResult {
            events += Event.SetComposing(sessionId, text)
            return ok()
        }

        override fun insertText(sessionId: String, text: String): ImeBridgeResult {
            events += Event.Insert(sessionId, text)
            return ok()
        }

        override fun finishComposingText(sessionId: String): ImeBridgeResult {
            events += Event.FinishComposing(sessionId)
            return ok()
        }

        override fun cancelSession(sessionId: String): ImeBridgeResult {
            events += Event.Cancel(sessionId)
            return ok()
        }
    }

    private data class Frame(
        val bytes: List<Byte>,
        val sampleRate: Int,
        val channels: Int
    )

    private sealed interface Event {
        data class Begin(val sessionId: String) : Event
        data class SetComposing(val sessionId: String, val text: String) : Event
        data class Insert(val sessionId: String, val text: String) : Event
        data class FinishComposing(val sessionId: String) : Event
        data class Cancel(val sessionId: String) : Event
    }

    private companion object {
        fun ok() = ImeBridgeResult(
            code = ImeBridgeContract.RESULT_OK,
            message = "ok",
            targetPackage = "com.example.ime",
            hasInputConnection = true,
            isSensitiveField = false,
            isImeWindowVisible = true
        )

        fun bridgeFailure() = ImeBridgeResult(
            code = ImeBridgeContract.RESULT_NO_INPUT_CONNECTION,
            message = "no input connection",
            targetPackage = "com.example.ime",
            hasInputConnection = false,
            isSensitiveField = false,
            isImeWindowVisible = true
        )
    }
}
