package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse as ModernMockResponse
import mockwebserver3.MockResponseBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LlmPostProcessorTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var prefs: Prefs
    private lateinit var processor: LlmPostProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .build()
        prefs = Prefs(context).apply {
            llmVendor = LlmVendor.CUSTOM
            llmProvidersJson = ""
            activeLlmId = ""
            llmEndpoint = server.url("/v1").toString().trimEnd('/')
            llmApiKey = "test-key"
            llmModel = "test-model"
        }
        processor = LlmPostProcessor(client)
    }

    @After
    fun tearDown() {
        processor.cancelActiveRequest()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun delayedHeaders_areNotCountedAsConnectionSetup() = runBlocking {
        server.enqueue(
            sseResponse(sseEvent("hi") + doneEvent())
                .setHeadersDelay(180, TimeUnit.MILLISECONDS)
        )

        val result = processor.testConnectivity(prefs)

        assertTrue(result.ok)
        assertFalse(result.connectionReused)
        assertTrue(
            "header delay should exceed connection setup: $result",
            result.responseHeadersMs - result.connectionMs >= 100L
        )
    }

    @Test
    fun completedSse_reusesPooledConnectionOnNextRequest() = runBlocking {
        server.enqueue(sseResponse(sseEvent("first") + doneEvent()))
        server.enqueue(sseResponse(sseEvent("second") + doneEvent()))

        val first = processor.testConnectivity(prefs)
        awaitIdleConnection()
        val second = processor.testConnectivity(prefs)

        assertTrue(first.ok)
        assertFalse(first.connectionReused)
        assertTrue(second.ok)
        assertTrue(second.connectionReused)
    }

    @Test
    fun sseTiming_usesHeadersFirstVisibleAndProtocolCompletion() = runBlocking {
        val firstEvent = sseEvent("hello")
        server.enqueue(
            sseResponse(firstEvent + sseEvent(" world") + doneEvent())
                .setBodyDelay(100, TimeUnit.MILLISECONDS)
                .throttleBody(firstEvent.toByteArray().size.toLong(), 120, TimeUnit.MILLISECONDS)
        )

        val result = processor.testConnectivity(prefs)

        assertTrue(result.ok)
        assertEquals(LlmPostProcessor.LlmResponseMode.SSE, result.responseMode)
        assertTrue("first visible timing was ${result.firstVisibleMs}ms", result.firstVisibleMs >= 70L)
        assertTrue("output timing was ${result.outputMs}ms", result.outputMs >= 80L)
        assertEquals(0L, result.responseBodyMs)
    }

    @Test
    fun doneEvent_returnsBeforeSlowTrailingBodyIsDrained() = runBlocking {
        val protocolBody = sseEvent("hi") + doneEvent()
        val trailingBody = "x".repeat(4_096)
        val slowServer = mockwebserver3.MockWebServer()
        slowServer.start()
        try {
            slowServer.enqueue(
                ModernMockResponse.Builder()
                    .setHeader("Content-Type", "text/event-stream")
                    .body(object : MockResponseBody {
                        override val contentLength: Long =
                            (protocolBody + trailingBody).toByteArray().size.toLong()

                        override fun writeTo(sink: BufferedSink) {
                            sink.writeUtf8(protocolBody)
                            sink.flush()
                            Thread.sleep(2_000L)
                            sink.writeUtf8(trailingBody)
                        }
                    })
                    .build()
            )
            prefs.llmEndpoint = slowServer.url("/v1").toString().trimEnd('/')

            val startedAt = System.nanoTime()
            val result = processor.testConnectivity(prefs)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue(result.ok)
            assertTrue("result waited for trailing body: ${elapsedMs}ms", elapsedMs < 1_000L)
            assertTrue("drain leaked into total: ${result.totalMs}ms", result.totalMs < 1_000L)
        } finally {
            slowServer.close()
        }
    }

    @Test
    fun nonSseResponse_usesBodyTimingWithoutSseSegments() = runBlocking {
        server.enqueue(
            jsonResponse("hi")
                .setBodyDelay(120, TimeUnit.MILLISECONDS)
        )

        val result = processor.testConnectivity(prefs)

        assertTrue(result.ok)
        assertEquals(LlmPostProcessor.LlmResponseMode.NON_SSE, result.responseMode)
        assertTrue("body timing was ${result.responseBodyMs}ms", result.responseBodyMs >= 80L)
        assertEquals(0L, result.firstVisibleMs)
        assertEquals(0L, result.outputMs)
    }

    @Test
    fun streamingFailureAndNonStreamingSuccess_accumulateLogicalTotal() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeadersDelay(100, TimeUnit.MILLISECONDS)
                .setBody("stream unsupported")
        )
        server.enqueue(
            jsonResponse("fallback")
                .setBodyDelay(100, TimeUnit.MILLISECONDS)
        )

        val result = processor.testConnectivity(prefs)

        assertTrue(result.ok)
        assertTrue(result.fallbackUsed)
        assertEquals(LlmPostProcessor.LlmResponseMode.NON_SSE, result.responseMode)
        assertTrue(result.connectionReused)
        assertTrue(
            "first attempt missing from total: $result",
            result.totalMs - result.responseBodyMs >= 80L
        )
        val streamingRequest = server.takeRequest(1, TimeUnit.SECONDS)
        val fallbackRequest = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(streamingRequest)
        assertNotNull(fallbackRequest)
        assertTrue(JSONObject(streamingRequest!!.body.readUtf8()).getBoolean("stream"))
        assertFalse(JSONObject(fallbackRequest!!.body.readUtf8()).getBoolean("stream"))
    }

    @Test
    fun streamingAndFallbackFailure_doNotExposeSuccessTimingMode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("stream unsupported"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("server failed"))

        val result = processor.testConnectivity(prefs)

        assertFalse(result.ok)
        assertEquals(500, result.httpCode)
        assertEquals(null, result.responseMode)
        assertEquals(0L, result.totalMs)
    }

    @Test
    fun cancellation_closesImmediatelyWithoutDrainWait() = runBlocking {
        val firstEvent = sseEvent("partial")
        server.enqueue(
            sseResponse(firstEvent + "x".repeat(4_096))
                .setBodyDelay(5, TimeUnit.SECONDS)
        )
        val resultDeferred = async(Dispatchers.IO) { processor.testConnectivity(prefs) }
        val request = withContext(Dispatchers.IO) {
            server.takeRequest(2, TimeUnit.SECONDS)
        }
        assertNotNull(request)
        delay(80L)

        val cancelStartedAt = System.nanoTime()
        processor.cancelActiveRequest()
        val result = withTimeout(1_000L) { resultDeferred.await() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelStartedAt)

        assertFalse(result.ok)
        assertTrue("cancel waited for drain: ${elapsedMs}ms", elapsedMs < 700L)
    }

    private fun awaitIdleConnection() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L)
        while (client.connectionPool.idleConnectionCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertTrue(client.connectionPool.idleConnectionCount() > 0)
    }

    private fun sseResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun jsonResponse(text: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            JSONObject()
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put("content", text)
                        )
                    )
                )
                .toString()
        )

    private fun sseEvent(text: String): String =
        "data: ${
            JSONObject()
                .put(
                    "choices",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("delta", JSONObject().put("content", text))
                            .put("finish_reason", JSONObject.NULL)
                    )
                )
        }\n\n"

    private fun doneEvent(): String = "data: [DONE]\n\n"
}
