package com.brycewg.asrkb.ui.settings.backup

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.store.Prefs
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 备份公共辅助：供主工程复用的后台任务工具。
 * 注意：不包含任何 UI 展示，调用方需自行提示。
 */
object WebDavBackupHelper {
    private const val TAG = "WebDavBackupHelper"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val WEBDAV_DIRECTORY = "BiBiKeyboard"
    private const val WEBDAV_FILENAME = "asr_keyboard_settings.json"
    private val legacyHttpClient by lazy { OkHttpClient.Builder().build() }

    sealed class UploadResult {
        object Success : UploadResult()
        data class Error(
            val statusCode: Int?,
            val responsePhrase: String?,
            val throwable: Throwable?,
        ) : UploadResult()
    }

    sealed class DownloadResult {
        data class Success(val json: String) : DownloadResult()
        object NotFound : DownloadResult()
        data class Error(
            val statusCode: Int?,
            val responsePhrase: String?,
            val throwable: Throwable?,
        ) : DownloadResult()
    }

    fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')
    fun buildDirectoryUrl(baseUrl: String): String = "$baseUrl/$WEBDAV_DIRECTORY/"
    fun buildFileUrl(baseUrl: String): String = "$baseUrl/$WEBDAV_DIRECTORY/$WEBDAV_FILENAME"

    private fun createSardine(prefs: Prefs): OkHttpSardine {
        val sardine = OkHttpSardine()
        val user = prefs.webdavUsername.trim()
        val pass = prefs.webdavPassword.trim()
        if (user.isNotEmpty()) {
            sardine.setCredentials(user, pass)
        }
        return sardine
    }

