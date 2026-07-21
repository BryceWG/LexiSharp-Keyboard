package com.brycewg.asrkb.clipboard

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 主进程 Direct Actor 会话：包装 [SyncClipboardManager] + SignalR，不依赖 FloatingAsrService。
 */
internal class DirectClipboardSyncRuntimeSession(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    initialListener: SyncClipboardManager.Listener? = null,
    private val clipboardStore: ClipboardHistoryStore? = ClipboardHistoryStore(context, prefs),
    private val realtimeClientFactory: (
        serverBase: String,
        username: String,
        password: String
    ) -> SyncClipboardSignalRClient = { serverBase, username, password ->
        SyncClipboardSignalRClient(
            serverBase = serverBase,
            username = username,
            password = password,
            scope = scope
        )
    },
    initialManager: SyncClipboardManager? = null
) : ClipboardSyncRuntimeSession {
    companion object {
        private const val TAG = "ClipboardSyncRuntime"
    }

    private val listenerHolder = object : SyncClipboardManager.Listener {
        @Volatile var delegate: SyncClipboardManager.Listener? = initialListener

        override fun onPulledNewContent(text: String) {
            delegate?.onPulledNewContent(text)
        }

        override fun onUploadSuccess() {
            delegate?.onUploadSuccess()
        }

        override fun onUploadFailed(reason: String?) {
            delegate?.onUploadFailed(reason)
        }

        override fun onFilePulled(type: EntryType, fileName: String, serverFileName: String) {
            delegate?.onFilePulled(type, fileName, serverFileName)
        }
    }

    @Volatile private var manager: SyncClipboardManager? = initialManager
    @Volatile private var realtimeClient: SyncClipboardSignalRClient? = null
    private val receiveMutex = Mutex()

    override fun updateListener(listener: SyncClipboardManager.Listener?) {
        listenerHolder.delegate = listener
    }

    override fun start(pollingEnabled: Boolean) {
        val mgr = ensureManager()
        mgr.start(pollingEnabled)
        scope.launch(Dispatchers.IO) {
            try {
                mgr.proactiveUploadIfChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed proactive sync on session start", t)
            }
        }
    }

    override fun stopPolling() {
        manager?.setPollingEnabled(false)
    }

    override fun stop() {
        stopRealtime()
        try {
            manager?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stop SyncClipboardManager", t)
        }
    }

    override fun invalidateReceivePath() {
        stopRealtime()
        manager?.invalidateReceivePath()
    }

    override fun catchUpPull() {
        val mgr = ensureManager()
        val requestEpoch = mgr.captureRemoteProfileEpoch()
        scope.launch {
            receiveMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        mgr.pullNow(updateClipboard = true, requestEpoch = requestEpoch)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed catch-up pull", t)
                    }
                }
            }
        }
    }

    override fun applyRemoteProfile(profileJson: String, onResult: (Boolean) -> Boolean) {
        val mgr = ensureManager()
        val requestEpoch = mgr.captureRemoteProfileEpoch()
        scope.launch {
            receiveMutex.withLock {
                val applied = withContext(Dispatchers.IO) {
                    try {
                        mgr.applyRemoteProfileJson(profileJson, requestEpoch)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed realtime profile apply", t)
                        false
                    }
                }
                val shouldFallback = onResult(applied)
                if (!applied && shouldFallback) {
                    withContext(Dispatchers.IO) {
                        try {
                            mgr.pullNow(updateClipboard = true, requestEpoch = requestEpoch)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed realtime profile fallback pull", t)
                        }
                    }
                }
            }
        }
    }

    override fun downloadFile(entryId: String): Boolean =
        try {
            manager?.downloadFile(entryId) ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download clipboard file", t)
            false
        }

    override fun pauseClipboardSideEffects() {
        try {
            manager?.pauseClipboardSideEffects()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to pause clipboard side effects", t)
        }
    }

    override fun resumeClipboardSideEffects() {
        try {
            manager?.resumeClipboardSideEffects()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resume clipboard side effects", t)
        }
    }

    override fun startRealtime(listener: SyncClipboardSignalRClient.Listener) {
        stopRealtime()
        if (!prefs.syncClipboardEnabled ||
            prefs.syncClipboardReceiveMode != ClipboardSyncReceiveMode.REALTIME
        ) {
            listener.onDisconnected(IllegalStateException("realtime not enabled"))
            return
        }
        val serverBase = prefs.syncClipboardServerBase
        if (serverBase.isBlank()) {
            listener.onDisconnected(IllegalStateException("empty server base"))
            return
        }
        val client = realtimeClientFactory(
            serverBase,
            prefs.syncClipboardUsername,
            prefs.syncClipboardPassword
        )
        realtimeClient = client
        client.start(object : SyncClipboardSignalRClient.Listener {
            override fun onConnected() {
                listener.onConnected()
            }

            override fun onDisconnected(error: Throwable?) {
                listener.onDisconnected(error)
            }

            override fun onRemoteProfileChanged(profileJson: String?) {
                listener.onRemoteProfileChanged(profileJson)
            }
        })
    }

    override fun stopRealtime() {
        try {
            realtimeClient?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stop realtime client", t)
        }
        realtimeClient = null
    }

    private fun ensureManager(): SyncClipboardManager {
        manager?.let { return it }
        val created = SyncClipboardManager(
            context = context,
            prefs = prefs,
            scope = scope,
            listener = listenerHolder,
            clipboardStore = clipboardStore,
            clipboardPort = SystemClipboardPortFactory.create(context, prefs)
        )
        manager = created
        return created
    }
}
