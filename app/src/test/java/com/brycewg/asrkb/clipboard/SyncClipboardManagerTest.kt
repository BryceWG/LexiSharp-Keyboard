package com.brycewg.asrkb.clipboard

import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncClipboardManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: Prefs
    private lateinit var port: FakeSystemClipboardPort
    private lateinit var historyStore: ClipboardHistoryStore
    private lateinit var httpClient: OkHttpClient
    private val pulledTexts = mutableListOf<String>()
    private val pulledFiles = mutableListOf<String>()
    private val uploadSuccesses = mutableListOf<Unit>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = Prefs(context)
        prefs.syncClipboardEnabled = true
        prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.OFF
        prefs.syncClipboardServerBase = server.url("/").toString().trimEnd('/')
        prefs.syncClipboardUsername = "user"
        prefs.syncClipboardPassword = "pass"
        prefs.syncClipboardLastUploadedHash = ""
        prefs.syncClipboardImagesEnabled = true
        prefs.syncClipboardFilesEnabled = true
        prefs.syncClipboardAttachmentMaxSizeMb = 50
        port = FakeSystemClipboardPort()
        historyStore = ClipboardHistoryStore(context, prefs).apply { clearAll() }
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        pulledTexts.clear()
        pulledFiles.clear()
        uploadSuccesses.clear()
    }

    @After
    fun tearDown() {
        historyStore.clearAll()
        server.shutdown()
    }

    @Test
    fun pullNow_writesNewTextViaPort_andRecordsHashToSuppressEcho() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"hello-remote","type":"Text"}""")
        )
        port.text = "local-old"

        val manager = createManager(this)
        val (ok, text) = manager.pullNow(updateClipboard = true)

        assertTrue(ok)
        assertEquals("hello-remote", text)
        assertEquals(listOf("hello-remote"), port.writeHistory)
        assertEquals("hello-remote", port.text)
        assertEquals(listOf("hello-remote"), pulledTexts)
        assertEquals(sha256Hex("hello-remote"), prefs.syncClipboardLastUploadedHash)
    }

    @Test
    fun applyRemoteProfileJson_writesTextThroughSharedPipeline() = runTest {
        port.text = "local-old"
        val manager = createManager(this)

        val applied = manager.applyRemoteProfileJson(
            """{"text":"signalr-remote","type":"Text"}"""
        )

        assertTrue(applied)
        assertEquals(listOf("signalr-remote"), port.writeHistory)
        assertEquals(listOf("signalr-remote"), pulledTexts)
        assertEquals(sha256Hex("signalr-remote"), prefs.syncClipboardLastUploadedHash)
    }

    @Test
    fun applyRemoteProfileJson_whenWriteFails_retriesSameText() = runTest {
        port.text = "local"
        port.writeSucceeds = false
        val manager = createManager(this)
        val profileJson = """{"text":"retry-remote","type":"Text"}"""

        assertFalse(manager.applyRemoteProfileJson(profileJson))
        assertEquals("", prefs.syncClipboardLastUploadedHash)

        port.writeSucceeds = true
        assertTrue(manager.applyRemoteProfileJson(profileJson))
        assertEquals(listOf("retry-remote", "retry-remote"), port.writeHistory)
        assertEquals(sha256Hex("retry-remote"), prefs.syncClipboardLastUploadedHash)
    }

    @Test
    fun applyRemoteProfileJson_textWithData_downloadsBodyThroughSharedPipeline() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("large remote text"))
        port.text = "local"
        val manager = createManager(this)

        val applied = manager.applyRemoteProfileJson(
            """{"type":"Text","hasData":true,"dataName":"profile.txt"}"""
        )

        assertTrue(applied)
        assertEquals(listOf("large remote text"), port.writeHistory)
        assertEquals("/file/profile.txt", server.takeRequest().path)
    }

    @Test
    fun applyRemoteProfileJson_fileAndImage_updateHistoryWithoutClipboardWrite() = runTest {
        val manager = createManager(this, historyStore)

        assertFalse(
            manager.applyRemoteProfileJson(
                """{"type":"Image","hash":"hash-a","size":123,"hasData":true,"dataName":"remote.png"}""",
                attachmentDownloadAttempts = 0
            )
        )
        historyStore.getHistory().single().let { entry ->
            assertEquals(EntryType.IMAGE, entry.type)
            assertEquals("hash-a", entry.serverHash)
            assertEquals(123L, entry.fileSize)
        }

        assertFalse(
            manager.applyRemoteProfileJson(
                """{"type":"File","size":456,"hasData":true,"dataName":"remote.pdf"}""",
                attachmentDownloadAttempts = 0
            )
        )
        assertEquals(EntryType.FILE, historyStore.getHistory().single().type)
        assertTrue(port.writeHistory.isEmpty())
    }

    @Test
    fun applyRemoteProfileJson_attachmentDisabled_skipsItWithoutTreatingProfileAsFailed() = runTest {
        prefs.syncClipboardImagesEnabled = false
        val manager = createManager(this, historyStore)

        assertTrue(
            manager.applyRemoteProfileJson(
                """{"type":"Image","hash":"hash-a","size":123,"hasData":true,"dataName":"remote.png"}"""
            )
        )
        assertTrue(historyStore.getHistory().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun applyRemoteProfileJson_retriesAttachmentDownloadBeforeSucceeding() = runTest {
        val dataName = "retry-${System.nanoTime()}.pdf"
        val body = "retry file"
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val manager = createManager(this, historyStore)

        assertTrue(
            manager.applyRemoteProfileJson(
                """{"type":"File","hasData":true,"dataName":"$dataName","size":${body.length},"hash":"${syncClipboardAttachmentHash(dataName, sha256Hex(body))}"}"""
            )
        )
        assertEquals(3, server.requestCount)
        assertEquals(DownloadStatus.COMPLETED, historyStore.getHistory().single().downloadStatus)
    }

    @Test
    fun applyRemoteProfileJson_realtimeFailureSchedulesOneDelayedRecovery() = runTest {
        prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.REALTIME
        val dataName = "recovery-${System.nanoTime()}.pdf"
        val body = "recovered file"
        val profile =
            """{"type":"File","hasData":true,"dataName":"$dataName","size":${body.length},"hash":"${syncClipboardAttachmentHash(dataName, sha256Hex(body))}"}"""
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(profile))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val manager = createManager(this, historyStore)

        assertTrue(
            manager.applyRemoteProfileJson(
                profile
            )
        )
        assertEquals(DownloadStatus.FAILED, historyStore.getHistory().single().downloadStatus)

        advanceTimeBy(30_000L)
        advanceUntilIdle()

        assertEquals(5, server.requestCount)
        assertEquals(DownloadStatus.COMPLETED, historyStore.getHistory().single().downloadStatus)
    }

    @Test
    fun applyRemoteProfileJson_sameFileNameWithNewHash_replacesHistory() = runTest {
        val manager = createManager(this, historyStore)

        assertFalse(
            manager.applyRemoteProfileJson(
                """{"type":"File","hash":"old","size":10,"dataName":"same.pdf"}""",
                attachmentDownloadAttempts = 0
            )
        )
        assertFalse(
            manager.applyRemoteProfileJson(
                """{"type":"File","hash":"new","size":20,"dataName":"same.pdf"}""",
                attachmentDownloadAttempts = 0
            )
        )

        historyStore.getHistory().single().let { entry ->
            assertEquals("new", entry.serverHash)
            assertEquals(20L, entry.fileSize)
        }
        assertEquals(listOf("same.pdf", "same.pdf"), pulledFiles)
    }

    @Test
    fun directSession_serializesRealtimeProfilesInArrivalOrder() = runTest {
        val oldDownloadStarted = CountDownLatch(1)
        val releaseOldDownload = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/file/old.txt") {
                    oldDownloadStarted.countDown()
                    releaseOldDownload.await(2, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("old-A")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        port.text = "local"
        val manager = createManager(this)
        val resultOrder = Collections.synchronizedList(mutableListOf<String>())
        val results = CountDownLatch(2)
        val newerResult = CountDownLatch(1)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = DirectClipboardSyncRuntimeSession(
            context = ApplicationProvider.getApplicationContext(),
            prefs = prefs,
            scope = sessionScope,
            clipboardStore = null,
            initialManager = manager
        )

        try {
            session.applyRemoteProfile(
                """{"type":"Text","hasData":true,"dataName":"old.txt"}"""
            ) {
                resultOrder += "A"
                results.countDown()
                false
            }
            assertTrue(oldDownloadStarted.await(2, TimeUnit.SECONDS))
            session.applyRemoteProfile("""{"type":"Text","text":"new-B"}""") {
                resultOrder += "B"
                results.countDown()
                newerResult.countDown()
                false
            }

            val newerOvertook = newerResult.await(500, TimeUnit.MILLISECONDS)
            releaseOldDownload.countDown()

            assertTrue(results.await(2, TimeUnit.SECONDS))
            assertFalse("new profile overtook blocked old profile", newerOvertook)
            assertEquals(listOf("A", "B"), resultOrder)
            assertEquals(listOf("old-A", "new-B"), port.writeHistory)
            assertEquals(listOf("old-A", "new-B"), pulledTexts)
            assertEquals("new-B", port.text)
            assertEquals(sha256Hex("new-B"), prefs.syncClipboardLastUploadedHash)
        } finally {
            releaseOldDownload.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun directSession_serializesCatchUpBeforeFollowingRealtimeProfile() = runTest {
        val catchUpStarted = CountDownLatch(1)
        val releaseCatchUp = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/SyncClipboard.json") {
                    catchUpStarted.countDown()
                    releaseCatchUp.await(2, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"type":"Text","text":"catchup-A"}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        port.text = "local"
        val manager = createManager(this)
        val profileApplied = CountDownLatch(1)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = DirectClipboardSyncRuntimeSession(
            context = ApplicationProvider.getApplicationContext(),
            prefs = prefs,
            scope = sessionScope,
            clipboardStore = null,
            initialManager = manager
        )

        try {
            session.catchUpPull()
            assertTrue(catchUpStarted.await(2, TimeUnit.SECONDS))
            session.applyRemoteProfile("""{"type":"Text","text":"profile-B"}""") {
                profileApplied.countDown()
                false
            }

            assertFalse(profileApplied.await(500, TimeUnit.MILLISECONDS))
            releaseCatchUp.countDown()

            assertTrue(profileApplied.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("catchup-A", "profile-B"), port.writeHistory)
            assertEquals(listOf("catchup-A", "profile-B"), pulledTexts)
            assertEquals("profile-B", port.text)
        } finally {
            releaseCatchUp.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun directSession_queuedProfileIsDroppedAfterManagerStops() = runTest {
        val oldDownloadStarted = CountDownLatch(1)
        val releaseOldDownload = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/file/old.txt") {
                    oldDownloadStarted.countDown()
                    releaseOldDownload.await(2, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("old-A")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"type":"Text","text":"fallback"}""")
            }
        }
        port.text = "local"
        val manager = createManager(this)
        val queuedFinished = CountDownLatch(1)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = DirectClipboardSyncRuntimeSession(
            context = ApplicationProvider.getApplicationContext(),
            prefs = prefs,
            scope = sessionScope,
            clipboardStore = null,
            initialManager = manager
        )

        try {
            session.applyRemoteProfile(
                """{"type":"Text","hasData":true,"dataName":"old.txt"}"""
            ) { false }
            assertTrue(oldDownloadStarted.await(2, TimeUnit.SECONDS))
            session.applyRemoteProfile("""{"type":"Text","text":"queued-B"}""") {
                queuedFinished.countDown()
                true
            }

            manager.stop()
            releaseOldDownload.countDown()

            assertTrue(queuedFinished.await(2, TimeUnit.SECONDS))
            assertTrue(port.writeHistory.isEmpty())
            assertTrue(pulledTexts.isEmpty())
            assertEquals(1, server.requestCount)
        } finally {
            releaseOldDownload.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun directSession_queuedCatchUpIsDroppedAfterReceivePathInvalidates() = runTest {
        val oldDownloadStarted = CountDownLatch(1)
        val releaseOldDownload = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/file/old.txt") {
                    oldDownloadStarted.countDown()
                    releaseOldDownload.await(2, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(200).setBody("old-A")
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"type":"Text","text":"stale-catchup"}""")
            }
        }
        port.text = "local"
        val manager = createManager(this)
        val queueDrained = CountDownLatch(1)
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = DirectClipboardSyncRuntimeSession(
            context = ApplicationProvider.getApplicationContext(),
            prefs = prefs,
            scope = sessionScope,
            clipboardStore = null,
            initialManager = manager
        )

        try {
            session.applyRemoteProfile(
                """{"type":"Text","hasData":true,"dataName":"old.txt"}"""
            ) { false }
            assertTrue(oldDownloadStarted.await(2, TimeUnit.SECONDS))
            session.catchUpPull()
            session.applyRemoteProfile("""{"type":"Text","text":"queued-barrier"}""") {
                queueDrained.countDown()
                false
            }

            manager.invalidateReceivePath()
            releaseOldDownload.countDown()

            assertTrue(queueDrained.await(2, TimeUnit.SECONDS))
            assertTrue(port.writeHistory.isEmpty())
            assertTrue(pulledTexts.isEmpty())
            assertEquals(1, server.requestCount)
        } finally {
            releaseOldDownload.countDown()
            sessionScope.cancel()
        }
    }

    @Test
    fun pullNow_whenPortWriteFails_retriesSameTextAfterPortRecovers() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"remote","type":"Text"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"remote","type":"Text"}""")
        )
        port.text = "other"
        port.writeSucceeds = false

        val manager = createManager(this)
        val first = manager.pullNow(updateClipboard = true)

        assertFalse(first.first)
        assertEquals("remote", first.second)
        assertEquals(listOf("remote"), port.writeHistory)
        assertTrue(pulledTexts.isEmpty())
        assertEquals("", prefs.syncClipboardLastUploadedHash)

        port.writeSucceeds = true
        val second = manager.pullNow(updateClipboard = true)

        assertTrue(second.first)
        assertEquals("remote", second.second)
        assertEquals(listOf("remote", "remote"), port.writeHistory)
        assertEquals(listOf("remote"), pulledTexts)
        assertEquals(sha256Hex("remote"), prefs.syncClipboardLastUploadedHash)
    }

    @Test
    fun pullNow_whenPausedDuringRequest_doesNotWriteOrSuppressResumeRetry() = runTest {
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()
        port.text = "local"
        historyStore.addFileEntry(EntryType.FILE, "current.pdf", "current.pdf")
        prefs.syncClipboardLastFileName = "current.pdf"

        val manager = createManager(this, historyStore)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        manager.pauseClipboardSideEffects()
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertTrue(port.writeHistory.isEmpty())
        assertEquals(listOf("current.pdf"), historyStore.getHistory().map { it.fileName })
        assertEquals("current.pdf", prefs.syncClipboardLastFileName)

        manager.resumeClipboardSideEffects()
        val resumedPull = manager.pullNow(updateClipboard = true)
        assertTrue(resumedPull.first)
        assertEquals(listOf("remote"), port.writeHistory)
    }

    @Test
    fun stopPolling_dropsInFlightPollingResponse() = runTest {
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()
        port.text = "local"
        val manager = createManager(this)
        val startJob = async(Dispatchers.IO) { manager.start(pollingEnabled = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        manager.setPollingEnabled(false)
        releaseResponse.countDown()
        startJob.await()

        assertTrue(port.writeHistory.isEmpty())
        manager.stop()
    }

    @Test
    fun pullNow_whenStoppedDuringRequest_dropsResponseAndAllowsNextStart() = runTest {
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()
        port.text = "local"
        historyStore.addFileEntry(EntryType.FILE, "current.pdf", "current.pdf")
        prefs.syncClipboardLastFileName = "current.pdf"

        val manager = createManager(this, historyStore)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        manager.stop()
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertTrue(port.writeHistory.isEmpty())
        assertEquals(listOf("current.pdf"), historyStore.getHistory().map { it.fileName })
        assertEquals("current.pdf", prefs.syncClipboardLastFileName)

        manager.start()
        val restartedPull = manager.pullNow(updateClipboard = true)
        assertTrue(restartedPull.first)
        assertEquals(listOf("remote"), port.writeHistory)
        manager.stop()
    }

    @Test
    fun pullNow_whenSyncDisabledDuringRequest_doesNotWrite() = runTest {
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()
        port.text = "local"
        historyStore.addFileEntry(EntryType.FILE, "current.pdf", "current.pdf")
        prefs.syncClipboardLastFileName = "current.pdf"

        val manager = createManager(this, historyStore)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        prefs.syncClipboardEnabled = false
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertTrue(port.writeHistory.isEmpty())
        assertEquals(listOf("current.pdf"), historyStore.getHistory().map { it.fileName })
        assertEquals("current.pdf", prefs.syncClipboardLastFileName)
    }

    @Test
    fun pullNow_whenCredentialsChangeDuringRequest_doesNotWrite() = runTest {
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()
        port.text = "local"

        val manager = createManager(this)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        manager.invalidateReceivePath()
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertTrue(port.writeHistory.isEmpty())
    }

    @Test
    fun pullNow_whenCredentialsChangeDuringRequest_doesNotClearCurrentFileHistory() = runTest {
        historyStore.addFileEntry(EntryType.FILE, "current.pdf", "current.pdf")
        prefs.syncClipboardLastFileName = "current.pdf"
        val (requestStarted, releaseResponse) = blockRemoteTextResponse()

        val manager = createManager(this, historyStore)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        manager.invalidateReceivePath()
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertEquals(listOf("current.pdf"), historyStore.getHistory().map { it.fileName })
        assertEquals("current.pdf", prefs.syncClipboardLastFileName)
    }

    @Test
    fun pullNow_whenPausedDuringImageResponse_doesNotReplaceHistoryOrNotify() = runTest {
        historyStore.addFileEntry(EntryType.FILE, "current.pdf", "current.pdf")
        prefs.syncClipboardLastFileName = "current.pdf"
        val (requestStarted, releaseResponse) = blockResponse(
            """{"type":"Image","hasData":true,"dataName":"stale.png"}"""
        )

        val manager = createManager(this, historyStore)
        val inFlightPull = async(Dispatchers.IO) { manager.pullNow(updateClipboard = true) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        manager.pauseClipboardSideEffects()
        releaseResponse.countDown()

        assertFalse(inFlightPull.await().first)
        assertEquals(listOf("current.pdf"), historyStore.getHistory().map { it.fileName })
        assertEquals("current.pdf", prefs.syncClipboardLastFileName)
        assertTrue(pulledFiles.isEmpty())
    }

    @Test
    fun pullNow_withoutClipboardUpdate_stillReportsSuccessfulFetch() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"remote","type":"Text"}""")
        )
        port.text = "local"

        val result = createManager(this).pullNow(updateClipboard = false)

        assertTrue(result.first)
        assertEquals("remote", result.second)
        assertTrue(port.writeHistory.isEmpty())
    }

    @Test
    fun bridgeObservation_isRenewedPeriodically() = runTest {
        port.actor = SystemClipboardActor.BRIDGE
        val manager = createManager(this)

        manager.start()
        assertEquals(1, port.observeStartCount)

        advanceTimeBy(15_001L)
        assertEquals(2, port.observeStartCount)

        manager.stop()
    }

    @Test
    fun externalChange_uploadsWhenHashDiffers() = runTest(UnconfinedTestDispatcher()) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val manager = createManager(this)
        manager.start()

        port.emulateExternalChange("copied-text")
        advanceUntilIdle()

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("PUT", recorded?.method)
        assertTrue(recorded?.body?.readUtf8()?.contains("copied-text") == true)
        assertEquals(sha256Hex("copied-text"), prefs.syncClipboardLastUploadedHash)
        assertEquals(1, uploadSuccesses.size)

        manager.stop()
    }

    @Test
    fun externalChange_skipsUploadWhenHashMatchesLastPulled() = runTest(UnconfinedTestDispatcher()) {
        prefs.syncClipboardLastUploadedHash = sha256Hex("same-text")
        val manager = createManager(this)
        manager.start()

        port.emulateExternalChange("same-text")
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        manager.stop()
    }

    @Test
    fun externalChange_skipsSensitiveClipboard() = runTest(UnconfinedTestDispatcher()) {
        val manager = createManager(this)
        manager.start()

        port.emulateExternalChange("secret-password", sensitive = true)
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        manager.stop()
    }

    @Test
    fun uploadOnce_skipsWhenPortWriteWouldSucceedButSensitive() = runTest {
        port.text = "secret"
        port.isSensitive = true
        val manager = createManager(this)

        assertFalse(manager.uploadOnce())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun start_withPollingEnabled_runsPullLoop() = runTest {
        prefs.syncClipboardPullIntervalSec = 1
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"polled","type":"Text"}""")
        )
        port.text = "local"

        val manager = createManager(this)
        try {
            manager.start(pollingEnabled = true)
            // Unconfined IO：首轮 pull 在 delay 前已执行；勿 advanceUntilIdle（会吞掉无限 delay）
            assertEquals(listOf("polled"), port.writeHistory)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun start_withPollingDisabled_doesNotRunPullLoop() = runTest {
        val manager = createManager(this)
        try {
            manager.start(pollingEnabled = false)
            advanceTimeBy(5_000L)

            assertEquals(0, server.requestCount)
        } finally {
            manager.stop()
        }
    }

    @Test
    fun invalidateReceivePath_stopsPollingImmediately() = runTest {
        prefs.syncClipboardReceiveMode = ClipboardSyncReceiveMode.POLLING
        prefs.syncClipboardPullIntervalSec = 1
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"a","type":"Text"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"b","type":"Text"}""")
        )
        port.text = "local"

        val manager = createManager(this)
        try {
            manager.start(pollingEnabled = true)
            assertEquals(1, server.requestCount)

            prefs.syncClipboardEnabled = false
            manager.invalidateReceivePath()
            advanceTimeBy(5_000L)
            assertEquals(1, server.requestCount)
        } finally {
            manager.stop()
        }
    }

    private fun createManager(
        scope: TestScope,
        clipboardStore: ClipboardHistoryStore? = null
    ): SyncClipboardManager =
        SyncClipboardManager(
            context = ApplicationProvider.getApplicationContext(),
            prefs = prefs,
            scope = scope,
            listener = object : SyncClipboardManager.Listener {
                override fun onPulledNewContent(text: String) {
                    pulledTexts += text
                }

                override fun onUploadSuccess() {
                    uploadSuccesses += Unit
                }

                override fun onUploadFailed(reason: String?) = Unit

                override fun onFilePulled(
                    type: EntryType,
                    fileName: String,
                    serverFileName: String
                ) {
                    pulledFiles += fileName
                }
            },
            clipboardStore = clipboardStore,
            clipboardPort = port,
            httpClient = httpClient,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        )

    private fun blockRemoteTextResponse(): Pair<CountDownLatch, CountDownLatch> {
        return blockResponse("""{"text":"remote","type":"Text"}""")
    }

    private fun blockResponse(body: String): Pair<CountDownLatch, CountDownLatch> {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(releaseResponse.await(2, TimeUnit.SECONDS))
                return MockResponse()
                    .setResponseCode(200)
                    .setBody(body)
            }
        }
        return requestStarted to releaseResponse
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
