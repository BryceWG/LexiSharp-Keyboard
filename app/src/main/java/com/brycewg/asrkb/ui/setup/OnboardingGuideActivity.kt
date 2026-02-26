/**
 * 新手引导主页面（可左右滑动）。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.ProPromoDialog
import com.brycewg.asrkb.ui.WindowInsetsHelper
import com.brycewg.asrkb.ui.permission.PermissionActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * 重构后的“查看使用指南”入口：整合权限、服务选择、隐私和相关信息。
 */
class OnboardingGuideActivity : BaseActivity(), OnboardingPagerAdapter.Callbacks {

    companion object {
        private const val TAG = "OnboardingGuideActivity"
    }

    private enum class AsrChoice {
        SiliconFlowFree,
        LocalModel,
        OnlineCustom,
    }

    private lateinit var prefs: Prefs
    private lateinit var viewPager: ViewPager2
    private lateinit var progressOnboarding: LinearProgressIndicator
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnSkip: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var pagerAdapter: OnboardingPagerAdapter

    private lateinit var actionExecutor: OnboardingActionExecutor

    private var asrChoice: AsrChoice = AsrChoice.SiliconFlowFree
    private var isCompletingOnboarding: Boolean = false

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionPage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_guide)

        findViewById<View>(android.R.id.content).let { rootView ->
            WindowInsetsHelper.applySystemBarsInsets(rootView)
        }

        prefs = Prefs(this)
        actionExecutor = OnboardingActionExecutor(this)
        asrChoice = deriveInitialAsrChoice()

        markOnboardingAsShown()
        bindViews()
        setupToolbar()
        setupPager()
        setupBottomButtons()
        refreshNavigationUi()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionPage()
    }

    override fun bindPermissionPage(root: View) {
        val floatingEnabled = prefs.floatingAsrEnabled

        root.findViewById<View>(R.id.cardOverlayRequired).isVisible = floatingEnabled
        root.findViewById<View>(R.id.cardA11yRequired).isVisible = floatingEnabled
        root.findViewById<View>(R.id.cardOverlayOptional).isVisible = !floatingEnabled
        root.findViewById<View>(R.id.cardA11yOptional).isVisible = !floatingEnabled

        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvImeEnableStatus,
            actionButtonId = R.id.btnImeEnableAction,
            granted = isOurImeEnabled(),
            onRequest = { requestEnableIme() },
        )
        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvImeSwitchStatus,
            actionButtonId = R.id.btnImeSwitchAction,
            granted = isOurImeCurrent(),
            onRequest = { requestSwitchIme() },
        )

        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvMicStatus,
            actionButtonId = R.id.btnMicAction,
            granted = hasMicrophonePermission(),
            onRequest = { requestMicrophonePermission() },
        )

        val overlayGranted = hasOverlayPermission()
        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvOverlayRequiredStatus,
            actionButtonId = R.id.btnOverlayRequiredAction,
            granted = overlayGranted,
            onRequest = { requestOverlayPermission() },
        )
        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvOverlayOptionalStatus,
            actionButtonId = R.id.btnOverlayOptionalAction,
            granted = overlayGranted,
            onRequest = { requestOverlayPermission() },
        )

        val a11yGranted = hasAccessibilityPermission()
        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvA11yRequiredStatus,
            actionButtonId = R.id.btnA11yRequiredAction,
            granted = a11yGranted,
            onRequest = { requestAccessibilityPermission() },
        )
        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvA11yOptionalStatus,
            actionButtonId = R.id.btnA11yOptionalAction,
            granted = a11yGranted,
            onRequest = { requestAccessibilityPermission() },
        )

        updatePermissionCard(
            root = root,
            statusViewId = R.id.tvNotifStatus,
            actionButtonId = R.id.btnNotifAction,
            granted = hasNotificationPermission(),
            onRequest = { requestNotificationPermission() },
        )
    }

    override fun bindAsrChoicePage(root: View) {
        val cardSfFree = root.findViewById<MaterialCardView>(R.id.cardOnboardingSiliconFlowFree)
        val cardLocal = root.findViewById<MaterialCardView>(R.id.cardOnboardingLocalModel)
        val cardOnline = root.findViewById<MaterialCardView>(R.id.cardOnboardingOnlineModel)

        cardSfFree.isChecked = asrChoice == AsrChoice.SiliconFlowFree
        cardLocal.isChecked = asrChoice == AsrChoice.LocalModel
        cardOnline.isChecked = asrChoice == AsrChoice.OnlineCustom

        cardSfFree.setOnClickListener { updateAsrChoice(AsrChoice.SiliconFlowFree) }
        cardLocal.setOnClickListener { updateAsrChoice(AsrChoice.LocalModel) }
        cardOnline.setOnClickListener { updateAsrChoice(AsrChoice.OnlineCustom) }
    }

    override fun bindPrivacyPage(root: View) {
        val switchDataCollection = root.findViewById<MaterialSwitch>(R.id.switchOnboardingDataCollection)

        switchDataCollection.setOnCheckedChangeListener(null)
        switchDataCollection.isChecked = prefs.dataCollectionEnabled
        switchDataCollection.setOnCheckedChangeListener { _, checked ->
            updateDataCollectionEnabled(checked)
        }
    }

    override fun bindLinksPage(root: View) {
        root.findViewById<Button>(R.id.btnOnboardingOpenProject).setOnClickListener {
            openUrl(getString(R.string.about_project_url))
        }
        root.findViewById<Button>(R.id.btnOnboardingOpenWebsite).setOnClickListener {
            openUrl(getString(R.string.about_website_url))
        }
        root.findViewById<Button>(R.id.btnOnboardingOpenDocs).setOnClickListener {
            openUrl(getString(R.string.about_docs_url))
        }
        root.findViewById<Button>(R.id.btnOnboardingLearnPro).setOnClickListener {
            try {
                ProPromoDialog.showForce(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to show Pro promo dialog", t)
            }
        }
    }

    private fun bindViews() {
        viewPager = findViewById(R.id.viewPagerOnboarding)
        progressOnboarding = findViewById(R.id.progressOnboarding)
        tvPageIndicator = findViewById(R.id.tvOnboardingPageIndicator)
        btnSkip = findViewById(R.id.btnOnboardingSkip)
        btnPrev = findViewById(R.id.btnOnboardingPrev)
        btnNext = findViewById(R.id.btnOnboardingNext)
    }

    private fun setupToolbar() {
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarOnboarding)
            .setNavigationOnClickListener {
                finishOnboarding()
            }
    }

    private fun setupPager() {
        pagerAdapter = OnboardingPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = OnboardingPagerAdapter.PAGE_LINKS
        progressOnboarding.max = pagerAdapter.itemCount

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                refreshNavigationUi()
            }
        })
    }

    private fun setupBottomButtons() {
        btnSkip.setOnClickListener {
            finishOnboarding()
        }
        btnPrev.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.currentItem = current - 1
            }
        }
        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current >= OnboardingPagerAdapter.PAGE_LINKS) {
                completeOnboardingWithSelectedAsrChoice()
            } else {
                viewPager.currentItem = current + 1
            }
        }
    }

    private fun refreshNavigationUi() {
        val current = viewPager.currentItem
        val total = pagerAdapter.itemCount

        tvPageIndicator.text = getString(
            R.string.onboarding_page_indicator,
            current + 1,
            total,
        )
        progressOnboarding.setProgressCompat(current + 1, true)

        btnPrev.isEnabled = current > 0
        btnSkip.isVisible = current < OnboardingPagerAdapter.PAGE_LINKS
        btnNext.text = if (current >= OnboardingPagerAdapter.PAGE_LINKS) {
            getString(R.string.onboarding_btn_finish)
        } else {
            getString(R.string.onboarding_btn_next)
        }
    }

    private fun refreshPermissionPage() {
        if (!::pagerAdapter.isInitialized) return
        pagerAdapter.notifyItemChanged(OnboardingPagerAdapter.PAGE_PERMISSIONS)
    }

    private fun updateAsrChoice(newChoice: AsrChoice) {
        if (asrChoice == newChoice) return
        asrChoice = newChoice
        pagerAdapter.notifyItemChanged(OnboardingPagerAdapter.PAGE_ASR_CHOICE)
    }

    private fun updatePermissionCard(
        root: View,
        statusViewId: Int,
        actionButtonId: Int,
        granted: Boolean,
        onRequest: () -> Unit,
    ) {
        val statusView = root.findViewById<TextView>(statusViewId)
        val actionButton = root.findViewById<Button>(actionButtonId)

        statusView.text = if (granted) {
            getString(R.string.onboarding_permission_status_granted)
        } else {
            getString(R.string.onboarding_permission_status_missing)
        }

        actionButton.isEnabled = !granted
        actionButton.text = if (granted) {
            getString(R.string.onboarding_permission_btn_enabled)
        } else {
            getString(R.string.onboarding_permission_btn_go_enable)
        }

        actionButton.setOnClickListener(if (granted) null else View.OnClickListener { onRequest() })
    }

    private fun updateDataCollectionEnabled(enabled: Boolean) {
        prefs.dataCollectionConsentShown = true
        prefs.dataCollectionEnabled = enabled

        if (enabled) {
            try {
                AnalyticsManager.init(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to init analytics after enabling data collection", t)
            }
        }

        try {
            AnalyticsManager.sendConsentChoice(this, enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send consent choice from onboarding", t)
        }
    }

    private fun requestMicrophonePermission() {
        try {
            startActivity(Intent(this, PermissionActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request microphone permission", e)
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri(),
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay settings", e)
        }
    }

    private fun requestAccessibilityPermission() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.onboarding_permission_status_granted, Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestEnableIme() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open input method settings", e)
        }
    }

    private fun requestSwitchIme() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show input method picker", e)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open url: $url", e)
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishOnboarding() {
        markOnboardingAsShown()
        finish()
    }

    private fun completeOnboardingWithSelectedAsrChoice() {
        if (isCompletingOnboarding) return
        isCompletingOnboarding = true
        when (asrChoice) {
            AsrChoice.SiliconFlowFree -> actionExecutor.applySiliconFlowFree { finishOnboarding() }
            AsrChoice.LocalModel -> actionExecutor.applyLocalModel { finishOnboarding() }
            AsrChoice.OnlineCustom -> actionExecutor.applyOnlineCustom { finishOnboarding() }
        }
    }

    private fun markOnboardingAsShown() {
        if (!prefs.hasShownOnboardingGuideV2Once) {
            prefs.hasShownOnboardingGuideV2Once = true
        }
    }

    private fun deriveInitialAsrChoice(): AsrChoice {
        return when {
            prefs.sfFreeAsrEnabled -> AsrChoice.SiliconFlowFree
            prefs.asrVendor == com.brycewg.asrkb.asr.AsrVendor.SenseVoice -> AsrChoice.LocalModel
            else -> AsrChoice.OnlineCustom
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasAccessibilityPermission(): Boolean {
        return try {
            val expectedComponentName = "$packageName/com.brycewg.asrkb.ui.AsrAccessibilityService"
            val enabledServicesSetting = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            enabledServicesSetting?.contains(expectedComponentName) == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility service state", e)
            false
        }
    }

    private fun isOurImeEnabled(): Boolean {
        val imm = try {
            getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get InputMethodManager", e)
            return false
        }

        val enabledList = try {
            imm.enabledInputMethodList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get enabled IME list", e)
            null
        }

        if (enabledList?.any { it.packageName == packageName } == true) {
            return true
        }

        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            )
            val ids = getOurImeIdCandidates()
            ids.any { enabled?.contains(it) == true } ||
                (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check IME enabled via Settings", e)
            false
        }
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            val ids = getOurImeIdCandidates()
            current != null && ids.contains(current)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check current IME", e)
            false
        }
    }

    private fun getOurImeIdCandidates(): Set<String> {
        val component = ComponentName(this, AsrKeyboardService::class.java)
        return setOf(
            component.flattenToShortString(),
            component.flattenToString(),
        )
    }
}
