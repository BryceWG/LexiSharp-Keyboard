package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsOptionSheet
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.slider.Slider

internal class AsrSettingsBinding(
    val activity: AsrSettingsActivity,
    val rootView: View,
    val prefs: Prefs,
    val viewModel: AsrSettingsViewModel,
    val modelImportUiController: ModelImportUiController,
    val modelDownloadUiController: ModelDownloadUiController,
    val senseVoiceModelPicker: ActivityResultLauncher<String>,
    val funAsrNanoModelPicker: ActivityResultLauncher<String>,
    val telespeechModelPicker: ActivityResultLauncher<String>,
    val paraformerModelPicker: ActivityResultLauncher<String>,
    val punctuationModelPicker: ActivityResultLauncher<String>,
) {

    inline fun <reified T : View> view(id: Int): T = rootView.findViewById(id)

    inline fun <reified T : View> viewOrNull(id: Int): T? = rootView.findViewById(id)

    fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(activity, prefs, view)
    }

    fun openUrlSafely(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to open url: $url", t)
            Toast.makeText(activity, activity.getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    fun setupSlider(slider: Slider, onValueChange: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                onValueChange(value)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })
    }

    fun showSingleChoiceDialog(
        titleResId: Int,
        items: Array<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        SettingsOptionSheet.showSingleChoice(
            context = activity,
            titleResId = titleResId,
            items = items.toList(),
            selectedIndex = currentIndex,
            onSelected = onSelected,
        )
    }

    private companion object {
        private const val TAG = "AsrSettingsBinding"
    }
}
