package com.brycewg.asrkb.ui

import android.content.Context
import com.brycewg.asrkb.R

object DownloadSourceConfig {
    private data class Mirror(val labelRes: Int, val prefix: String)

    private val mirrors = listOf(
        Mirror(R.string.download_source_mirror_1, "https://ghproxy.net/"),
        Mirror(R.string.download_source_mirror_2, "https://hub.gitmirror.com/"),
        Mirror(R.string.download_source_mirror_3, "https://fastgit.cc/"),
    )

    fun buildOptions(
        context: Context,
        officialUrl: String,
        officialLabelRes: Int = R.string.download_source_github_official,
    ): List<DownloadSourceDialog.Option> {
        val options = ArrayList<DownloadSourceDialog.Option>(mirrors.size + 1)
        options.add(
            DownloadSourceDialog.Option(
                context.getString(officialLabelRes),
                officialUrl,
            ),
        )
        mirrors.forEach { mirror ->
            options.add(
                DownloadSourceDialog.Option(
                    context.getString(mirror.labelRes),
                    applyMirrorPrefix(officialUrl, mirror.prefix),
                ),
            )
        }
        return options
    }

    private fun applyMirrorPrefix(originalUrl: String, mirrorPrefix: String): String {
        return if (originalUrl.startsWith("https://github.com/")) {
            mirrorPrefix + originalUrl
        } else {
            originalUrl
        }
    }
}
