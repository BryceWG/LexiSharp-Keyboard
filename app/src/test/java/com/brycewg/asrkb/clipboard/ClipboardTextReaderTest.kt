package com.brycewg.asrkb.clipboard

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardTextReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readClipboardText_returnsPlainText() {
        val clip = ClipData.newPlainText("label", "hello")

        assertEquals("hello", readClipboardText(clip))
    }

    @Test
    fun readClipboardText_ignoresFileUri() {
        Robolectric.buildContentProvider(BinaryContentProvider::class.java)
            .create("clipboard")
            .get()
        val clip = ClipData.newUri(
            context.contentResolver,
            "photo.jpg",
            Uri.parse("content://clipboard/photo.jpg")
        )

        assertNull(readClipboardText(clip))
    }

    class BinaryContentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?
        ): AssetFileDescriptor {
            val file = checkNotNull(context).cacheDir.resolve("photo.jpg")
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + "JFIF binary".toByteArray())
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
    }
}