    private fun ensureDirectoryExists(sardine: OkHttpSardine, dirUrl: String) {
        val normalizedDirUrl = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"

        val exists = try {
            sardine.exists(normalizedDirUrl)
        } catch (t: Throwable) {
            // 某些 WebDAV 服务器在目录 URL 无尾斜杠或已存在时返回 3xx/405，这里通过文案粗略判断视作“已存在”
            if (isDirectoryAlreadyExistsError(t)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "exists($normalizedDirUrl) -> ${t.javaClass.simpleName}: ${t.message}, treat as existing directory")
                }
                true
            } else {
                throw t
            }
        }

        if (!exists) {
            try {
                sardine.createDirectory(normalizedDirUrl)
            } catch (t: Throwable) {
                if (isDirectoryAlreadyExistsError(t)) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "createDirectory($normalizedDirUrl) -> ${t.javaClass.simpleName}: ${t.message}, treat as existing directory")
                    }
                } else {
                    throw t
                }
            }
        }
    }

    /**
     * 将当前偏好（含密钥）导出为 JSON 并通过 WebDAV 上传到固定路径。
     * @return true 表示上传成功；false 表示参数不全或上传失败。
     */
    suspend fun uploadSettings(context: Context, prefs: Prefs): Boolean =
        when (uploadSettingsWithStatus(context, prefs)) {
            is UploadResult.Success -> true
            is UploadResult.Error -> false
        }

    /**
     * 带详细状态的上传版本，便于 UI 显示具体错误信息。
     */
    suspend fun uploadSettingsWithStatus(context: Context, prefs: Prefs): UploadResult =
        withContext(Dispatchers.IO) {
            val rawUrl = prefs.webdavUrl.trim()
            if (rawUrl.isEmpty()) {
                return@withContext UploadResult.Error(null, "EMPTY_URL", null)
            }
            val baseUrl = normalizeBaseUrl(rawUrl)

            val sardineUpload = runCatching { performSardineUpload(baseUrl, prefs) }
            sardineUpload.getOrNull()?.let { return@withContext UploadResult.Success }

            val sardineError = sardineUpload.exceptionOrNull()
            val sardineStatus = extractStatusCode(sardineError)
            val sardineReason = extractResponsePhrase(sardineError)
            if (sardineError != null) {
                Log.e(TAG, "WebDAV upload error (sardine): ${sardineError.message}", sardineError)
            }

            if (shouldTryLegacy(sardineStatus, baseUrl)) {
                Log.i(TAG, "Retry WebDAV upload with legacy client for compatibility")
                val legacyResult = uploadWithLegacy(baseUrl, prefs)
                return@withContext legacyResult
            }

            UploadResult.Error(sardineStatus, sardineReason, sardineError)
        }

    /**
     * 从 WebDAV 下载备份 JSON 文本。
     * @return JSON 字符串；若未配置 URL 或下载失败返回 null。
     */
    suspend fun downloadSettings(prefs: Prefs): String? =
        when (val result = downloadSettingsWithStatus(prefs)) {
            is DownloadResult.Success -> result.json
            is DownloadResult.NotFound -> null
            is DownloadResult.Error -> null
        }

    /**
     * 带详细状态的下载版本，便于 UI 显示具体错误信息（包括 404 备份缺失）。
     */
    suspend fun downloadSettingsWithStatus(prefs: Prefs): DownloadResult =
        withContext(Dispatchers.IO) {
            val rawUrl = prefs.webdavUrl.trim()
            if (rawUrl.isEmpty()) {
                return@withContext DownloadResult.Error(null, "EMPTY_URL", null)
            }
            val baseUrl = normalizeBaseUrl(rawUrl)
            val fileUrl = buildFileUrl(baseUrl)

            val sardineDownload = runCatching { performSardineDownload(fileUrl, prefs) }
            sardineDownload.getOrNull()?.let { return@withContext DownloadResult.Success(it) }

            val sardineError = sardineDownload.exceptionOrNull()
            val sardineStatus = extractStatusCode(sardineError)
            val sardineReason = extractResponsePhrase(sardineError)

            if (sardineError != null) {
                if (isNotFoundError(sardineError) || sardineStatus == 404) {
                    Log.w(TAG, "WebDAV backup not found at $fileUrl: ${sardineError.message}")
                    return@withContext DownloadResult.NotFound
                }
                Log.e(TAG, "WebDAV download error (sardine): ${sardineError.message}", sardineError)
            }

            if (shouldTryLegacy(sardineStatus, baseUrl)) {
                Log.i(TAG, "Retry WebDAV download with legacy client for compatibility")
                val legacyResult = downloadWithLegacy(fileUrl, prefs)
                return@withContext legacyResult
            }

            DownloadResult.Error(sardineStatus, sardineReason, sardineError)
        }

    private fun performSardineUpload(baseUrl: String, prefs: Prefs) {
        val sardine = createSardine(prefs)
        val dirUrl = buildDirectoryUrl(baseUrl)
        ensureDirectoryExists(sardine, dirUrl)

        val payload = prefs.exportJsonString()
        val fileUrl = buildFileUrl(baseUrl)
        sardine.put(fileUrl, payload.toByteArray(Charsets.UTF_8), "application/json")
    }

    private fun performSardineDownload(fileUrl: String, prefs: Prefs): String {
        val sardine = createSardine(prefs)
        sardine.get(fileUrl).use { stream ->
            return stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun uploadWithLegacy(baseUrl: String, prefs: Prefs): UploadResult {
        return try {
            ensureDirectoryExistsLegacy(prefs, baseUrl)

            val payload = prefs.exportJsonString()
            val fileUrl = buildFileUrl(baseUrl)
            val reqBuilder = Request.Builder()
                .url(fileUrl)
                .put(payload.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA))
            addBasicAuthIfNeeded(reqBuilder, prefs)

            legacyHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    UploadResult.Success
                } else {
                    UploadResult.Error(resp.code, resp.message, null)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy WebDAV upload error: ${t.message}", t)
            UploadResult.Error(extractStatusCode(t), extractResponsePhrase(t), t)
        }
    }

    private fun downloadWithLegacy(fileUrl: String, prefs: Prefs): DownloadResult {
        return try {
            val reqBuilder = Request.Builder().url(fileUrl)
            addBasicAuthIfNeeded(reqBuilder, prefs)

            legacyHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                when {
                    resp.code == 404 -> DownloadResult.NotFound
                    resp.isSuccessful -> {
                        val text = resp.body?.string().orEmpty()
                        DownloadResult.Success(text)
                    }
                    else -> DownloadResult.Error(resp.code, resp.message, null)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy WebDAV download error: ${t.message}", t)
            DownloadResult.Error(extractStatusCode(t), extractResponsePhrase(t), t)
        }
    }

    private fun ensureDirectoryExistsLegacy(prefs: Prefs, baseUrl: String) {
        val dirUrl = buildDirectoryUrl(baseUrl).trimEnd('/')
        val checkBuilder = Request.Builder()
            .url(dirUrl)
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .addHeader("Depth", "0")
        addBasicAuthIfNeeded(checkBuilder, prefs)

        legacyHttpClient.newCall(checkBuilder.build()).execute().use { resp ->
            when (resp.code) {
                207 -> return@use
                404 -> Unit
                301, 302, 405 -> return@use
                else -> throw Exception("HTTP ${resp.code}")
            }
        }

        val mkdirBuilder = Request.Builder()
            .url(dirUrl)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
        addBasicAuthIfNeeded(mkdirBuilder, prefs)

        legacyHttpClient.newCall(mkdirBuilder.build()).execute().use { resp ->
            when (resp.code) {
                201, 405 -> return@use
                else -> throw Exception("HTTP ${resp.code}")
            }
        }
    }

    private fun addBasicAuthIfNeeded(builder: Request.Builder, prefs: Prefs) {
        val user = prefs.webdavUsername.trim()
        val pass = prefs.webdavPassword.trim()
        if (user.isNotEmpty()) {
            try {
                val token = Credentials.basic(user, pass, Charsets.UTF_8)
                builder.header("Authorization", token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append basic auth header", e)
            }
        }
    }

    private fun shouldTryLegacy(statusCode: Int?, baseUrl: String): Boolean {
        val lowerBase = baseUrl.lowercase()
        return statusCode == 401 ||
            statusCode == 403 ||
            lowerBase.contains("jianguoyun") ||
            lowerBase.contains("nutstore")
    }

    private fun extractStatusCode(t: Throwable?): Int? {
        return when (t) {
            is SardineException -> t.statusCode
            else -> null
        }
    }

    private fun extractResponsePhrase(t: Throwable?): String? {
        return when (t) {
            is SardineException -> t.responsePhrase
            else -> t?.message
        }
    }

    // 粗略根据异常信息判断是否属于“目录已存在 / 尾斜杠问题”等可忽略错误
    private fun isDirectoryAlreadyExistsError(t: Throwable): Boolean {
        if (t is SardineException && (t.statusCode == 301 || t.statusCode == 302 || t.statusCode == 405)) return true
        val msg = t.message?.lowercase() ?: return false
        return msg.contains("301") ||
            msg.contains("302") ||
            msg.contains("405") ||
            msg.contains("already exists")
    }

    // 粗略根据异常信息判断 404（备份文件不存在）场景
    private fun isNotFoundError(t: Throwable): Boolean {
        if (t is SardineException && t.statusCode == 404) return true
        val msg = t.message?.lowercase() ?: return false
        return msg.contains("404") || msg.contains("not found")
    }
}
