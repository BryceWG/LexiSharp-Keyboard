// VAD 判停灵敏度调参与设置入口的 JVM 回归测试。
package com.brycewg.asrkb.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadDetectorTuningTest {
    @Test
    fun defaultTuningUsesConfiguredSensitivityLevel() {
        val tuning = VadTuning.Default

        assertEquals(VadTuning.tuningForLevel(4), tuning)
        assertEquals(0.3533f, tuning.threshold, 0.0001f)
        assertEquals(0.50f, tuning.minSilenceDuration, 0.0001f)
        assertEquals(0.25f, tuning.minSpeechDuration, 0.0001f)
        assertEquals(256, tuning.windowSize)
        assertEquals(450, tuning.speechHangoverMs)
        assertEquals(2600, tuning.initialDebounceMs)
    }

    @Test
    fun tuningForLevelUsesLowerAndNarrowerContinuousThresholdCurve() {
        val lowest = VadTuning.tuningForLevel(1)
        val highest = VadTuning.tuningForLevel(10)

        assertEquals(0.28f, lowest.threshold, 0.0001f)
        assertEquals(0.50f, highest.threshold, 0.0001f)
        assertEquals(0.60f, lowest.minSilenceDuration, 0.0001f)
        assertEquals(0.30f, highest.minSilenceDuration, 0.0001f)
        assertEquals(550, lowest.speechHangoverMs)
        assertEquals(250, highest.speechHangoverMs)
        assertEquals(3200, lowest.initialDebounceMs)
        assertEquals(1400, highest.initialDebounceMs)

        (1 until VadTuning.LEVELS).forEach { level ->
            val current = VadTuning.tuningForLevel(level)
            val next = VadTuning.tuningForLevel(level + 1)
            assertTrue(next.threshold > current.threshold)
            assertTrue(next.minSilenceDuration < current.minSilenceDuration)
            assertTrue(next.speechHangoverMs < current.speechHangoverMs)
            assertTrue(next.initialDebounceMs < current.initialDebounceMs)
        }
    }

    @Test
    fun conservativeFilterTuningKeepsOldOfflineFilterBehavior() {
        val tuning = VadTuning.ConservativeFilter

        assertEquals(0.40f, tuning.threshold, 0.0001f)
        assertEquals(0.55f, tuning.minSilenceDuration, 0.0001f)
        assertEquals(0.25f, tuning.minSpeechDuration, 0.0001f)
        assertEquals(256, tuning.windowSize)
        assertEquals(300, tuning.speechHangoverMs)
        assertEquals(3000, tuning.initialDebounceMs)
    }

    @Test
    fun vadDetectorUsesSensitivityConstructorAndResetsContinuousSilenceOnSpeech() {
        val source = mainSource("asr/VadDetector.kt")

        assertTrue(source.contains("sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY"))
        assertTrue(source.contains("fun preload("))
        assertTrue(source.contains("fun rebuildGlobal("))
        assertTrue(source.contains("silentMsAcc = 0"))
        assertTrue(source.contains("silentMsAcc >= windowMs"))
    }

    @Test
    fun silenceSettingsKeepWindowAndStopSensitivity() {
        val section = mainSource("ui/settings/compose/screens/AsrSilenceSection.kt")
        val route = mainSource("ui/settings/compose/screens/AsrSettingsRouteContent.kt")
        val input = mainSource("ui/settings/compose/screens/InputSettingsRouteContent.kt")
        val search = mainSource("ui/settings/search/SettingsSearchIndex.kt")

        assertTrue(section.contains("R.string.label_silence_window_ms"))
        assertTrue(section.contains("R.string.label_silence_sensitivity"))
        assertTrue(section.contains("onSensitivityChange"))
        assertTrue(route.contains("updateSilenceSensitivity"))
        assertTrue(search.contains("label_silence_sensitivity"))
        assertFalse(input.contains("R.string.label_waveform_sensitivity"))
        assertFalse(search.contains("label_waveform_sensitivity"))
    }

    @Test
    fun sensitivityPreferenceRemainsBackedUpAndRestored() {
        val prefs = mainSource("store/Prefs.kt")
        val backup = mainSource("store/PrefsBackup.kt")

        assertTrue(prefs.contains("var autoStopSilenceSensitivity: Int"))
        assertTrue(backup.contains("KEY_AUTO_STOP_SILENCE_SENSITIVITY"))
        assertTrue(backup.contains("autoStopSilenceSensitivity = it"))
    }

    private fun mainSource(relativePath: String): String {
        val root = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
        return File(root, "src/main/java/com/brycewg/asrkb/$relativePath").readText()
    }
}
