package com.brycewg.asrkb.clipboard

import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    private lateinit var httpClient: OkHttpClient
    private val pulledTexts = mutableListOf<String>()
    private val uploadSuccesses = mutableListOf<Unit>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        prefs = Prefs(context)
        prefs.syncClipboardEnabled = true
        prefs.syncClipboardAutoPullEnabled = false
        prefs.syncClipboardServerBase = server.url("/").toString().trimEnd('/')
        prefs.syncClipboardUsername = "user"
        prefs.syncClipboardPassword = "pass"
        prefs.syncClipboardLastUploadedHash = ""
        port = FakeSystemClipboardPort()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        pulledTexts.clear()
        uploadSuccesses.clear()
    }

    @After
    fun tearDown() {
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

    private fun createManager(scope: TestScope): SyncClipboardManager =
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
                ) = Unit
            },
            clipboardStore = null,
            clipboardPort = port,
            httpClient = httpClient,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        )

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
