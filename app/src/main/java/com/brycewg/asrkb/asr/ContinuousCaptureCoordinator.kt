/**
 * 持续热采集协调器。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object ContinuousCaptureAudioSpec {
    const val SAMPLE_RATE = 16000
    const val CHUNK_MS = 200
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun isCompatible(sampleRate: Int, channelConfig: Int, audioFormat: Int): Boolean {
        return sampleRate == SAMPLE_RATE &&
            channelConfig == CHANNEL_CONFIG &&
            audioFormat == AUDIO_FORMAT
    }
}

enum class ContinuousCaptureOwner {
    Ime,
    FloatingBall
}

/**
 * 在键盘/悬浮球可见期间预先持有 AudioRecord，并在 ASR 会话开始时把热采集 PCM 交给引擎。
 */
object ContinuousCaptureCoordinator {
    private const val TAG = "ContinuousCapture"
    private const val ATTACH_TIMEOUT_MS = 3000L
    private const val SESSION_CHANNEL_CAPACITY = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val owners = linkedMapOf<ContinuousCaptureOwner, Context>()

    private var captureJob: Job? = null
    private var activeSession: ActiveSession? = null
    private var suspendedForSession = false

    private data class ActiveSession(
        val id: Long,
        val chunks: Channel<ByteArray>,
        var attached: Boolean = false,
        var timeoutJob: Job? = null
    )

    fun acquire(owner: ContinuousCaptureOwner, context: Context) {
        synchronized(lock) {
            owners[owner] = context.applicationContext
            ensureCaptureLocked()
        }
    }

    fun release(owner: ContinuousCaptureOwner) {
        synchronized(lock) {
            owners.remove(owner)
            if (owners.isEmpty()) {
                closeActiveSessionLocked()
                stopCaptureLocked()
            }
        }
    }

    fun beginSession(sessionId: Long) {
        synchronized(lock) {
            if (!isEnabledLocked() || owners.isEmpty()) return
            closeActiveSessionLocked()
            suspendedForSession = false
            activeSession = ActiveSession(
                id = sessionId,
                chunks = Channel(SESSION_CHANNEL_CAPACITY)
            ).also { session ->
                session.timeoutJob = scope.launch {
                    delay(ATTACH_TIMEOUT_MS)
                    synchronized(lock) {
                        if (activeSession?.id == session.id && activeSession?.attached != true) {
                            Log.w(TAG, "Session ${session.id} was not attached in time")
                            closeActiveSessionLocked()
                            ensureCaptureLocked()
                        }
                    }
                }
            }
            ensureCaptureLocked()
        }
    }

    fun endSession(sessionId: Long) {
        synchronized(lock) {
            if (activeSession?.id == sessionId) {
                closeActiveSessionLocked()
            }
            suspendedForSession = false
            ensureCaptureLocked()
        }
    }

    fun endAnySession() {
        synchronized(lock) {
            closeActiveSessionLocked()
            suspendedForSession = false
            ensureCaptureLocked()
        }
    }

    internal fun attachActiveSessionFlow(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int
    ): Flow<ByteArray>? {
        val session = synchronized(lock) {
            val current = activeSession ?: return@synchronized null
            if (!ContinuousCaptureAudioSpec.isCompatible(sampleRate, channelConfig, audioFormat)) {
                Log.i(TAG, "Active session is incompatible; falling back to platform capture")
                suspendedForSession = true
                closeActiveSessionLocked()
                stopCaptureLocked()
                return@synchronized null
            }
            current.attached = true
            current.timeoutJob?.cancel()
            current.timeoutJob = null
            current
        } ?: return null

        return flow {
            try {
                for (chunk in session.chunks) {
                    emit(chunk)
                }
            } finally {
                endSession(session.id)
            }
        }
    }

    private fun isEnabledLocked(): Boolean {
        val context = owners.values.firstOrNull() ?: return false
        return try {
            Prefs(context).continuousCaptureEnabled
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read continuous capture preference", t)
            false
        }
    }

    private fun ensureCaptureLocked() {
        if (owners.isEmpty() || !isEnabledLocked() || suspendedForSession) {
            stopCaptureLocked()
            return
        }
        if (captureJob?.isActive == true) return
        val context = owners.values.firstOrNull() ?: return
        captureJob = scope.launch {
            val manager = AudioCaptureManager(
                context = context,
                sampleRate = ContinuousCaptureAudioSpec.SAMPLE_RATE,
                channelConfig = ContinuousCaptureAudioSpec.CHANNEL_CONFIG,
                audioFormat = ContinuousCaptureAudioSpec.AUDIO_FORMAT,
                chunkMillis = ContinuousCaptureAudioSpec.CHUNK_MS
            )
            try {
                manager.startPlatformCapture().collect { chunk ->
                    if (!isActive || chunk.isEmpty()) return@collect
                    dispatchChunk(chunk)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Continuous capture cancelled")
                } else {
                    Log.w(TAG, "Continuous capture failed", t)
                    closeActiveSession(t)
                }
            } finally {
                synchronized(lock) {
                    if (captureJob == coroutineContext[Job]) {
                        captureJob = null
                    }
                }
            }
        }
    }

    private suspend fun dispatchChunk(chunk: ByteArray) {
        val channel = synchronized(lock) {
            activeSession?.chunks
        } ?: return
        channel.sendWhileSessionActive(chunk.copyOf())
    }

    private fun closeActiveSessionLocked() {
        val session = activeSession ?: return
        activeSession = null
        try {
            session.timeoutJob?.cancel()
        } catch (_: Throwable) {
        }
        session.timeoutJob = null
        session.chunks.closeSessionDispatch()
    }

    private fun closeActiveSession(cause: Throwable) {
        synchronized(lock) {
            val session = activeSession ?: return
            activeSession = null
            try {
                session.timeoutJob?.cancel()
            } catch (_: Throwable) {
            }
            session.timeoutJob = null
            session.chunks.closeSessionDispatch(cause)
        }
    }

    private fun stopCaptureLocked() {
        val job = captureJob ?: return
        captureJob = null
        job.cancel()
    }
}
