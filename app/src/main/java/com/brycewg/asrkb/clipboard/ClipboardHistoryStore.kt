package com.brycewg.asrkb.clipboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class EntryType { TEXT, IMAGE, FILE }

@Serializable
enum class DownloadStatus { NONE, DOWNLOADING, COMPLETED, FAILED }

/**
 * SQLite-backed clipboard history shared by the IME panel and sync runtime.
 * Pinned and regular entries use one table; only pinned entries are included in backups.
 */
class ClipboardHistoryStore(context: Context, @Suppress("UNUSED_PARAMETER") prefs: Prefs) {
    @Serializable
    data class Entry(
        val id: String,
        val text: String = "",
        val ts: Long,
        val pinned: Boolean,
        val type: EntryType = EntryType.TEXT,
        val fileName: String? = null,
        val fileSize: Long? = null,
        val mimeType: String? = null,
        val localFilePath: String? = null,
        val downloadStatus: DownloadStatus = DownloadStatus.NONE,
        val serverFileName: String? = null,
        val serverHash: String? = null
    ) {
        fun getDisplayLabel(): String {
            if (type == EntryType.TEXT) return text
            val rawName = fileName ?: serverFileName ?: text
            if (rawName.isBlank()) return ""
            val dotIndex = rawName.lastIndexOf('.')
            val base = if (dotIndex > 0) rawName.substring(0, dotIndex) else rawName
            val ext = if (dotIndex > 0 && dotIndex < rawName.length - 1) {
                rawName.substring(dotIndex + 1).uppercase()
            } else {
                "FILE"
            }
            return "$ext-$base"
        }
    }

    companion object {
        private const val TAG = "ClipboardHistoryStore"
        private const val MAX_HISTORY = 200
        private const val MAX_PINNED = 200
        internal const val MAX_STORED_TEXT_CHARS = 32 * 1024
        internal const val KEY_CLIP_HISTORY_JSON = "clip_history"
        internal const val KEY_CLIP_PINNED_JSON = "clip_pinned"
        private val STORE_LOCK = Any()
        private val migrationFailedThisProcess = AtomicBoolean(false)
    }

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
    private val database = ClipboardHistoryDatabase.get(appContext)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getAll(): List<Entry> = withReadableDb(emptyList()) {
        database.queryGroup(it, true) + database.queryGroup(it, false)
    }
    fun getPinned(): List<Entry> = withReadableDb(emptyList()) { database.queryGroup(it, true) }
    fun getHistory(): List<Entry> = withReadableDb(emptyList()) { database.queryGroup(it, false) }
    fun totalCount(): Int = withReadableDb(0, database::count)

    fun clearFileEntries() {
        withWritableDb { database.deleteNonTextHistory(it) }
    }

