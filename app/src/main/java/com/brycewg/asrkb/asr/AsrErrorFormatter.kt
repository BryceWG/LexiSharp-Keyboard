package com.brycewg.asrkb.asr

internal fun formatHttpDetail(message: String?, extra: String? = null): String {
    val primary = message?.trim().orEmpty()
    val extraPart = extra?.trim().orEmpty()
    return buildString {
        if (primary.isNotEmpty()) append(": ").append(primary)
        if (extraPart.isNotEmpty()) append(" — ").append(extraPart)
    }
}
