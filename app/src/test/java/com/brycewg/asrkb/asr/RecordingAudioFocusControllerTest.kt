package com.brycewg.asrkb.asr

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingAudioFocusControllerTest {
    @Test
    fun acquireAndRelease_abandonsGrantedFocusExactlyOnce() {
        val gateway = FakeGateway()
        val controller = RecordingAudioFocusController(gateway) { }

        assertTrue(controller.acquire())
        assertTrue(controller.isHeldForTest())

        controller.release()
        controller.release()

        assertFalse(controller.isHeldForTest())
        assertEquals(1, gateway.abandoned.size)
    }

    @Test
    fun repeatedAcquire_releasesPreviousRequestBeforeReplacingIt() {
        val gateway = FakeGateway()
        val controller = RecordingAudioFocusController(gateway) { }

        assertTrue(controller.acquire())
        val firstHandle = gateway.granted.single()
        assertTrue(controller.acquire())

        assertEquals(listOf(firstHandle), gateway.abandoned)
        assertTrue(controller.isHeldForTest())
    }

    @Test
    fun failedAcquire_doesNotCreateLeaseOrAbandonUnknownRequest() {
        val gateway = FakeGateway(grantRequests = false)
        val controller = RecordingAudioFocusController(gateway) { }

        assertFalse(controller.acquire())
        controller.release()

        assertFalse(controller.isHeldForTest())
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun focusLoss_releasesLeaseAndNotifiesOnce() {
        val gateway = FakeGateway()
        val losses = mutableListOf<RecordingAudioFocusLoss>()
        val controller = RecordingAudioFocusController(gateway, losses::add)

        assertTrue(controller.acquire())
        gateway.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        gateway.emit(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse(controller.isHeldForTest())
        assertEquals(listOf(RecordingAudioFocusLoss.Transient), losses)
        assertEquals(1, gateway.abandoned.size)
    }

    @Test
    fun focusChange_mapsOnlyLossEvents() {
        assertEquals(
            RecordingAudioFocusLoss.Transient,
            recordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        )
        assertEquals(
            RecordingAudioFocusLoss.MayDuck,
            recordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        )
        assertEquals(
            RecordingAudioFocusLoss.Permanent,
            recordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS)
        )
        assertEquals(null, recordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_GAIN))
    }

    private class FakeGateway(
        private val grantRequests: Boolean = true
    ) : RecordingAudioFocusGateway {
        val granted = mutableListOf<RecordingAudioFocusHandle>()
        val abandoned = mutableListOf<RecordingAudioFocusHandle>()
        private var listener: ((Int) -> Unit)? = null

        override fun requestFocus(onFocusChange: (Int) -> Unit): RecordingAudioFocusHandle? {
            listener = onFocusChange
            if (!grantRequests) return null
            return FakeHandle(granted.size + 1).also(granted::add)
        }

        override fun abandonFocus(handle: RecordingAudioFocusHandle) {
            abandoned += handle
        }

        fun emit(change: Int) {
            listener?.invoke(change)
        }
    }

    private data class FakeHandle(val id: Int) : RecordingAudioFocusHandle
}
