/**
 * 设置页更新检查与下载流程协调器。
 *
 * 归属模块：ui/settings/compose/state
 */
package com.brycewg.asrkb.ui.settings.compose.state

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.components.SettingsUpdateUiState
import com.brycewg.asrkb.ui.update.ApkDownloadService
import com.brycewg.asrkb.ui.update.UpdateApkNaming
import com.brycewg.asrkb.ui.update.UpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsUpdateCoordinator(
    private val activity: BaseActivity
) {
    val uiState = mutableStateOf<SettingsUpdateUiState>(SettingsUpdateUiState.Idle)

    val updatesEnabled: Boolean by lazy {
        try {
            activity.resources.getBoolean(R.bool.enable_update_checker)
        } catch (_: Throwable) {
            true
        }
    }

    private val updateChecker: UpdateChecker? by lazy {
        if (updatesEnabled) UpdateChecker(activity) else null
    }

    fun onResume() {
        if (!updatesEnabled) return
        maybeResumePendingApkInstall()
        maybeAutoCheckUpdatesDaily()
    }

    fun checkForUpdates() {
        if (!updatesEnabled) return
        Log.d(TAG, "User initiated update check")

        cleanOldApkFiles()
        uiState.value = SettingsUpdateUiState.Checking

        activity.lifecycleScope.launch {
            try {
                val checker = updateChecker ?: return@launch
                val result = withContext(Dispatchers.IO) { checker.checkGitHubRelease() }

                uiState.value = if (result.hasUpdate) {
                    Log.d(TAG, "Update available: ${result.latestVersion}")
                    SettingsUpdateUiState.UpdateAvailable(result)
                } else {
                    Log.d(TAG, "No update available")
                    SettingsUpdateUiState.CurrentVersion(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                uiState.value = SettingsUpdateUiState.CheckFailed(e.message ?: "Unknown error")
            }
        }
    }

    fun showDownloadSources(result: UpdateChecker.UpdateCheckResult) {
        if (!updatesEnabled) return
        val directApkUrls = buildDirectApkUrls(result.downloadUrl, result.latestVersion)
        val downloadOptions = DownloadSourceConfig.buildOptions(activity, directApkUrls)
        uiState.value = SettingsUpdateUiState.DownloadSources(
            version = result.latestVersion,
            options = downloadOptions
        )
    }

    fun startDownload(option: DownloadSourceOption, version: String) {
        if (!updatesEnabled) return
        try {
            ApkDownloadService.startDownload(activity, option.fallbackUrls, version)
            showMessage(R.string.update_feedback_title, R.string.apk_download_started)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download", e)
            showMessage(R.string.update_feedback_title, R.string.apk_download_start_failed)
        }
    }

    fun dismiss() {
        uiState.value = SettingsUpdateUiState.Idle
    }

    fun openReleasePage(url: String) {
        openUrl(url)
    }

    fun openChangelogHistory() {
        openUrl("https://bibi.brycewg.com/changelog.html")
    }

    fun openManualReleasePage() {
        openUrl("https://github.com/BryceWG/BiBi-Keyboard/releases")
    }

    private fun maybeResumePendingApkInstall() {
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) return

            val prefs = Prefs(activity)
            val path = prefs.pendingApkPath
            if (path.isBlank()) return

            val apkFile = File(path)
            if (!apkFile.exists()) {
                prefs.pendingApkPath = ""
                return
            }

            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            prefs.pendingApkPath = ""
            activity.startActivity(intent)
            Log.d(TAG, "Resumed pending APK install: ${apkFile.path}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to resume pending APK install", t)
        }
    }

    private fun maybeAutoCheckUpdatesDaily() {
        try {
            val prefs = Prefs(activity)
            if (!prefs.autoUpdateCheckEnabled) {
                return
            }
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            if (prefs.lastUpdateCheckDate == today) {
                Log.d(TAG, "Already checked update today, skipping auto check")
                return
            }

            prefs.lastUpdateCheckDate = today
            Log.d(TAG, "Starting daily auto update check")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/update last update check date", e)
            return
        }

        val checker = updateChecker ?: return
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { checker.checkGitHubRelease() }

                if (result.hasUpdate) {
                    Log.d(TAG, "Auto check found update: ${result.latestVersion}")
                    uiState.value = SettingsUpdateUiState.UpdateAvailable(result)
                } else {
                    Log.d(TAG, "Auto check: no update available")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Auto update check failed (silent): ${e.message}")
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
            showMessage(R.string.update_feedback_title, R.string.error_open_browser)
        }
    }

    private fun showMessage(titleRes: Int, messageRes: Int) {
        uiState.value = SettingsUpdateUiState.Message(
            title = activity.getString(titleRes),
            message = activity.getString(messageRes)
        )
    }

    private fun buildDirectApkUrls(originalUrl: String, version: String): List<String> {
        val baseEnd = originalUrl.indexOf("/releases/tag/")
        val base = if (baseEnd > 0) {
            originalUrl.substring(0, baseEnd)
        } else {
            "https://github.com/BryceWG/BiBi-Keyboard"
        }
        val tag = "v$version"
        return UpdateApkNaming.releaseDownloadCandidates(version, selectUpdateApkAbi()).map { apkName ->
            "$base/releases/download/$tag/$apkName"
        }
    }

    private fun selectUpdateApkAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        return when {
            "arm64-v8a" in supportedAbis -> "arm64-v8a"
            "armeabi-v7a" in supportedAbis -> "armeabi-v7a"
            else -> "arm64-v8a"
        }
    }

    private fun cleanOldApkFiles() {
        if (!updatesEnabled) return
        try {
            ApkDownloadService.cleanOldApks(activity)
            Log.d(TAG, "Old APK files cleaned")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old APK files", e)
        }
    }

    private companion object {
        private const val TAG = "SettingsUpdateCoordinator"
    }
}
