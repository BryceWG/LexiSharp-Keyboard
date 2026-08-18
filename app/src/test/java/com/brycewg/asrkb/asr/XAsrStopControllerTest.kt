// X-ASR stop latch tests: re-entry ignore and captured session identity.
package com.brycewg.asrkb.asr

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XAsrStopControllerTest {
    @Test
    fun secondStopIsIgnoredWhileFirstIsArmed() = runTest {
        val controller = XAsrStopController()
        val session = XAsrStreamSession(
            scope = this,
            nowMs = { 0L },
            onPartial = {},
            onFinal = {},
            processorDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor]
                as kotlinx.coroutines.CoroutineDispatcher
        )
        controller.onNewSession(session)
        controller.captureJob = Job()

        val first = controller.tryBeginStop(externalPcmMode = false)
        val second = controller.tryBeginStop(externalPcmMode = false)

        assertNotNull(first)
        assertTrue(first!!.waitForCapture)
        assertSame(session, first.session)
        assertNull(second)
        first.captureJob?.cancel()
        session.cancel()
    }

    @Test
    fun armedStopKeepsOriginalSessionAfterNewSessionStarts() = runTest {
        val controller = XAsrStopController()
        val dispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor]
            as kotlinx.coroutines.CoroutineDispatcher
        val sessionA = XAsrStreamSession(
            scope = this,
            nowMs = { 0L },
            onPartial = {},
            onFinal = {},
            processorDispatcher = dispatcher
        )
        val sessionB = XAsrStreamSession(
            scope = this,
            nowMs = { 0L },
            onPartial = {},
            onFinal = {},
            processorDispatcher = dispatcher
        )
        controller.onNewSession(sessionA)
        controller.captureJob = Job()
        val armed = controller.tryBeginStop(externalPcmMode = false)
        assertNotNull(armed)
        val previous = controller.onNewSession(sessionB)

        assertSame(sessionA, armed!!.session)
        assertSame(sessionA, previous)
        assertSame(sessionB, controller.session)
        assertFalse(armed.session === controller.session)
        armed.captureJob?.cancel()
        sessionA.cancel()
        sessionB.cancel()
    }
}
