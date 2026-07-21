package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncClipboardReceiveModePrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @Test
    fun migrate_legacyReceiveSettings() {
        data class Case(
            val autoPull: Boolean,
            val realtime: Boolean,
            val mode: ClipboardSyncReceiveMode
        )
        listOf(
            Case(false, false, ClipboardSyncReceiveMode.OFF),
            Case(true, false, ClipboardSyncReceiveMode.POLLING),
            Case(false, true, ClipboardSyncReceiveMode.REALTIME),
            Case(true, true, ClipboardSyncReceiveMode.REALTIME)
        ).forEach { case ->
            clearPrefs()
            writeLegacy(case.autoPull, case.realtime)

            val prefs = Prefs(context)

            assertEquals(case.mode, prefs.syncClipboardReceiveMode)
            assertFalse(prefs.syncClipboardKeepBackgroundRealtimeEnabled)
        }
    }

    @Test
    fun migrate_doesNotRerunWhenReceiveModeAlreadyPresent() {
        val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_SC_RECEIVE_MODE, ClipboardSyncReceiveMode.OFF.id)
            .putBoolean(KEY_SC_AUTO_PULL, true)
            .putBoolean(KEY_SC_REALTIME, true)
            .putBoolean(KEY_SC_KEEP_BACKGROUND_REALTIME, false)
            .commit()

        val prefs = Prefs(context)

        assertEquals(ClipboardSyncReceiveMode.OFF, prefs.syncClipboardReceiveMode)
    }

    @Test
    fun keepBackgroundDefaultsFalseWhenMissingAfterReceiveModeExists() {
        val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
        sp.edit()
            .putString(KEY_SC_RECEIVE_MODE, ClipboardSyncReceiveMode.REALTIME.id)
            .commit()

        val prefs = Prefs(context)

        assertFalse(prefs.syncClipboardKeepBackgroundRealtimeEnabled)
        assertTrue(sp.contains(KEY_SC_KEEP_BACKGROUND_REALTIME))
        assertFalse(sp.getBoolean(KEY_SC_KEEP_BACKGROUND_REALTIME, true))
    }

    @Test
    fun newPrefs_exportAndImport_roundTrip() {
        val prefs = Prefs(context)
        prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.REALTIME
        prefs.syncClipboardKeepBackgroundRealtimeEnabled = true
        prefs.syncClipboardPullIntervalSec = 42

        val exported = prefs.exportJsonString()
        val json = JSONObject(exported)
        assertEquals(ClipboardSyncReceiveMode.REALTIME.id, json.getString(KEY_SC_RECEIVE_MODE))
        assertTrue(json.getBoolean(KEY_SC_AUTO_PULL))
        assertTrue(json.getBoolean(KEY_SC_KEEP_BACKGROUND_REALTIME))

        clearPrefs()
        val restored = Prefs(context)
        assertTrue(restored.importJsonString(exported))
        assertEquals(ClipboardSyncReceiveMode.REALTIME, restored.syncClipboardReceiveMode)
        assertTrue(restored.syncClipboardKeepBackgroundRealtimeEnabled)
        assertEquals(42, restored.syncClipboardPullIntervalSec)
    }

    @Test
    fun import_legacyBackup_withOnlyAutoPull_mapsToReceiveMode() {
        val payload = JSONObject()
            .put(KEY_SC_ENABLED, true)
            .put(KEY_SC_AUTO_PULL, true)
            .put(KEY_SC_PULL_INTERVAL_SEC, 20)
            .toString()

        val prefs = Prefs(context)
        assertTrue(prefs.importJsonString(payload))

        assertEquals(ClipboardSyncReceiveMode.POLLING, prefs.syncClipboardReceiveMode)
        assertEquals(20, prefs.syncClipboardPullIntervalSec)
    }

    @Test
    fun legacyAutoPullAccessor_mapsToPollingOrOff() {
        val prefs = Prefs(context)

        prefs.syncClipboardAutoPullEnabled = true
        assertEquals(ClipboardSyncReceiveMode.POLLING, prefs.syncClipboardReceiveMode)
        assertTrue(prefs.syncClipboardAutoPullEnabled)

        prefs.syncClipboardAutoPullEnabled = false
        assertEquals(ClipboardSyncReceiveMode.OFF, prefs.syncClipboardReceiveMode)
        assertFalse(prefs.syncClipboardAutoPullEnabled)
    }

    private fun writeLegacy(autoPull: Boolean, realtime: Boolean) {
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SC_AUTO_PULL, autoPull)
            .putBoolean(KEY_SC_REALTIME, realtime)
            .commit()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
