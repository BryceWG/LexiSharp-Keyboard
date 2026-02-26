package com.brycewg.asrkb.ui.permission

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionActivity : ComponentActivity() {
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论结果如何，返回即可；权限不足时上层流程会再次提示
            finish()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequest()
    }

    private fun maybeRequest() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            finish()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
