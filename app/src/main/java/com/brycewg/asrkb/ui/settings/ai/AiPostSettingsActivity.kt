package com.brycewg.asrkb.ui.settings.ai

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity for configuring AI post-processing settings
 * Manages LLM providers and prompt presets with reactive UI updates
 */
class AiPostSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: AiPostSettingsViewModel

    // LLM Profile Views
    private lateinit var tvLlmProfiles: TextView
    private lateinit var etLlmProfileName: EditText
    private lateinit var etLlmEndpoint: EditText
    private lateinit var etLlmApiKey: EditText
    private lateinit var etLlmModel: EditText
    private lateinit var etLlmTemperature: EditText
    private lateinit var btnLlmAddProfile: Button
    private lateinit var btnLlmDeleteProfile: Button
    private lateinit var btnLlmTestCall: Button

    // Prompt Preset Views
    private lateinit var tvPromptPresets: TextView
    private lateinit var etLlmPromptTitle: EditText
    private lateinit var etLlmPrompt: EditText
    private lateinit var btnAddPromptPreset: Button
    private lateinit var btnDeletePromptPreset: Button

    // Flag to prevent recursive updates during programmatic text changes
    private var isUpdatingProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_post_settings)

        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[AiPostSettingsViewModel::class.java]

        initViews()
        setupLlmProfileSection()
        setupPromptPresetSection()
        loadInitialData()
    }

    // ======== Initialization Methods ========

    /**
     * Initializes all view references
     */
    private fun initViews() {
        // Toolbar
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setTitle(R.string.title_ai_settings)
            setNavigationOnClickListener { finish() }
        }

        // LLM Profile Views
        tvLlmProfiles = findViewById(R.id.tvLlmProfilesValue)
        etLlmProfileName = findViewById(R.id.etLlmProfileName)
        etLlmEndpoint = findViewById(R.id.etLlmEndpoint)
        etLlmApiKey = findViewById(R.id.etLlmApiKey)
        etLlmModel = findViewById(R.id.etLlmModel)
        etLlmTemperature = findViewById(R.id.etLlmTemperature)
        btnLlmAddProfile = findViewById(R.id.btnLlmAddProfile)
        btnLlmDeleteProfile = findViewById(R.id.btnLlmDeleteProfile)
        btnLlmTestCall = findViewById(R.id.btnLlmTestCall)

        // Prompt Preset Views
        tvPromptPresets = findViewById(R.id.tvPromptPresetsValue)
        etLlmPromptTitle = findViewById(R.id.etLlmPromptTitle)
        etLlmPrompt = findViewById(R.id.etLlmPrompt)
        btnAddPromptPreset = findViewById(R.id.btnAddPromptPreset)
        btnDeletePromptPreset = findViewById(R.id.btnDeletePromptPreset)
    }

    /**
     * Sets up LLM profile section with listeners and observers
     */
    private fun setupLlmProfileSection() {
        // Click listener for profile selector
        tvLlmProfiles.setOnClickListener {
            showLlmProfileSelectionDialog()
        }

        // Text change listeners with ViewModel updates
        etLlmProfileName.addTextChangeListener { text ->
            val name = text.ifBlank { getString(R.string.untitled_profile) }
            viewModel.updateActiveLlmProvider(prefs) { it.copy(name = name) }
        }

        etLlmEndpoint.addTextChangeListener { text ->
            val endpoint = text.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT }
            viewModel.updateActiveLlmProvider(prefs) { it.copy(endpoint = endpoint) }
        }

        etLlmApiKey.addTextChangeListener { text ->
            viewModel.updateActiveLlmProvider(prefs) { it.copy(apiKey = text) }
        }

        etLlmModel.addTextChangeListener { text ->
            val model = text.ifBlank { Prefs.DEFAULT_LLM_MODEL }
            viewModel.updateActiveLlmProvider(prefs) { it.copy(model = model) }
        }

        etLlmTemperature.addTextChangeListener { text ->
            val temperature = text.toFloatOrNull()
                ?.coerceIn(0f, 2f)
                ?: Prefs.DEFAULT_LLM_TEMPERATURE
            viewModel.updateActiveLlmProvider(prefs) { it.copy(temperature = temperature) }
        }

        // 测试 LLM 调用
        btnLlmTestCall.setOnClickListener {
            handleTestLlmCall()
        }

        // Button listeners
        btnLlmAddProfile.setOnClickListener {
            handleAddLlmProfile()
        }

        btnLlmDeleteProfile.setOnClickListener {
            handleDeleteLlmProfile()
        }

        // Observe ViewModel state
        observeLlmProfileState()
    }

    /**
     * Sets up prompt preset section with listeners and observers
     */
    private fun setupPromptPresetSection() {
        // Click listener for preset selector
        tvPromptPresets.setOnClickListener {
            showPromptPresetSelectionDialog()
        }

        // Text change listeners with ViewModel updates
        etLlmPromptTitle.addTextChangeListener { text ->
            val title = text.ifBlank { getString(R.string.untitled_preset) }
            viewModel.updateActivePromptPreset(prefs) { it.copy(title = title) }
        }

        etLlmPrompt.addTextChangeListener { text ->
            viewModel.updateActivePromptPreset(prefs) { it.copy(content = text) }
        }

        // Button listeners
        btnAddPromptPreset.setOnClickListener {
            handleAddPromptPreset()
        }

        btnDeletePromptPreset.setOnClickListener {
            handleDeletePromptPreset()
        }

        // Observe ViewModel state
        observePromptPresetState()
    }

    /**
     * Loads initial data from preferences into ViewModel
     */
    private fun loadInitialData() {
        viewModel.loadData(prefs)
    }

    // ======== Observer Methods ========

    /**
     * Observes LLM profile state changes and updates UI
     */
    private fun observeLlmProfileState() {
        lifecycleScope.launch {
            viewModel.activeLlmProvider.collectLatest { provider ->
                updateLlmProfileUI(provider)
            }
        }
    }

    /**
     * Observes prompt preset state changes and updates UI
     */
    private fun observePromptPresetState() {
        lifecycleScope.launch {
            viewModel.activePromptPreset.collectLatest { preset ->
                updatePromptPresetUI(preset)
            }
        }
    }

    // ======== UI Update Methods ========

    /**
     * Updates LLM profile UI with the given provider
     */
    private fun updateLlmProfileUI(provider: Prefs.LlmProvider?) {
        isUpdatingProgrammatically = true

        val name = provider?.name ?: getString(R.string.untitled_profile)
        tvLlmProfiles.text = name

        etLlmProfileName.setTextIfDifferent(provider?.name ?: "")
        etLlmEndpoint.setTextIfDifferent(provider?.endpoint ?: prefs.llmEndpoint)
        etLlmApiKey.setTextIfDifferent(provider?.apiKey ?: prefs.llmApiKey)
        etLlmModel.setTextIfDifferent(provider?.model ?: prefs.llmModel)
        etLlmTemperature.setTextIfDifferent(
            (provider?.temperature ?: prefs.llmTemperature).toString()
        )

        isUpdatingProgrammatically = false
    }

    /**
     * Updates prompt preset UI with the given preset
     */
    private fun updatePromptPresetUI(preset: PromptPreset?) {
        isUpdatingProgrammatically = true

        tvPromptPresets.text = preset?.title ?: getString(R.string.untitled_preset)
        etLlmPromptTitle.setTextIfDifferent(preset?.title ?: "")
        etLlmPrompt.setTextIfDifferent(preset?.content ?: Prefs.DEFAULT_LLM_PROMPT)

        isUpdatingProgrammatically = false
    }

    // ======== Dialog Methods ========

    /**
     * Shows dialog to select LLM profile
     */
    private fun showLlmProfileSelectionDialog() {
        val profiles = viewModel.llmProfiles.value
        if (profiles.isEmpty()) return

        val titles = profiles.map { it.name }.toTypedArray()
        val selectedIndex = viewModel.getActiveLlmProviderIndex()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.label_llm_choose_profile)
            .setSingleChoiceItems(titles, selectedIndex) { dialog, which ->
                val selected = profiles.getOrNull(which)
                if (selected != null) {
                    viewModel.selectLlmProvider(prefs, selected.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Shows dialog to select prompt preset
     */
    private fun showPromptPresetSelectionDialog() {
        val presets = viewModel.promptPresets.value
        if (presets.isEmpty()) return

        val titles = presets.map { it.title }.toTypedArray()
        val selectedIndex = viewModel.getActivePromptPresetIndex()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.label_llm_prompt_presets)
            .setSingleChoiceItems(titles, selectedIndex) { dialog, which ->
                val selected = presets.getOrNull(which)
                if (selected != null) {
                    viewModel.selectPromptPreset(prefs, selected.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ======== Action Handlers ========

    /**
     * Handles adding a new LLM profile
     */
    private fun handleAddLlmProfile() {
        val defaultName = getString(R.string.untitled_profile)
        if (viewModel.addLlmProvider(prefs, defaultName)) {
            Toast.makeText(
                this,
                getString(R.string.toast_llm_profile_added),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Handles deleting the active LLM profile
     */
    private fun handleDeleteLlmProfile() {
        if (viewModel.deleteActiveLlmProvider(prefs)) {
            Toast.makeText(
                this,
                getString(R.string.toast_llm_profile_deleted),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 触发“测试 LLM 调用”并反馈结果
     */
    private fun handleTestLlmCall() {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.llm_test_running)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val processor = LlmPostProcessor()
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    processor.testConnectivity(prefs)
                }
                progressDialog.dismiss()

                if (result.ok) {
                    val preview = result.contentPreview ?: ""
                    MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                        .setTitle(R.string.llm_test_success_title)
                        .setMessage(getString(R.string.llm_test_success_preview, preview))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val msg = when {
                        result.message?.contains("Missing endpoint or model", ignoreCase = true) == true ->
                            getString(R.string.llm_test_missing_params)
                        result.httpCode != null ->
                            "HTTP ${result.httpCode}: ${result.message ?: ""}"
                        else -> result.message ?: getString(R.string.llm_test_failed_generic)
                    }
                    MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                        .setTitle(R.string.llm_test_failed_title)
                        .setMessage(getString(R.string.llm_test_failed_reason, msg))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                MaterialAlertDialogBuilder(this@AiPostSettingsActivity)
                    .setTitle(R.string.llm_test_failed_title)
                    .setMessage(getString(R.string.llm_test_failed_reason, e.message ?: "unknown"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * Handles adding a new prompt preset
     */
    private fun handleAddPromptPreset() {
        val defaultTitle = getString(R.string.untitled_preset)
        val defaultContent = Prefs.DEFAULT_LLM_PROMPT
        if (viewModel.addPromptPreset(prefs, defaultTitle, defaultContent)) {
            Toast.makeText(
                this,
                getString(R.string.toast_preset_added),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Handles deleting the active prompt preset
     */
    private fun handleDeletePromptPreset() {
        if (viewModel.deleteActivePromptPreset(prefs)) {
            Toast.makeText(
                this,
                getString(R.string.toast_preset_deleted),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ======== Extension Functions ========

    /**
     * Extension function to add TextWatcher that respects programmatic update flag
     */
    private fun EditText.addTextChangeListener(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingProgrammatically) return
                onChange(s?.toString() ?: "")
            }
        })
    }

    /**
     * Extension function to set text only if different from current value
     * Prevents unnecessary cursor jumps and listener triggers
     */
    private fun EditText.setTextIfDifferent(newText: String) {
        val currentText = this.text?.toString() ?: ""
        if (currentText != newText) {
            setText(newText)
        }
    }
}
