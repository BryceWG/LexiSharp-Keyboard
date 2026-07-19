// Tests floating-ball hold-to-record preference persistence and backup restore.
package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingBallHoldToRecordPrefsTest {
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
    fun holdToRecordIsDisabledByDefault() {
        assertFalse(Prefs(context).floatingBallHoldToRecordEnabled)
    }

    @Test
    fun holdToRecordSelectionPersists() {
        Prefs(context).floatingBallHoldToRecordEnabled = true

        assertTrue(Prefs(context).floatingBallHoldToRecordEnabled)
    }

    @Test
    fun holdToRecordSelectionIsExportedAndImported() {
        val prefs = Prefs(context)
        prefs.floatingBallHoldToRecordEnabled = true

        val exported = prefs.exportJsonString()
        assertTrue(JSONObject(exported).getBoolean(KEY_FLOATING_HOLD_TO_RECORD_ENABLED))

        setUp()
        val restored = Prefs(context)
        assertTrue(restored.importJsonString(exported))
        assertTrue(restored.floatingBallHoldToRecordEnabled)
    }
}
