package com.brycewg.asrkb.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import android.text.Editable
import android.text.TextWatcher

class FloatingSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_floating_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_floating_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val switchFloating = findViewById<MaterialSwitch>(R.id.switchFloatingSwitcher)
    val switchFloatingOnlyWhenImeVisible = findViewById<MaterialSwitch>(R.id.switchFloatingOnlyWhenImeVisible)
    val switchImeVisibilityCompat = findViewById<MaterialSwitch>(R.id.switchImeVisibilityCompat)
    val etImeVisibilityCompatPkgs = findViewById<TextInputEditText>(R.id.etImeVisibilityCompatPkgs)
    val sliderFloatingAlpha = findViewById<Slider>(R.id.sliderFloatingAlpha)
    val sliderFloatingSize = findViewById<Slider>(R.id.sliderFloatingSize)
    val switchFloatingAsr = findViewById<MaterialSwitch>(R.id.switchFloatingAsr)
    val switchFloatingWriteCompat = findViewById<MaterialSwitch>(R.id.switchFloatingWriteCompat)
    val etFloatingWriteCompatPkgs = findViewById<TextInputEditText>(R.id.etFloatingWriteCompatPkgs)

    // 初始状态
    switchFloating.isChecked = prefs.floatingSwitcherEnabled
    switchFloatingOnlyWhenImeVisible.isChecked = prefs.floatingSwitcherOnlyWhenImeVisible
    switchImeVisibilityCompat.isChecked = prefs.floatingImeVisibilityCompatEnabled
    etImeVisibilityCompatPkgs.setText(prefs.floatingImeVisibilityCompatPackages)
    sliderFloatingAlpha.value = (prefs.floatingSwitcherAlpha * 100f).coerceIn(30f, 100f)
    sliderFloatingSize.value = prefs.floatingBallSizeDp.toFloat()
    switchFloatingAsr.isChecked = prefs.floatingAsrEnabled
    switchFloatingWriteCompat.isChecked = prefs.floatingWriteTextCompatEnabled
    etFloatingWriteCompatPkgs.setText(prefs.floatingWriteCompatPackages)

    // 若两者同开，兜底优先语音识别
    if (switchFloating.isChecked && switchFloatingAsr.isChecked) {
      switchFloating.isChecked = false
      prefs.floatingSwitcherEnabled = false
    }

    var suppressSwitchChange = false

    // 输入法悬浮球
    switchFloating.setOnCheckedChangeListener { _, isChecked ->
      if (suppressSwitchChange) return@setOnCheckedChangeListener
      prefs.floatingSwitcherEnabled = isChecked
      if (isChecked) {
        if (!Settings.canDrawOverlays(this)) {
          Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
          suppressSwitchChange = true
          switchFloating.isChecked = false
          prefs.floatingSwitcherEnabled = false
          suppressSwitchChange = false
          try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) } catch (_: Throwable) {}
          return@setOnCheckedChangeListener
        }
        // 互斥：关闭语音识别悬浮球
        if (switchFloatingAsr.isChecked) {
          suppressSwitchChange = true
          switchFloatingAsr.isChecked = false
          prefs.floatingAsrEnabled = false
          suppressSwitchChange = false
        }
        // 显示输入法切换悬浮球
        try {
          val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_SHOW }
          startService(intent)
        } catch (_: Throwable) { }
      } else {
        // 隐藏输入法切换悬浮球
        try {
          val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_HIDE }
          startService(intent)
        } catch (_: Throwable) { }
      }
    }

    // 仅在键盘显示时显示悬浮球
    switchFloatingOnlyWhenImeVisible.setOnCheckedChangeListener { _, isChecked ->
      prefs.floatingSwitcherOnlyWhenImeVisible = isChecked
      if (isChecked && !isAccessibilityServiceEnabled()) {
        Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
      }
    }

    // 键盘可见性兼容模式
    switchImeVisibilityCompat.setOnCheckedChangeListener { _, isChecked ->
      prefs.floatingImeVisibilityCompatEnabled = isChecked
      if (prefs.floatingSwitcherOnlyWhenImeVisible && isChecked && !isAccessibilityServiceEnabled()) {
        Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
      }
    }

    // 兼容目标包名列表（每行一个）
    etImeVisibilityCompatPkgs.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        prefs.floatingImeVisibilityCompatPackages = s?.toString() ?: ""
      }
    })

    // 悬浮窗透明度
    sliderFloatingAlpha.addOnChangeListener { _, value, fromUser ->
      if (fromUser) {
        prefs.floatingSwitcherAlpha = (value / 100f).coerceIn(0.2f, 1.0f)
        if (prefs.floatingSwitcherEnabled) {
          try {
            val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_SHOW }
            startService(intent)
          } catch (_: Throwable) { }
        }
      }
    }

    // 悬浮球大小
    sliderFloatingSize.addOnChangeListener { _, value, fromUser ->
      if (fromUser) {
        prefs.floatingBallSizeDp = value.toInt().coerceIn(28, 96)
        if (prefs.floatingSwitcherEnabled) {
          try {
            val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_SHOW }
            startService(intent)
          } catch (_: Throwable) { }
        }
      }
    }

    // 语音识别悬浮球
    switchFloatingAsr.setOnCheckedChangeListener { _, isChecked ->
      if (suppressSwitchChange) return@setOnCheckedChangeListener
      prefs.floatingAsrEnabled = isChecked
      if (isChecked) {
        if (!Settings.canDrawOverlays(this)) {
          Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
          suppressSwitchChange = true
          switchFloatingAsr.isChecked = false
          prefs.floatingAsrEnabled = false
          suppressSwitchChange = false
          try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) } catch (_: Throwable) {}
          return@setOnCheckedChangeListener
        }
        if (!isAccessibilityServiceEnabled()) {
          Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
          try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
        }
        // 互斥：关闭输入法悬浮球
        if (switchFloating.isChecked) {
          suppressSwitchChange = true
          switchFloating.isChecked = false
          prefs.floatingSwitcherEnabled = false
          suppressSwitchChange = false
        }
        try {
          val intent = Intent(this, FloatingAsrService::class.java).apply { action = FloatingAsrService.ACTION_SHOW }
          startService(intent)
        } catch (_: Throwable) { }
      } else {
        try {
          val intent = Intent(this, FloatingAsrService::class.java).apply { action = FloatingAsrService.ACTION_HIDE }
          startService(intent)
          stopService(Intent(this, FloatingAsrService::class.java))
        } catch (_: Throwable) { }
      }
    }

    // 悬浮球写入文字兼容性模式（默认开）
    switchFloatingWriteCompat.setOnCheckedChangeListener { _, isChecked ->
      prefs.floatingWriteTextCompatEnabled = isChecked
    }

    // 兼容目标包名（每行一个）
    etFloatingWriteCompatPkgs.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        prefs.floatingWriteCompatPackages = s?.toString() ?: ""
      }
    })
  }

  private fun isAccessibilityServiceEnabled(): Boolean {
    val expectedComponentName = "$packageName/com.brycewg.asrkb.ui.AsrAccessibilityService"
    val enabledServicesSetting = try {
      Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    } catch (_: Throwable) {
      return false
    }
    return enabledServicesSetting?.contains(expectedComponentName) == true
  }
}
