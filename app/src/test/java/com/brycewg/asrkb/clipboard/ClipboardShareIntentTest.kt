package com.brycewg.asrkb.clipboard

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardShareIntentTest {
    @Test
    fun singleSharedFileUri_acceptsOneContentUriAndRejectsMultipleDifferentUris() {
        val first = Uri.parse("content://example/first")
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first)
        assertEquals(first, single.singleSharedFileUri())

        val metadataAndFile = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = ClipData.newPlainText("metadata", "shared text").apply {
                addItem(ClipData.Item(first))
            }
        }
        assertEquals(first, metadataAndFile.singleSharedFileUri())

        val second = Uri.parse("content://example/second")
        val mismatched = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = ClipData.newUri(
                ApplicationProvider.getApplicationContext<Context>().contentResolver,
                "files",
                second
            )
        }
        assertNull(mismatched.singleSharedFileUri())

        assertNull(
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/local/tmp/private.txt"))
                .singleSharedFileUri()
        )
    }
}
