package com.brycewg.asrkb.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runtime 对外可见的接收相；控制逻辑只存在于 [ClipboardSyncRuntime]。 */
enum class ClipboardSyncRuntimePhase {
    INACTIVE,
    UPLOAD_ONLY,
    CONNECTING,
    REALTIME,
    POLLING,
    POLLING_FALLBACK,
    SCREEN_OFF_DORMANT
}

/** Internal I/O seam kept local to the deep Runtime; production has one implementation. */
internal interface ClipboardSyncRuntimeSession {
    fun updateListener(listener: SyncClipboardManager.Listener?)
    fun start(pollingEnabled: Boolean)
    fun stopPolling()
    fun stop()
    fun invalidateReceivePath()
    fun catchUpPull()
    fun applyRemoteProfile(profileJson: String, onResult: (Boolean) -> Boolean)
    fun downloadFile(entryId: String): Boolean
    fun pauseClipboardSideEffects()
    fun resumeClipboardSideEffects()
    fun startRealtime(listener: SyncClipboardSignalRClient.Listener)
    fun stopRealtime()
}

/**
 * 剪贴板同步的唯一生命周期所有者。
 *
 * 调用方只报告 Actor、屏幕、网络和配置事实；本类直接收敛 Manager、SignalR、
 * polling 与 retry 资源，不再通过 Policy/Action/Controller 二次解释状态。
 */
