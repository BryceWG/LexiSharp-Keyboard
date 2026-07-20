// Stores re-recognition audio in the app-private, no-backup directory.
package com.brycewg.asrkb.store

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AsrHistoryAudioStore(context: Context) {
    companion object {
        private const val TAG = "AsrHistoryAudioStore"
        private const val DIRECTORY = "asr_history_audio"
        private const val EXTENSION = ".pcm"
        private const val PENDING_FILE_GRACE_MS = 10 * 60 * 1000L
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val deletedIds = ConcurrentHashMap.newKeySet<String>()
        private val storageGeneration = AtomicLong(0L)

        fun saveAsync(context: Context, recordId: String, pcm16kMono: ByteArray) {
            val appContext = context.applicationContext
            val scheduledGeneration = storageGeneration.get()
            ioScope.launch {
                val prefs = Prefs(appContext)
                if (scheduledGeneration != storageGeneration.get() ||
                    recordId in deletedIds ||
                    prefs.disableAsrHistory ||
                    prefs.audioHistoryRetentionCount <= 0
                ) {
                    return@launch
                }
                val store = AsrHistoryAudioStore(appContext)
                if (store.save(recordId, pcm16kMono)) {
                    val latestPrefs = Prefs(appContext)
                    if (scheduledGeneration != storageGeneration.get() ||
                        latestPrefs.disableAsrHistory ||
                        latestPrefs.audioHistoryRetentionCount <= 0
                    ) {
                        store.delete(recordId)
                        return@launch
                    }
                    store.prune(
                        AsrHistoryStore(appContext).listAll(),
                        latestPrefs.audioHistoryRetentionCount
                    )
                }
            }
        }

        fun pruneAsync(
            context: Context,
            recordsNewestFirst: List<AsrHistoryStore.AsrHistoryRecord>,
            maxCount: Int
        ) {
            val appContext = context.applicationContext
            ioScope.launch {
                AsrHistoryAudioStore(appContext).prune(recordsNewestFirst, maxCount)
            }
        }
    }

    private val directory = File(context.noBackupFilesDir, DIRECTORY)

    fun hasAudio(recordId: String): Boolean = audioFile(recordId).isFile

    fun readAudio(recordId: String): ByteArray? = try {
        audioFile(recordId).takeIf { it.isFile }?.readBytes()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read archived audio", e)
        null
    }

    fun save(recordId: String, pcm16kMono: ByteArray): Boolean {
        if (pcm16kMono.isEmpty()) return false
        if (recordId in deletedIds) return false
        return try {
            if (!directory.exists() && !directory.mkdirs()) return false
            val target = audioFile(recordId)
            val temp = File(directory, "$recordId.tmp")
            FileOutputStream(temp).use { it.write(pcm16kMono) }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            if (recordId in deletedIds) {
                target.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive audio", e)
            runCatching { File(directory, "$recordId.tmp").delete() }
            false
        }
    }

    fun delete(recordId: String) {
        deletedIds.add(recordId)
        runCatching { audioFile(recordId).delete() }
            .onFailure { Log.w(TAG, "Failed to delete archived audio", it) }
    }

    fun clearAll() {
        storageGeneration.incrementAndGet()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) Log.w(TAG, "Failed to delete ${file.name}")
        }
        deletedIds.clear()
    }

    fun prune(recordsNewestFirst: List<AsrHistoryStore.AsrHistoryRecord>, maxCount: Int) {
        if (maxCount <= 0) {
            clearAll()
            return
        }
        val knownIds = recordsNewestFirst.mapTo(mutableSetOf()) { it.id }
        val keepIds = if (maxCount <= 0) emptySet() else {
            recordsNewestFirst.asSequence()
                .map { it.id }
                .filter(::hasAudio)
                .take(maxCount.coerceIn(0, 100))
                .toSet()
        }
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            val recordId = file.name.removeSuffix(EXTENSION)
            val pending = file.extension == "pcm" &&
                recordId !in knownIds &&
                now - file.lastModified() < PENDING_FILE_GRACE_MS
            val keep = file.extension == "pcm" && (recordId in keepIds || pending)
            if (!keep && !file.delete()) Log.w(TAG, "Failed to prune ${file.name}")
        }
    }

    private fun audioFile(recordId: String): File = File(directory, "$recordId$EXTENSION")
}
