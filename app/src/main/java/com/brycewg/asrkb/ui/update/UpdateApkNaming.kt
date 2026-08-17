package com.brycewg.asrkb.ui.update

/**
 * GitHub Release APK 资产与本地缓存文件名规则。
 *
 * CI 产物命名须与 [releaseDownloadCandidates] 首选项保持一致，见
 * `.github/workflows/release-on-version-bump.yml`。
 *
 * 新产物使用旧客户端已有的 `app-release-{version}-{abi}.apk` 兜底名，
 * 以便未更新的版本在 lexisharp 主名 404 后仍能下到包。
 */
object UpdateApkNaming {
    private const val LOCAL_CACHE_PREFIX = "bibi-keyboard"
    private const val LEGACY_PREFIX = "lexisharp-keyboard"

    /**
     * 按优先级返回 GitHub Release asset 文件名候选。
     * 首选与旧客户端兜底名一致；历史 LexiSharp 资产作为次选。
     */
    fun releaseDownloadCandidates(version: String, abi: String): List<String> {
        val resolvedAbi = if (abi == "armeabi-v7a") "armeabi-v7a" else "arm64-v8a"
        return listOf(
            currentReleaseFileName(version, resolvedAbi),
            legacyReleaseFileName(version, resolvedAbi)
        )
    }

    fun currentReleaseFileName(version: String, abi: String): String =
        "app-release-$version-$abi.apk"

    /** 应用内下载缓存文件名（不含 ABI 后缀）。 */
    fun localCacheFileName(version: String): String = "$LOCAL_CACHE_PREFIX-$version.apk"

    private fun legacyReleaseFileName(version: String, abi: String): String = if (abi == "armeabi-v7a") {
        "$LEGACY_PREFIX-$version-armeabi-v7a-release.apk"
    } else {
        "$LEGACY_PREFIX-$version-release.apk"
    }
}
