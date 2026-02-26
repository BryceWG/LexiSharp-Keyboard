/**
 * 设置搜索索引构建与缓存。
 *
 * 归属模块：ui/settings/search
 */
package com.brycewg.asrkb.ui.settings.search

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsActivity
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsActivity
import com.brycewg.asrkb.ui.settings.backup.BackupSettingsActivity
import com.brycewg.asrkb.ui.settings.floating.FloatingSettingsActivity
import com.brycewg.asrkb.ui.settings.input.InputSettingsActivity
import com.brycewg.asrkb.ui.settings.other.OtherSettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

object SettingsSearchIndex {

    @Volatile
    private var cachedLocaleTag: String? = null

    @Volatile
    private var cachedEntries: List<SettingsSearchEntry>? = null

    fun get(context: Context): List<SettingsSearchEntry> {
        val localeTag = context.resources.configuration.locales[0]?.toLanguageTag() ?: ""
        val existing = cachedEntries
        if (existing != null && cachedLocaleTag == localeTag) {
            return existing
        }
        synchronized(this) {
            val existing2 = cachedEntries
            if (existing2 != null && cachedLocaleTag == localeTag) {
                return existing2
            }
            val built = buildIndex(context)
            cachedLocaleTag = localeTag
            cachedEntries = built
            return built
        }
    }

    private data class ScreenSpec(
        @param:LayoutRes @field:LayoutRes val layoutResId: Int,
        @param:StringRes @field:StringRes val screenTitleResId: Int,
        val activityClass: Class<out Activity>,
        val manualMappings: List<ManualMapping>,
    )

    private data class ManualMapping(
        @param:StringRes @field:StringRes val labelResId: Int,
        @param:IdRes @field:IdRes val targetViewId: Int,
    )

    private data class VendorHint(
        val title: String,
        val asrVendorId: String? = null,
        val llmVendorId: String? = null,
        val keywords: List<String> = emptyList(),
    )

    private fun buildIndex(context: Context): List<SettingsSearchEntry> {
        val minCardTitlePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            context.resources.displayMetrics,
        )
        val specs = listOf(
            ScreenSpec(
                layoutResId = R.layout.activity_input_settings,
                screenTitleResId = R.string.title_input_settings,
                activityClass = InputSettingsActivity::class.java,
                manualMappings = listOf(
                    ManualMapping(R.string.label_ime_switch_target, R.id.tvImeSwitchTargetValue),
                    ManualMapping(R.string.label_keyboard_height, R.id.toggleKeyboardHeight),
                    ManualMapping(R.string.label_haptic_feedback_strength, R.id.sliderHapticFeedbackStrength),
                    ManualMapping(R.string.label_keyboard_bottom_padding, R.id.sliderBottomPadding),
                    ManualMapping(R.string.label_waveform_sensitivity, R.id.sliderWaveformSensitivity),
                    ManualMapping(R.string.label_language, R.id.tvLanguageValue),
                    ManualMapping(R.string.label_extension_buttons, R.id.tvExtensionButtonsValue),
                ),
            ),
            ScreenSpec(
                layoutResId = R.layout.activity_asr_settings,
                screenTitleResId = R.string.title_asr_settings,
                activityClass = AsrSettingsActivity::class.java,
                manualMappings = listOf(
                    ManualMapping(R.string.label_asr_vendor, R.id.tvAsrVendorValue),
                    ManualMapping(R.string.label_silence_window_ms, R.id.sliderSilenceWindow),
                    ManualMapping(R.string.label_silence_sensitivity, R.id.sliderSilenceSensitivity),
                ),
            ),
            ScreenSpec(
                layoutResId = R.layout.activity_ai_post_settings,
                screenTitleResId = R.string.title_ai_settings,
                activityClass = AiPostSettingsActivity::class.java,
                manualMappings = listOf(
                    ManualMapping(R.string.label_llm_vendor, R.id.tvLlmVendor),
                    ManualMapping(R.string.title_ai_skip_under, R.id.sliderSkipAiUnderChars),
                ),
            ),
            ScreenSpec(
                layoutResId = R.layout.activity_floating_settings,
                screenTitleResId = R.string.title_floating_settings,
                activityClass = FloatingSettingsActivity::class.java,
                manualMappings = listOf(
                    ManualMapping(R.string.label_floating_alpha, R.id.sliderFloatingAlpha),
                    ManualMapping(R.string.label_floating_size, R.id.sliderFloatingSize),
                ),
            ),
            ScreenSpec(
                layoutResId = R.layout.activity_other_settings,
                screenTitleResId = R.string.title_other_settings,
                activityClass = OtherSettingsActivity::class.java,
                manualMappings = listOf(
                    ManualMapping(R.string.label_speech_preset_section, R.id.tvSpeechPresetsValue),
                ),
            ),
            ScreenSpec(
                layoutResId = R.layout.activity_backup_settings,
                screenTitleResId = R.string.title_backup_settings,
                activityClass = BackupSettingsActivity::class.java,
                manualMappings = emptyList(),
            ),
        )

