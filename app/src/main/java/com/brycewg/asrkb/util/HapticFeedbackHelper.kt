package com.brycewg.asrkb.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import com.brycewg.asrkb.store.Prefs

object HapticFeedbackHelper {
    private const val TAG = "HapticFeedbackHelper"
    private const val DEFAULT_DURATION_MS = 20L

    fun performTap(context: Context, prefs: Prefs, view: View? = null) {
        when (prefs.hapticFeedbackLevel) {
            Prefs.HAPTIC_FEEDBACK_LEVEL_OFF -> return
            Prefs.HAPTIC_FEEDBACK_LEVEL_SYSTEM -> {
                if (view != null) {
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        return
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to perform view haptic feedback", e)
                    }
                }
                vibrate(context, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            else -> {
                val amplitude = amplitudeForLevel(prefs.hapticFeedbackLevel) ?: return
                vibrate(context, amplitude)
            }
        }
    }

    private fun amplitudeForLevel(level: Int): Int? {
        return when (level) {
            Prefs.HAPTIC_FEEDBACK_LEVEL_WEAK -> 30
            Prefs.HAPTIC_FEEDBACK_LEVEL_LIGHT -> 50
            Prefs.HAPTIC_FEEDBACK_LEVEL_MEDIUM -> 70
            Prefs.HAPTIC_FEEDBACK_LEVEL_STRONG -> 100
            Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY -> 140
            else -> null
        }
    }

    private fun vibrate(context: Context, amplitude: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val safeAmplitude = if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
            amplitude
        } else {
            amplitude.coerceIn(1, 255)
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(DEFAULT_DURATION_MS, safeAmplitude))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }
}
