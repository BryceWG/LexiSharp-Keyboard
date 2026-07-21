package com.brycewg.asrkb.clipboard

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 写入 SyncClipboard 的文本数据载荷
 */
@Serializable
private data class UploadClipboardPayload(
    val hasData: Boolean = false,
    val text: String,
    val type: String = "Text"
)

/**
 * 从 SyncClipboard 读取的数据载荷
 */
@Serializable
private data class PullClipboardPayload(
    val text: String? = null,
    val type: String? = null,
    val hash: String? = null,
    val size: Long? = null,
    val hasData: Boolean? = null,
    val dataName: String? = null,
    @SerialName("Clipboard") val legacyClipboard: String? = null,
    @SerialName("Type") val legacyType: String? = null,
    @SerialName("Hash") val legacyHash: String? = null,
    @SerialName("Size") val legacySize: Long? = null,
    @SerialName("File") val legacyFile: String? = null
)

private data class HandledPullPayload(
    val value: String,
    val clipboardApplied: Boolean = true
)

/**
 * SyncClipboard 客户端：
 * - 经 [SystemClipboardPort] 监听剪贴板变动并上传（文本类型）
 * - 按设定周期从服务器拉取文本并经 Port 写入系统剪贴板
 *
 * 注意：服务端认证使用标准 HTTP Basic（`Authorization: Basic <base64(username:password)>`）。
 * 网络与凭证只留在本体；Port 可指向本进程 ClipboardManager 或 IME Bridge。
 */
