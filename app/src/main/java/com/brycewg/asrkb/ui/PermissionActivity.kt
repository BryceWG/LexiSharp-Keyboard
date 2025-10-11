package com.brycewg.asrkb.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionActivity : ComponentActivity() {
  private val requestAudioPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) {
      // 无论授予与否，都结束当前页
      finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    maybeRequest()
  }

  private fun maybeRequest() {
    val granted =
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) {
      finish()
    } else {
      requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
  }
}
