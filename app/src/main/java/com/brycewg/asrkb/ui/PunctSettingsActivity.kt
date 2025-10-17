package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.SpeechPreset
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

class PunctSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_punct_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_punct_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val etP1 = findViewById<EditText>(R.id.etPunct1)
    val etP2 = findViewById<EditText>(R.id.etPunct2)
    val etP3 = findViewById<EditText>(R.id.etPunct3)
    val etP4 = findViewById<EditText>(R.id.etPunct4)

    etP1.setText(prefs.punct1)
    etP2.setText(prefs.punct2)
    etP3.setText(prefs.punct3)
    etP4.setText(prefs.punct4)

    fun EditText.bind(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
      })
    }

    etP1.bind { prefs.punct1 = it }
    etP2.bind { prefs.punct2 = it }
    etP3.bind { prefs.punct3 = it }
    etP4.bind { prefs.punct4 = it }

    val spSpeechPresets = findViewById<Spinner>(R.id.spSpeechPresets)
    val tilSpeechPresetName = findViewById<TextInputLayout>(R.id.tilSpeechPresetName)
    val tilSpeechPresetContent = findViewById<TextInputLayout>(R.id.tilSpeechPresetContent)
    val etSpeechPresetName = findViewById<TextInputEditText>(R.id.etSpeechPresetName)
    val etSpeechPresetContent = findViewById<TextInputEditText>(R.id.etSpeechPresetContent)
    val btnSpeechPresetAdd = findViewById<MaterialButton>(R.id.btnSpeechPresetAdd)
    val btnSpeechPresetDelete = findViewById<MaterialButton>(R.id.btnSpeechPresetDelete)

    var suppressSpeechPresetSpinner = false
    var updatingSpeechPresetFields = false

    fun refreshSpeechPresetSection() {
      val presets = prefs.getSpeechPresets()
      val hasAny = presets.isNotEmpty()
      val displayNames = if (hasAny) {
        presets.map { preset ->
          val name = preset.name.trim()
          if (name.isEmpty()) getString(R.string.speech_preset_untitled) else name
        }
      } else {
        listOf(getString(R.string.speech_preset_empty_placeholder))
      }
      val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames)
      adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
      suppressSpeechPresetSpinner = true
      spSpeechPresets.adapter = adapter
      if (hasAny) {
        val activeId = prefs.activeSpeechPresetId
        val idx = presets.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
        spSpeechPresets.setSelection(idx)
      } else {
        spSpeechPresets.setSelection(0)
      }
      suppressSpeechPresetSpinner = false

      val current = if (hasAny) {
        val activeId = prefs.activeSpeechPresetId
        presets.firstOrNull { it.id == activeId } ?: presets.firstOrNull()
      } else {
        null
      }
      if (current != null && prefs.activeSpeechPresetId != current.id) {
        prefs.activeSpeechPresetId = current.id
      }
      updatingSpeechPresetFields = true
      val currentName = current?.name ?: ""
      if (etSpeechPresetName.text?.toString() != currentName) {
        etSpeechPresetName.setText(currentName)
      }
      val currentContent = current?.content ?: ""
      if (etSpeechPresetContent.text?.toString() != currentContent) {
        etSpeechPresetContent.setText(currentContent)
      }
      updatingSpeechPresetFields = false
      tilSpeechPresetName.error = null
      val enable = hasAny
      spSpeechPresets.isEnabled = enable
      tilSpeechPresetName.isEnabled = enable
      tilSpeechPresetContent.isEnabled = enable
      etSpeechPresetName.isEnabled = enable
      etSpeechPresetContent.isEnabled = enable
      btnSpeechPresetDelete.isEnabled = enable
    }

    fun mutateActiveSpeechPreset(refreshSpinner: Boolean = false, block: (SpeechPreset) -> SpeechPreset?) {
      val list = prefs.getSpeechPresets().toMutableList()
      val idx = list.indexOfFirst { it.id == prefs.activeSpeechPresetId }
      if (idx < 0) return
      val current = list[idx]
      val mutated = block(current) ?: run {
        tilSpeechPresetName.error = null
        return
      }
      if (mutated == current) {
        tilSpeechPresetName.error = null
        return
      }
      list[idx] = mutated
      prefs.setSpeechPresets(list)
      if (refreshSpinner) {
        refreshSpeechPresetSection()
      }
    }

    fun TextInputEditText.bindSpeechPreset(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          if (updatingSpeechPresetFields) return
          onChange(s?.toString() ?: "")
        }
      })
    }

    spSpeechPresets.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (suppressSpeechPresetSpinner) return
        val presets = prefs.getSpeechPresets()
        if (presets.isEmpty()) return
        val preset = presets.getOrNull(position) ?: return
        if (prefs.activeSpeechPresetId != preset.id) {
          prefs.activeSpeechPresetId = preset.id
        }
        updatingSpeechPresetFields = true
        if (etSpeechPresetName.text?.toString() != preset.name) {
          etSpeechPresetName.setText(preset.name)
        }
        if (etSpeechPresetContent.text?.toString() != preset.content) {
          etSpeechPresetContent.setText(preset.content)
        }
        updatingSpeechPresetFields = false
        tilSpeechPresetName.error = null
      }

      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    etSpeechPresetName.bindSpeechPreset { value ->
      if (prefs.getSpeechPresets().isEmpty()) return@bindSpeechPreset
      val trimmed = value.trim()
      if (trimmed.isEmpty()) {
        tilSpeechPresetName.error = getString(R.string.error_speech_preset_name_required)
        return@bindSpeechPreset
      }
      tilSpeechPresetName.error = null
      mutateActiveSpeechPreset(refreshSpinner = true) { preset ->
        if (preset.name == trimmed) preset else preset.copy(name = trimmed)
      }
    }

    etSpeechPresetContent.bindSpeechPreset { value ->
      if (prefs.getSpeechPresets().isEmpty()) return@bindSpeechPreset
      mutateActiveSpeechPreset(refreshSpinner = false) { preset ->
        if (preset.content == value) preset else preset.copy(content = value)
      }
    }

    btnSpeechPresetAdd.setOnClickListener {
      val list = prefs.getSpeechPresets().toMutableList()
      val newId = UUID.randomUUID().toString()
      val defaultName = getString(R.string.speech_preset_default_name, list.size + 1)
      list.add(SpeechPreset(newId, defaultName, ""))
      prefs.setSpeechPresets(list)
      prefs.activeSpeechPresetId = newId
      refreshSpeechPresetSection()
      tilSpeechPresetName.error = null
      etSpeechPresetName.post {
        etSpeechPresetName.requestFocus()
        etSpeechPresetName.setSelection(etSpeechPresetName.text?.length ?: 0)
      }
      Toast.makeText(this, getString(R.string.toast_speech_preset_added), Toast.LENGTH_SHORT).show()
    }

    btnSpeechPresetDelete.setOnClickListener {
      val presets = prefs.getSpeechPresets()
      if (presets.isEmpty()) return@setOnClickListener
      val activeId = prefs.activeSpeechPresetId
      val current = presets.firstOrNull { it.id == activeId } ?: presets.first()
      MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_speech_preset_delete_title)
        .setMessage(getString(R.string.dialog_speech_preset_delete_message, current.name.ifBlank { getString(R.string.speech_preset_untitled) }))
        .setPositiveButton(R.string.btn_speech_preset_delete) { _, _ ->
          val list = prefs.getSpeechPresets().toMutableList()
          val idx = list.indexOfFirst { it.id == current.id }
          if (idx >= 0) {
            list.removeAt(idx)
            prefs.setSpeechPresets(list)
            if (list.isNotEmpty()) {
              val nextIdx = idx.coerceAtMost(list.lastIndex)
              prefs.activeSpeechPresetId = list[nextIdx].id
            } else {
              prefs.activeSpeechPresetId = ""
            }
            refreshSpeechPresetSection()
            Toast.makeText(this, getString(R.string.toast_speech_preset_deleted), Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    refreshSpeechPresetSection()
  }
}
