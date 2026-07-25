package com.brycewg.asrkb.ui.settings.asr

import java.io.File

/**
 * 本地模型下载缓存管理（cacheDir 下的未完成 .zip）。
 *
 * - 失败/取消保留 partial 以便断点续传
 * - 超过 [MAX_AGE_MS] 的过期文件删除
 * - 总占用超过 [MAX_TOTAL_BYTES] 时按最旧优先删到阈值以下
 * - [protectFileNames] 中的正在使用文件永不删除
 */
internal object ModelDownloadCache {
    /** 未完成下载最多保留 7 天 */
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** 未完成下载缓存总上限约 2GB */
    const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024

    data class PruneResult(
        val deletedCount: Int,
        val keptCount: Int,
        val freedBytes: Long
    )

    fun touch(file: File) {
        try {
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            // best-effort
        }
    }

    fun prune(
        cacheDir: File,
        protectFileNames: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_AGE_MS,
        maxTotalBytes: Long = MAX_TOTAL_BYTES
    ): PruneResult {
        if (!cacheDir.isDirectory) {
            return PruneResult(deletedCount = 0, keptCount = 0, freedBytes = 0L)
        }
        val candidates = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        var deletedCount = 0
        var freedBytes = 0L
        val kept = ArrayList<File>()

        for (file in candidates) {
            if (file.name in protectFileNames) {
                kept.add(file)
                continue
            }
            val age = nowMs - file.lastModified()
            if (age > maxAgeMs) {
                val size = file.length()
                if (delete(file)) {
                    deletedCount++
                    freedBytes += size
                }
            } else {
                kept.add(file)
            }
        }

        var total = kept.sumOf { it.length() }
        if (total > maxTotalBytes) {
            // 已按 lastModified 升序；从最旧开始删（跳过 protect）
            val mutable = kept.filterNotTo(ArrayList()) { it.name in protectFileNames }
            var i = 0
            while (total > maxTotalBytes && i < mutable.size) {
                val file = mutable[i++]
                val size = file.length()
                if (delete(file)) {
                    deletedCount++
                    freedBytes += size
                    total -= size
                    kept.remove(file)
                }
            }
        }

        return PruneResult(
            deletedCount = deletedCount,
            keptCount = kept.size,
            freedBytes = freedBytes
        )
    }

    private fun delete(file: File): Boolean {
        val deleted = file.delete()
        if (deleted) {
            File(file.path + ".resume").delete()
        }
        return deleted
    }
}