    fun addFromClipboard(text: String) {
        val normalized = normalizeClipboardHistoryText(text)
        if (normalized.isEmpty()) return
        withWritableDb { db ->
            db.beginTransaction()
            try {
                if (database.queryLatestHistory(db)?.text != normalized) {
                    database.insertOrReplace(
                        db,
                        Entry(
                            UUID.randomUUID().toString(),
                            normalized,
                            System.currentTimeMillis(),
                            pinned = false
                        )
                    )
                    database.pruneGroup(db, false, MAX_HISTORY)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun togglePin(id: String): Boolean = withWritableDb { db ->
        db.beginTransaction()
        try {
            val current = database.queryById(db, id) ?: return@withWritableDb false
            val pinned = !current.pinned
            database.setPinned(db, id, pinned, System.currentTimeMillis())
            database.pruneGroup(db, pinned, if (pinned) MAX_PINNED else MAX_HISTORY)
            db.setTransactionSuccessful()
            pinned
        } finally {
            db.endTransaction()
        }
    }

    fun deleteHistoryBefore(cutoffEpochMs: Long): Int = withWritableDb {
        database.deleteHistoryBefore(it, cutoffEpochMs)
    }
    fun clearAllNonPinned(): Int = withWritableDb { database.deleteNonPinned(it) }
    fun clearAll() {
        withWritableDb { database.deleteAll(it) }
        removeLegacyJsonOrThrow()
    }
    fun deleteHistoryById(id: String): Boolean = withWritableDb {
        database.deleteHistoryById(it, id) > 0
    }

    fun pasteInto(ic: android.view.inputmethod.InputConnection?, text: String) {
        if (ic == null) return
        try {
            ic.commitText(text, 1)
        } catch (e: Throwable) {
            Log.e(TAG, "commitText failed", e)
        }
    }

    fun addFileEntry(
        type: EntryType,
        fileName: String,
        serverFileName: String,
        fileSize: Long? = null,
        serverHash: String? = null,
        mimeType: String? = null,
        localFilePath: String? = null,
        downloadStatus: DownloadStatus = DownloadStatus.NONE
    ): Boolean = withWritableDb { db ->
        db.beginTransaction()
        try {
            database.deleteNonTextHistory(db)
            database.insertOrReplace(
                db,
                Entry(
                    id = UUID.randomUUID().toString(), ts = System.currentTimeMillis(), pinned = false,
                    type = type, fileName = fileName, fileSize = fileSize, serverHash = serverHash,
                    mimeType = mimeType, localFilePath = localFilePath, downloadStatus = downloadStatus,
                    serverFileName = serverFileName
                )
            )
            database.pruneGroup(db, false, MAX_HISTORY)
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun updateFileEntry(id: String, localFilePath: String?, downloadStatus: DownloadStatus): Boolean = withWritableDb { database.setFileState(it, id, localFilePath, downloadStatus) > 0 }

    fun getEntryById(id: String): Entry? = withReadableDb(null) { database.queryById(it, id) }
    internal fun getLatestFileEntry(): Entry? = withReadableDb(null) { database.queryLatestFileHistory(it) }

    fun exportPinnedJson(): String = synchronized(STORE_LOCK) {
        migrateFromPrefsIfNeeded()
        check(!migrationFailedThisProcess.get()) { "Clipboard history migration is incomplete" }
        val db = database.readableOrNull()
            ?: throw SQLiteException("Failed to open clipboard history database for export")
        json.encodeToString(database.queryGroup(db, true))
    }

    fun replacePinnedFromJson(raw: String) {
        val entries = parseEntries(raw).sortedByDescending(Entry::ts).distinctBy(Entry::id)
            .map { it.copy(pinned = true) }
        withWritableDb { db ->
            db.beginTransaction()
            try {
                database.deletePinned(db)
                entries.forEach { database.insertOrReplace(db, it) }
                database.pruneGroup(db, true, MAX_PINNED)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun <T> withReadableDb(fallback: T, block: (SQLiteDatabase) -> T): T = synchronized(STORE_LOCK) {
        migrateFromPrefsIfNeeded()
        if (migrationFailedThisProcess.get()) return@synchronized fallback
        val db = database.readableOrNull() ?: return@synchronized fallback
        try {
            block(db)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard history read failed", e)
            logVerbose("clipboard_history_read_failed", mapOf("error" to e.javaClass.simpleName))
            fallback
        }
    }

    private fun <T> withWritableDb(block: (SQLiteDatabase) -> T): T = synchronized(STORE_LOCK) {
        migrateFromPrefsIfNeeded()
        check(!migrationFailedThisProcess.get()) { "Clipboard history migration failed; retry after cold start" }
        val db = database.writableOrNull()
            ?: throw SQLiteException("Failed to open clipboard history database for writing")
        try {
            block(db)
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard history write failed", e)
            logVerbose("clipboard_history_write_failed", mapOf("error" to e.javaClass.simpleName))
            throw e
        }
    }

    private fun migrateFromPrefsIfNeeded() {
        if (migrationFailedThisProcess.get()) return
        val hasHistory = sp.contains(KEY_CLIP_HISTORY_JSON)
        val hasPinned = sp.contains(KEY_CLIP_PINNED_JSON)
        if (!hasHistory && !hasPinned) return
        val db = database.writableOrNull()
        if (db == null) {
            migrationFailedThisProcess.set(true)
            return
        }
        try {
            val pinned = parseEntries(sp.getString(KEY_CLIP_PINNED_JSON, "").orEmpty())
                .sortedByDescending(Entry::ts).map { it.copy(pinned = true) }
            val pinnedIds = pinned.mapTo(HashSet()) { it.id }
            val history = parseEntries(sp.getString(KEY_CLIP_HISTORY_JSON, "").orEmpty())
                .sortedByDescending(Entry::ts).filterNot { it.id in pinnedIds }
                .map { it.copy(pinned = false) }
            db.beginTransaction()
            try {
                pinned.distinctBy(Entry::id).forEach { database.insertOrReplace(db, it) }
                history.distinctBy(Entry::id).forEach { database.insertOrReplace(db, it) }
                database.pruneGroup(db, true, MAX_PINNED)
                database.pruneGroup(db, false, MAX_HISTORY)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            removeLegacyJsonOrThrow()
            logBase("clipboard_history_migrate_ok", mapOf("count" to pinned.size + history.size))
        } catch (e: Exception) {
            migrationFailedThisProcess.set(true)
            Log.e(TAG, "Failed to migrate clipboard history", e)
            logError("clipboard_history_migrate_failed", e)
        }
    }

    private fun parseEntries(raw: String): List<Entry> = if (raw.isBlank()) emptyList() else json.decodeFromString(raw)

    private fun removeLegacyJsonOrThrow() {
        check(sp.edit().remove(KEY_CLIP_HISTORY_JSON).remove(KEY_CLIP_PINNED_JSON).commit()) {
            "Failed to clear migrated clipboard history JSON"
        }
    }

    private fun logBase(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.logBase(appContext, "app", event, data)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", e)
        }
    }
    private fun logVerbose(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.log("app", event, data)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", e)
        }
    }
    private fun logError(event: String, throwable: Throwable) {
        try {
            DebugLogManager.logError(appContext, "app", event, throwable)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", e)
        }
    }
}

internal fun normalizeClipboardHistoryText(text: String): String = text.trim().take(ClipboardHistoryStore.MAX_STORED_TEXT_CHARS)
