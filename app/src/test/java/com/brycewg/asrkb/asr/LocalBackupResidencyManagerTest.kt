// Tests idle residency lifecycle for lazy local backup ASR models.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupResidencyManagerTest {
    @Test
    fun backupUseSchedulesIdleUnloadAfterSessionFinishes() {
        val lifecycle = FakeLifecycle()
        val scheduler = FakeScheduler()
        val manager = manager(lifecycle, scheduler)

        manager.onSessionStarted()
        manager.onBackupUsed(
            primaryVendor = AsrVendor.Volc,
            mode = BackupAsrLocalResidency.OnDemand
        )
        assertEquals(0, scheduler.activePendingCount)

        manager.onSessionFinished(
            primaryVendor = AsrVendor.Volc,
            mode = BackupAsrLocalResidency.OnDemand
        )
        assertEquals(1, scheduler.activePendingCount)

        scheduler.runLatest()

        assertEquals(listOf(AsrVendor.SenseVoice), lifecycle.unloaded)
    }

    @Test
    fun backupReuseResetsIdleUnloadTimer() {
        val lifecycle = FakeLifecycle()
        val scheduler = FakeScheduler()
        val manager = manager(lifecycle, scheduler)

        manager.onSessionStarted()
        manager.onBackupUsed(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        manager.onSessionFinished(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        val firstTask = scheduler.latestTask()

        manager.onSessionStarted()
        manager.onBackupUsed(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        manager.onSessionFinished(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        val secondTask = scheduler.latestTask()

        firstTask.runIfActive()
        assertTrue(lifecycle.unloaded.isEmpty())

        secondTask.runIfActive()
        assertEquals(listOf(AsrVendor.SenseVoice), lifecycle.unloaded)
    }

    @Test
    fun activeSessionPreventsIdleUnload() {
        val lifecycle = FakeLifecycle()
        val scheduler = FakeScheduler()
        val manager = manager(lifecycle, scheduler)

        manager.onSessionStarted()
        manager.onBackupUsed(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        manager.onSessionFinished(AsrVendor.Volc, BackupAsrLocalResidency.OnDemand)
        val pendingTask = scheduler.latestTask()

        manager.onSessionStarted()
        pendingTask.runIfActive()

        assertTrue(lifecycle.unloaded.isEmpty())
    }

    @Test
    fun residentModeDoesNotScheduleIdleUnload() {
        val lifecycle = FakeLifecycle()
        val scheduler = FakeScheduler()
        val manager = manager(lifecycle, scheduler)

        manager.onSessionStarted()
        manager.onBackupUsed(AsrVendor.Volc, BackupAsrLocalResidency.Resident)
        manager.onSessionFinished(AsrVendor.Volc, BackupAsrLocalResidency.Resident)

        assertEquals(0, scheduler.activePendingCount)
        assertTrue(lifecycle.unloaded.isEmpty())
    }

    @Test
    fun primaryUsingSameVendorDoesNotScheduleIdleUnload() {
        val lifecycle = FakeLifecycle()
        val scheduler = FakeScheduler()
        val manager = manager(lifecycle, scheduler)

        manager.onSessionStarted()
        manager.onBackupUsed(AsrVendor.SenseVoice, BackupAsrLocalResidency.OnDemand)
        manager.onSessionFinished(AsrVendor.SenseVoice, BackupAsrLocalResidency.OnDemand)

        assertEquals(0, scheduler.activePendingCount)
        assertTrue(lifecycle.unloaded.isEmpty())
    }

    private fun manager(
        lifecycle: FakeLifecycle,
        scheduler: FakeScheduler
    ): LocalBackupResidencyManager = LocalBackupResidencyManager(
        backupVendor = AsrVendor.SenseVoice,
        idleTtlMs = 123L,
        lifecycle = lifecycle,
        scheduler = scheduler
    )

    private class FakeLifecycle : LocalBackupResidencyLifecycle {
        val unloaded = mutableListOf<AsrVendor>()

        override fun unload(vendor: AsrVendor) {
            unloaded += vendor
        }
    }

    private class FakeScheduler : LocalBackupResidencyScheduler {
        private val tasks = mutableListOf<FakeTask>()

        val activePendingCount: Int
            get() = tasks.count { !it.cancelled }

        override fun schedule(delayMs: Long, task: () -> Unit): LocalBackupResidencyHandle {
            val pending = FakeTask(delayMs, task)
            tasks += pending
            return pending
        }

        fun latestTask(): FakeTask = tasks.last()

        fun runLatest() {
            latestTask().runIfActive()
        }
    }

    private class FakeTask(
        val delayMs: Long,
        private val task: () -> Unit
    ) : LocalBackupResidencyHandle {
        var cancelled: Boolean = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun runIfActive() {
            if (!cancelled) task()
        }
    }
}
