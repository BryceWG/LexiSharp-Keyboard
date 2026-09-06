/** 剪贴板附件的类型与大小筛选。 */
package com.brycewg.asrkb.clipboard

import com.brycewg.asrkb.store.Prefs
import java.security.MessageDigest
import java.util.Locale

internal enum class ClipboardAttachmentKind(val remoteType: String) {
    IMAGE("Image"),
    FILE("File")
}

internal class ClipboardAttachmentPolicy(private val prefs: Prefs) {
    fun allows(kind: ClipboardAttachmentKind, sizeBytes: Long?): Boolean {
        if (sizeBytes == null || sizeBytes < 0L) return false
        val enabled = when (kind) {
            ClipboardAttachmentKind.IMAGE -> prefs.syncClipboardImagesEnabled
            ClipboardAttachmentKind.FILE -> prefs.syncClipboardFilesEnabled
        }
        return enabled && sizeBytes <= prefs.syncClipboardAttachmentMaxSizeMb * BYTES_PER_MB
    }

    fun hasEnabledType(): Boolean = prefs.syncClipboardImagesEnabled || prefs.syncClipboardFilesEnabled

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}

/** SyncClipboard File/Image Profile 约定的哈希：文件名与大写内容哈希再计算一次 SHA-256。 */
internal fun syncClipboardAttachmentHash(dataName: String, contentHash: String): String {
    val input = "$dataName|${contentHash.uppercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") {
        "%02X".format(Locale.ROOT, it.toInt() and 0xff)
    }
}

/** 跨输入通道保留最近一次本机发布的附件 Profile，用于跳过服务端回环。 */
internal class ClipboardAttachmentOriginStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    fun record(profileHash: String) {
        synchronized(lock) {
            val hashes = readHashes().filterNot {
                it.equals(profileHash, ignoreCase = true)
            }.toMutableList().apply {
                add(profileHash)
                while (size > MAX_RECENT_ORIGINS) removeAt(0)
            }
            prefs.edit().putString(KEY_RECENT_PROFILE_HASHES, hashes.joinToString("\n")).apply()
        }
    }

    fun clear(profileHash: String) {
        synchronized(lock) {
            val hashes = readHashes().filterNot {
                it.equals(profileHash, ignoreCase = true)
            }
            prefs.edit().putString(KEY_RECENT_PROFILE_HASHES, hashes.joinToString("\n")).apply()
        }
    }

    fun isLocal(profileHash: String?): Boolean = !profileHash.isNullOrBlank() &&
        synchronized(lock) {
            readHashes().any { it.equals(profileHash, ignoreCase = true) }
        }

    private fun readHashes(): MutableList<String> = prefs.getString(KEY_RECENT_PROFILE_HASHES, "").orEmpty()
        .lineSequence()
        .filter(String::isNotBlank)
        .toMutableList()

    companion object {
        private const val PREFS_NAME = "clipboard_attachment_origin"
        private const val KEY_RECENT_PROFILE_HASHES = "recent_profile_hashes"
        private const val MAX_RECENT_ORIGINS = 64
        private val lock = Any()
    }
}

/**
 * Attachment Profile is single-valued on the server; serialize local transfers across all actors.
 * ponytail: one process-wide lock is sufficient for the low-frequency attachment path; split by hash if throughput matters.
 */
internal object ClipboardAttachmentTransferGate {
    private val lock = Any()

    fun <T> run(block: () -> T): T = synchronized(lock, block)
}
