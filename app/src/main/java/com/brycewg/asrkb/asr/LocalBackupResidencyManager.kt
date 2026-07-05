// Manages idle unloading for lazy local backup ASR models.
package com.brycewg.asrkb.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface LocalBackupResidencyHandle {
    fun cancel()
}

internal interface LocalBackupResidencyScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): LocalBackupResidencyHandle
}

internal fun interface LocalBackupResidencyLifecycle {
    fun unload(vendor: AsrVendor)
}

internal interface LocalBackupResidencyController {
    fun onSessionStarted()

    fun onBackupUsed(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency)

    fun onSessionFinished(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency)
}

internal class LocalBackupResidencyManager(
    private val backupVendor: AsrVendor,
    private val idleTtlMs: Long = DEFAULT_IDLE_TTL_MS,
    private val lifecycle: LocalBackupResidencyLifecycle = LocalBackupResidencyLifecycle { vendor ->
        AsrLocalVendorLifecycles.unload(vendor)
    },
    private val scheduler: LocalBackupResidencyScheduler = CoroutineLocalBackupResidencyScheduler()
) : LocalBackupResidencyController {
    private val lock = Any()
    private var activeSessions: Int = 0
    private var pendingUse: Boolean = false
    private var pendingHandle: LocalBackupResidencyHandle? = null

    override fun onSessionStarted() {
        synchronized(lock) {
            activeSessions += 1
            cancelPendingLocked(keepPendingUse = pendingUse || pendingHandle != null)
        }
    }

    override fun onBackupUsed(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) {
        synchronized(lock) {
            pendingUse = true
            if (activeSessions == 0) {
                scheduleIfAllowedLocked(primaryVendor, mode)
            }
        }
    }

    override fun onSessionFinished(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) {
        synchronized(lock) {
            if (activeSessions > 0) {
                activeSessions -= 1
            }
            if (activeSessions == 0 && pendingUse) {
                scheduleIfAllowedLocked(primaryVendor, mode)
            }
        }
    }

    private fun scheduleIfAllowedLocked(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) {
        if (mode != BackupAsrLocalResidency.OnDemand || primaryVendor == backupVendor) {
            pendingUse = false
            cancelPendingLocked(keepPendingUse = false)
            return
        }
        cancelPendingLocked(keepPendingUse = true)
        pendingHandle = scheduler.schedule(idleTtlMs) {
            val shouldUnload = synchronized(lock) {
                if (activeSessions > 0) {
                    pendingUse = true
                    false
                } else {
                    pendingUse = false
                    pendingHandle = null
                    true
                }
            }
            if (shouldUnload) {
                lifecycle.unload(backupVendor)
            }
        }
    }

    private fun cancelPendingLocked(keepPendingUse: Boolean) {
        pendingHandle?.cancel()
        pendingHandle = null
        if (!keepPendingUse) {
            pendingUse = false
        }
    }

    private class CoroutineLocalBackupResidencyScheduler(
        private val scope: CoroutineScope = sharedScope
    ) : LocalBackupResidencyScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit): LocalBackupResidencyHandle {
            val job = scope.launch {
                delay(delayMs.coerceAtLeast(0L))
                task()
            }
            return LocalBackupResidencyHandle { job.cancel() }
        }
    }

    companion object {
        const val DEFAULT_IDLE_TTL_MS: Long = 5 * 60 * 1000L
        private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

internal object LocalBackupResidencyManagers {
    private val managers = mutableMapOf<AsrVendor, LocalBackupResidencyManager>()

    fun forVendor(vendor: AsrVendor): LocalBackupResidencyManager = synchronized(managers) {
        managers.getOrPut(vendor) {
            LocalBackupResidencyManager(backupVendor = vendor)
        }
    }
}
