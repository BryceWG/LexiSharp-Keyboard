package com.brycewg.asrkb.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import com.brycewg.asrkb.store.Prefs
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_SHOW_IME_PICKER = "extra_auto_show_ime_picker"
    }

    private var wasAccessibilityEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        wasAccessibilityEnabled = isAccessibilityServiceEnabled()

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnAllPermissions).setOnClickListener {
            requestAllPermissions()
        }

        findViewById<Button>(R.id.btnShowGuide).setOnClickListener {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null, false)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_quick_guide)
                .setView(view)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            checkForUpdates()
        }

        // 关于
        findViewById<Button>(R.id.btnAbout)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // 直达子设置页
        findViewById<Button>(R.id.btnOpenAsrSettings)?.setOnClickListener {
            startActivity(Intent(this, AsrSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenAiSettings)?.setOnClickListener {
            startActivity(Intent(this, AiPostSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenPunctSettings)?.setOnClickListener {
            startActivity(Intent(this, PunctSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenFloatingSettings)?.setOnClickListener {
            startActivity(Intent(this, FloatingSettingsActivity::class.java))
        }

        val prefs = Prefs(this)
        val spLanguage = findViewById<Spinner>(R.id.spLanguage)
        val tvAsrTotalChars = findViewById<TextView>(R.id.tvAsrTotalChars)
        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchAutoSwitchPassword = findViewById<MaterialSwitch>(R.id.switchAutoSwitchPassword)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)


        fun applyPrefsToUi() {
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            switchAutoSwitchPassword.isChecked = prefs.autoSwitchOnPassword
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            try {
                tvAsrTotalChars.text = getString(R.string.label_asr_total_chars, prefs.totalAsrChars)
            } catch (_: Throwable) { }
        }
        applyPrefsToUi()
        // 已在 applyPrefsToUi 中统一设置上述字段

        // 应用语言选择器设置（独立于高级视图）
        val languageItems = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_en)
        )
        spLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageItems)
        val savedTag = prefs.appLanguageTag
        spLanguage.setSelection(
            when (savedTag) {
                "zh", "zh-CN", "zh-Hans" -> 1
                "en" -> 2
                else -> 0
            }
        )
        val languageSpinnerInitialized = true

        spLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!languageSpinnerInitialized) return
                val newTag = when (position) {
                    // 使用通用中文标签，避免区域标签在部分设备上匹配异常
                    1 -> "zh-CN"
                    2 -> "en"
                    else -> "" // 跟随系统
                }
                if (newTag != prefs.appLanguageTag) {
                    prefs.appLanguageTag = newTag
                    val locales = if (newTag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(newTag)
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }


        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            prefs.trimFinalTrailingPunct = switchTrimTrailingPunct.isChecked
            prefs.autoSwitchOnPassword = switchAutoSwitchPassword.isChecked
            prefs.micHapticEnabled = switchMicHaptic.isChecked
            prefs.micTapToggleEnabled = switchMicTapToggle.isChecked
            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        }


        // 设置导入/导出
        val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(Prefs(this).exportJsonString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    val name = uri.lastPathSegment ?: "settings.json"
                    Toast.makeText(this, getString(R.string.toast_export_success, name), Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val ok = Prefs(this).importJsonString(json)
                    if (ok) {
                        // 重新从 Prefs 读取并刷新界面与下拉框，确保导入立即生效
                        applyPrefsToUi()
                        // 高级控件（如存在）刷新在对应页面完成
                        // 同步语言选择（将触发 onItemSelected 从而应用语言）
                        val tag = prefs.appLanguageTag
                        spLanguage.setSelection(
                            when (tag) {
                                "zh", "zh-CN", "zh-Hans" -> 1
                                "en" -> 2
                                else -> 0
                            }
                        )
                        Toast.makeText(this, getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnExportSettings).setOnClickListener {
            val fileName = "asr_keyboard_settings_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date()) + ".json"
            exportLauncher.launch(fileName)
        }
        findViewById<Button>(R.id.btnImportSettings).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }


    private var autoShownImePicker = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onResume() {
        super.onResume()

        // 检查无障碍服务是否刚刚被启用
        Prefs(this)
        val isNowEnabled = isAccessibilityServiceEnabled()

        if (!wasAccessibilityEnabled && isNowEnabled) {
            // 无障碍服务刚刚被启用
            Log.d("SettingsActivity", "Accessibility service just enabled")
            Toast.makeText(this, "无障碍服务已启用,现在可以自动插入文本了", Toast.LENGTH_SHORT).show()
        }

        wasAccessibilityEnabled = isNowEnabled

        // 每天首次进入设置页时，静默检查一次更新（仅在有新版本时弹窗提示）
        maybeAutoCheckUpdatesDaily()

        // 提前标记即可，实际弹出放到 onWindowFocusChanged，避免过早调用被系统忽略
        // 如果没有该标记，不做任何处理
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (autoShownImePicker) return
        if (intent?.getBooleanExtra(EXTRA_AUTO_SHOW_IME_PICKER, false) != true) return
        autoShownImePicker = true
        // 延迟到窗口获得焦点后调用，稳定性更好
        handler.post {
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Throwable) { }
        }
    }

    private fun requestAllPermissions() {
        val prefs = Prefs(this)

        // 1. 麦克风权限
        startActivity(Intent(this, PermissionActivity::class.java))

        // 2. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
            } catch (_: Throwable) { }
        }

        // 3. 通知权限 (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // 4. 如果启用了悬浮球语音识别,还需要无障碍权限
        if (prefs.floatingAsrEnabled && !isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Throwable) {
            Log.e("SettingsActivity", "Failed to get accessibility services")
            return false
        }
        Log.d("SettingsActivity", "Expected: $expectedComponentName")
        Log.d("SettingsActivity", "Enabled services: $enabledServicesSetting")
        val result = enabledServicesSetting?.contains(expectedComponentName) == true
        Log.d("SettingsActivity", "Accessibility service enabled: $result")
        return result
    }

    private fun checkForUpdates() {
        // 显示检查中的提示
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    checkGitHubRelease()
                }
                progressDialog.dismiss()

                if (result.hasUpdate) {
                    showUpdateDialog(result.currentVersion, result.latestVersion, result.downloadUrl, result.updateTime, result.releaseNotes)
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_no_update),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("SettingsActivity", "Update check failed", e)

                // 显示错误对话框，提供手动查看选项
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.update_check_failed)
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton(R.string.btn_manual_check) { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BryceWG/LexiSharp-Keyboard/releases"))
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@SettingsActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    // 静默检查更新：不显示“正在检查”或“已是最新版本”的提示，仅在有更新时弹窗提示
    private fun maybeAutoCheckUpdatesDaily() {
        try {
            val prefs = Prefs(this)
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                .format(java.util.Date())
            if (prefs.lastUpdateCheckDate == today) return
            // 记录为今日已检查，避免同日重复触发
            prefs.lastUpdateCheckDate = today
        } catch (_: Throwable) {
            // 读取或写入失败则不自动检查
            return
        }

        // 开始静默检查
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { checkGitHubRelease() }
                if (result.hasUpdate) {
                    showUpdateDialog(
                        result.currentVersion,
                        result.latestVersion,
                        result.downloadUrl,
                        result.updateTime,
                        result.releaseNotes
                    )
                }
            } catch (e: Exception) {
                Log.d("SettingsActivity", "Auto update check failed", e)
            }
        }
    }

    private data class UpdateCheckResult(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String,
        val updateTime: String? = null,
        val releaseNotes: String? = null
    )

    private suspend fun checkGitHubRelease(): UpdateCheckResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        // 加入时间戳参数，降低 CDN 旧缓存命中概率（按分钟变动，避免每秒都变）
        val ts = (System.currentTimeMillis() / 60_000L).toString()
        val urls = listOf(
            // 优先使用可用镜像代理 raw 内容（更新更及时）
            "https://hub.gitmirror.com/https://raw.githubusercontent.com/BryceWG/LexiSharp-Keyboard/main/version.json?t=$ts",
            "https://ghproxy.net/https://raw.githubusercontent.com/BryceWG/LexiSharp-Keyboard/main/version.json?t=$ts",
            // 直接读取 GitHub 原始内容
            "https://raw.githubusercontent.com/BryceWG/LexiSharp-Keyboard/main/version.json?t=$ts",
            // jsDelivr 主域 (作为兜底）
            "https://cdn.jsdelivr.net/gh/BryceWG/LexiSharp-Keyboard@main/version.json?t=$ts"
        )

        val currentRaw = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val currentVersion = normalizeVersion(currentRaw)

        return coroutineScope {
            data class Remote(val version: String, val downloadUrl: String, val updateTime: String?, val releaseNotes: String?)

            val jobs = urls.map { url ->
                async(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "LexiSharp-Android")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val body = response.body?.string() ?: throw Exception("Empty response")
                        val json = JSONObject(body)

                        val candidate = normalizeVersion(json.optString("version", "").removePrefix("v"))
                        if (candidate.isEmpty()) throw Exception("Invalid version format")

                        val downloadUrl = json.optString("download_url", "https://github.com/BryceWG/LexiSharp-Keyboard/releases")
                        val updateTime = json.optString("update_time", "").ifBlank { null }
                        val releaseNotes = json.optString("release_notes", "").ifBlank { null }
                        Remote(candidate, downloadUrl, updateTime, releaseNotes)
                    }
                }
            }

            val remotes = mutableListOf<Remote>()
            var lastError: Exception? = null
            for (j in jobs) {
                try {
                    remotes += j.await()
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (remotes.isEmpty()) throw lastError ?: Exception("All sources failed")

            var best: Remote? = null
            for (r in remotes) {
                best = when {
                    best == null -> r
                    compareVersions(r.version, best!!.version) > 0 -> r
                    else -> best
                }
            }

            val hasUpdate = compareVersions(best!!.version, currentVersion) > 0
            UpdateCheckResult(hasUpdate, currentRaw, best!!.version, best!!.downloadUrl, best!!.updateTime, best!!.releaseNotes)
        }
    }

    private fun normalizeVersion(v: String): String {
        if (v.isBlank()) return ""
        // 移除前缀 v/V 和非数字点字符，仅保留比较需要的主次修订号
        return v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .replace(Regex("[^0-9.]"), "")
            .trim('.')
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").mapNotNull { it.toIntOrNull() }
        val v2Parts = version2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(v1Parts.size, v2Parts.size)
        for (i in 0 until maxLength) {
            val v1 = v1Parts.getOrNull(i) ?: 0
            val v2 = v2Parts.getOrNull(i) ?: 0
            if (v1 != v2) {
                return v1.compareTo(v2)
            }
        }
        return 0
    }

    private fun showUpdateDialog(
        currentVersion: String,
        latestVersion: String,
        downloadUrl: String,
        updateTime: String? = null,
        releaseNotes: String? = null
    ) {
        // 构建消息内容
        val messageBuilder = StringBuilder()
        messageBuilder.append(getString(R.string.update_dialog_message, currentVersion, latestVersion))

        // 添加更新时间（如果有）
        if (!updateTime.isNullOrEmpty()) {
            messageBuilder.append("\n\n")
            // 尝试格式化时间戳
            val formattedTime = try {
                // 解析 ISO 8601 格式并转换为本地时间
                val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
                utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = utcFormat.parse(updateTime)

                val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                if (date != null) localFormat.format(date) else updateTime
            } catch (e: Exception) {
                updateTime // 如果解析失败，直接显示原始时间
            }
            messageBuilder.append("更新时间：$formattedTime")
        }

        // 添加发布说明（如果有）
        if (!releaseNotes.isNullOrEmpty() && releaseNotes != "版本 $latestVersion 已发布") {
            messageBuilder.append("\n\n")
            messageBuilder.append("更新说明：\n$releaseNotes")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(messageBuilder.toString())
            .setPositiveButton(R.string.btn_download) { _, _ ->
                // 显示下载源选择对话框
                showDownloadSourceDialog(downloadUrl, latestVersion)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showDownloadSourceDialog(originalUrl: String, version: String) {
        // 准备下载源列表
        val downloadSources = arrayOf(
            "GitHub 官方",
            "GitHub 镜像 (ghproxy.net)",
            "GitHub 镜像 (hub.gitmirror.com)",
            "GitHub 镜像 (gh-proxy.net)"
        )

        // 根据 release 页面构造 APK 直链（用于镜像站加速）；官方仍跳转 release 页面
        val directApkUrl = buildDirectApkUrl(originalUrl, version)

        // 生成对应的 URL：
        // - 官方：release 页面（originalUrl）
        // - 镜像：一律使用 APK 直链
        val downloadUrls = arrayOf(
            originalUrl,
            convertToMirrorUrl(directApkUrl, "https://ghproxy.net/"),
            convertToMirrorUrl(directApkUrl, "https://hub.gitmirror.com/"),
            convertToMirrorUrl(directApkUrl, "https://gh-proxy.net/")
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_source_title)
            .setItems(downloadSources) { _, which ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrls[which]))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun buildDirectApkUrl(originalUrl: String, version: String): String {
        // 输入 URL 形如: https://github.com/{owner}/{repo}/releases/tag/{tag}
        // 输出直链: https://github.com/{owner}/{repo}/releases/download/v{version}/lexisharp-keyboard-{version}-release.apk
        val baseEnd = originalUrl.indexOf("/releases/tag/")
        val base = if (baseEnd > 0) originalUrl.substring(0, baseEnd) else "https://github.com/BryceWG/LexiSharp-Keyboard"
        val tag = "v$version"
        val apkName = "lexisharp-keyboard-$version-release.apk"
        return "$base/releases/download/$tag/$apkName"
    }

    private fun convertToMirrorUrl(originalUrl: String, mirrorPrefix: String): String {
        // 只对 GitHub 链接加镜像前缀；既支持 release 页面也支持 releases/download 直链
        return if (originalUrl.startsWith("https://github.com/")) mirrorPrefix + originalUrl else originalUrl
    }
}
