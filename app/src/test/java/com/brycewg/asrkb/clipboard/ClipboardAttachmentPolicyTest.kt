package com.brycewg.asrkb.clipboard

import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardAttachmentPolicyTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.syncClipboardImagesEnabled = false
        prefs.syncClipboardFilesEnabled = false
        prefs.syncClipboardAttachmentMaxSizeMb = 1
    }

    @Test
    fun allows_onlyEnabledTypesWithinSharedLimit() {
        val policy = ClipboardAttachmentPolicy(prefs)

        assertFalse(policy.allows(ClipboardAttachmentKind.IMAGE, 1L))
        prefs.syncClipboardImagesEnabled = true
        assertTrue(policy.allows(ClipboardAttachmentKind.IMAGE, 1024L * 1024L))
        assertFalse(policy.allows(ClipboardAttachmentKind.IMAGE, 1024L * 1024L + 1L))
        assertFalse(policy.allows(ClipboardAttachmentKind.FILE, 1L))
        prefs.syncClipboardFilesEnabled = true
        assertTrue(policy.allows(ClipboardAttachmentKind.FILE, 1L))
    }

    @Test
    fun attachmentHash_usesDataNameAndUppercaseContentHash() {
        assertEquals(
            "E7204F363B5FF5F5D0057D9BCEE8B245DC5F4CF0C9ABB2D66A7AB5CA7E913F85",
            syncClipboardAttachmentHash(
                "sample.txt",
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        )
    }

    @Test
    fun originStore_recognizesRecentLocallyPublishedProfiles() {
        val originStore = ClipboardAttachmentOriginStore(
            ApplicationProvider.getApplicationContext()
        )
        originStore.record("LOCAL-HASH-A")
        originStore.record("LOCAL-HASH-B")

        assertTrue(originStore.isLocal("local-hash-a"))
        assertTrue(originStore.isLocal("local-hash-b"))
        assertFalse(originStore.isLocal("remote-hash"))
        originStore.clear("local-hash-a")
        assertFalse(originStore.isLocal("local-hash-a"))
    }

    @Test
    fun downloadDirectory_cannotBeUsedAsWatchTree() {
        assertTrue(
            isSyncClipboardDownloadDirectory(
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FBiBi")
            )
        )
    }
}
