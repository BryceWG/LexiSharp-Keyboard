// Tests backup ASR local residency preference persistence and backup restore.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.BackupAsrLocalResidency
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupAsrLocalResidencyPrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultLocalResidencyIsOnDemand() {
        val prefs = Prefs(context)

        assertEquals(BackupAsrLocalResidency.OnDemand, prefs.backupAsrLocalResidency)
    }

    @Test
    fun unknownLocalResidencyFallsBackToOnDemand() {
        val prefs = Prefs(context)
        prefs.setPrefString(KEY_BACKUP_ASR_LOCAL_RESIDENCY, "unknown")

        assertEquals(BackupAsrLocalResidency.OnDemand, prefs.backupAsrLocalResidency)
    }

    @Test
    fun localResidencySelectionPersists() {
        val prefs = Prefs(context)

        prefs.backupAsrLocalResidency = BackupAsrLocalResidency.Resident

        assertEquals(BackupAsrLocalResidency.Resident, Prefs(context).backupAsrLocalResidency)
    }

    @Test
    fun localResidencySelectionIsExportedAndImported() {
        val prefs = Prefs(context)
        prefs.backupAsrLocalResidency = BackupAsrLocalResidency.Resident

        val exported = prefs.exportJsonString()
        val exportedJson = JSONObject(exported)

        assertEquals(BackupAsrLocalResidency.Resident.id, exportedJson.getString(KEY_BACKUP_ASR_LOCAL_RESIDENCY))

        setUp()
        val restored = Prefs(context)
        assertTrue(restored.importJsonString(exported))
        assertEquals(BackupAsrLocalResidency.Resident, restored.backupAsrLocalResidency)
    }
}
