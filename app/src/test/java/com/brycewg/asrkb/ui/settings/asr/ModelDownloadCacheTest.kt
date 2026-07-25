package com.brycewg.asrkb.ui.settings.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun prune_deletesExpiredZip_keepsFreshAndProtected() {
        val dir = tempFolder.root
        val now = 1_700_000_000_000L
        val expired = File(dir, "old.zip").apply {
            writeBytes(ByteArray(100))
            setLastModified(now - ModelDownloadCache.MAX_AGE_MS - 1)
        }
        val expiredMetadata = File(expired.path + ".resume").apply { writeText("metadata") }
        val fresh = File(dir, "fresh.zip").apply {
            writeBytes(ByteArray(50))
            setLastModified(now - 1_000)
        }
        val protected = File(dir, "active.zip").apply {
            writeBytes(ByteArray(80))
            setLastModified(now - ModelDownloadCache.MAX_AGE_MS - 1)
        }

        val result = ModelDownloadCache.prune(
            cacheDir = dir,
            protectFileNames = setOf("active.zip"),
            nowMs = now
        )

        assertFalse(expired.exists())
        assertFalse(expiredMetadata.exists())
        assertTrue(fresh.exists())
        assertTrue(protected.exists())
        assertEquals(1, result.deletedCount)
        assertEquals(2, result.keptCount)
        assertEquals(100L, result.freedBytes)
    }

    @Test
    fun prune_enforcesTotalSizeCap_deletesOldestFirst() {
        val dir = tempFolder.root
        val now = 1_700_000_000_000L
        val older = File(dir, "a.zip").apply {
            writeBytes(ByteArray(60))
            setLastModified(now - 3_000)
        }
        val newer = File(dir, "b.zip").apply {
            writeBytes(ByteArray(60))
            setLastModified(now - 1_000)
        }

        val result = ModelDownloadCache.prune(
            cacheDir = dir,
            nowMs = now,
            maxAgeMs = ModelDownloadCache.MAX_AGE_MS,
            maxTotalBytes = 80L
        )

        assertFalse(older.exists())
        assertTrue(newer.exists())
        assertEquals(1, result.deletedCount)
        assertTrue(result.freedBytes >= 60L)
    }

    @Test
    fun touch_updatesLastModified() {
        val file = tempFolder.newFile("partial.zip")
        file.writeBytes(byteArrayOf(1, 2, 3))
        file.setLastModified(1_000L)
        ModelDownloadCache.touch(file)
        assertTrue(file.lastModified() > 1_000L)
    }
}
