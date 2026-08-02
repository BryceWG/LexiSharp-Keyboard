package com.brycewg.asrkb.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardAttachmentWatcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(WatchTreeProvider::class.java)
            .create(AUTHORITY)
            .get()
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context).apply {
            syncClipboardImagesEnabled = false
            syncClipboardFilesEnabled = true
            syncClipboardAttachmentMaxSizeMb = 1
            syncClipboardWatchTreeUri = TREE_URI.toString()
        }
        context.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, TREE_URI.toString())
            .commit()
        WatchTreeProvider.rows = listOf(
            WatchTreeProvider.Row("old-file", "old.txt", "text/plain", 1L, 10L),
            WatchTreeProvider.Row("new-image", "new.jpg", "image/jpeg", 1L, 20L)
        )
    }

    @Test
    fun failedEligibleAttachment_retriesWhenNewerAttachmentIsSkipped() {
        val watcher = ClipboardAttachmentWatcher(context, prefs, ClipboardAttachmentPolicy(prefs))
        var attempts = 0

        watcher.scanAndUpload {
            attempts++
            false
        }
        watcher.scanAndUpload {
            attempts++
            false
        }

        assertEquals(2, attempts)
    }

    class WatchTreeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor = MatrixCursor(projection).apply {
            rows.forEach { row ->
                addRow(arrayOf<Any>(row.id, row.name, row.mimeType, row.sizeBytes, row.lastModifiedMillis))
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        data class Row(
            val id: String,
            val name: String,
            val mimeType: String,
            val sizeBytes: Long,
            val lastModifiedMillis: Long
        )

        companion object {
            var rows: List<Row> = emptyList()
        }
    }

    companion object {
        private const val AUTHORITY = "clipboard-watch-test"
        private val TREE_URI = Uri.parse("content://$AUTHORITY/tree/root")
        private const val SEEN_PREFS = "clipboard_attachment_uploads"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
