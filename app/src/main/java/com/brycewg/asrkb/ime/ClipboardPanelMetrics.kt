/**
 * 剪贴板面板列表项共享尺寸，保证卡片与滑动背景圆角一致。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import kotlin.math.min

internal object ClipboardPanelMetrics {
    /** 列表卡片圆角上限：避免单行高度下变成胶囊条。 */
    private const val ITEM_RADIUS_CAP_DP = 14f

    fun itemRadiusDp(panelRadiusDp: Float): Float = min(panelRadiusDp, ITEM_RADIUS_CAP_DP)
}
