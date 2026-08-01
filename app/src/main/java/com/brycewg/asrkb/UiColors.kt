package com.brycewg.asrkb

import android.content.Context
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors

/**
 * 统一的颜色获取工具类
 *
 * 封装 MaterialColors.getColor，提供便捷的取色方法，避免分散硬编码。
 * 所有代码侧取色应优先使用此工具，确保与主题系统和动态取色的一致性。
 */
object UiColors {

    /**
     * 从 View 的上下文获取主题属性颜色
     *
     * @param view 视图，用于获取上下文和主题
     * @param attr 颜色属性（通常来自 UiColorTokens）
     * @param defaultColor 回退颜色（当属性不存在时使用）
     * @return 解析后的颜色值
     */
    @ColorInt
    fun get(view: View, @AttrRes attr: Int, @ColorInt defaultColor: Int): Int = MaterialColors.getColor(view, attr, defaultColor)

    /**
     * 从 Context 获取主题属性颜色
     *
     * @param context 上下文
     * @param attr 颜色属性（通常来自 UiColorTokens）
     * @param defaultColor 回退颜色（当属性不存在时使用）
     * @return 解析后的颜色值
     */
    @ColorInt
    fun get(context: Context, @AttrRes attr: Int, @ColorInt defaultColor: Int): Int = MaterialColors.getColor(context, attr, defaultColor)

    /**
     * 从 View 的上下文获取主题属性颜色（使用标准 Material 回退色）
     *
     * 提供常用颜色的标准回退值，避免每次都手动指定
     */
    @ColorInt
    fun get(view: View, @AttrRes attr: Int): Int = get(view, attr, getDefaultFallback(attr))

    /**
     * 从 Context 获取主题属性颜色（使用标准 Material 回退色）
     */
    @ColorInt
    fun get(context: Context, @AttrRes attr: Int): Int = get(context, attr, getDefaultFallback(attr))

    /**
     * 获取标准的回退颜色
     *
     * 根据颜色语义提供合理的回退值（Material3 基线）
     */
    @ColorInt
    private fun getDefaultFallback(@AttrRes attr: Int): Int = when (attr) {
        UiColorTokens.error, UiColorTokens.floatingError -> 0xFFB3261E.toInt()
        UiColorTokens.primary -> 0xFF6750A4.toInt()
        UiColorTokens.secondary, UiColorTokens.floatingIcon -> 0xFF625B71.toInt()
        UiColorTokens.tertiary -> 0xFF7D5260.toInt()
        UiColorTokens.primaryContainer -> 0xFFEADDFF.toInt()
        UiColorTokens.onPrimaryContainer -> 0xFF21005D.toInt()
        UiColorTokens.onSecondaryContainer -> 0xFF1D192B.toInt()
        UiColorTokens.tertiaryContainer -> 0xFFFFD8E4.toInt()
        UiColorTokens.onTertiaryContainer -> 0xFF31111D.toInt()
        UiColorTokens.panelBg, UiColorTokens.kbdContainerBg, UiColorTokens.floatingBallBg -> 0xFFFFFBFE.toInt()
        UiColorTokens.panelFg -> 0xFF1C1B1F.toInt()
        UiColorTokens.panelFgVariant, UiColorTokens.containerFg, UiColorTokens.chipFg, UiColorTokens.kbdKeyFg -> 0xFF49454F.toInt()
        UiColorTokens.containerBg, UiColorTokens.chipBg, UiColorTokens.kbdKeyBg -> 0xFFE7E0EC.toInt()
        UiColorTokens.selectedBg, UiColorTokens.secondaryContainer -> 0xFFE8DEF8.toInt()
        UiColorTokens.outline -> 0xFF79747E.toInt()
        UiColorTokens.outlineVariant -> 0xFFCAC4D0.toInt()
        UiColorTokens.scrim -> 0xFF000000.toInt()
        // 使用统计分享卡：固定浅色导出构图，不跟随深色主题
        UiColorTokens.shareCardBg -> 0xFFF7F4FF.toInt()
        UiColorTokens.shareCardSurface -> 0xFFFFFFFF.toInt()
        UiColorTokens.shareCardOnBg -> 0xFF1C1B1F.toInt()
        UiColorTokens.shareCardOnBgVariant -> 0xFF49454F.toInt()
        UiColorTokens.shareCardAccent -> 0xFF6750A4.toInt()
        UiColorTokens.shareCardAccentSoft -> 0xFFE8DEF8.toInt()
        UiColorTokens.shareCardTrack -> 0xFFE7E0EC.toInt()
        UiColorTokens.shareCardFooter -> 0xFF79747E.toInt()
        else -> 0xFF000000.toInt() // 黑色作为最终回退
    }

    /**
     * 分享卡取色：优先主题 attr（若已配置），否则使用固定浅色回退，保证导出构图稳定。
     */
    @ColorInt
    fun shareCard(context: Context, @AttrRes attr: Int): Int = get(context, attr)

    // ==================== 便捷方法（常用颜色） ====================

    /** 获取面板背景色 */
    @ColorInt
    fun panelBg(view: View): Int = get(view, UiColorTokens.panelBg)

    @ColorInt
    fun panelBg(context: Context): Int = get(context, UiColorTokens.panelBg)

    /** 获取面板前景色 */
    @ColorInt
    fun panelFg(view: View): Int = get(view, UiColorTokens.panelFg)

    @ColorInt
    fun panelFg(context: Context): Int = get(context, UiColorTokens.panelFg)

    /** 获取面板前景色（次要） */
    @ColorInt
    fun panelFgVariant(view: View): Int = get(view, UiColorTokens.panelFgVariant)

    @ColorInt
    fun panelFgVariant(context: Context): Int = get(context, UiColorTokens.panelFgVariant)

    /** 获取错误色 */
    @ColorInt
    fun error(view: View): Int = get(view, UiColorTokens.error)

    @ColorInt
    fun error(context: Context): Int = get(context, UiColorTokens.error)

    /** 获取主强调色 */
    @ColorInt
    fun primary(view: View): Int = get(view, UiColorTokens.primary)

    @ColorInt
    fun primary(context: Context): Int = get(context, UiColorTokens.primary)

    /** 获取次要强调色 */
    @ColorInt
    fun secondary(view: View): Int = get(view, UiColorTokens.secondary)

    @ColorInt
    fun secondary(context: Context): Int = get(context, UiColorTokens.secondary)

    /** 获取悬浮球图标色 */
    @ColorInt
    fun floatingIcon(view: View): Int = get(view, UiColorTokens.floatingIcon)

    @ColorInt
    fun floatingIcon(context: Context): Int = get(context, UiColorTokens.floatingIcon)

    /** 获取选中背景色 */
    @ColorInt
    fun selectedBg(view: View): Int = get(view, UiColorTokens.selectedBg)

    @ColorInt
    fun selectedBg(context: Context): Int = get(context, UiColorTokens.selectedBg)
}
