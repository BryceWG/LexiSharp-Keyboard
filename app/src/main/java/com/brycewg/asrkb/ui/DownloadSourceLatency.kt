/**
 * 下载源可达性测速工具。
 *
 * 对真实下载 URL 做 HTTPS Range 探测，测量到响应头为止的 TTFB。
 *
 * 归属模块：ui
 */
package com.brycewg.asrkb.ui

import android.net.Uri
import android.util.Log
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TAG = "DownloadSourceLatency"
private const val LATENCY_TIMEOUT_MS = 3000L

enum class DownloadSourceLatencyStatus { Pending, Ok, Timeout, Error }

data class DownloadSourceLatencyResult(
    val status: DownloadSourceLatencyStatus,
    val latencyMs: Long = 0L
)

suspend fun measureDownloadSourceLatency(url: String): DownloadSourceLatencyResult = measureDownloadSourceLatency(url, createLatencyHttpClient())

/**
 * 可注入 OkHttpClient 的测速入口，供单测缩短超时或接入 MockWebServer。
 */
internal suspend fun measureDownloadSourceLatency(
    url: String,
    client: OkHttpClient
): DownloadSourceLatencyResult = withContext(Dispatchers.IO) {
    val httpUrl = url.toHttpUrlOrNull()
    if (httpUrl == null || httpUrl.host.isEmpty()) {
        Log.w(TAG, "Invalid URL for latency check: $url")
        return@withContext DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Error)
    }
    val firstAttempt = probeDownloadSourceOnce(client, httpUrl)
    if (firstAttempt.status != DownloadSourceLatencyStatus.Timeout) {
        firstAttempt
    } else {
        Log.w(TAG, "Latency check timeout, retrying: $httpUrl")
        probeDownloadSourceOnce(client, httpUrl)
    }
}

fun buildDownloadSourceAddressDisplay(url: String): String {
    val uri = Uri.parse(url)
    val host = uri.host ?: return url
    val scheme = uri.scheme ?: "https"
    return "$scheme://$host"
}

private fun createLatencyHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(LATENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(LATENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .writeTimeout(LATENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .callTimeout(LATENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    // 避免 keep-alive 复用把后续探测压成个位数毫秒
    .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
    .retryOnConnectionFailure(false)
    .build()

private fun probeDownloadSourceOnce(
    client: OkHttpClient,
    httpUrl: HttpUrl
): DownloadSourceLatencyResult {
    val request = Request.Builder()
        .url(httpUrl)
        .header("Range", "bytes=0-0")
        .get()
        .build()
    val startNs = System.nanoTime()
    return try {
        client.newCall(request).execute().use { response ->
            val costMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val code = response.code
            if (code in 200..399) {
                DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Ok, costMs)
            } else {
                Log.w(TAG, "Latency check HTTP $code: $httpUrl")
                DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Error)
            }
        }
    } catch (e: SocketTimeoutException) {
        Log.w(TAG, "Latency check timeout: $httpUrl", e)
        DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Timeout)
    } catch (e: InterruptedIOException) {
        Log.w(TAG, "Latency check timeout: $httpUrl", e)
        DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Timeout)
    } catch (e: Exception) {
        Log.w(TAG, "Latency check failed: $httpUrl", e)
        DownloadSourceLatencyResult(DownloadSourceLatencyStatus.Error)
    }
}
