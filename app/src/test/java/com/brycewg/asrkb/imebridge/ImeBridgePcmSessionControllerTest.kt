package com.brycewg.asrkb.imebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBridgePcmSessionControllerTest {
    @Test
    fun acceptedBeginStartsSession() {
        val fixture = Fixture()

        val result = fixture.controller.begin(fixture.beginRequest())

        assertOk(result)
        assertEquals(1, fixture.sessions.size)
        assertEquals(1, fixture.sessions.single().starts)
    }

    @Test
    fun startFailureRejectsBeginAndDoesNotKeepActiveSession() {
        val sessions = mutableListOf<FakeSession>()
        val fixture = Fixture(
            sessionFactory = BridgePcmSessionFactory { config, _ ->
                FakeSession(config.sessionId, failOnStart = true).also { sessions += it }
            }
        )

        val result = fixture.controller.begin(fixture.beginRequest("session-1"))
        val staleFrame = fixture.controller.writeFrame("session-1", byteArrayOf(1, 2), 16000, 1)

        assertCode(ImeBridgePcmContract.RESULT_UNSUPPORTED, result)
        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, staleFrame)
        assertEquals(1, sessions.single().starts)
        assertEquals(1, sessions.single().cancels)
        assertTrue(sessions.single().frames.isEmpty())
    }

    @Test
    fun disabledFeatureRejectsBeginWithoutCreatingSession() {
        val fixture = Fixture(featureEnabled = false)

        val result = fixture.controller.begin(fixture.beginRequest())

        assertCode(ImeBridgePcmContract.RESULT_FEATURE_DISABLED, result)
        assertTrue(fixture.sessions.isEmpty())
    }

    @Test
    fun packageMismatchRejectsBegin() {
        val fixture = Fixture()

        val result = fixture.controller.begin(
            fixture.beginRequest(callerPackages = setOf("other.ime"))
        )

        assertCode(ImeBridgePcmContract.RESULT_PACKAGE_MISMATCH, result)
        assertTrue(fixture.sessions.isEmpty())
    }

    @Test
    fun sensitiveFieldRejectsBegin() {
        val fixture = Fixture(status = okStatus(isSensitiveField = true))

        val result = fixture.controller.begin(fixture.beginRequest())

        assertCode(ImeBridgePcmContract.RESULT_SENSITIVE_FIELD, result)
        assertTrue(fixture.sessions.isEmpty())
    }

    @Test
    fun missingInputConnectionRejectsBegin() {
        val fixture = Fixture(status = okStatus(hasInputConnection = false))

        val result = fixture.controller.begin(fixture.beginRequest())

        assertCode(ImeBridgePcmContract.RESULT_NO_INPUT_CONNECTION, result)
        assertTrue(fixture.sessions.isEmpty())
    }

    @Test
    fun hiddenImeWindowRejectsBegin() {
        val fixture = Fixture(status = okStatus(isImeWindowVisible = false))

        val result = fixture.controller.begin(fixture.beginRequest())

        assertCode(ImeBridgePcmContract.RESULT_BRIDGE_UNAVAILABLE, result)
        assertTrue(fixture.sessions.isEmpty())
    }

    @Test
    fun duplicateBeginIsRejectedWhileSessionActive() {
        val fixture = Fixture()

        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))
        val duplicate = fixture.controller.begin(fixture.beginRequest("session-2"))

        assertCode(ImeBridgePcmContract.RESULT_BUSY, duplicate)
        assertEquals(1, fixture.sessions.size)
    }

    @Test
    fun staleFrameIsRejected() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val result = fixture.controller.writeFrame("old-session", byteArrayOf(1, 2), 16000, 1)

        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, result)
        assertTrue(fixture.sessions.single().frames.isEmpty())
    }

    @Test
    fun frameFromNonOwnerPackageIsRejected() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val result = fixture.controller.writeFrame(
            BridgePcmSessionOperationRequest("session-1", setOf("other.ime")),
            byteArrayOf(1, 2),
            16000,
            1
        )

        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, result)
        assertTrue(fixture.sessions.single().frames.isEmpty())
    }

    @Test
    fun invalidSampleRateIsRejectedWithoutWritingFrame() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val zeroRate = fixture.controller.writeFrame("session-1", byteArrayOf(1, 2), 0, 1)
        val absurdRate = fixture.controller.writeFrame("session-1", byteArrayOf(3, 4), 384000, 1)

        assertCode(ImeBridgePcmContract.RESULT_BAD_REQUEST, zeroRate)
        assertCode(ImeBridgePcmContract.RESULT_BAD_REQUEST, absurdRate)
        assertTrue(fixture.sessions.single().frames.isEmpty())
    }

    @Test
    fun invalidChannelsAndStereoAreRejectedWithoutWritingFrame() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val noChannels = fixture.controller.writeFrame("session-1", byteArrayOf(1, 2), 16000, 0)
        val stereo = fixture.controller.writeFrame("session-1", byteArrayOf(3, 4), 16000, 2)

        assertCode(ImeBridgePcmContract.RESULT_BAD_REQUEST, noChannels)
        assertCode(ImeBridgePcmContract.RESULT_BAD_REQUEST, stereo)
        assertTrue(fixture.sessions.single().frames.isEmpty())
    }

    @Test
    fun staleFinishIsRejected() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val result = fixture.controller.finish("old-session")

        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, result)
        assertEquals(0, fixture.sessions.single().finishes)
    }

    @Test
    fun repeatedFinishAndCancelAfterFinishDoNotDuplicateTerminalCall() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val firstFinish = fixture.controller.finish("session-1")
        val repeatedFinish = fixture.controller.finish("session-1")
        val cancelAfterFinish = fixture.controller.cancel("session-1")

        assertOk(firstFinish)
        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, repeatedFinish)
        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, cancelAfterFinish)
        assertEquals(1, fixture.sessions.single().finishes)
        assertEquals(0, fixture.sessions.single().cancels)
    }

    @Test
    fun cancelCancelsActiveSessionAndRejectsFurtherFrames() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        val cancel = fixture.controller.cancel("session-1")
        val staleFrame = fixture.controller.writeFrame("session-1", byteArrayOf(1), 16000, 1)

        assertOk(cancel)
        assertCode(ImeBridgePcmContract.RESULT_STALE_SESSION, staleFrame)
        assertEquals(1, fixture.sessions.single().cancels)
    }

    @Test
    fun bridgePcmLogSummaryContainsOnlySafeCountsAndFlags() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-secret-token")))

        fixture.controller.writeFrame(
            "session-secret-token",
            byteArrayOf(1, 2, 3, 4, 5, 6),
            16000,
            1
        )
        fixture.controller.finish("session-secret-token")

        val finishLog = fixture.logs.last { it.operation == "finish" }
        val combined = finishLog.requestSummary() + finishLog.responseSummary()
        assertEquals("session-", finishLog.sessionSummary)
        assertEquals(6L, finishLog.pcmBytes)
        assertEquals(1, finishLog.frameCount)
        assertTrue(finishLog.supportsComposingPreview)
        assertTrue(finishLog.supportsPcmRecording)
        assertFalse(combined.contains("1, 2, 3, 4, 5, 6"))
        assertFalse(combined.contains("recognized text"))
        assertFalse(combined.contains("before_cursor"))
        assertFalse(combined.contains("secret-token"))
        assertFalse(combined.contains("https://private.example"))
    }

    @Test
    fun unsafeFailureMessageIsRedactedInBridgePcmLogSummary() {
        val fixture = Fixture(
            sessionFactory = BridgePcmSessionFactory { config, _ ->
                object : BridgePcmSession {
                    override fun start() {
                        error("token=abc https://private.example text=recognized text pcm=0102")
                    }

                    override fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int) = Unit
                    override fun finish() = Unit
                    override fun cancel() = Unit
                }.also { fixtureSession ->
                    fixtureSession.hashCode()
                }
            }
        )

        fixture.controller.begin(fixture.beginRequest())

        val beginLog = fixture.logs.single()
        val response = beginLog.responseSummary()
        assertTrue(response.contains("message=redacted"))
        assertFalse(response.contains("token=abc"))
        assertFalse(response.contains("recognized text"))
        assertFalse(response.contains("https://private.example"))
    }

    @Test
    fun shutdownCancelIsIdempotent() {
        val fixture = Fixture()
        assertOk(fixture.controller.begin(fixture.beginRequest("session-1")))

        fixture.controller.cancelActiveForShutdown()
        fixture.controller.cancelActiveForShutdown()

        assertEquals(1, fixture.sessions.single().cancels)
    }

    private class Fixture(
        featureEnabled: Boolean = true,
        private val currentImePackage: String = DEFAULT_IME_PACKAGE,
        private val status: ImeBridgeResult = okStatus(),
        private val sessionFactory: BridgePcmSessionFactory? = null
    ) {
        val sessions = mutableListOf<FakeSession>()
        val logs = mutableListOf<BridgePcmSessionLogRecord>()
        private var nowMs = 1_000L
        val controller = ImeBridgePcmSessionController(
            featureGate = BridgePcmFeatureGate { featureEnabled },
            currentImePackageProvider = CurrentImePackageProvider { currentImePackage },
            bridgeStatusProvider = BridgeStatusProvider { status },
            sessionFactory = sessionFactory ?: BridgePcmSessionFactory { config, _ ->
                FakeSession(config.sessionId).also { sessions += it }
            },
            logSink = BridgePcmSessionLogSink { logs += it },
            clockMs = { nowMs.also { nowMs += 100L } }
        )

        fun beginRequest(
            sessionId: String = "session-1",
            callerPackages: Set<String> = setOf(DEFAULT_IME_PACKAGE)
        ) = BridgePcmBeginRequest(sessionId, callerPackages)
    }

    private class FakeSession(
        val sessionId: String,
        private val failOnStart: Boolean = false
    ) : BridgePcmSession {
        var starts = 0
        var finishes = 0
        var cancels = 0
        val frames = mutableListOf<ByteArray>()

        override fun start() {
            starts += 1
            if (failOnStart) error("bridge begin failed")
        }

        override fun writeFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
            assertEquals(16000, sampleRate)
            assertEquals(1, channels)
            frames += pcm
        }

        override fun finish() {
            finishes += 1
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private companion object {
        private const val DEFAULT_IME_PACKAGE = "com.example.ime"

        fun okStatus(
            hasInputConnection: Boolean = true,
            isSensitiveField: Boolean = false,
            isImeWindowVisible: Boolean = true
        ) = ImeBridgeResult(
            code = ImeBridgeContract.RESULT_OK,
            message = "ok",
            targetPackage = DEFAULT_IME_PACKAGE,
            hasInputConnection = hasInputConnection,
            isSensitiveField = isSensitiveField,
            isImeWindowVisible = isImeWindowVisible,
            supportsInsertText = true,
            supportsComposingPreview = true,
            supportsFinishComposingText = true,
            supportsSessions = true,
            supportsPcmRecording = true
        )

        fun assertOk(result: BridgePcmOperationResult) {
            assertTrue(result.message, result.isSuccess)
        }

        fun assertCode(expected: Int, result: BridgePcmOperationResult) {
            assertFalse(result.isSuccess)
            assertEquals(expected, result.code)
        }
    }
}
