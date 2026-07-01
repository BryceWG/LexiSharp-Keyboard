/**
 * 设置首页三 Tab Compose 页面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import com.brycewg.asrkb.ui.settings.compose.model.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsHomeScreen(
    selectedTab: Int,
    uiMode: BibiUiMode,
    themeMode: String,
    hasUpdateAvailable: Boolean,
    onSelectTab: (Int) -> Unit,
    onPushRoute: (BibiSettingsRoute) -> Unit,
    onSetUiMode: (BibiUiMode) -> Unit,
    onSetThemeMode: (String) -> Unit,
    actions: SettingsActionController
) {
    val tabs = remember { settingsHomeTabs() }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(appContext) { Prefs(appContext) }
    var homeSnapshot by remember(appContext) {
        mutableStateOf(SettingsHomeSnapshot.placeholder(appContext))
    }
    var hasRecentApiErrors by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = selectedTab,
        pageCount = { tabs.size }
    )
    val homePagerState = rememberSettingsHomePagerState(pagerState)

    LaunchedEffect(selectedTab) {
        if (homePagerState.selectedPage != selectedTab) {
            homePagerState.animateToPage(selectedTab)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        homePagerState.syncPage()
        if (selectedTab != homePagerState.selectedPage) {
            onSelectTab(homePagerState.selectedPage)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(refreshToken) {
        val loaded = withContext(Dispatchers.IO) {
            SettingsHomeLoadedState(
                snapshot = SettingsHomeSnapshot.fromPrefs(appContext, prefs),
                hasRecentApiErrors = hasRecentApiLogErrors()
            )
        }
        if (homeSnapshot != loaded.snapshot) {
            homeSnapshot = loaded.snapshot
        }
        if (hasRecentApiErrors != loaded.hasRecentApiErrors) {
            hasRecentApiErrors = loaded.hasRecentApiErrors
        }
    }

    val inputPageSections = remember(homeSnapshot, actions, onPushRoute) {
        inputSections(homeSnapshot, actions, onPushRoute)
    }
    val apiErrorsSummary = if (hasRecentApiErrors) {
        stringResource(R.string.home_summary_api_log_errors)
    } else {
        null
    }
    val smartPageSections = remember(homeSnapshot, apiErrorsSummary, actions, onPushRoute) {
        smartSections(
            snapshot = homeSnapshot,
            apiErrorsSummary = apiErrorsSummary,
            actions = actions,
            onPushRoute = onPushRoute
        )
    }
    val updateAvailableSummary = if (homeSnapshot.autoUpdateCheckEnabled && hasUpdateAvailable) {
        stringResource(R.string.home_summary_update_available)
    } else {
        null
    }
    val miuixUiModeLabel = stringResource(R.string.settings_ui_mode_miuix)
    val materialUiModeLabel = stringResource(R.string.settings_ui_mode_material)
    val uiModeOptions = remember(miuixUiModeLabel, materialUiModeLabel) {
        listOf(
            DropdownOption(BibiUiMode.Miuix.id, miuixUiModeLabel),
            DropdownOption(BibiUiMode.Material.id, materialUiModeLabel)
        )
    }
    val systemThemeLabel = stringResource(R.string.settings_theme_mode_system)
    val lightThemeLabel = stringResource(R.string.settings_theme_mode_light)
    val darkThemeLabel = stringResource(R.string.settings_theme_mode_dark)
    val themeModeOptions = remember(systemThemeLabel, lightThemeLabel, darkThemeLabel) {
        listOf(
            DropdownOption("system", systemThemeLabel),
            DropdownOption("light", lightThemeLabel),
            DropdownOption("dark", darkThemeLabel)
        )
    }
    val updatesEnabled = actions.updatesEnabled
    val systemPageSections = remember(
        uiMode,
        themeMode,
        uiModeOptions,
        themeModeOptions,
        updateAvailableSummary,
        updatesEnabled,
        actions,
        onSetUiMode,
        onSetThemeMode,
        onPushRoute
    ) {
        systemSections(
            uiMode = uiMode,
            themeMode = themeMode,
            uiModeOptions = uiModeOptions,
            themeModeOptions = themeModeOptions,
            updateAvailableSummary = updateAvailableSummary,
            updatesEnabled = updatesEnabled,
            onSetUiMode = onSetUiMode,
            onSetThemeMode = onSetThemeMode,
            actions = actions,
            onPushRoute = onPushRoute
        )
    }

    SettingsHomeScaffold(
        uiMode = uiMode,
        tabs = tabs,
        selectedTab = homePagerState.selectedPage,
        onSelectTab = { page ->
            homePagerState.animateToPage(page)
            onSelectTab(page)
        }
    ) { innerPadding, scrollModifier ->
        val contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding)
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> SettingsSectionList(
                    sections = inputPageSections,
                    uiMode = uiMode,
                    modifier = scrollModifier,
                    contentPadding = contentPadding
                )

                1 -> SettingsSectionList(
                    sections = smartPageSections,
                    uiMode = uiMode,
                    modifier = scrollModifier,
                    contentPadding = contentPadding
                )

                else -> SettingsSectionList(
                    sections = systemPageSections,
                    uiMode = uiMode,
                    modifier = scrollModifier,
                    contentPadding = contentPadding
                )
            }
        }
    }
}

private fun inputSections(
    snapshot: SettingsHomeSnapshot,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "input_quick",
        entries = listOf(
            SettingsEntry.Action(
                id = "one_click_setup",
                titleRes = R.string.btn_one_click_setup,
                summary = snapshot.oneClickSetupSummary,
                icon = Icons.Rounded.RocketLaunch,
                onClick = actions::startOneClickSetup
            ),
            SettingsEntry.Action(
                id = "settings_search",
                titleRes = R.string.btn_settings_search,
                icon = Icons.Rounded.Search,
                onClick = {
                    onPushRoute(BibiSettingsRoute.Search)
                }
            ),
            SettingsEntry.Action(
                id = "test_input",
                titleRes = R.string.btn_test_input,
                icon = Icons.Rounded.TextFields,
                onClick = actions::showTestInput
            ),
            SettingsEntry.Action(
                id = "recording_test",
                titleRes = R.string.title_recording_test,
                icon = Icons.Rounded.Mic,
                onClick = {
                    onPushRoute(BibiSettingsRoute.RecordingTest)
                }
            ),
            SettingsEntry.Action(
                id = "input_settings",
                titleRes = R.string.title_input_settings,
                summary = snapshot.inputControlSummary,
                icon = Icons.Rounded.Keyboard,
                onClick = { onPushRoute(BibiSettingsRoute.Input) }
            ),
            SettingsEntry.Action(
                id = "floating_settings",
                titleRes = R.string.title_floating_settings,
                summary = snapshot.floatingSummary,
                icon = Icons.Rounded.TouchApp,
                onClick = { onPushRoute(BibiSettingsRoute.Floating) }
            )
        )
    )
)

private fun smartSections(
    snapshot: SettingsHomeSnapshot,
    apiErrorsSummary: String?,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "smart_main",
        entries = listOf(
            SettingsEntry.Action(
                id = "asr_settings",
                titleRes = R.string.title_asr_settings,
                summary = snapshot.asrSummary,
                icon = Icons.Rounded.Mic,
                onClick = { onPushRoute(BibiSettingsRoute.Asr) }
            ),
            SettingsEntry.Action(
                id = "ai_settings",
                titleRes = R.string.title_ai_settings,
                summary = snapshot.aiSummary,
                icon = Icons.Rounded.AutoAwesome,
                onClick = { onPushRoute(BibiSettingsRoute.Ai) }
            ),
            SettingsEntry.Action(
                id = "asr_history",
                titleRes = R.string.btn_open_asr_history,
                summary = apiErrorsSummary,
                icon = Icons.Rounded.History,
                onClick = {
                    onPushRoute(BibiSettingsRoute.History)
                }
            )
        )
    )
)

private fun systemSections(
    uiMode: BibiUiMode,
    themeMode: String,
    uiModeOptions: List<DropdownOption>,
    themeModeOptions: List<DropdownOption>,
    updateAvailableSummary: String?,
    updatesEnabled: Boolean,
    onSetUiMode: (BibiUiMode) -> Unit,
    onSetThemeMode: (String) -> Unit,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "system_style",
        entries = listOf(
            SettingsEntry.Dropdown(
                id = "settings_ui_mode",
                titleRes = R.string.settings_ui_mode,
                summaryRes = R.string.settings_ui_mode_summary,
                icon = Icons.Rounded.Dashboard,
                options = uiModeOptions,
                selectedOptionId = uiMode.id,
                onSelectedOptionChange = {
                    onSetUiMode(BibiUiMode.fromId(it))
                }
            ),
            SettingsEntry.Dropdown(
                id = "settings_theme_mode",
                titleRes = R.string.settings_theme_mode,
                icon = Icons.Rounded.Palette,
                options = themeModeOptions,
                selectedOptionId = themeMode,
                onSelectedOptionChange = {
                    onSetThemeMode(it)
                }
            )
        )
    ),
    SettingsSection(
        id = "system_more",
        titleRes = R.string.section_more,
        entries = listOf(
            SettingsEntry.Action(
                id = "backup_settings",
                titleRes = R.string.btn_open_backup_settings,
                icon = Icons.Rounded.Backup,
                onClick = { onPushRoute(BibiSettingsRoute.Backup) }
            ),
            SettingsEntry.Action(
                id = "other_settings",
                titleRes = R.string.title_other_settings,
                icon = Icons.Rounded.MoreHoriz,
                onClick = { onPushRoute(BibiSettingsRoute.Other) }
            ),
            SettingsEntry.Action(
                id = "check_update",
                titleRes = R.string.btn_check_update,
                icon = Icons.Rounded.SystemUpdate,
                enabled = updatesEnabled,
                onClick = actions::checkForUpdates
            ),
            SettingsEntry.Action(
                id = "guide",
                titleRes = R.string.btn_show_guide,
                icon = Icons.AutoMirrored.Rounded.Help,
                onClick = actions::openOnboardingGuide
            ),
            SettingsEntry.Action(
                id = "about",
                titleRes = R.string.btn_about,
                summary = updateAvailableSummary,
                icon = Icons.Rounded.Info,
                onClick = { onPushRoute(BibiSettingsRoute.About) }
            )
        )
    )
)

private data class SettingsHomeSnapshot(
    val oneClickSetupSummary: String,
    val inputControlSummary: String,
    val floatingSummary: String,
    val asrSummary: String,
    val aiSummary: String,
    val autoUpdateCheckEnabled: Boolean
) {
    companion object {
        fun placeholder(context: Context): SettingsHomeSnapshot = SettingsHomeSnapshot(
            oneClickSetupSummary = "",
            inputControlSummary = "",
            floatingSummary = context.getString(R.string.home_summary_more_input_disabled),
            asrSummary = "",
            aiSummary = "",
            autoUpdateCheckEnabled = false
        )

        fun fromPrefs(context: Context, prefs: Prefs): SettingsHomeSnapshot {
            val floatingEnabled = prefs.floatingAsrEnabled
            val volumeKeyEnabled = prefs.volumeKeyRecordingEnabled
            val accessibilityMissing = (floatingEnabled || volumeKeyEnabled) && !isAccessibilityServiceEnabled(context)
            return SettingsHomeSnapshot(
                oneClickSetupSummary = oneClickSetupSummary(context, prefs),
                inputControlSummary = inputControlSummary(context, prefs),
                floatingSummary = moreInputSummary(
                    context = context,
                    floatingEnabled = floatingEnabled,
                    volumeKeyEnabled = volumeKeyEnabled,
                    accessibilityMissing = accessibilityMissing
                ),
                asrSummary = asrSummary(context, prefs),
                aiSummary = aiSummary(context, prefs),
                autoUpdateCheckEnabled = prefs.autoUpdateCheckEnabled
            )
        }
    }
}

private data class SettingsHomeLoadedState(
    val snapshot: SettingsHomeSnapshot,
    val hasRecentApiErrors: Boolean
)

private fun enabledSummary(context: Context, enabled: Boolean): String = context.getString(if (enabled) R.string.home_summary_enabled else R.string.home_summary_disabled)

private fun moreInputSummary(
    context: Context,
    floatingEnabled: Boolean,
    volumeKeyEnabled: Boolean,
    accessibilityMissing: Boolean
): String = context.getString(
    when {
        accessibilityMissing -> R.string.home_summary_more_input_accessibility_missing
        floatingEnabled && volumeKeyEnabled -> R.string.home_summary_more_input_both_enabled
        volumeKeyEnabled -> R.string.home_summary_more_input_volume_key_enabled
        floatingEnabled -> R.string.home_summary_more_input_floating_enabled
        else -> R.string.home_summary_more_input_disabled
    }
)

private fun inputControlSummary(context: Context, prefs: Prefs): String = context.getString(
    if (prefs.micTapToggleEnabled) {
        R.string.home_summary_input_tap_control
    } else {
        R.string.home_summary_input_hold_control
    }
)

private fun asrSummary(context: Context, prefs: Prefs): String = context.getString(
    R.string.home_summary_asr_format,
    enabledSummary(context, prefs.recordingAutoStopMode != Prefs.RecordingAutoStopMode.MANUAL),
    AsrVendorUi.name(context, prefs.asrVendor)
)

private fun aiSummary(context: Context, prefs: Prefs): String {
    val vendor = prefs.llmVendor
    val vendorName = if (vendor == com.brycewg.asrkb.asr.LlmVendor.CUSTOM) {
        prefs.getActiveLlmProvider()?.name?.takeIf { it.isNotBlank() }
            ?: context.getString(vendor.displayNameResId)
    } else {
        context.getString(vendor.displayNameResId)
    }
    val promptName = activePromptPresetTitle(prefs)
    return context.getString(
        R.string.home_summary_ai_format,
        enabledSummary(context, prefs.postProcessEnabled),
        vendorName,
        promptName
    )
}

private fun activePromptPresetTitle(prefs: Prefs): String {
    val presets = prefs.getPromptPresets()
    val activeId = prefs.activePromptId
    return presets.firstOrNull { it.id == activeId }?.title
        ?: presets.firstOrNull()?.title
        ?: ""
}

private fun oneClickSetupSummary(context: Context, prefs: Prefs): String {
    val checks = buildList {
        add(isOurImeEnabled(context))
        add(isOurImeCurrent(context))
        add(hasMicrophonePermission(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(hasNotificationPermission(context))
        }
        if (prefs.floatingAsrEnabled) {
            add(Settings.canDrawOverlays(context))
        }
        if (prefs.floatingAsrEnabled || prefs.volumeKeyRecordingEnabled) {
            add(isAccessibilityServiceEnabled(context))
        }
    }
    val done = checks.count { it }
    return if (done == checks.size) {
        context.getString(R.string.home_summary_setup_done)
    } else {
        context.getString(R.string.home_summary_setup_progress, done, checks.size)
    }
}

private fun hasRecentApiLogErrors(): Boolean = ApiLogStore.listAll()
    .take(10)
    .any { !it.success && !it.canceled }

private fun hasMicrophonePermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
} else {
    true
}

private fun isOurImeEnabled(context: Context): Boolean {
    val imm = try {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    } catch (_: Exception) {
        return false
    }
    val enabledList = try {
        imm.enabledInputMethodList
    } catch (_: Exception) {
        null
    }
    if (enabledList?.any { it.packageName == context.packageName } == true) return true
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        )
        val ids = ourImeIdCandidates(context)
        ids.any { enabled?.contains(it) == true } ||
            (enabled?.split(':')?.any { it.startsWith(context.packageName) } == true)
    } catch (_: Exception) {
        false
    }
}

private fun isOurImeCurrent(context: Context): Boolean = try {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    current != null && ourImeIdCandidates(context).contains(current)
} catch (_: Exception) {
    false
}

private fun ourImeIdCandidates(context: Context): Set<String> {
    val component = ComponentName(context, AsrKeyboardService::class.java)
    return setOf(component.flattenToShortString(), component.flattenToString())
}
