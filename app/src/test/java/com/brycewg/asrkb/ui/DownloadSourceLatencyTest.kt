package com.brycewg.asrkb.ui

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadSourceLatencyTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun measure_successPartialContent_returnsOkWithLatency() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("x")
                .addHeader("Content-Range", "bytes 0-0/1")
        )

        val result = measureDownloadSourceLatency(server.url("/model.zip").toString(), shortTimeoutClient())

        assertEquals(DownloadSourceLatencyStatus.Ok, result.status)
        assertTrue(result.latencyMs >= 1L)
        val recorded = server.takeRequest()
        assertEquals("bytes=0-0", recorded.getHeader("Range"))
        assertEquals("GET", recorded.method)
    }

    @Test
    fun measure_delayedHeaders_reportsHigherLatency() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(150, TimeUnit.MILLISECONDS)
                .setBody("ok")
        )

        val result = measureDownloadSourceLatency(server.url("/file.bin").toString(), shortTimeoutClient())

        assertEquals(DownloadSourceLatencyStatus.Ok, result.status)
        assertTrue(
            "expected latency >= 100ms, was ${result.latencyMs}",
            result.latencyMs >= 100L
        )
    }

    @Test
    fun measure_timeout_returnsTimeoutAfterRetry() = runTest {
        // TTFB 在响应头到达时结束，因此超时必须延迟 headers 而非 body
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody("slow")
        )
        server.enqueue(
            MockResponse()
                .setHeadersDelay(2, TimeUnit.SECONDS)
                .setBody("slow")
        )

        val result = measureDownloadSourceLatency(
            server.url("/slow").toString(),
            shortTimeoutClient(timeoutMs = 150L)
        )

        assertEquals(DownloadSourceLatencyStatus.Timeout, result.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun measure_httpError_returnsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

        val result = measureDownloadSourceLatency(server.url("/missing").toString(), shortTimeoutClient())

        assertEquals(DownloadSourceLatencyStatus.Error, result.status)
    }

    @Test
    fun measure_invalidUrl_returnsError() = runTest {
        val result = measureDownloadSourceLatency("not-a-url", shortTimeoutClient())

        assertEquals(DownloadSourceLatencyStatus.Error, result.status)
    }

    @Test
    fun buildDownloadSourceAddressDisplay_keepsSchemeAndHost() {
        val display = buildDownloadSourceAddressDisplay(
            "https://ghproxy.net/https://github.com/org/repo/releases/download/v1/app.apk"
        )
        assertEquals("https://ghproxy.net", display)
    }

    private fun shortTimeoutClient(timeoutMs: Long = 2_000L): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
}
