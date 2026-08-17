// Tests auto-switch IME after ASR preference persistence and backup restore.
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
class AutoSwitchImeAfterAsrPrefsTest {
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
    fun autoSwitchImeAfterAsrIsDisabledByDefault() {
        assertFalse(Prefs(context).autoSwitchImeAfterAsrEnabled)
    }

    @Test
    fun autoSwitchImeAfterAsrSelectionPersists() {
        Prefs(context).autoSwitchImeAfterAsrEnabled = true

        assertTrue(Prefs(context).autoSwitchImeAfterAsrEnabled)
    }

    @Test
    fun autoSwitchImeAfterAsrSelectionIsExportedAndImported() {
        val prefs = Prefs(context)
        prefs.autoSwitchImeAfterAsrEnabled = true

        val exported = prefs.exportJsonString()
        assertTrue(JSONObject(exported).getBoolean(KEY_AUTO_SWITCH_IME_AFTER_ASR))

        setUp()
        val restored = Prefs(context)
        assertTrue(restored.importJsonString(exported))
        assertTrue(restored.autoSwitchImeAfterAsrEnabled)
    }
}