        val unique = LinkedHashMap<String, SettingsSearchEntry>()
        for (spec in specs) {
            val root = LayoutInflater.from(context).inflate(spec.layoutResId, null, false)
            val auto = collectAutoEntries(spec, root, minCardTitlePx)
            for (entry in auto) {
                unique.putIfAbsent(entry.uniqueKey(), entry)
            }
            for (mapping in spec.manualMappings) {
                val title = runCatching { context.getString(mapping.labelResId) }.getOrNull().orEmpty()
                if (title.isBlank()) continue
                val sectionPathAndVendors = resolveSectionPathAndVendors(root, mapping.targetViewId, minCardTitlePx)
                val manual = SettingsSearchEntry(
                    title = title,
                    sectionPath = sectionPathAndVendors.sectionPath,
                    screenTitleResId = spec.screenTitleResId,
                    activityClass = spec.activityClass,
                    targetViewId = mapping.targetViewId,
                    keywords = sectionPathAndVendors.keywords,
                    forceAsrVendorId = sectionPathAndVendors.forceAsrVendorId,
                    forceLlmVendorId = sectionPathAndVendors.forceLlmVendorId,
                )
                unique.putIfAbsent(manual.uniqueKey(), manual)
            }
            if (spec.activityClass == AiPostSettingsActivity::class.java) {
                val vendorEntries = buildAiPostProcessModelVendorSubgroupEntries(spec, context)
                for (entry in vendorEntries) {
                    unique.putIfAbsent(entry.uniqueKey(), entry)
                }
            }
        }

