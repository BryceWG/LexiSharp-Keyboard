package com.brycewg.asrkb.ui.settings.asr

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableHttpDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var destFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        destFile = File(tempFolder.root, "model.zip")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parseContentRange_validAndInvalid() {
        val parsed = ResumableHttpDownloader.parseContentRange("bytes 100-199/500")
        assertEquals(100L, parsed?.start)
        assertEquals(199L, parsed?.end)
        assertEquals(500L, parsed?.total)

        val unknownTotal = ResumableHttpDownloader.parseContentRange("bytes 0-9/*")
        assertEquals(0L, unknownTotal?.start)
        assertEquals(null, unknownTotal?.total)

        assertEquals(null, ResumableHttpDownloader.parseContentRange("bytes */500"))
        assertEquals(null, ResumableHttpDownloader.parseContentRange("invalid"))
    }

    @Test
    fun download_noPartial_writesFullBodyOn200() {
        val body = "hello-full-download".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .setHeader("Content-Length", body.size.toString())
        )

        val progress = mutableListOf<Int>()
        ResumableHttpDownloader.download(
            client = client,
            url = server.url("/model.zip").toString(),
            destFile = destFile,
            onProgress = { progress.add(it) }
        )

        assertArrayEquals(body, destFile.readBytes())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(null, recorded.getHeader("Range"))
        assertEquals(1, server.requestCount)
        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())
    }

    @Test
    fun download_partialPlus206_appendsAndReportsProgressFromTotal() {
        val full = ByteArray(20) { it.toByte() }
        val existing = full.copyOfRange(0, 8)
        destFile.writeBytes(existing)
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"model-v1\"")

        val remaining = full.copyOfRange(8, full.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(remaining))
                .setHeader("Content-Range", "bytes 8-19/20")
                .setHeader("Content-Length", remaining.size.toString())
        )

        val progress = mutableListOf<Int>()
        ResumableHttpDownloader.download(
            client = client,
            url = url,
            destFile = destFile,
            onProgress = { progress.add(it) }
        )

        assertArrayEquals(full, destFile.readBytes())
        val recorded = server.takeRequest()
        assertEquals("bytes=8-", recorded.getHeader("Range"))
        assertEquals("\"model-v1\"", recorded.getHeader("If-Range"))
        assertEquals(40, progress.first()) // 8/20
        assertEquals(100, progress.last())
    }

    @Test
    fun download_partialPlus200_truncatesAndRewrites() {
        destFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"old-version\"")
        val body = "replacement-content".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .setHeader("Content-Length", body.size.toString())
        )

        ResumableHttpDownloader.download(
            client = client,
            url = url,
            destFile = destFile
        )

        assertArrayEquals(body, destFile.readBytes())
        assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun download_416_deletesPartialAndRetriesWithoutRange() {
        destFile.writeBytes(ByteArray(10) { 7 })
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"old-version\"")
        val body = "fresh-after-416".toByteArray()
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .setHeader("Content-Length", body.size.toString())
        )

        ResumableHttpDownloader.download(
            client = client,
            url = url,
            destFile = destFile
        )

        assertArrayEquals(body, destFile.readBytes())
        assertEquals(2, server.requestCount)
        assertEquals("bytes=10-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun download_cancelMidway_keepsPartialFile() {
        val body = ByteArray(256 * 1024) { 9 }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(body))
                .throttleBody(8 * 1024, 30, TimeUnit.MILLISECONDS)
                .setHeader("Content-Length", body.size.toString())
        )

        val active = AtomicBoolean(true)
        val lastProgress = AtomicInteger(0)
        try {
            ResumableHttpDownloader.download(
                client = client,
                url = server.url("/model.zip").toString(),
                destFile = destFile,
                isActive = { active.get() },
                onProgress = { percent ->
                    lastProgress.set(percent)
                    if (percent >= 5) {
                        active.set(false)
                    }
                }
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected from ensureActive
        } catch (_: IOException) {
            // canceled socket during read is also acceptable
        }

        assertTrue("expected partial file to remain", destFile.exists())
        assertTrue(
            "expected partial bytes, length=${destFile.length()}, progress=${lastProgress.get()}",
            destFile.length() > 0L && destFile.length() < body.size
        )
    }

    @Test
    fun download_ioFailure_keepsPartialFile() {
        destFile.writeBytes(byteArrayOf(1, 2, 3))
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"model-v1\"")
        val before = destFile.length()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            ResumableHttpDownloader.download(
                client = client,
                url = url,
                destFile = destFile
            )
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }

        assertTrue(destFile.exists())
        assertEquals(before, destFile.length())
    }

    @Test
    fun download_shortRead_throwsAndKeepsPartial() {
        val body = ByteArray(10) { it.toByte() }
        val shortClient = clientWithClaimedLengthBody(
            code = 200,
            actualBytes = body,
            claimedLength = 40L,
            etag = "\"model-v1\""
        )

        try {
            ResumableHttpDownloader.download(
                client = shortClient,
                url = "https://example.invalid/model.zip",
                destFile = destFile
            )
            fail("Expected IncompleteDownloadException")
        } catch (e: IncompleteDownloadException) {
            assertEquals(40L, e.expectedBytes)
            assertEquals(10L, e.actualBytes)
        }

        assertTrue(destFile.exists())
        assertEquals(10L, destFile.length())
        assertTrue(File(destFile.path + ".resume").readText().contains("\"model-v1\""))
    }

    @Test
    fun download_partialPlus206_shortRead_keepsPartialForResume() {
        destFile.writeBytes(ByteArray(8) { it.toByte() })
        writeResumeMetadata("https://example.invalid/model.zip", "\"model-v1\"")
        val remaining = ByteArray(4) { (it + 8).toByte() }
        val shortClient = clientWithClaimedLengthBody(
            code = 206,
            actualBytes = remaining,
            claimedLength = 12L,
            contentRange = "bytes 8-19/20"
        )

        try {
            ResumableHttpDownloader.download(
                client = shortClient,
                url = "https://example.invalid/model.zip",
                destFile = destFile
            )
            fail("Expected IncompleteDownloadException")
        } catch (e: IncompleteDownloadException) {
            assertEquals(20L, e.expectedBytes)
            assertEquals(12L, e.actualBytes)
        }

        assertTrue(destFile.exists())
        assertEquals(12L, destFile.length())
    }

    @Test
    fun download_repeatedMismatched206_stopsAfterSingleFallback() {
        destFile.writeBytes(ByteArray(8))
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"model-v1\"")
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setBody("wrong-range")
                    .setHeader("Content-Range", "bytes 4-14/20")
            )
        }

        try {
            ResumableHttpDownloader.download(client, url, destFile)
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun download_partialPlus206_rejectsBodyLongerThanDeclaredRange() {
        destFile.writeBytes(ByteArray(8))
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"model-v1\"")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(ByteArray(13)))
                .setHeader("Content-Range", "bytes 8-19/20")
        )

        try {
            ResumableHttpDownloader.download(client, url, destFile)
            fail("Expected IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun download_partialPlus206WithChangedValidator_restartsFromZero() {
        destFile.writeBytes(ByteArray(8) { 1 })
        val url = server.url("/model.zip").toString()
        writeResumeMetadata(url, "\"model-v1\"")
        val replacement = ByteArray(20) { 2 }
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(Buffer().write(replacement.copyOfRange(8, replacement.size)))
                .setHeader("Content-Range", "bytes 8-19/20")
                .setHeader("ETag", "\"model-v2\"")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(replacement))
                .setHeader("ETag", "\"model-v2\"")
        )

        ResumableHttpDownloader.download(client, url, destFile)

        assertArrayEquals(replacement, destFile.readBytes())
        assertEquals("\"model-v1\"", server.takeRequest().getHeader("If-Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun discardIfSourceChanged_removesOldCacheAndMetadata() {
        destFile.writeBytes(ByteArray(4))
        writeResumeMetadata("https://old.example/model.zip", "\"old\"")

        assertTrue(
            ResumableHttpDownloader.discardIfSourceChanged(
                destFile,
                "https://new.example/model.zip"
            )
        )
        assertFalse(destFile.exists())
        assertFalse(File(destFile.path + ".resume").exists())
    }

    private fun writeResumeMetadata(url: String, validator: String) {
        File(destFile.path + ".resume").writeText("$url\n$validator")
    }

    /**
     * Returns a client whose interceptor serves a body that EOFs early while
     * advertising a larger Content-Length (avoids MockWebServer socket wait).
     */
    private fun clientWithClaimedLengthBody(
        code: Int,
        actualBytes: ByteArray,
        claimedLength: Long,
        contentRange: String? = null,
        etag: String? = null
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength() = claimedLength
                override fun source(): BufferedSource = Buffer().write(actualBytes)
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .apply {
                    if (contentRange != null) {
                        header("Content-Range", contentRange)
                    }
                    if (etag != null) {
                        header("ETag", etag)
                    }
                }
                .body(body)
                .build()
        }
        .build()
}