internal class ClipboardSyncRuntime(
    private val syncEnabled: () -> Boolean,
    private val receiveMode: () -> ClipboardSyncReceiveMode,
    private val isScreenInteractive: () -> Boolean,
    private val session: ClipboardSyncRuntimeSession,
    private val scope: CoroutineScope,
    private val keepBackgroundRealtimeEnabled: () -> Boolean = { false },
    private val onTerminalPhase: (ClipboardSyncRuntimePhase) -> Unit = {}
) {
    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

        internal fun retryDelayMs(attempt: Int): Long =
            RETRY_DELAYS_MS[attempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
    }

    private var actorDesired = false
    private var backgroundArmed = false
    private var realtimeConnected = false
    private var realtimeUnavailable = false
    private var retryAttempt = 0
    private var remoteDirty = false
    private var credentialsEpoch = 0L
    private var retryJob: Job? = null

    var phase: ClipboardSyncRuntimePhase = ClipboardSyncRuntimePhase.INACTIVE
        private set

    fun activateSession() {
        if (!isScreenInteractive()) return
        actorDesired = true
        activate()
    }

    fun deactivateSession() {
        actorDesired = false
        if (!canStayInBackground()) stop()
    }

    fun forceDeactivateSession() {
        actorDesired = false
        backgroundArmed = false
        stop()
    }

    fun notifyConfigChanged() {
        credentialsEpoch += 1
        session.invalidateReceivePath()
        realtimeConnected = false
        realtimeUnavailable = false
        retryAttempt = 0

        val actorAuthorized = actorDesired && syncEnabled() && isScreenInteractive()
        val backgroundAuthorized = backgroundArmed && canStayInBackground()
        if (!actorAuthorized && !backgroundAuthorized) {
            backgroundArmed = false
            stop()
            return
        }
        if (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) {
            remoteDirty = true
            setPhase(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT)
            return
        }
        enterConfiguredPath()
    }

    fun onScreenOff() {
        if (backgroundArmed && canStayInBackground()) {
            actorDesired = false
            session.pauseClipboardSideEffects()
            setPhase(ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT)
            return
        }
        actorDesired = false
        backgroundArmed = false
        stop()
    }

    fun onScreenOn() {
        if (phase != ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) return
        if (!canStayInBackground()) {
            stop()
            return
        }
        if (realtimeUnavailable) {
            enterWebDavPath()
        } else if (realtimeConnected) {
            session.stopPolling()
            session.resumeClipboardSideEffects()
            setPhase(ClipboardSyncRuntimePhase.REALTIME)
            if (remoteDirty) session.catchUpPull()
            remoteDirty = false
        } else {
            session.stopPolling()
            enterConnecting()
        }
    }

    fun onRemoteProfileChanged(profileJson: String? = null) {
        onRemoteProfileChanged(profileJson, credentialsEpoch)
    }

    fun onNetworkAvailable() {
        if (phase == ClipboardSyncRuntimePhase.INACTIVE ||
            receiveMode() != ClipboardSyncReceiveMode.REALTIME
        ) {
            return
        }
        if (!realtimeConnected && !realtimeUnavailable) {
            beginRealtime()
        }
    }

    fun downloadFile(entryId: String): Boolean = session.downloadFile(entryId)

    fun updateListener(listener: SyncClipboardManager.Listener?) = session.updateListener(listener)

    private fun activate() {
        if (!syncEnabled()) {
            backgroundArmed = false
            stop()
            return
        }
        enterConfiguredPath()
        if (backgroundAllowed() && phase.isRunning()) backgroundArmed = true
    }

    private fun enterConfiguredPath() {
        if (!syncEnabled()) {
            stop()
            return
        }
        when (receiveMode()) {
            ClipboardSyncReceiveMode.OFF -> enterUploadOnly()
            ClipboardSyncReceiveMode.POLLING -> enterPolling(fallback = false)
            ClipboardSyncReceiveMode.REALTIME -> enterConnecting()
        }
    }

    private fun enterUploadOnly() {
        cancelRetry()
        session.stopRealtime()
        session.stop()
        session.start(pollingEnabled = false)
        setPhase(ClipboardSyncRuntimePhase.UPLOAD_ONLY)
    }

    private fun enterPolling(fallback: Boolean) {
        session.stopRealtime()
        session.stop()
        session.start(pollingEnabled = true)
        setPhase(
            if (fallback) ClipboardSyncRuntimePhase.POLLING_FALLBACK
            else ClipboardSyncRuntimePhase.POLLING
        )
    }

    private fun enterConnecting() {
        session.start(pollingEnabled = false)
        setPhase(ClipboardSyncRuntimePhase.CONNECTING)
        beginRealtime()
    }

    private fun enterWebDavPath() {
        cancelRetry()
        enterPolling(fallback = true)
    }

    private fun beginRealtime() {
        if (phase == ClipboardSyncRuntimePhase.INACTIVE) {
            return
        }
        cancelRetry()
        val epoch = credentialsEpoch
        session.startRealtime(object : SyncClipboardSignalRClient.Listener {
            override fun onConnected() {
                scope.launch { onRealtimeConnected(epoch) }
            }

            override fun onDisconnected(error: Throwable?) {
                scope.launch { onRealtimeDisconnected(epoch, error) }
            }

            override fun onRemoteProfileChanged(profileJson: String?) {
                scope.launch { onRemoteProfileChanged(profileJson, epoch) }
            }
        })
    }

    private fun onRealtimeConnected(epoch: Long) {
        if (epoch != credentialsEpoch || phase == ClipboardSyncRuntimePhase.INACTIVE) return
        retryAttempt = 0
        realtimeConnected = true
        realtimeUnavailable = false
        if (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) {
            remoteDirty = true
            return
        }
        cancelRetry()
        session.stopPolling()
        setPhase(ClipboardSyncRuntimePhase.REALTIME)
        session.catchUpPull()
        remoteDirty = false
    }

    private fun onRealtimeDisconnected(epoch: Long, error: Throwable?) {
        if (epoch != credentialsEpoch || phase == ClipboardSyncRuntimePhase.INACTIVE) {
            return
        }
        realtimeConnected = false
        if (error is RealtimeUnavailableException) {
            realtimeUnavailable = true
            if (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) {
                remoteDirty = true
                cancelRetry()
                return
            }
            enterWebDavPath()
            return
        }
        if (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) {
            remoteDirty = true
            scheduleRetry()
            return
        }
        enterPolling(fallback = true)
        scheduleRetry()
    }

    private fun onRemoteProfileChanged(profileJson: String?, epoch: Long) {
        if (epoch != credentialsEpoch || !syncEnabled() || phase == ClipboardSyncRuntimePhase.INACTIVE) {
            return
        }
        if (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT) {
            remoteDirty = true
            return
        }
        remoteDirty = false
        if (profileJson.isNullOrBlank()) {
            session.catchUpPull()
            return
        }
        session.applyRemoteProfile(profileJson) { applied ->
            !applied && epoch == credentialsEpoch && syncEnabled() &&
                phase != ClipboardSyncRuntimePhase.INACTIVE
        }
    }

    private fun scheduleRetry() {
        cancelRetry()
        val delayMs = retryDelayMs(retryAttempt++)
        retryJob = scope.launch {
            delay(delayMs)
            if (phase == ClipboardSyncRuntimePhase.INACTIVE ||
                receiveMode() != ClipboardSyncReceiveMode.REALTIME ||
                (phase == ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT && !canStayInBackground())
            ) {
                return@launch
            }
            beginRealtime()
        }
    }

    private fun stop() {
        cancelRetry()
        session.stop()
        realtimeConnected = false
        realtimeUnavailable = false
        retryAttempt = 0
        remoteDirty = false
        setPhase(ClipboardSyncRuntimePhase.INACTIVE)
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun backgroundAllowed(): Boolean =
        syncEnabled() && receiveMode() == ClipboardSyncReceiveMode.REALTIME &&
            keepBackgroundRealtimeEnabled()

    private fun canStayInBackground(): Boolean = backgroundArmed && backgroundAllowed()

    private fun setPhase(next: ClipboardSyncRuntimePhase) {
        val previous = phase
        phase = next
        if (!previous.isTerminal() && next.isTerminal()) onTerminalPhase(next)
    }

    private fun ClipboardSyncRuntimePhase.isTerminal(): Boolean =
        this == ClipboardSyncRuntimePhase.INACTIVE

    private fun ClipboardSyncRuntimePhase.isRunning(): Boolean =
        this != ClipboardSyncRuntimePhase.INACTIVE &&
            this != ClipboardSyncRuntimePhase.SCREEN_OFF_DORMANT
}
