// Tests ParallelAsrEngine wrapper behavior around primary/backup arbitration.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParallelAsrEngineTest {
    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun switchDeadlineUsesBackupSwitchPlanFallbackDelayBySensitivity() {
        val sensitiveDelayMs = scheduledDeadlineDelayMs(sensitivityTier = 2)
        val balancedDelayMs = scheduledDeadlineDelayMs(sensitivityTier = 1)
        val relaxedDelayMs = scheduledDeadlineDelayMs(sensitivityTier = 0)

        assertEquals(3_000L, sensitiveDelayMs)
        assertEquals(5_000L, balancedDelayMs)
        assertEquals(8_000L, relaxedDelayMs)
    }

    @Test
    fun switchDeadlineDeliversCachedBackupFinalOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context).also { it.backupAsrTimeoutSensitivity = 2 }
        val listener = RecordingListener()
        val engine = ParallelAsrEngine(
            context = context,
            scope = CoroutineScope(ImmediateDelayDispatcher),
            prefs = prefs,
            listener = listener,
            primaryVendor = AsrVendor.Volc,
            backupVendor = AsrVendor.OpenAI,
            externalPcmInput = true
        )
        val primary = FakeStreamingAsrEngine()
        val backup = FakeStreamingAsrEngine()

        engine.setPrivateField("primaryEngine", primary)
        engine.setPrivateField("backupEngine", backup)
        engine.terminalCoordinator().reset(hasPrimary = true, hasBackup = true)
        engine.terminalCoordinator().dispatch(AsrBackupArbitrationEvent.BackupFinal("backup text"))

        engine.invokePrivate("scheduleSwitchDeadlineIfNeeded")
        engine.terminalCoordinator().dispatch(AsrBackupArbitrationEvent.BackupFinal("late backup"))
        engine.terminalCoordinator().dispatch(AsrBackupArbitrationEvent.PrimaryError("late primary"))

        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(listener.errors.isEmpty())
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun fatalCaptureErrorDeliversErrorImmediately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        val listener = RecordingListener()
        val engine = ParallelAsrEngine(
            context = context,
            scope = CoroutineScope(ImmediateDelayDispatcher),
            prefs = prefs,
            listener = listener,
            primaryVendor = AsrVendor.Volc,
            backupVendor = AsrVendor.OpenAI,
            externalPcmInput = true
        )

        engine.terminalCoordinator().reset(hasPrimary = true, hasBackup = true)
        engine.invokePrivate("fatalCaptureError", "record permission denied")

        assertEquals(listOf("record permission denied (backup: record permission denied)"), listener.errors)
        assertTrue(listener.finals.isEmpty())
    }

    private fun scheduledDeadlineDelayMs(sensitivityTier: Int): Long {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context).also { it.backupAsrTimeoutSensitivity = sensitivityTier }
        val dispatcher = RecordingImmediateDelayDispatcher()
        val engine = ParallelAsrEngine(
            context = context,
            scope = CoroutineScope(dispatcher),
            prefs = prefs,
            listener = RecordingListener(),
            primaryVendor = AsrVendor.Volc,
            backupVendor = AsrVendor.OpenAI,
            externalPcmInput = true
        )

        engine.setPrivateField("primaryEngine", FakeStreamingAsrEngine())
        engine.setPrivateField("backupEngine", FakeStreamingAsrEngine())
        engine.terminalCoordinator().reset(hasPrimary = true, hasBackup = true)

        engine.invokePrivate("scheduleSwitchDeadlineIfNeeded")

        return dispatcher.delays.single()
    }

    private fun ParallelAsrEngine.terminalCoordinator(): BackupAsrTerminalCoordinator {
        val field = javaClass.getDeclaredField("terminalCoordinator")
        field.isAccessible = true
        return field.get(this) as BackupAsrTerminalCoordinator
    }

    private fun Any.setPrivateField(name: String, value: Any?) {
        val field = javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    private fun Any.invokePrivate(name: String) {
        val method = javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(this)
    }

    private fun Any.invokePrivate(name: String, value: String) {
        val method = javaClass.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        method.invoke(this, value)
    }

    private class FakeStreamingAsrEngine : StreamingAsrEngine {
        override val isRunning: Boolean = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    private class RecordingListener : StreamingAsrEngine.Listener {
        val finals = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())

        override fun onFinal(text: String) {
            finals += text
        }

        override fun onError(message: String) {
            errors += message
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private object ImmediateDelayDispatcher : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>
        ) {
            continuation.resume(Unit)
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext
        ): DisposableHandle {
            block.run()
            return DisposableHandle { }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class RecordingImmediateDelayDispatcher :
        CoroutineDispatcher(),
        Delay {
        val delays = Collections.synchronizedList(mutableListOf<Long>())

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }

        override fun scheduleResumeAfterDelay(
            timeMillis: Long,
            continuation: CancellableContinuation<Unit>
        ) {
            delays += timeMillis
            continuation.resume(Unit)
        }

        override fun invokeOnTimeout(
            timeMillis: Long,
            block: Runnable,
            context: CoroutineContext
        ): DisposableHandle {
            delays += timeMillis
            block.run()
            return DisposableHandle { }
        }
    }
}
