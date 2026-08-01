/** Extracts one file URI from Android's standard share intent. */
package com.brycewg.asrkb.clipboard

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal fun Intent.singleSharedFileUri(): Uri? {
    if (action != Intent.ACTION_SEND) return null
    val streamUri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    val clipUris = clipData?.let { data ->
        (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri }.distinct()
    }.orEmpty()
    val uri = when {
        streamUri != null -> streamUri
        clipUris.size == 1 -> clipUris.single()
        else -> null
    }
    return uri?.takeIf {
        it.scheme == ContentResolver.SCHEME_CONTENT && clipUris.all { clipUri -> clipUri == it }
    }
}
