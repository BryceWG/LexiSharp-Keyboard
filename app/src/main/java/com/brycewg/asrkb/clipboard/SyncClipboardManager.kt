package com.brycewg.asrkb.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 剪贴板同步数据载荷
 */
@Serializable
private data class ClipboardPayload(
  val File: String = "",
  val Clipboard: String,
  val Type: String = "Text"
)

/**
 * 在 IME 面板可见期间启用：
 * - 监听剪贴板变动并上传（文本类型）
 * - 按设定周期从服务器拉取文本并写入系统剪贴板
 *
 * 注意：服务端认证按文档要求使用 Header: `Authorization: Basic 用户名:密码`（非 Base64）。
 */
class SyncClipboardManager(
  private val context: Context,
  private val prefs: Prefs,
  private val scope: CoroutineScope,
  private val listener: Listener? = null
) {
  interface Listener {
    fun onPulledNewContent(text: String)
    fun onUploadSuccess()
  }

  private val clipboard by lazy { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
  private val client by lazy {
    OkHttpClient.Builder()
      .connectTimeout(8, TimeUnit.SECONDS)
      .readTimeout(8, TimeUnit.SECONDS)
      .writeTimeout(8, TimeUnit.SECONDS)
      .build()
  }
  private val json by lazy { Json { ignoreUnknownKeys = true } }

  companion object {
    private const val TAG = "SyncClipboardManager"
  }

  private var pullJob: Job? = null
  private var listenerRegistered = false
  @Volatile private var suppressNextChange = false
  // 记录最近一次从服务端拉取的文本哈希，用于减少本地剪贴板读取次数
  @Volatile private var lastPulledServerHash: String? = null

  private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
    if (suppressNextChange) {
      // 忽略由我们主动写入导致的回调
      suppressNextChange = false
      return@OnPrimaryClipChangedListener
    }
    if (!prefs.syncClipboardEnabled) return@OnPrimaryClipChangedListener
    scope.launch(Dispatchers.IO) {
      try {
        uploadCurrentClipboardText()
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to upload clipboard text on change", e)
      }
    }
  }

  fun start() {
    if (!prefs.syncClipboardEnabled) return
    ensureListener()
    ensurePullLoop()
  }

  fun stop() {
    try {
      if (listenerRegistered) clipboard.removePrimaryClipChangedListener(clipListener)
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to remove clipboard listener", e)
    }
    listenerRegistered = false
    pullJob?.cancel()
    pullJob = null
    suppressNextChange = false
    lastPulledServerHash = null
  }

  private fun ensureListener() {
    if (!listenerRegistered) {
      try {
        clipboard.addPrimaryClipChangedListener(clipListener)
        listenerRegistered = true
      } catch (e: Throwable) {
        Log.e(TAG, "Failed to add clipboard listener", e)
      }
    }
  }

  private fun ensurePullLoop() {
    pullJob?.cancel()
    if (!prefs.syncClipboardAutoPullEnabled) return
    val intervalSec = prefs.syncClipboardPullIntervalSec.coerceIn(1, 600)
    pullJob = scope.launch(Dispatchers.IO) {
      while (isActive && prefs.syncClipboardEnabled && prefs.syncClipboardAutoPullEnabled) {
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

  private fun authHeaderPlain(): String? {
    val u = prefs.syncClipboardUsername
    val p = prefs.syncClipboardPassword
    if (u.isBlank() || p.isBlank()) return null
    // 按文档要求：Authorization: Basic 用户名:密码（非 Base64）
    return "Basic $u:$p"
  }

  private fun authHeaderB64(): String? {
    val u = prefs.syncClipboardUsername
    val p = prefs.syncClipboardPassword
    if (u.isBlank() || p.isBlank()) return null
    val token = "$u:$p".toByteArray(Charsets.UTF_8)
    val b64 = Base64.encodeToString(token, Base64.NO_WRAP)
    return "Basic $b64"
  }

  private fun readClipboardText(): String? {
    val clip = try {
      clipboard.primaryClip
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to read clipboard", e)
      null
    } ?: return null
    if (clip.itemCount <= 0) return null
    val item = clip.getItemAt(0)
    val text = try {
      item.coerceToText(context)?.toString()
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to coerce clipboard item to text", e)
      null
    }
    return text?.takeIf { it.isNotEmpty() }
  }

  private fun writeClipboardText(text: String) {
    val clip = ClipData.newPlainText("SyncClipboard", text)
    suppressNextChange = true
    try {
      clipboard.setPrimaryClip(clip)
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to write clipboard text", e)
    } finally {
      suppressNextChange = false
    }
  }

  private fun uploadCurrentClipboardText() {
    val url = buildUrl() ?: return
    val auth1 = authHeaderPlain() ?: return
    val auth2 = authHeaderB64() ?: return
    val text = readClipboardText() ?: return
    if (text.isEmpty()) return
    // 若与最近一次成功上传（或最近一次拉取写入）相同，则跳过上传，避免重复
    try {
      val newHash = sha256Hex(text)
      val last = try { prefs.syncClipboardLastUploadedHash } catch (e: Throwable) {
        Log.e(TAG, "Failed to read last uploaded hash", e)
        ""
      }
      if (newHash == last) return
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to compute hash for clipboard text", e)
      // 继续尝试上传
    }
    // 先按文档尝试明文；失败则回退标准 Basic Base64
    if (!uploadText(url, auth1, text)) {
      uploadText(url, auth2, text)
    }
  }

  private fun uploadText(url: String, auth: String, text: String): Boolean {
    return try {
      val payload = ClipboardPayload(Clipboard = text)
      val bodyJson = json.encodeToString(payload)
      val req = Request.Builder()
        .url(url)
        .header("Authorization", auth)
        .put(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
      client.newCall(req).execute().use { resp ->
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
          false
        }
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Failed to upload clipboard text", e)
      false
    }
  }

  /**
   * 一次性上传当前系统粘贴板文本（不进行"与上次一致"跳过判断）。
   * 返回是否成功。
   */
  fun uploadOnce(): Boolean {
    val url = buildUrl() ?: return false
    val auth1 = authHeaderPlain() ?: return false
    val auth2 = authHeaderB64() ?: return false
    val text = readClipboardText() ?: return false
    if (text.isEmpty()) return false
    return try {
      val ok = if (!uploadText(url, auth1, text)) uploadText(url, auth2, text) else true
      ok
    } catch (e: Throwable) {
      Log.e(TAG, "uploadOnce failed", e)
      false
    }
  }

  /**
   * 执行带认证回退的请求
   * 先尝试明文认证，失败则回退到 Base64 认证
   */
  private fun <T> executeRequestWithAuthFallback(
    requestBuilder: (auth: String) -> Request,
    responseHandler: (okhttp3.Response) -> T?
  ): T? {
    val auth1 = authHeaderPlain() ?: return null
    val auth2 = authHeaderB64() ?: return null

    // 尝试明文认证
    try {
      val req1 = requestBuilder(auth1)
      client.newCall(req1).execute().use { resp ->
        if (resp.isSuccessful) {
          return responseHandler(resp)
        }
        Log.w(TAG, "Plain auth failed with status: ${resp.code}")
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Plain auth request failed", e)
    }

    // 回退到 Base64 认证
    try {
      val req2 = requestBuilder(auth2)
      client.newCall(req2).execute().use { resp ->
        if (resp.isSuccessful) {
          return responseHandler(resp)
        }
        Log.w(TAG, "Base64 auth failed with status: ${resp.code}")
      }
    } catch (e: Throwable) {
      Log.e(TAG, "Base64 auth request failed", e)
    }

    return null
  }

  fun pullNow(updateClipboard: Boolean): Pair<Boolean, String?> {
    val url = buildUrl() ?: return false to null

    val result = try {
      executeRequestWithAuthFallback(
        requestBuilder = { auth ->
          Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .get()
            .build()
        },
        responseHandler = { resp ->
          val body = resp.body?.string()?.takeIf { it.isNotEmpty() }
          if (body == null) {
            Log.w(TAG, "Pull response body is empty")
            return@executeRequestWithAuthFallback null
          }

          val payload = try {
            json.decodeFromString<ClipboardPayload>(body)
          } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse clipboard payload", e)
            return@executeRequestWithAuthFallback null
          }

          if (!TextUtils.equals(payload.Type, "Text")) {
            Log.w(TAG, "Unsupported payload type: ${payload.Type}")
            return@executeRequestWithAuthFallback null
          }

          val text = payload.Clipboard
          if (text.isBlank()) {
            Log.w(TAG, "Clipboard text is blank")
            return@executeRequestWithAuthFallback null
          }

          // 计算服务端文本哈希并与上次拉取缓存对比，未变化则避免读取系统剪贴板
          val newServerHash = try {
            sha256Hex(text)
          } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for pulled text", e)
            null
          }
          val prevServerHash = lastPulledServerHash
          lastPulledServerHash = newServerHash

          if (updateClipboard) {
            if (newServerHash != null && newServerHash == prevServerHash) {
              // 服务端内容未变化：跳过本地剪贴板读取以降低读取频率
              return@executeRequestWithAuthFallback text
            }
            val cur = readClipboardText()
            if (text.isNotEmpty() && text != cur) {
              writeClipboardText(text)
              // 将此次拉取的内容也记录到"最近一次上传哈希"，避免后续补上传（减少不必要的上传）
              try {
                prefs.syncClipboardLastUploadedHash = sha256Hex(text)
              } catch (e: Throwable) {
                Log.e(TAG, "Failed to save pulled hash", e)
              }
              try {
                listener?.onPulledNewContent(text)
              } catch (e: Throwable) {
                Log.e(TAG, "Failed to notify pulled content listener", e)
              }
            }
          }
          text
        }
      )
    } catch (e: Throwable) {
      Log.e(TAG, "pullNow failed", e)
      null
    }

    return if (result != null) {
      true to result
    } else {
      false to null
    }
  }

  /**
   * 在启动时调用：若系统剪贴板文本与上次成功上传不一致，则主动上传一次。
   */
  fun proactiveUploadIfChanged() {
    val url = buildUrl() ?: return
    val auth1 = authHeaderPlain() ?: return
    val auth2 = authHeaderB64() ?: return
    val text = readClipboardText() ?: return
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
        if (!uploadText(url, auth1, text)) {
          uploadText(url, auth2, text)
        }
      } catch (e: Throwable) {
        Log.e(TAG, "proactiveUploadIfChanged failed", e)
      }
    }
  }
}
