/**
 * 设置搜索条目数据结构。
 *
 * 归属模块：ui/settings/search
 */
package com.brycewg.asrkb.ui.settings.search

import android.app.Activity
import androidx.annotation.IdRes
import androidx.annotation.StringRes

data class SettingsSearchEntry(
    val title: String,
    /**
     * 设置项所在的分组路径（不包含页面标题）。
     *
     * 示例：["音频与联动"] 或 ["识别服务商", "豆包语音"]。
     */
    val sectionPath: List<String> = emptyList(),
    @param:StringRes @field:StringRes val screenTitleResId: Int,
    val activityClass: Class<out Activity>,
    @param:IdRes @field:IdRes val targetViewId: Int,
    val keywords: List<String> = emptyList(),
    /**
     * 搜索跳转时强制切换到指定 ASR 供应商（用于进入被隐藏的供应商配置分组）。
     *
     * 值为 [com.brycewg.asrkb.asr.AsrVendor.id]。
     */
    val forceAsrVendorId: String? = null,
    /**
     * 搜索跳转时强制切换到指定 LLM 供应商（用于进入被隐藏的供应商配置分组）。
     *
     * 值为 [com.brycewg.asrkb.asr.LlmVendor.id]。
     */
    val forceLlmVendorId: String? = null,
)