        return unique.values.toList()
    }

    private fun SettingsSearchEntry.uniqueKey(): String {
        return buildString {
            append(activityClass.name)
            append('#')
            append(targetViewId)
            append('#')
            append(title.lowercase(Locale.ROOT))
            if (sectionPath.isNotEmpty()) {
                append('#')
                append(sectionPath.joinToString(">").lowercase(Locale.ROOT))
            }
            if (!forceAsrVendorId.isNullOrBlank()) {
                append("#asr=")
                append(forceAsrVendorId.lowercase(Locale.ROOT))
            }
            if (!forceLlmVendorId.isNullOrBlank()) {
                append("#llm=")
                append(forceLlmVendorId.lowercase(Locale.ROOT))
            }
        }
    }

    private fun collectAutoEntries(
        spec: ScreenSpec,
        root: View,
        minCardTitlePx: Float,
    ): List<SettingsSearchEntry> {
        val results = mutableListOf<SettingsSearchEntry>()
        collectFromView(
            spec = spec,
            view = root,
            currentSectionPath = emptyList(),
            extraKeywords = emptyList(),
            forceAsrVendorId = null,
            forceLlmVendorId = null,
            minCardTitlePx = minCardTitlePx,
            out = results,
        )
        return results
    }

    private fun collectFromView(
        spec: ScreenSpec,
        view: View,
        currentSectionPath: List<String>,
        extraKeywords: List<String>,
        forceAsrVendorId: String?,
        forceLlmVendorId: String?,
        minCardTitlePx: Float,
        out: MutableList<SettingsSearchEntry>,
    ) {
        when (view) {
            is MaterialSwitch -> {
                val title = view.text?.toString()?.trim().orEmpty()
                if (title.isNotBlank() && view.id != View.NO_ID) {
                    out.add(
                        SettingsSearchEntry(
                            title = title,
                            sectionPath = currentSectionPath,
                            screenTitleResId = spec.screenTitleResId,
                            activityClass = spec.activityClass,
                            targetViewId = view.id,
                            keywords = extraKeywords,
                            forceAsrVendorId = forceAsrVendorId,
                            forceLlmVendorId = forceLlmVendorId,
                        ),
                    )
                }
            }

            is MaterialButton -> {
                val title = view.text?.toString()?.trim().orEmpty()
                if (title.isNotBlank() && view.id != View.NO_ID) {
                    out.add(
                        SettingsSearchEntry(
                            title = title,
                            sectionPath = currentSectionPath,
                            screenTitleResId = spec.screenTitleResId,
                            activityClass = spec.activityClass,
                            targetViewId = view.id,
                            keywords = extraKeywords,
                            forceAsrVendorId = forceAsrVendorId,
                            forceLlmVendorId = forceLlmVendorId,
                        ),
                    )
                }
            }

            is TextInputLayout -> {
                val title = view.hint?.toString()?.trim().orEmpty()
                val editTextId = view.editText?.id ?: View.NO_ID
                if (title.isNotBlank() && editTextId != View.NO_ID) {
                    out.add(
                        SettingsSearchEntry(
                            title = title,
                            sectionPath = currentSectionPath,
                            screenTitleResId = spec.screenTitleResId,
                            activityClass = spec.activityClass,
                            targetViewId = editTextId,
                            keywords = extraKeywords,
                            forceAsrVendorId = forceAsrVendorId,
                            forceLlmVendorId = forceLlmVendorId,
                        ),
                    )
                }
            }
        }

        if (view is ViewGroup) {
            if (shouldSkipAiPostProcessModelVendorDetails(spec, view.id)) {
                return
            }
            val nextSectionPath = extractCardTitleText(view, minCardTitlePx)
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(it) }
                ?: currentSectionPath

            val vendorHint = resolveVendorHint(view.context, view.id)
            val nextForceAsrVendorId = vendorHint?.asrVendorId ?: forceAsrVendorId
            val nextForceLlmVendorId = vendorHint?.llmVendorId ?: forceLlmVendorId
            val nextKeywords = buildList(extraKeywords.size + 4) {
                addAll(extraKeywords)
                if (vendorHint != null) {
                    addAll(vendorHint.keywords)
                }
            }
            val vendorTitle = vendorHint?.title?.takeIf { it.isNotBlank() }
            val nextSectionPathWithVendor = if (vendorTitle == null) {
                nextSectionPath
            } else if (nextSectionPath.lastOrNull() == vendorTitle) {
                nextSectionPath
            } else {
                nextSectionPath + vendorTitle
            }

            var pendingLabel: String? = null
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)

                val childTextView = child as? TextView
                if (childTextView != null) {
                    val labelText = childTextView.text?.toString()?.trim().orEmpty()
                    val labelMinPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        14f,
                        childTextView.resources.displayMetrics,
                    )
                    val isLabelCandidate =
                        !childTextView.isClickable &&
                            !childTextView.isFocusable &&
                            labelText.isNotBlank() &&
                            childTextView.textSize >= labelMinPx &&
                            !isLikelyCardTitleTextView(childTextView, minCardTitlePx)
                    if (isLabelCandidate) {
                        pendingLabel = labelText
                    }

                    val isClickableValue =
                        childTextView.isClickable &&
                            childTextView.id != View.NO_ID &&
                            childTextView !is android.widget.Button
                    if (isClickableValue) {
                        val title = pendingLabel?.takeIf { it.isNotBlank() } ?: labelText
                        if (title.isNotBlank()) {
                            out.add(
                                SettingsSearchEntry(
                                    title = title,
                                    sectionPath = nextSectionPathWithVendor,
                                    screenTitleResId = spec.screenTitleResId,
                                    activityClass = spec.activityClass,
                                    targetViewId = childTextView.id,
                                    keywords = nextKeywords,
                                    forceAsrVendorId = nextForceAsrVendorId,
                                    forceLlmVendorId = nextForceLlmVendorId,
                                ),
                            )
                        }
                        pendingLabel = null
                        continue
                    }
                }

                if (
                    child is MaterialSwitch ||
                    child is MaterialButton ||
                    child is TextInputLayout
                ) {
                    pendingLabel = null
                }

                collectFromView(
                    spec = spec,
                    view = child,
                    currentSectionPath = nextSectionPathWithVendor,
                    extraKeywords = nextKeywords,
                    forceAsrVendorId = nextForceAsrVendorId,
                    forceLlmVendorId = nextForceLlmVendorId,
                    minCardTitlePx = minCardTitlePx,
                    out = out,
                )
            }
        }
    }

    private fun shouldSkipAiPostProcessModelVendorDetails(spec: ScreenSpec, @IdRes viewId: Int): Boolean {
        if (spec.activityClass != AiPostSettingsActivity::class.java) return false
        return when (viewId) {
            R.id.groupSfFreeLlm,
            R.id.groupBuiltinLlm,
            R.id.groupCustomLlm,
            -> true
            else -> false
        }
    }

    private fun buildAiPostProcessModelVendorSubgroupEntries(
        spec: ScreenSpec,
        context: Context,
    ): List<SettingsSearchEntry> {
        val sectionTitle = runCatching { context.getString(R.string.section_post_process_model) }.getOrNull().orEmpty()
        if (sectionTitle.isBlank()) return emptyList()

        return buildList(LlmVendor.allVendors().size) {
            for (vendor in LlmVendor.allVendors()) {
                val title = runCatching { context.getString(vendor.displayNameResId) }.getOrNull().orEmpty()
                if (title.isBlank()) continue
                val targetViewId = when (vendor) {
                    LlmVendor.SF_FREE -> R.id.groupSfFreeLlm
                    LlmVendor.CUSTOM -> R.id.groupCustomLlm
                    else -> R.id.groupBuiltinLlm
                }
                add(
                    SettingsSearchEntry(
                        title = title,
                        sectionPath = listOf(sectionTitle),
                        screenTitleResId = spec.screenTitleResId,
                        activityClass = spec.activityClass,
                        targetViewId = targetViewId,
                        keywords = listOf(vendor.id),
                        forceLlmVendorId = vendor.id,
                    ),
                )
            }
        }
    }

    private fun extractCardTitleText(group: ViewGroup, minCardTitlePx: Float): String? {
        val linear = group as? LinearLayout ?: return null
        if (linear.orientation != LinearLayout.VERTICAL) return null

        // SettingsSectionCardTitle：bold + 20sp。使用字号与粗体做启发式识别。
        val titleView = (0 until linear.childCount)
            .asSequence()
            .map { linear.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { v ->
                val text = v.text?.toString()?.trim().orEmpty()
                text.isNotBlank() && isLikelyCardTitleTextView(v, minCardTitlePx)
            } ?: return null

        val title = titleView.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) return null
        return title
    }

    private fun isLikelyCardTitleTextView(view: TextView, minCardTitlePx: Float): Boolean {
        if (view.textSize < minCardTitlePx) return false
        val typefaceStyle = view.typeface?.style ?: 0
        val isBold = (typefaceStyle and Typeface.BOLD) == Typeface.BOLD || view.paint.isFakeBoldText
        return isBold
    }

    private data class ResolvedPathAndVendors(
        val sectionPath: List<String>,
        val forceAsrVendorId: String?,
        val forceLlmVendorId: String?,
        val keywords: List<String>,
    )

    private fun resolveSectionPathAndVendors(
        root: View,
        @IdRes targetViewId: Int,
        minCardTitlePx: Float,
    ): ResolvedPathAndVendors {
        val target = root.findViewById<View>(targetViewId)
        if (target == null) {
            return ResolvedPathAndVendors(
                sectionPath = emptyList(),
                forceAsrVendorId = null,
                forceLlmVendorId = null,
                keywords = emptyList(),
            )
        }

        var sectionTitle: String? = null
        var vendorHint: VendorHint? = null
        var p: Any? = target.parent
        while (p is ViewGroup) {
            if (sectionTitle.isNullOrBlank()) {
                sectionTitle = extractCardTitleText(p, minCardTitlePx)
            }
            if (vendorHint == null && p.id != View.NO_ID) {
                vendorHint = resolveVendorHint(p.context, p.id)
            }
            p = p.parent
        }

        val sectionPath = buildList(2) {
            if (!sectionTitle.isNullOrBlank()) add(sectionTitle!!.trim())
            if (!vendorHint?.title.isNullOrBlank()) add(vendorHint!!.title)
        }
        val keywords = buildList(4) {
            if (vendorHint != null) addAll(vendorHint!!.keywords)
        }
        return ResolvedPathAndVendors(
            sectionPath = sectionPath,
            forceAsrVendorId = vendorHint?.asrVendorId,
            forceLlmVendorId = vendorHint?.llmVendorId,
            keywords = keywords,
        )
    }

    private fun resolveVendorHint(context: Context, @IdRes viewId: Int): VendorHint? {
        return when (viewId) {
            // ======== ASR 供应商分组 ========
            R.id.groupVolc -> VendorHint(
                title = context.getString(R.string.vendor_volc),
                asrVendorId = "volc",
                keywords = listOf("volc"),
            )
            R.id.groupSf,
            R.id.groupSfFreeModel,
            R.id.groupSfApiKey,
            -> VendorHint(
                title = context.getString(R.string.vendor_sf),
                asrVendorId = "siliconflow",
                keywords = listOf("siliconflow", "sf"),
            )
            R.id.groupEleven -> VendorHint(
                title = context.getString(R.string.vendor_eleven),
                asrVendorId = "elevenlabs",
                keywords = listOf("eleven", "elevenlabs"),
            )
            R.id.groupOpenAI -> VendorHint(
                title = context.getString(R.string.vendor_openai),
                asrVendorId = "openai",
                keywords = listOf("openai"),
            )
            R.id.groupDashScope -> VendorHint(
                title = context.getString(R.string.vendor_dashscope),
                asrVendorId = "dashscope",
                keywords = listOf("dashscope"),
            )
            R.id.groupGemini -> VendorHint(
                title = context.getString(R.string.vendor_gemini),
                asrVendorId = "gemini",
                keywords = listOf("gemini"),
            )
            R.id.groupSoniox -> VendorHint(
                title = context.getString(R.string.vendor_soniox),
                asrVendorId = "soniox",
                keywords = listOf("soniox"),
            )
            R.id.groupZhipu -> VendorHint(
                title = context.getString(R.string.vendor_zhipu),
                asrVendorId = "zhipu",
                keywords = listOf("zhipu", "glm"),
            )
            R.id.groupSenseVoice -> VendorHint(
                title = context.getString(R.string.vendor_sensevoice),
                asrVendorId = "sensevoice",
                keywords = listOf("sensevoice"),
            )
            R.id.groupFunAsrNano -> VendorHint(
                title = context.getString(R.string.vendor_funasr_nano),
                asrVendorId = "funasr_nano",
                keywords = listOf("funasr", "funasr_nano"),
            )
            R.id.groupTelespeech -> VendorHint(
                title = context.getString(R.string.vendor_telespeech),
                asrVendorId = "telespeech",
                keywords = listOf("telespeech"),
            )
            R.id.groupParaformer -> VendorHint(
                title = context.getString(R.string.vendor_paraformer),
                asrVendorId = "paraformer",
                keywords = listOf("paraformer", "zipformer"),
            )

            // ======== LLM 分组 ========
            R.id.groupSfFreeLlm -> VendorHint(
                title = context.getString(R.string.llm_vendor_sf_free),
                llmVendorId = "sf_free",
                keywords = listOf("sf_free", "siliconflow", "sf"),
            )
            R.id.groupCustomLlm -> VendorHint(
                title = context.getString(R.string.llm_vendor_custom),
                llmVendorId = "custom",
                keywords = listOf("custom"),
            )
            else -> null
        }
    }
}
