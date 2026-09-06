package com.brycewg.asrkb.clipboard

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncClipboardSignalRClientTest {

    @Test
    fun remoteProfileChanged_forwardsFirstObjectArgumentAsJson() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"connectionToken":"token"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("{}\u001e")
                    webSocket.send(
                        """{"type":1,"target":"RemoteProfileChanged","arguments":[{"text":"remote","type":"Text"}]}""" +
                            "\u001e"
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)
        val received = CountDownLatch(1)
        val receivedProfile = AtomicReference<String?>()

        try {
            client.start(object : SyncClipboardSignalRClient.Listener {
                override fun onConnected() = Unit
                override fun onDisconnected(error: Throwable?) = Unit
                override fun onRemoteProfileChanged(profileJson: String?) {
                    receivedProfile.set(profileJson)
                    received.countDown()
                }
            })

            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals("""{"text":"remote","type":"Text"}""", receivedProfile.get())
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun remoteProfileChanged_missingNullOrNonObjectArgument_forwardsNull() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"connectionToken":"token"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("{}\u001e")
                    listOf(
                        """{"type":1,"target":"RemoteProfileChanged"}""",
                        """{"type":1,"target":"RemoteProfileChanged","arguments":[null]}""",
                        """{"type":1,"target":"RemoteProfileChanged","arguments":["bad"]}"""
                    ).forEach { webSocket.send(it + "\u001e") }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)
        val received = CountDownLatch(3)
        val allNull = AtomicBoolean(true)

        try {
            client.start(object : SyncClipboardSignalRClient.Listener {
                override fun onConnected() = Unit
                override fun onDisconnected(error: Throwable?) = Unit
                override fun onRemoteProfileChanged(profileJson: String?) {
                    if (profileJson != null) allNull.set(false)
                    received.countDown()
                }
            })

            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertTrue(allNull.get())
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun hubUrl_appendsHubPathFromJsonBase() {
        assertEquals(
            "https://example.com:5033/SyncClipboardHub",
            SyncClipboardSignalRClient.hubUrlFromServerBase("https://example.com:5033/")
        )
        assertEquals(
            "https://example.com:5033/SyncClipboardHub",
            SyncClipboardSignalRClient.hubUrlFromServerBase(
                "https://example.com:5033/SyncClipboard.json"
            )
        )
    }

    @Test
    fun hubUrl_rejectsBlank() {
        assertNull(SyncClipboardSignalRClient.hubUrlFromServerBase("  "))
    }

    @Test
    fun versionProbe_distinguishesSyncClipboardFromWebDav() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"3.1.9\""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"3.0.9\""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("proxy login"))
        server.start()
        try {
            val root = server.url("/").toString()
            assertEquals(
                true,
                SyncClipboardSignalRClient.probeServerVersion(root, "user", "password")
            )
            assertEquals(
                false,
                SyncClipboardSignalRClient.probeServerVersion(root, "user", "password")
            )
            assertEquals(
                false,
                SyncClipboardSignalRClient.probeServerVersion(root, "user", "password")
            )
            assertNull(
                SyncClipboardSignalRClient.probeServerVersion(root, "user", "password")
            )
            assertEquals("/api/version", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingHub_isReportedAsRealtimeUnavailable() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)
        val disconnected = CountDownLatch(1)
        val disconnectError = AtomicReference<Throwable?>()

        try {
            client.start(object : SyncClipboardSignalRClient.Listener {
                override fun onConnected() = Unit
                override fun onDisconnected(error: Throwable?) {
                    disconnectError.set(error)
                    disconnected.countDown()
                }
                override fun onRemoteProfileChanged(profileJson: String?) = Unit
            })

            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
            assertTrue(disconnectError.get() is RealtimeUnavailableException)
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun stopDuringNegotiate_doesNotCreateWebSocketAfterResponseArrives() {
        val server = MockWebServer()
        val negotiateStarted = CountDownLatch(1)
        val releaseNegotiate = CountDownLatch(1)
        val webSocketOpened = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.contains("/negotiate") == true) {
                    negotiateStarted.countDown()
                    releaseNegotiate.await(2, TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"connectionToken":"token"}""")
                }
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocketOpened.countDown()
                    }
                })
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)

        try {
            client.start(NoOpListener)
            assertTrue(negotiateStarted.await(2, TimeUnit.SECONDS))
            client.stop()
            releaseNegotiate.countDown()

            assertFalse(webSocketOpened.await(1, TimeUnit.SECONDS))
        } finally {
            releaseNegotiate.countDown()
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun normalCloseBeforeHandshake_notifiesDisconnected() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"connectionToken":"token"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "closed before handshake")
                }
            })
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)
        val disconnected = CountDownLatch(1)

        try {
            client.start(object : SyncClipboardSignalRClient.Listener {
                override fun onConnected() = Unit
                override fun onDisconnected(error: Throwable?) {
                    disconnected.countDown()
                }
                override fun onRemoteProfileChanged(profileJson: String?) = Unit
            })

            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun closeFrame_closesActiveWebSocket() {
        val server = MockWebServer()
        val serverObservedClose = CountDownLatch(1)
        server.enqueue(MockResponse().setBody("""{"connectionToken":"token"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("{}\u001e{\"type\":7}\u001e")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    serverObservedClose.countDown()
                    webSocket.close(code, reason)
                }
            })
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)

        try {
            client.start(NoOpListener)

            assertTrue(serverObservedClose.await(2, TimeUnit.SECONDS))
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun connectedListener_canWaitForStopFromAnotherThread() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"connectionToken":"token"}"""))
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.send("{}\u001e")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
        )
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = createClient(server, scope)
        val callbackFinished = CountDownLatch(1)
        val stopWasBlocked = AtomicBoolean(false)

        try {
            client.start(object : SyncClipboardSignalRClient.Listener {
                override fun onConnected() {
                    val stopFinished = CountDownLatch(1)
                    Thread {
                        client.stop()
                        stopFinished.countDown()
                    }.start()
                    stopWasBlocked.set(!stopFinished.await(500, TimeUnit.MILLISECONDS))
                    callbackFinished.countDown()
                }

                override fun onDisconnected(error: Throwable?) = Unit
                override fun onRemoteProfileChanged(profileJson: String?) = Unit
            })

            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS))
            assertFalse(stopWasBlocked.get())
        } finally {
            client.stop()
            scope.cancel()
            server.shutdown()
        }
    }

    private fun createClient(
        server: MockWebServer,
        scope: CoroutineScope
    ) = SyncClipboardSignalRClient(
        serverBase = server.url("/").toString(),
        username = "user",
        password = "pass",
        scope = scope,
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
    )

    private object NoOpListener : SyncClipboardSignalRClient.Listener {
        override fun onConnected() = Unit
        override fun onDisconnected(error: Throwable?) = Unit
        override fun onRemoteProfileChanged(profileJson: String?) = Unit
    }
}
