/**
 * Prompt 预设迁移与默认值同步逻辑。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import com.brycewg.asrkb.R

/**
 * Prompt 预设的迁移/兼容逻辑（从 [Prefs] 中拆出）。
 *
 * 当前用途：
 * - 从旧版单一 `llmPrompt` 字段迁移为一个新的预设项
 */
internal object PromptPresetMigrations {
    fun migrateLegacyPromptIfNeeded(
        prefs: Prefs,
        current: List<PromptPreset>,
        localizedDefaults: List<PromptPreset>,
        knownDefaultVariants: List<List<PromptPreset>>,
        legacyPrompt: String,
        initializedFromDefaults: Boolean,
    ): List<PromptPreset> {
        if (legacyPrompt.isBlank()) return current
        val knownBuiltinContents = knownDefaultVariants
            .flatten()
            .map { it.content }
            .toSet()
        if (legacyPrompt in knownBuiltinContents) return current
        if (current.any { it.content == legacyPrompt }) return current

        val migratedPreset = PromptPreset(
            id = java.util.UUID.randomUUID().toString(),
            title = prefs.getLocalizedString(R.string.llm_prompt_preset_mine_title),
            content = legacyPrompt,
        )
        val updated = current + migratedPreset
        val shouldActivate = initializedFromDefaults ||
            prefs.activePromptId.isBlank() ||
            current.none { it.id == prefs.activePromptId } ||
            matchesAnyDefaultPromptPresets(current, localizedDefaults, knownDefaultVariants)
        if (shouldActivate) {
            prefs.activePromptId = migratedPreset.id
        }
        prefs.setPromptPresets(updated)
        return updated
    }

    private fun matchesDefaultPromptPresets(
        presets: List<PromptPreset>,
        defaults: List<PromptPreset>,
    ): Boolean {
        if (presets.size != defaults.size) return false
        return presets.map { it.title to it.content } == defaults.map { it.title to it.content }
    }

    private fun matchesAnyDefaultPromptPresets(
        presets: List<PromptPreset>,
        localizedDefaults: List<PromptPreset>,
        knownDefaultVariants: List<List<PromptPreset>>,
    ): Boolean {
        if (matchesDefaultPromptPresets(presets, localizedDefaults)) return true
        return knownDefaultVariants.any { defaults -> matchesDefaultPromptPresets(presets, defaults) }
    }

    fun remapLegacyDefaultPresetIdsIfNeeded(
        prefs: Prefs,
        current: List<PromptPreset>,
        localizedDefaults: List<PromptPreset>,
        knownDefaultVariants: List<List<PromptPreset>>,
    ): List<PromptPreset> {
        if (current.isEmpty() || localizedDefaults.isEmpty()) return current
        val builtinIds = localizedDefaults.map { it.id }.toSet()
        val knownDefaultsById: Map<String, Set<Pair<String, String>>> = knownDefaultVariants
            .flatten()
            .groupBy { it.id }
            .mapValues { (_, list) -> list.map { it.title to it.content }.toSet() }

        val remapped = current.toMutableList()
        val usedBuiltinIds = remapped.asSequence()
            .map { it.id }
            .filter { it in builtinIds }
            .toMutableSet()
        val activeIdMap = mutableMapOf<String, String>()
        var changed = false

        remapped.indices.forEach { index ->
            val oldItem = remapped[index]
            if (oldItem.id in builtinIds) return@forEach

            val signature = oldItem.title to oldItem.content
            val targetId = knownDefaultsById.entries.firstOrNull { (id, values) ->
                id !in usedBuiltinIds && signature in values
            }?.key ?: return@forEach

            activeIdMap[oldItem.id] = targetId
            remapped[index] = oldItem.copy(id = targetId)
            usedBuiltinIds += targetId
            changed = true
        }

        if (!changed) return current

        val newActiveId = activeIdMap[prefs.activePromptId]
        if (!newActiveId.isNullOrBlank()) {
            prefs.activePromptId = newActiveId
        }
        prefs.setPromptPresets(remapped)
        return remapped
    }

    fun syncDefaultsForLanguageIfNeeded(
        prefs: Prefs,
        current: List<PromptPreset>,
        localizedDefaults: List<PromptPreset>,
        knownDefaultVariants: List<List<PromptPreset>>,
    ): List<PromptPreset> {
        if (current.isEmpty() || localizedDefaults.isEmpty()) return current
        val defaultById = localizedDefaults.associateBy { it.id }
        val knownDefaultsById: Map<String, Set<Pair<String, String>>> = knownDefaultVariants
            .flatten()
            .groupBy { it.id }
            .mapValues { (_, list) -> list.map { it.title to it.content }.toSet() }
        var changed = false
        val mapped = current.map { preset ->
            val localized = defaultById[preset.id] ?: return@map preset
            val knownValues = knownDefaultsById[preset.id] ?: return@map preset
            if ((preset.title to preset.content) !in knownValues) return@map preset

            val titleChanged = preset.title != localized.title
            val contentChanged = preset.content != localized.content
            if (!titleChanged && !contentChanged) return@map preset

            changed = true
            preset.copy(
                title = localized.title,
                content = localized.content,
            )
        }
        if (!changed) return current
        prefs.setPromptPresets(mapped)
        return mapped
    }
}
