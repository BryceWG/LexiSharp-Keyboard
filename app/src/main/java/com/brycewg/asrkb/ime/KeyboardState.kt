package com.brycewg.asrkb.ime

/**
 * 键盘状态机：使用 sealed class 明确定义各种状态，替代布尔标志位。
 * 状态转换将变得明确且易于跟踪。
 */
sealed class KeyboardState {
    /**
     * 空闲状态：键盘就绪，等待用户操作
     */
    data object Idle : KeyboardState()

    /**
     * 普通听写模式：正在录音
     * @param partialText 当前的中间识别结果（用于实时预览）
     * @param committedStableLen 已提交的稳定长度（分段录音使用）
     */
    data class Listening(
        val partialText: String? = null,
        val committedStableLen: Int = 0
    ) : KeyboardState()

    /**
     * ASR 识别处理中：录音已停止，等待最终识别结果
     */
    data object Processing : KeyboardState()

    /**
     * AI 后处理中：正在使用 LLM 优化识别结果
     * @param rawText 原始识别文本
     */
    data class AiProcessing(val rawText: String) : KeyboardState()

    /**
     * AI 编辑模式：正在录音捕获编辑指令
     * @param targetIsSelection 编辑目标是否为选中文本
     * @param targetText 编辑目标文本（选中文本或最后一次 ASR 提交）
     * @param instruction 当前录音的编辑指令（部分结果）
     */
    data class AiEditListening(
        val targetIsSelection: Boolean,
        val targetText: String,
        val instruction: String? = null
    ) : KeyboardState()

    /**
     * AI 编辑执行中：正在使用 LLM 执行编辑指令
     * @param targetIsSelection 编辑目标是否为选中文本
     * @param targetText 原始文本
     * @param instruction 编辑指令
     */
    data class AiEditProcessing(
        val targetIsSelection: Boolean,
        val targetText: String,
        val instruction: String
    ) : KeyboardState()
}

/**
 * 撤销快照：记录操作前后的文本状态，用于全局撤销
 */
data class UndoSnapshot(
    val beforeCursor: CharSequence,
    val afterCursor: CharSequence
)

/**
 * AI 后处理提交记录：记录原始识别结果和 AI 优化后的结果，用于撤销
 */
data class PostprocCommit(
    val processed: String,
    val raw: String
)

/**
 * 剪贴板预览状态
 */
data class ClipboardPreview(
    val fullText: String,
    val displaySnippet: String
)

/**
 * 键盘会话上下文：保存会话相关的数据
 */
data class KeyboardSessionContext(
    val lastAsrCommitText: String? = null,
    val lastPostprocCommit: PostprocCommit? = null,
    val undoSnapshot: UndoSnapshot? = null,
    val lastRequestDurationMs: Long? = null,
    val clipboardPreview: ClipboardPreview? = null
)
