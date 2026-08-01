/** Receives a shared file, uploads it to SyncClipboard, then returns to the source app. */
package com.brycewg.asrkb.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.brycewg.asrkb.clipboard.ClipboardFileShareUploadService
import com.brycewg.asrkb.clipboard.singleSharedFileUri

class ClipboardFileShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.singleSharedFileUri() ?: return finish()
        ClipboardFileShareUploadService.start(this, uri)
        finish()
    }
}
