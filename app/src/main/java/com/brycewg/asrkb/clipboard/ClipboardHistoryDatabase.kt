// SQLite storage for clipboard history and synchronized attachment state.
package com.brycewg.asrkb.clipboard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

internal class ClipboardHistoryDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "clipboard_history.db", null, 1) {
    companion object {
        private const val TAG = "ClipboardHistoryDb"
        private const val TABLE = "clipboard_history"
        private const val ID = "id"
        private const val TEXT = "text"
        private const val TS = "timestamp"
        private const val PINNED = "pinned"
        private const val TYPE = "entry_type"
        private const val FILE_NAME = "file_name"
        private const val FILE_SIZE = "file_size"
        private const val MIME = "mime_type"
        private const val LOCAL_PATH = "local_file_path"
        private const val DOWNLOAD = "download_status"
        private const val SERVER_NAME = "server_file_name"
        private const val HASH = "server_hash"

        @Volatile private var instance: ClipboardHistoryDatabase? = null
        fun get(context: Context): ClipboardHistoryDatabase = instance ?: synchronized(this) {
            instance ?: ClipboardHistoryDatabase(context.applicationContext).also { instance = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $ID TEXT PRIMARY KEY NOT NULL, $TEXT TEXT NOT NULL,
                $TS INTEGER NOT NULL, $PINNED INTEGER NOT NULL, $TYPE TEXT NOT NULL,
                $FILE_NAME TEXT, $FILE_SIZE INTEGER, $MIME TEXT, $LOCAL_PATH TEXT,
                $DOWNLOAD TEXT NOT NULL, $SERVER_NAME TEXT, $HASH TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clipboard_history_group_ts ON $TABLE ($PINNED, $TS DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun writableOrNull(): SQLiteDatabase? = open(true)
    fun readableOrNull(): SQLiteDatabase? = open(false)

    fun insertOrReplace(db: SQLiteDatabase, entry: ClipboardHistoryStore.Entry) {
        val result = db.insertWithOnConflict(TABLE, null, values(entry), SQLiteDatabase.CONFLICT_REPLACE)
        if (result == -1L) throw SQLException("Failed to insert clipboard history")
    }

    fun queryGroup(db: SQLiteDatabase, pinned: Boolean): List<ClipboardHistoryStore.Entry> = db.query(
        TABLE,
        null,
        "$PINNED = ?",
        arrayOf(if (pinned) "1" else "0"),
        null,
        null,
        "$TS DESC, $ID DESC"
    ).use(::readEntries)

    fun queryById(db: SQLiteDatabase, id: String): ClipboardHistoryStore.Entry? = db.query(TABLE, null, "$ID = ?", arrayOf(id), null, null, null, "1").use {
        if (it.moveToFirst()) toEntry(it) else null
    }

    fun queryLatestHistory(db: SQLiteDatabase): ClipboardHistoryStore.Entry? = db.query(TABLE, null, "$PINNED = 0", null, null, null, "$TS DESC, $ID DESC", "1").use {
        if (it.moveToFirst()) toEntry(it) else null
    }

    fun queryLatestFileHistory(db: SQLiteDatabase): ClipboardHistoryStore.Entry? = db.query(
        TABLE,
        null,
        "$PINNED = 0 AND $TYPE != ?",
        arrayOf(EntryType.TEXT.name),
        null,
        null,
        "$TS DESC, $ID DESC",
        "1"
    ).use {
        if (it.moveToFirst()) toEntry(it) else null
    }

    fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun setPinned(db: SQLiteDatabase, id: String, pinned: Boolean, timestamp: Long): Int = db.update(
        TABLE,
        ContentValues().apply {
            put(PINNED, if (pinned) 1 else 0)
            put(TS, timestamp)
        },
        "$ID = ?",
        arrayOf(id)
    )

    fun setFileState(db: SQLiteDatabase, id: String, localPath: String?, status: DownloadStatus): Int = db.update(
        TABLE,
        ContentValues().apply {
            if (localPath != null) put(LOCAL_PATH, localPath)
            put(DOWNLOAD, status.name)
        },
        "$ID = ?",
        arrayOf(id)
    )

    fun deleteHistoryBefore(db: SQLiteDatabase, cutoff: Long): Int = db.delete(TABLE, "$PINNED = 0 AND $TS < ?", arrayOf(cutoff.toString()))
    fun deleteHistoryById(db: SQLiteDatabase, id: String): Int = db.delete(TABLE, "$ID = ? AND $PINNED = 0", arrayOf(id))
    fun deleteNonPinned(db: SQLiteDatabase): Int = db.delete(TABLE, "$PINNED = 0", null)
    fun deletePinned(db: SQLiteDatabase): Int = db.delete(TABLE, "$PINNED = 1", null)
    fun deleteNonTextHistory(db: SQLiteDatabase): Int = db.delete(TABLE, "$PINNED = 0 AND $TYPE != ?", arrayOf(EntryType.TEXT.name))
    fun deleteAll(db: SQLiteDatabase) {
        db.delete(TABLE, null, null)
    }

    fun pruneGroup(db: SQLiteDatabase, pinned: Boolean, maxEntries: Int) {
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE $ID IN (
                SELECT $ID FROM $TABLE WHERE $PINNED = ?
                ORDER BY $TS DESC, $ID DESC LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            arrayOf(if (pinned) 1 else 0, maxEntries)
        )
    }

    private fun open(writable: Boolean): SQLiteDatabase? = try {
        if (writable) writableDatabase else readableDatabase
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open clipboard history database", e)
        null
    }

    private fun values(entry: ClipboardHistoryStore.Entry) = ContentValues().apply {
        put(ID, entry.id)
        put(TEXT, entry.text)
        put(TS, entry.ts)
        put(PINNED, if (entry.pinned) 1 else 0)
        put(TYPE, entry.type.name)
        putNullable(FILE_NAME, entry.fileName)
        putNullable(FILE_SIZE, entry.fileSize)
        putNullable(MIME, entry.mimeType)
        putNullable(LOCAL_PATH, entry.localFilePath)
        put(DOWNLOAD, entry.downloadStatus.name)
        putNullable(SERVER_NAME, entry.serverFileName)
        putNullable(HASH, entry.serverHash)
    }

    private fun readEntries(cursor: Cursor): List<ClipboardHistoryStore.Entry> {
        val result = ArrayList<ClipboardHistoryStore.Entry>(cursor.count)
        while (cursor.moveToNext()) result += toEntry(cursor)
        return result
    }

    private fun toEntry(cursor: Cursor) = ClipboardHistoryStore.Entry(
        id = cursor.string(ID).orEmpty(), text = cursor.string(TEXT).orEmpty(), ts = cursor.long(TS),
        pinned = cursor.int(PINNED) != 0, type = cursor.enum(TYPE, EntryType.TEXT),
        fileName = cursor.string(FILE_NAME), fileSize = cursor.nullableLong(FILE_SIZE),
        mimeType = cursor.string(MIME), localFilePath = cursor.string(LOCAL_PATH),
        downloadStatus = cursor.enum(DOWNLOAD, DownloadStatus.NONE),
        serverFileName = cursor.string(SERVER_NAME), serverHash = cursor.string(HASH)
    )

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }
    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }
    private fun Cursor.string(column: String): String? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getString(i)
    }
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.nullableLong(column: String): Long? {
        val i = getColumnIndexOrThrow(column)
        return if (isNull(i)) null else getLong(i)
    }
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private inline fun <reified T : Enum<T>> Cursor.enum(column: String, fallback: T): T = try {
        java.lang.Enum.valueOf(T::class.java, string(column).orEmpty())
    } catch (_: IllegalArgumentException) {
        fallback
    }
}
