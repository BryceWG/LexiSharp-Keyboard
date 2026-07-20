package com.brycewg.asrkb.clipboard

/**
 * 测试用 Clipboard Port：内存剪贴板 + 可注入失败。
 */
class FakeSystemClipboardPort(
    override var actor: SystemClipboardActor = SystemClipboardActor.DIRECT
) : SystemClipboardPort {
    var text: String? = null
    var isSensitive: Boolean = false
    var writeSucceeds: Boolean = true
    var readSucceeds: Boolean = true

    val writeHistory = mutableListOf<String>()
    var observeStarted: Boolean = false
        private set
    var observeStartCount: Int = 0
        private set

    private var observer: (() -> Unit)? = null

    override fun readText(): ClipboardTextRead? {
        if (!readSucceeds) return null
        val value = text?.takeIf { it.isNotEmpty() } ?: return null
        return ClipboardTextRead(text = value, isSensitive = isSensitive)
    }

    override fun writeText(text: String): Boolean {
        writeHistory += text
        if (!writeSucceeds) return false
        this.text = text
        // 自身写入不通知观察者（回声抑制）
        return true
    }

    override fun startObserving(onChanged: () -> Unit) {
        observer = onChanged
        observeStarted = true
        observeStartCount += 1
    }

    override fun stopObserving() {
        observer = null
        observeStarted = false
    }

    /** 模拟外部复制（非本 Port write） */
    fun emulateExternalChange(newText: String, sensitive: Boolean = false) {
        text = newText
        isSensitive = sensitive
        observer?.invoke()
    }
}
