package com.brycewg.asrkb.ui.setup

/**
 * 一键设置流程的状态机定义
 *
 * 流程顺序：
 * 1. NotStarted -> EnablingIme: 用户未启用输入法，需要前往系统设置启用
 * 2. EnablingIme -> SelectingIme: 输入法已启用，但未选择为当前输入法
 * 3. SelectingIme -> RequestingPermissions: 输入法已选择，开始请求权限
 * 4. RequestingPermissions -> Completed: 所有必需权限已授予，设置完成
 *
 * 每个状态对应一个具体的操作，状态转换通过 advance() 方法触发
 */
sealed class SetupState {
    /**
     * 初始状态：未开始设置流程
     */
    data object NotStarted : SetupState()

    /**
     * 等待用户启用输入法
     * @property askedOnce 是否已经提示过用户（避免重复跳转）
     */
    data class EnablingIme(val askedOnce: Boolean = false) : SetupState()

    /**
     * 等待用户选择输入法为当前输入法
     * @property askedOnce 是否已经唤起过选择器
     * @property waitingSince 开始等待的时间戳（用于超时检测）
     */
    data class SelectingIme(
        val askedOnce: Boolean = false,
        val waitingSince: Long = 0L,
    ) : SetupState()

    /**
     * 请求权限阶段（麦克风、悬浮窗、通知、无障碍）
     * @property askedMic 是否已请求过麦克风权限
     * @property askedOverlay 是否已请求过悬浮窗权限
     * @property askedNotif 是否已请求过通知权限
     * @property askedA11y 是否已请求过无障碍权限
     */
    data class RequestingPermissions(
        val askedMic: Boolean = false,
        val askedOverlay: Boolean = false,
        val askedNotif: Boolean = false,
        val askedA11y: Boolean = false,
    ) : SetupState()

    /**
     * 设置完成
     */
    data object Completed : SetupState()

    /**
     * 设置被中止（用户超时未操作或主动取消）
     * @property reason 中止原因
     */
    data class Aborted(val reason: String) : SetupState()
}
