/**
 * 录音阶段的短时独占音频焦点控制器。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

internal enum class RecordingAudioFocusLoss {
    Transient,
    MayDuck,
    Permanent
}

internal interface RecordingAudioFocusHandle

internal interface RecordingAudioFocusGateway {
    fun requestFocus(onFocusChange: (Int) -> Unit): RecordingAudioFocusHandle?

    fun abandonFocus(handle: RecordingAudioFocusHandle)
}

internal fun recordingAudioFocusLossFromChange(change: Int): RecordingAudioFocusLoss? = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> RecordingAudioFocusLoss.Transient
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> RecordingAudioFocusLoss.MayDuck
    AudioManager.AUDIOFOCUS_LOSS -> RecordingAudioFocusLoss.Permanent
    else -> null
}

internal class RecordingAudioFocusController internal constructor(
    private val gateway: RecordingAudioFocusGateway,
    private val onFocusLost: (RecordingAudioFocusLoss) -> Unit
) {
    companion object {
        private const val TAG = "RecordingAudioFocus"
    }

    constructor(
        context: Context,
        onFocusLost: (RecordingAudioFocusLoss) -> Unit
    ) : this(
        gateway = AndroidRecordingAudioFocusGateway(context.applicationContext),
        onFocusLost = onFocusLost
    )

    private val lock = Any()
    private var requestGeneration = 0L
    private var activeHandle: RecordingAudioFocusHandle? = null

    fun acquire(): Boolean {
        release()
        val generation = synchronized(lock) {
            requestGeneration += 1L
            requestGeneration
        }
        val handle = try {
            gateway.requestFocus { change -> onPlatformFocusChange(generation, change) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to request recording audio focus", t)
            null
        } ?: return false

        val retained = synchronized(lock) {
            if (requestGeneration == generation && activeHandle == null) {
                activeHandle = handle
                true
            } else {
                false
            }
        }
        if (!retained) {
            abandonSafely(handle)
        }
        return retained
    }

    fun release() {
        val handle = synchronized(lock) {
            requestGeneration += 1L
            val current = activeHandle
            activeHandle = null
            current
        } ?: return
        abandonSafely(handle)
    }

    internal fun isHeldForTest(): Boolean = synchronized(lock) { activeHandle != null }

    private fun onPlatformFocusChange(generation: Long, change: Int) {
        val loss = recordingAudioFocusLossFromChange(change) ?: return
        val handle = synchronized(lock) {
            if (requestGeneration != generation) return
            val current = activeHandle ?: return
            activeHandle = null
            requestGeneration += 1L
            current
        }
        abandonSafely(handle)
        try {
            onFocusLost(loss)
        } catch (t: Throwable) {
            Log.w(TAG, "Recording audio focus loss callback failed", t)
        }
    }

    private fun abandonSafely(handle: RecordingAudioFocusHandle) {
        try {
            gateway.abandonFocus(handle)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to abandon recording audio focus", t)
        }
    }
}

private class AndroidRecordingAudioFocusGateway(
    context: Context
) : RecordingAudioFocusGateway {
    companion object {
        private const val TAG = "RecordingAudioFocus"
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun requestFocus(onFocusChange: (Int) -> Unit): RecordingAudioFocusHandle? {
        val manager = audioManager ?: run {
            Log.w(TAG, "AudioManager is unavailable")
            return null
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(onFocusChange)
            .build()
        val result = manager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Recording audio focus was not granted: result=$result")
            return null
        }
        Log.d(TAG, "Recording audio focus granted")
        return AndroidRecordingAudioFocusHandle(request)
    }

    override fun abandonFocus(handle: RecordingAudioFocusHandle) {
        val request = (handle as? AndroidRecordingAudioFocusHandle)?.request ?: return
        val manager = audioManager ?: return
        val result = manager.abandonAudioFocusRequest(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Recording audio focus abandon returned result=$result")
        } else {
            Log.d(TAG, "Recording audio focus abandoned")
        }
    }

    private data class AndroidRecordingAudioFocusHandle(
        val request: AudioFocusRequest
    ) : RecordingAudioFocusHandle
}
