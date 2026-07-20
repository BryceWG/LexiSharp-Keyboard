/**
 * SyncClipboard 引擎的系统剪贴板执行入口（可替换 seam）。
 *
 * 归属模块：clipboard
 */
package com.brycewg.asrkb.clipboard

/**
 * 一次成功的主剪贴板文本读取结果。
 *
 * @param text 非空文本
 * @param isSensitive 系统标记为敏感的剪贴板（如密码），自动上传应跳过
 */
data class ClipboardTextRead(
    val text: String,
    val isSensitive: Boolean = false
)

enum class SystemClipboardActor {
    /** 本进程直接调用 ClipboardManager（说点啥为 default IME 或前台焦点时） */
    DIRECT,

    /** 经 IME Bridge 在第三方输入法进程内读写 */
    BRIDGE,

    /** 当前无法获得可靠剪贴板特权 */
    UNAVAILABLE
}

/**
 * SyncClipboard 对系统剪贴板的唯一依赖面。
 *
 * 实现负责：读/写主剪贴板、可选变更订阅，以及主动写入时的回声抑制
 * （勿把自身 write 触发的变更通知给观察者，或保证引擎侧哈希抑制仍生效）。
 */
interface SystemClipboardPort {
    val actor: SystemClipboardActor

    fun readText(): ClipboardTextRead?

    /** @return 是否认为写入已成功提交给系统/桥接 */
    fun writeText(text: String): Boolean

    fun startObserving(onChanged: () -> Unit)

    fun stopObserving()
}