class SyncClipboardManager(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val listener: Listener? = null,
    private val clipboardStore: ClipboardHistoryStore? = null,
    private val clipboardPort: SystemClipboardPort = DirectSystemClipboardPort(context),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    interface Listener {
        fun onPulledNewContent(text: String)
        fun onUploadSuccess()
        fun onUploadFailed(reason: String? = null)
        fun onFilePulled(type: EntryType, fileName: String, serverFileName: String)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val fileManager by lazy { ClipboardFileManager(context) }

    companion object {
        private const val TAG = "SyncClipboardManager"
        private const val BRIDGE_OBSERVER_RENEW_INTERVAL_MS = 15_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private var pullJob: Job? = null
    private var observerRenewJob: Job? = null
    private var observing = false

    // 记录最近一次从服务端拉取的文本哈希，用于减少本地剪贴板读取次数
    @Volatile private var lastPulledServerHash: String? = null

    /** 凭证/服务器配置世代；变化时立即作废旧接收路径。 */
    @Volatile private var credentialsEpoch: Long = 0L

    /** 息屏/后台保持：暂停观察与自动写入，保留 realtime 连接语义。 */
    @Volatile private var clipboardEffectsPaused: Boolean = false

    @Volatile private var pollingEnabled: Boolean = false

    val clipboardActor: SystemClipboardActor get() = clipboardPort.actor

    fun start(pollingEnabled: Boolean = prefs.syncClipboardAutoPullEnabled) {
        if (!prefs.syncClipboardEnabled) return
        this.pollingEnabled = pollingEnabled
        clipboardEffectsPaused = false
        ensureObserver()
        ensureObserverRenewLoop()
        ensurePullLoop()
    }

    fun stop() {
        credentialsEpoch++
        clipboardEffectsPaused = false
        if (observing) {
            try {
                clipboardPort.stopObserving()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to stop clipboard observer", e)
            }
        }
        observing = false
        stopPullLoop()
        observerRenewJob?.cancel()
        observerRenewJob = null
        lastPulledServerHash = null
        pollingEnabled = false
    }

    /** 暂停剪贴板副作用，但保留亮屏后应恢复的 polling 选择。 */
    fun pauseClipboardSideEffects() {
        clipboardEffectsPaused = true
        if (observing) {
            try {
                clipboardPort.stopObserving()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to pause clipboard observer", e)
            }
        }
        observing = false
        stopPullLoop()
        observerRenewJob?.cancel()
        observerRenewJob = null
    }

    /** 恢复剪贴板副作用（亮屏后）。 */
    fun resumeClipboardSideEffects() {
        if (!prefs.syncClipboardEnabled) return
        clipboardEffectsPaused = false
        ensureObserver()
        ensureObserverRenewLoop()
        ensurePullLoop()
    }

    /** 关闭同步或变更服务器/凭证后调用，立即作废旧响应与接收循环。 */
    internal fun invalidateReceivePath() {
        credentialsEpoch++
        pollingEnabled = false
        stopPullLoop()
    }

    internal fun setPollingEnabled(enabled: Boolean) {
        if (pollingEnabled && !enabled) {
            // OkHttp execute() is blocking; invalidate a response that may outlive Job.cancel().
            credentialsEpoch++
        }
        pollingEnabled = enabled
        ensurePullLoop()
    }

    private fun ensureObserver(force: Boolean = false) {
        if (observing && !force) return
        try {
            clipboardPort.startObserving {
                if (!prefs.syncClipboardEnabled || clipboardEffectsPaused) return@startObserving
                scope.launch(ioDispatcher) {
                    try {
                        uploadCurrentClipboardText()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to upload clipboard text on change", e)
                    }
                }
            }
            observing = true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start clipboard observer", e)
        }
    }

    private fun ensureObserverRenewLoop() {
        if (clipboardPort.actor != SystemClipboardActor.BRIDGE || observerRenewJob?.isActive == true) {
            return
        }
        observerRenewJob = scope.launch(ioDispatcher) {
            while (isActive && prefs.syncClipboardEnabled) {
                delay(BRIDGE_OBSERVER_RENEW_INTERVAL_MS)
                if (!isActive || !prefs.syncClipboardEnabled) break
                ensureObserver(force = true)
            }
        }
    }

    private fun stopPullLoop() {
        pullJob?.cancel()
        pullJob = null
    }

    private fun ensurePullLoop() {
        stopPullLoop()
        if (clipboardEffectsPaused) return
        if (!pollingEnabled || !prefs.syncClipboardEnabled) return
        val intervalSec = prefs.syncClipboardPullIntervalSec.coerceIn(1, 600)
        val epochAtStart = credentialsEpoch
        pullJob = scope.launch(ioDispatcher) {
            while (
                isActive &&
                prefs.syncClipboardEnabled &&
                !clipboardEffectsPaused &&
                pollingEnabled &&
                credentialsEpoch == epochAtStart
            ) {
                try {
                    pullNow(updateClipboard = true)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to pull clipboard in loop", e)
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    private fun buildUrl(): String? {
        val raw = prefs.syncClipboardServerBase.trim()
        if (raw.isBlank()) return null
        val base = raw.trimEnd('/')
        val lower = base.lowercase()
        return if (lower.endsWith(".json")) base else "$base/SyncClipboard.json"
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun authHeaderB64(): String? {
        val u = prefs.syncClipboardUsername
        val p = prefs.syncClipboardPassword
        if (u.isBlank() || p.isBlank()) return null
        val token = "$u:$p".toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(token, Base64.NO_WRAP)
        return "Basic $b64"
    }

    private fun readClipboardText(): String? =
        clipboardPort.readText()?.text?.takeIf { it.isNotEmpty() }

    private fun writeClipboardText(text: String): Boolean = try {
        clipboardPort.writeText(text)
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to write clipboard text via port", e)
        false
    }

    private fun uploadCurrentClipboardText() {
        val url = buildUrl() ?: return
        val authB64 = authHeaderB64() ?: return
        val read = clipboardPort.readText() ?: return
        if (read.isSensitive) {
            Log.d(TAG, "Skip upload: clipboard marked sensitive, chars=${read.text.length}")
            return
        }
        val text = read.text
        if (text.isEmpty()) return
        // 若与最近一次成功上传（或最近一次拉取写入）相同，则跳过上传，避免重复
        try {
            val newHash = sha256Hex(text)
            val last = try {
                prefs.syncClipboardLastUploadedHash
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read last uploaded hash", e)
                ""
            }
            if (newHash == last) return
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for clipboard text", e)
            // 继续尝试上传
        }
        // 仅使用标准 Basic Base64 认证
        uploadText(url, authB64, text)
    }

    private fun uploadText(url: String, auth: String, text: String): Boolean = try {
        val payload = UploadClipboardPayload(text = text)
        val bodyJson = json.encodeToString(payload)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .put(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                // 记录最近一次成功上传内容的哈希，便于后续对比
                try {
                    prefs.syncClipboardLastUploadedHash = sha256Hex(text)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save uploaded hash", e)
                }
                try {
                    listener?.onUploadSuccess()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify upload success listener", e)
                }
                true
            } else {
                Log.w(TAG, "Upload failed with status: ${resp.code}")
                try {
                    listener?.onUploadFailed("HTTP ${resp.code}")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify upload failed listener", e)
                }
                false
            }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to upload clipboard text", e)
        try {
            listener?.onUploadFailed(e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to notify upload failed listener (exception)", t)
        }
        false
    }

    /**
     * 一次性上传当前系统粘贴板文本（不进行"与上次一致"跳过判断）。
     * 返回是否成功。
     */
    fun uploadOnce(): Boolean {
        val url = buildUrl() ?: return false
        val authB64 = authHeaderB64() ?: return false
        val read = clipboardPort.readText() ?: return false
        if (read.isSensitive) {
            Log.d(TAG, "uploadOnce skipped: sensitive clipboard, chars=${read.text.length}")
            return false
        }
        val text = read.text
        if (text.isEmpty()) return false
        return try {
            uploadText(url, authB64, text)
        } catch (e: Throwable) {
            Log.e(TAG, "uploadOnce failed", e)
            false
        }
    }

    /**
     * 执行带认证的请求（HTTP Basic）。
     */
    private fun <T> executeRequestWithAuth(
        requestBuilder: (auth: String) -> Request,
        responseHandler: (okhttp3.Response) -> T?
    ): T? {
        val authB64 = authHeaderB64() ?: return null
        return try {
            val req = requestBuilder(authB64)
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return responseHandler(resp)
                }
                Log.w(TAG, "Auth failed with status: ${resp.code}")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Auth request failed", e)
            null
        }
    }

    fun pullNow(
        updateClipboard: Boolean,
        requestEpoch: Long = credentialsEpoch
    ): Pair<Boolean, String?> {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return false to null
        val url = buildUrl() ?: return false to null
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return false to null

        val result = try {
            executeRequestWithAuth(
                requestBuilder = { auth ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", auth)
                        .get()
                        .build()
                },
                responseHandler = { resp ->
                    val body = resp.body.string().takeIf { it.isNotEmpty() }
                    if (body == null) {
                        Log.w(TAG, "Pull response body is empty")
                        return@executeRequestWithAuth null
                    }

                    val payload = decodeRemoteProfile(body) ?: return@executeRequestWithAuth null
                    applyRemoteProfile(payload, updateClipboard, requestEpoch)
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "pullNow failed", e)
            null
        }

        return if (result != null) {
            result.clipboardApplied to result.value
        } else {
            false to null
        }
    }

    /** Applies a realtime ProfileDto through the same pipeline used by HTTP pulls. */
    internal fun captureRemoteProfileEpoch(): Long = credentialsEpoch

    internal fun applyRemoteProfileJson(
        profileJson: String,
        requestEpoch: Long = credentialsEpoch
    ): Boolean {
        if (!canApplyPullResponse(updateClipboard = true, requestEpoch)) return false
        val payload = decodeRemoteProfile(profileJson) ?: return false
        return applyRemoteProfile(payload, updateClipboard = true, requestEpoch)
            ?.clipboardApplied == true
    }

    private fun decodeRemoteProfile(profileJson: String): PullClipboardPayload? = try {
        json.decodeFromString<PullClipboardPayload>(profileJson)
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to parse remote clipboard profile", e)
        null
    }

    private fun applyRemoteProfile(
        payload: PullClipboardPayload,
        updateClipboard: Boolean,
        requestEpoch: Long
    ): HandledPullPayload? {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return null
        val payloadType = resolvePayloadType(payload)
        return when (payloadType.lowercase()) {
            "text" -> {
                val textDataName = if (payload.hasData == true) {
                    payload.dataName?.takeIf { it.isNotEmpty() }
                        ?: payload.legacyFile?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val text = if (!textDataName.isNullOrBlank()) {
                    downloadTextData(textDataName)
                } else {
                    resolvePayloadText(payload)
                }
                val nonBlankText = text?.takeIf { it.isNotBlank() }
                if (nonBlankText == null) {
                    Log.w(TAG, "Clipboard text is blank")
                    return null
                }
                if (!canApplyPullResponse(updateClipboard, requestEpoch)) return null
                handleTextPayload(nonBlankText, updateClipboard, requestEpoch)
            }
            "image", "file" -> {
                val fileName = resolvePayloadFileName(payload)?.takeIf { it.isNotBlank() }
                if (fileName == null) {
                    Log.w(TAG, "File name is blank for type: $payloadType")
                    return null
                }
                val normalizedType = if (payloadType.equals("image", ignoreCase = true)) {
                    "Image"
                } else {
                    "File"
                }
                handleFilePayload(
                    normalizedType,
                    fileName,
                    payload.hash ?: payload.legacyHash,
                    payload.size ?: payload.legacySize,
                    updateClipboard,
                    requestEpoch
                )
            }
            else -> {
                Log.w(TAG, "Unsupported payload type: $payloadType")
                null
            }
        }
    }

    /**
     * 统一解析 payload 类型，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadType(payload: PullClipboardPayload): String {
        val explicitType = payload.type?.trim().takeUnless { it.isNullOrBlank() }
            ?: payload.legacyType?.trim().takeUnless { it.isNullOrBlank() }
        if (explicitType != null) return explicitType
        if (payload.hasData == true &&
            (!payload.dataName.isNullOrBlank() || !payload.legacyFile.isNullOrBlank())
        ) {
            return "File"
        }
        return "Text"
    }

    /**
     * 统一解析 payload 文本内容，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadText(payload: PullClipboardPayload): String? = payload.text?.takeIf { it.isNotEmpty() }
        ?: payload.legacyClipboard?.takeIf { it.isNotEmpty() }

    /**
     * 统一解析 payload 文件名，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadFileName(payload: PullClipboardPayload): String? = payload.dataName?.takeIf { it.isNotEmpty() }
        ?: payload.legacyFile?.takeIf { it.isNotEmpty() }
        ?: payload.text?.takeIf { payload.hasData == true && it.isNotEmpty() }
        ?: payload.legacyClipboard?.takeIf { payload.hasData == true && it.isNotEmpty() }

    /**
     * 拉取文本内容：当 `Text + hasData=true` 时，正文存放在 `/file/{dataName}`。
     */
    private fun downloadTextData(dataName: String): String? {
        val fileUrl = buildFileUrl(dataName) ?: run {
            Log.w(TAG, "Failed to build text data url for: $dataName")
            return null
        }
        return executeRequestWithAuth(
            requestBuilder = { auth ->
                Request.Builder()
                    .url(fileUrl)
                    .header("Authorization", auth)
                    .get()
                    .build()
            },
            responseHandler = { resp ->
                val text = resp.body.string()
                if (text.isEmpty()) {
                    Log.w(TAG, "Downloaded text data is empty: $dataName")
                    return@executeRequestWithAuth null
                }
                text
            }
        )
    }

    /**
     * 处理文本类型的 payload
     */
    private fun handleTextPayload(
        text: String,
        updateClipboard: Boolean,
        requestEpoch: Long
    ): HandledPullPayload {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
            return HandledPullPayload(text, clipboardApplied = false)
        }
        // 远端内容变为文本时，清除历史中的文件条目与最近文件名记录
        try {
            clipboardStore?.clearFileEntries()
            prefs.syncClipboardLastFileName = ""
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear file entries on text payload", t)
        }

        // 计算服务端文本哈希并与上次拉取缓存对比，未变化则避免读取系统剪贴板
        val newServerHash = try {
            sha256Hex(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for pulled text", e)
            null
        }
        val prevServerHash = lastPulledServerHash

        if (updateClipboard) {
            if (newServerHash != null && newServerHash == prevServerHash) {
                // 服务端内容未变化：跳过本地剪贴板读取以降低读取频率
                return HandledPullPayload(text)
            }
            val cur = readClipboardText()
            if (text.isNotEmpty() && text != cur) {
                if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
                    return HandledPullPayload(text, clipboardApplied = false)
                }
                val written = writeClipboardText(text)
                if (!written) {
                    Log.w(TAG, "Clipboard port refused write, chars=${text.length} actor=${clipboardPort.actor}")
                    return HandledPullPayload(text, clipboardApplied = false)
                }
                // 将此次拉取的内容也记录到"最近一次上传哈希"，避免后续补上传（减少不必要的上传）
                try {
                    prefs.syncClipboardLastUploadedHash = sha256Hex(text)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save pulled hash", e)
                }
                if (written) {
                    try {
                        listener?.onPulledNewContent(text)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to notify pulled content listener", e)
                    }
                }
            }
        }
        lastPulledServerHash = newServerHash
        return HandledPullPayload(text)
    }

    private fun canApplyPullResponse(updateClipboard: Boolean, requestEpoch: Long): Boolean =
        credentialsEpoch == requestEpoch &&
            (!updateClipboard || (prefs.syncClipboardEnabled && !clipboardEffectsPaused))

    /**
     * 处理文件类型的 payload
     * 仅添加到历史记录，不自动下载
     */
    private fun handleFilePayload(
        type: String,
        fileName: String,
        serverHash: String?,
        serverSize: Long?,
        updateClipboard: Boolean,
        requestEpoch: Long
    ): HandledPullPayload {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
            return HandledPullPayload(fileName, clipboardApplied = false)
        }
        try {
            // 若文件名与最近一次处理的文件相同，则视为内容未更新，避免重复触发预览
            val prevName = try {
                prefs.syncClipboardLastFileName
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read last file name", e)
                ""
            }
            val previousEntry = clipboardStore?.getHistory()?.firstOrNull {
                it.type != EntryType.TEXT
            }
            val sameRemoteFile = fileName.isNotEmpty() && fileName == prevName &&
                (serverHash.isNullOrBlank() ||
                    previousEntry?.serverHash.equals(serverHash, ignoreCase = true))
            if (sameRemoteFile) {
                Log.d(TAG, "File payload unchanged, skip preview: $fileName")
                return HandledPullPayload(fileName)
            }

            val entryType = when (type.lowercase()) {
                "image" -> EntryType.IMAGE
                "file" -> EntryType.FILE
                else -> EntryType.FILE
            }

            // 检查文件是否已下载
            val localFile = fileManager.getFile(fileName)
            if (localFile.exists() && !serverHash.isNullOrBlank() &&
                !previousEntry?.serverHash.equals(serverHash, ignoreCase = true)
            ) {
                fileManager.deleteFile(fileName)
            }
            val downloadStatus = if (localFile.exists()) {
                DownloadStatus.COMPLETED
            } else {
                DownloadStatus.NONE
            }

            val localPath = if (localFile.exists()) localFile.absolutePath else null

            // 添加到历史记录（仅保留最新一条文件记录）
            clipboardStore?.addFileEntry(
                type = entryType,
                fileName = fileName,
                serverFileName = fileName,
                fileSize = serverSize ?: if (localFile.exists()) localFile.length() else null,
                serverHash = serverHash,
                localFilePath = localPath,
                downloadStatus = downloadStatus
            )

            // 通知监听器有新文件
            try {
                listener?.onFilePulled(entryType, fileName, fileName)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to notify file pulled listener", e)
            }

            // 记录最近一次成功处理的文件名
            try {
                prefs.syncClipboardLastFileName = fileName
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to save last file name", e)
            }

            Log.d(TAG, "File payload handled: $fileName (type: $type, status: $downloadStatus)")
            return HandledPullPayload(fileName)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle file payload: $fileName", e)
            return HandledPullPayload(fileName)
        }
    }

    /**
     * 下载文件
     * @param entryId 条目 ID
     * @param progressCallback 进度回调
     * @return 是否下载成功
     */
    fun downloadFile(entryId: String, progressCallback: ((Long, Long) -> Unit)? = null): Boolean {
        val store = clipboardStore ?: return false
        val entry = store.getEntryById(entryId) ?: return false
        val serverFileName = entry.serverFileName ?: entry.fileName ?: return false

        // 检查是否已下载
        if (fileManager.fileExists(serverFileName, entry.fileSize)) {
            Log.d(TAG, "File already downloaded: $serverFileName")
            store.updateFileEntry(
                entryId,
                fileManager.getFile(serverFileName).absolutePath,
                DownloadStatus.COMPLETED
            )
            return true
        }

        // 更新状态为下载中
        store.updateFileEntry(entryId, null, DownloadStatus.DOWNLOADING)

        val (ok, localPath) = downloadFileDirectInternal(
            serverFileName = serverFileName,
            expectedSize = entry.fileSize,
            progressCallback = progressCallback
        )

        if (ok && localPath != null) {
            store.updateFileEntry(entryId, localPath, DownloadStatus.COMPLETED)
            return true
        }

        store.updateFileEntry(entryId, null, DownloadStatus.FAILED)
        return false
    }

    /**
     * 直接按文件名下载文件（不依赖剪贴板历史条目）
     * @param fileName 服务器上的文件名
     * @param progressCallback 进度回调
     * @return Pair<是否成功, 本地路径（成功时非 null）>
     */
    fun downloadFileDirect(
        fileName: String,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Pair<Boolean, String?> {
        if (fileName.isBlank()) return false to null

        // 已存在则直接返回
        if (fileManager.fileExists(fileName)) {
            val local = fileManager.getFile(fileName)
            Log.d(TAG, "File already downloaded (direct): $fileName -> ${local.absolutePath}")
            return true to local.absolutePath
        }

        return downloadFileDirectInternal(
            serverFileName = fileName,
            expectedSize = null,
            progressCallback = progressCallback
        )
    }

    /**
     * 文件下载核心实现，供历史条目下载和直接按文件名下载复用
     */
    private fun downloadFileDirectInternal(
        serverFileName: String,
        expectedSize: Long?,
        progressCallback: ((Long, Long) -> Unit)?
    ): Pair<Boolean, String?> {
        val fileUrl = buildFileUrl(serverFileName) ?: run {
            Log.w(TAG, "Failed to build file url for: $serverFileName")
            return false to null
        }

        val authB64 = authHeaderB64() ?: run {
            Log.w(TAG, "Missing auth header for file download")
            return false to null
        }

        // 若已存在且大小匹配，直接返回
        if (fileManager.fileExists(serverFileName, expectedSize)) {
            val local = fileManager.getFile(serverFileName)
            Log.d(
                TAG,
                "File already exists with expected size: $serverFileName -> ${local.absolutePath}"
            )
            return true to local.absolutePath
        }

        return try {
            val req = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authB64)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Download failed: ${resp.code}")
                    return false to null
                }

                val body = resp.body

                val totalBytes = body.contentLength()
                val localPath = fileManager.saveFile(
                    serverFileName,
                    body.byteStream(),
                    totalBytes,
                    progressCallback
                )

                if (localPath != null) {
                    Log.d(TAG, "File downloaded successfully: $serverFileName -> $localPath")
                    true to localPath
                } else {
                    Log.w(TAG, "Failed to save downloaded file: $serverFileName")
                    false to null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: $serverFileName", e)
            false to null
        }
    }

    /**
     * 构建文件下载 URL
     */
    private fun buildFileUrl(fileName: String): String? {
        val raw = prefs.syncClipboardServerBase.trim()
        if (raw.isBlank()) return null
        val base = raw.trimEnd('/')
        // 文件在服务器的 /file/ 目录下
        val encodedFileName = Uri.encode(fileName)
        return "$base/file/$encodedFileName"
    }

    /**
     * 在启动时调用：若系统剪贴板文本与上次成功上传不一致，则主动上传一次。
     */
    fun proactiveUploadIfChanged() {
        val url = buildUrl() ?: return
        val authB64 = authHeaderB64() ?: return
        val read = clipboardPort.readText() ?: return
        if (read.isSensitive) {
            Log.d(TAG, "proactiveUpload skipped: sensitive clipboard, chars=${read.text.length}")
            return
        }
        val text = read.text
        if (text.isEmpty()) return
        val newHash = try {
            sha256Hex(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for proactive upload", e)
            return
        }
        val last = try {
            prefs.syncClipboardLastUploadedHash
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read last uploaded hash", e)
            ""
        }
        if (newHash != last) {
            try {
                uploadText(url, authB64, text)
            } catch (e: Throwable) {
                Log.e(TAG, "proactiveUploadIfChanged failed", e)
            }
        }
    }
}
