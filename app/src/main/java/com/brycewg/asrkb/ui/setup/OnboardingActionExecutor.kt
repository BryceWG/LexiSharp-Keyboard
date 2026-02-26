/**
 * 新手引导中的可复用动作执行器。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceDialog
import com.brycewg.asrkb.ui.settings.asr.ModelDownloadService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 负责执行引导页中的“服务选择”动作，避免在多个页面重复业务逻辑。
 */
internal class OnboardingActionExecutor(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "OnboardingActionExecutor"
        private const val SENSEVOICE_VARIANT = "small-full"
        private const val SENSEVOICE_ZIP_URL = "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"
    }

    private val prefs: Prefs = Prefs(activity)

    fun applySiliconFlowFree(onFinished: (() -> Unit)? = null) {
        prefs.asrVendor = AsrVendor.SiliconFlow
        prefs.sfFreeAsrEnabled = true
        prefs.sfFreeLlmEnabled = true
        Toast.makeText(activity, activity.getString(R.string.model_guide_sf_free_ready), Toast.LENGTH_SHORT).show()
        onFinished?.invoke()
    }

    fun applyLocalModel(onFinished: (() -> Unit)? = null) {
        prefs.asrVendor = AsrVendor.SenseVoice
        prefs.svModelVariant = "small-int8"
        prefs.sfFreeAsrEnabled = false
        prefs.sfFreeLlmEnabled = false
        try {
            showModelDownloadSourceDialog(onFinished)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show local model flow", t)
            onFinished?.invoke()
        }
    }

    fun applyOnlineCustom(onFinished: (() -> Unit)? = null) {
        prefs.sfFreeAsrEnabled = false
        prefs.sfFreeLlmEnabled = false
        try {
            showOnlineModelConfigGuide(onFinished)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show online model flow", t)
            onFinished?.invoke()
        }
    }

    private fun showOnlineModelConfigGuide(onFinished: (() -> Unit)? = null) {
        val docUrl = activity.getString(R.string.model_guide_config_doc_url)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.model_guide_option_online)
            .setMessage(R.string.model_guide_online_dialog_message)
            .setPositiveButton(R.string.btn_get_api_key_guide) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(docUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open documentation", e)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.external_aidl_guide_open_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnDismissListener { onFinished?.invoke() }
        dialog.show()
    }

    private fun showModelDownloadSourceDialog(onFinished: (() -> Unit)? = null) {
        val downloadOptions = DownloadSourceConfig.buildOptions(activity, SENSEVOICE_ZIP_URL)

        DownloadSourceDialog.show(
            context = activity,
            titleRes = R.string.download_source_title,
            options = downloadOptions,
            onDismiss = onFinished,
        ) { option ->
            try {
                ModelDownloadService.startDownload(
                    activity,
                    option.url,
                    SENSEVOICE_VARIANT,
                )
                Toast.makeText(
                    activity,
                    activity.getString(R.string.model_guide_downloading),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start model download", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.sv_download_status_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
