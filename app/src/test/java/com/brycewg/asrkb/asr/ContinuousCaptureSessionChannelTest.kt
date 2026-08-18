package com.brycewg.asrkb.asr

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousCaptureSessionChannelTest {
    @Test
    fun endingAndClosingSessionReleasesBlockedSenderWithoutFailure() = runTest {
        val channel = Channel<ByteArray>(1)
        channel.send(byteArrayOf(1))
        val blockedSend = async(start = CoroutineStart.UNDISPATCHED) {
            channel.sendWhileSessionActive(byteArrayOf(2))
        }

        assertFalse(blockedSend.isCompleted)
        channel.closeSessionDispatch()

        blockedSend.await()
        assertTrue(blockedSend.isCompleted)
        assertArrayEquals(byteArrayOf(1), channel.receive())
        assertTrue(channel.tryReceive().isFailure)
    }

    @Test
    fun closeDoesNotDropInFlightSendWhenChannelHasCapacity() = runTest {
        val channel = Channel<ByteArray>(1)
        channel.send(byteArrayOf(1))
        val blockedSend = async(start = CoroutineStart.UNDISPATCHED) {
            channel.sendWhileSessionActive(byteArrayOf(2))
        }

        assertFalse(blockedSend.isCompleted)
        assertArrayEquals(byteArrayOf(1), channel.receive())
        channel.closeSessionDispatch()

        blockedSend.await()
        assertArrayEquals(byteArrayOf(2), channel.receive())
    }

    @Test
    fun activeSessionKeepsBackpressure() = runTest {
        val channel = Channel<ByteArray>(1)
        channel.send(byteArrayOf(1))
        val blockedSend = async(start = CoroutineStart.UNDISPATCHED) {
            channel.sendWhileSessionActive(byteArrayOf(2))
        }

        assertFalse(blockedSend.isCompleted)
        channel.receive()
        blockedSend.await()

        assertTrue(blockedSend.isCompleted)
    }
}
