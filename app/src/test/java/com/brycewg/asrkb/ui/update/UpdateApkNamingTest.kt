package com.brycewg.asrkb.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkNamingTest {
    @Test
    fun releaseDownloadCandidates_arm64_prefersAppReleaseThenLexisharp() {
        val candidates = UpdateApkNaming.releaseDownloadCandidates("4.4.0", "arm64-v8a")

        assertEquals(
            listOf(
                "app-release-4.4.0-arm64-v8a.apk",
                "lexisharp-keyboard-4.4.0-release.apk"
            ),
            candidates
        )
    }

    @Test
    fun releaseDownloadCandidates_armeabiV7a_prefersAppReleaseThenLexisharp() {
        val candidates = UpdateApkNaming.releaseDownloadCandidates("4.4.0", "armeabi-v7a")

        assertEquals(
            listOf(
                "app-release-4.4.0-armeabi-v7a.apk",
                "lexisharp-keyboard-4.4.0-armeabi-v7a-release.apk"
            ),
            candidates
        )
    }

    @Test
    fun releaseDownloadCandidates_unknownAbi_defaultsToArm64Naming() {
        val candidates = UpdateApkNaming.releaseDownloadCandidates("4.4.0", "x86_64")

        assertEquals(
            listOf(
                "app-release-4.4.0-arm64-v8a.apk",
                "lexisharp-keyboard-4.4.0-release.apk"
            ),
            candidates
        )
    }

    @Test
    fun currentReleaseFileName_includesAbiForBothArchitectures() {
        assertEquals(
            "app-release-4.4.0-arm64-v8a.apk",
            UpdateApkNaming.currentReleaseFileName("4.4.0", "arm64-v8a")
        )
        assertEquals(
            "app-release-4.4.0-armeabi-v7a.apk",
            UpdateApkNaming.currentReleaseFileName("4.4.0", "armeabi-v7a")
        )
    }

    @Test
    fun localCacheFileName_usesBibiPrefixWithoutAbiSuffix() {
        assertEquals("bibi-keyboard-4.4.0.apk", UpdateApkNaming.localCacheFileName("4.4.0"))
    }
}
