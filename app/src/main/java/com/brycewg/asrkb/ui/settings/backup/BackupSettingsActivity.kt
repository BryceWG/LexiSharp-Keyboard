/**
 * 备份与同步相关设置页面。
 *
 * 归属模块：ui/settings/backup
 */
package com.brycewg.asrkb.ui.settings.backup

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.settings.search.SettingsSearchNavigator
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : BaseActivity() {
    companion object {
        private const val TAG = "BackupSettingsActivity"
    }

    private lateinit var prefs: Prefs
    private val http by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_settings)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        findViewById<android.view.View>(android.R.id.content).let { rootView ->
            com.brycewg.asrkb.ui.WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_backup_settings)
        toolbar.setNavigationOnClickListener { finish() }

        setupFileSection()
        setupWebdavSection()
    }

    override fun onPostResume() {
        super.onPostResume()
        SettingsSearchNavigator.applyScrollAndHighlightIfNeeded(this)
    }

    // ================= 文件导入/导出 =================
    private fun setupFileSection() {
        val btnExport = findViewById<MaterialButton>(R.id.btnExportToFile)
        val btnImport = findViewById<MaterialButton>(R.id.btnImportFromFile)

        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri != null) exportSettings(uri)
        }

        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) importSettings(uri)
        }

        btnExport.setOnClickListener {
            hapticTapIfEnabled(it)
            val fileName = "asr_keyboard_settings_" +
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) +
                ".json"
            exportLauncher.launch(fileName)
        }

        btnImport.setOnClickListener {
            hapticTapIfEnabled(it)
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }

    private fun exportSettings(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val jsonString = prefs.exportJsonString()
                os.write(jsonString.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val name = uri.lastPathSegment ?: "settings.json"
            Toast.makeText(this, getString(R.string.toast_export_success, name), Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Settings exported successfully to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""

            val success = prefs.importJsonString(json)
            if (success) {
                // 导入完成后，通知 IME 即时刷新（包含高度与按钮交换等）
                try {
                    sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send refresh broadcast", e)
                }
                Toast.makeText(this, getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Settings imported successfully from $uri")
            } else {
                Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Failed to import settings (invalid JSON or parsing error)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ================= WebDAV 同步 =================
    private fun setupWebdavSection() {
        val etUrl = findViewById<TextInputEditText>(R.id.etWebdavUrl)
        val etUser = findViewById<TextInputEditText>(R.id.etWebdavUsername)
        val etPass = findViewById<TextInputEditText>(R.id.etWebdavPassword)
        val btnUpload = findViewById<MaterialButton>(R.id.btnWebdavUpload)
        val btnDownload = findViewById<MaterialButton>(R.id.btnWebdavDownload)

        etUrl.setText(prefs.webdavUrl)
        etUser.setText(prefs.webdavUsername)
        etPass.setText(prefs.webdavPassword)

        etUrl.addTextChangedListener(SimpleTextWatcher { prefs.webdavUrl = it })
        etUser.addTextChangedListener(SimpleTextWatcher { prefs.webdavUsername = it })
        etPass.addTextChangedListener(SimpleTextWatcher { prefs.webdavPassword = it })

        btnUpload.setOnClickListener {
            hapticTapIfEnabled(it)
            uploadToWebdav()
        }
        btnDownload.setOnClickListener {
            hapticTapIfEnabled(it)
            downloadFromWebdav()
        }
    }

    private fun uploadToWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = WebDavBackupHelper.uploadSettingsWithStatus(this@BackupSettingsActivity, prefs)
            withContext(Dispatchers.Main) {
                if (result is WebDavBackupHelper.UploadResult.Success) {
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_upload_success), Toast.LENGTH_SHORT).show()
                } else {
                    val error = (result as? WebDavBackupHelper.UploadResult.Error)
                    val reason = buildWebdavErrorReason(error?.statusCode, error?.responsePhrase)
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_upload_failed, reason), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadFromWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = WebDavBackupHelper.downloadSettingsWithStatus(prefs)
            var imported = false
            var errorReason: String? = null

            when (result) {
                is WebDavBackupHelper.DownloadResult.Success -> {
                    imported = prefs.importJsonString(result.json)
                    if (!imported) {
                        errorReason = getString(R.string.toast_import_failed)
                    }
                }
                is WebDavBackupHelper.DownloadResult.NotFound -> {
                    errorReason = getString(R.string.toast_webdav_backup_not_found)
                }
                is WebDavBackupHelper.DownloadResult.Error -> {
                    errorReason = buildWebdavErrorReason(result.statusCode, result.responsePhrase)
                }
            }

            withContext(Dispatchers.Main) {
                if (imported) {
                    try {
                        sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send refresh broadcast", e)
                    }
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_download_success), Toast.LENGTH_SHORT).show()
                } else {
                    val reasonText = errorReason ?: getString(R.string.toast_import_failed)
                    Toast.makeText(this@BackupSettingsActivity, getString(R.string.toast_webdav_download_failed, reasonText), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }
}

private fun BackupSettingsActivity.buildWebdavErrorReason(statusCode: Int?, responsePhrase: String?): String {
    return when {
        statusCode != null && !responsePhrase.isNullOrBlank() -> "$statusCode $responsePhrase"
        statusCode != null -> statusCode.toString()
        !responsePhrase.isNullOrBlank() -> responsePhrase
        else -> "HTTP"
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString() ?: "")
    }
}
