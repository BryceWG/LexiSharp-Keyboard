/**
 * 在线 ASR 的连接预热：录音开始时提前建立一条可被后续请求复用的 TCP/TLS 连接，
 * 把 DNS + 握手耗时挪进用户说话的时间窗口。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 预热目标（origin）不是从各供应商配置里逐个解析，而是从真实请求里学习：
 * 每次会话开始记录当前 vendor，等本次会话第一个 ASR 请求发出时把它的 origin 存下来，
 * 供下次会话预热。这样新增/改动供应商端点都不需要同步维护映射表。
 *
 * 只保存 scheme://host:port，不保存路径与查询串，避免把放在 URL 里的凭证落盘。
 */
internal object AsrConnectionWarmer {
    private const val TAG = "AsrConnectionWarmer"
    private const val STORE_NAME = "asr_conn_warm"
    private const val KEY_ORIGIN_PREFIX = "origin_"

    // OkHttp 连接池默认空闲保活 5 分钟，留出余量：超过该窗口就认为连接可能已被回收。
    private const val WARM_SKIP_WINDOW_MS = 4 * 60 * 1000L
    private const val WARM_TIMEOUT_SECONDS = 4L

    private val lastTouchedAtByOrigin = ConcurrentHashMap<String, Long>()
    private val warmInFlightOrigins = ConcurrentHashMap<String, Boolean>()
    private val pendingLearnVendorId = AtomicReference<String?>(null)

    @Volatile private var appContext: Context? = null

    private val warmClient: OkHttpClient by lazy {
        // 必须由共享 builder 派生：连接复用要求 OkHttp Address 一致，且共用同一个连接池。
        AsrHttpClientProvider.newBuilder()
            .connectTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(WARM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 预热请求的标记：用于在观测回调里区分“真实识别请求”与“自己发的预热请求”。 */
    private object WarmupMarker

    /**
     * 录音会话即将开始时调用。仅对在线供应商生效，异步执行、失败静默，不影响会话状态。
     */
    fun warmForImmediateUse(context: Context, prefs: Prefs) {
        appContext = context.applicationContext
        val vendor = try {
            prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read vendor for connection warmup", t)
            return
        }
        if (isLocalAsrVendor(vendor)) return
        pendingLearnVendorId.set(vendor.id)
        val origin = readOrigin(vendor.id) ?: return
        warm(origin)
    }

    /**
     * 由共享 OkHttp 的 EventListener 回调：把本次会话第一个真实请求的 origin 关联到当前 vendor。
     *
     * 只取第一个，避免并行备选的请求把主供应商的 origin 覆盖掉。
     */
    fun observeRequest(request: Request) {
        try {
            val url = request.url
            val origin = originOf(url.scheme, url.host, url.port)
            lastTouchedAtByOrigin[origin] = SystemClock.elapsedRealtime()
            if (request.tag(WarmupMarker::class.java) != null) return
            val vendorId = pendingLearnVendorId.getAndSet(null) ?: return
            writeOrigin(vendorId, origin)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to observe ASR request origin", t)
        }
    }

    private fun warm(origin: String) {
        val now = SystemClock.elapsedRealtime()
        val lastTouchedAt = lastTouchedAtByOrigin[origin]
        val stillWarm = lastTouchedAt != null &&
            now - lastTouchedAt < WARM_SKIP_WINDOW_MS &&
            hasPooledConnection()
        if (stillWarm) return
        if (warmInFlightOrigins.putIfAbsent(origin, true) != null) return

        val url = origin.toHttpUrlOrNull()
        if (url == null) {
            warmInFlightOrigins.remove(origin)
            Log.w(TAG, "Discarding unparseable warmup origin")
            return
        }
        // 只发 HEAD 且不带任何凭证：目的仅是让连接进入连接池，响应码是 401/404/405 都无所谓。
        val request = Request.Builder()
            .url(url)
            .head()
            .tag(WarmupMarker::class.java, WarmupMarker)
            .build()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            warmClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    warmInFlightOrigins.remove(origin)
                    logWarmResult(url.host, "failed", startedAt, e.javaClass.simpleName)
                }

                override fun onResponse(call: Call, response: Response) {
                    warmInFlightOrigins.remove(origin)
                    response.use { lastTouchedAtByOrigin[origin] = SystemClock.elapsedRealtime() }
                    logWarmResult(url.host, "ok", startedAt, null)
                }
            })
        } catch (t: Throwable) {
            warmInFlightOrigins.remove(origin)
            Log.w(TAG, "Failed to enqueue connection warmup", t)
        }
    }

    private fun hasPooledConnection(): Boolean = try {
        warmClient.connectionPool.connectionCount() > 0
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read connection pool state", t)
        false
    }

    private fun originOf(scheme: String, host: String, port: Int): String = "$scheme://$host:$port"

    private fun readOrigin(vendorId: String): String? {
        val context = appContext ?: return null
        return try {
            context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ORIGIN_PREFIX + vendorId, null)
                ?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read warmup origin", t)
            null
        }
    }

    private fun writeOrigin(vendorId: String, origin: String) {
        val context = appContext ?: return
        try {
            context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ORIGIN_PREFIX + vendorId, origin)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist warmup origin", t)
        }
    }

    private fun logWarmResult(host: String, result: String, startedAt: Long, errorType: String?) {
        try {
            DebugLogManager.log(
                category = "asr",
                event = "asr_conn_warm",
                data = mapOf(
                    "host" to host,
                    "result" to result,
                    "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
                    "error" to errorType
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log connection warmup result", t)
        }
    }
}
