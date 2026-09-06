package com.brycewg.asrkb.ime

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag
import java.lang.ref.WeakReference

private const val STREAMING_PREVIEW_ANCHOR_MAX = 128
private const val STREAMING_PREVIEW_VERIFY_MAX = 10_000

/**
 * InputConnection 辅助类：封装所有与 InputConnection 的交互，统一异常处理和日志记录。
 *
 * 由于输入法宿主应用千差万别，InputConnection 的行为并不可靠。
 * 为所有操作添加详细日志，以便在特定应用中功能静默失败时能够快速定位问题。
 */
class InputConnectionHelper(private val tag: String = "InputConnectionHelper") {
    private data class StreamingPreviewOwnership(
        val inputConnection: WeakReference<InputConnection>,
        val anchorBeforeCursor: String,
        val text: String
    )

    private var streamingPreviewOwnership: StreamingPreviewOwnership? = null

    @Volatile
    var diagHostPkg: String = ""

    @Volatile
    var diagSession: Long = 0L

    /**
     * 提交文本到输入框
     * @param ic InputConnection 实例
     * @param text 要提交的文本
     * @param newCursorPosition 新的光标位置
     * @return 操作是否成功
     */
    fun commitText(ic: InputConnection?, text: CharSequence, newCursorPosition: Int = 1): Boolean {
        if (ic == null) {
            Log.w(tag, "commitText: InputConnection is null")
            return false
        }
        val ok = try {
            ic.commitText(text, newCursorPosition)
            true
        } catch (e: Throwable) {
            Log.e(tag, "commitText failed: text='$text', pos=$newCursorPosition", e)
            false
        }
        logCommitOrFinish(
            event = "commit",
            ic = ic,
            text = text.toString(),
            ok = ok
        )
        return ok
    }

    /**
     * 设置普通组合文本。
     * 需要由识别终态安全替换的 ASR 流式预览应使用 [setStreamingPreview]。
     */
    fun setComposingText(
        ic: InputConnection?,
        text: CharSequence,
        newCursorPosition: Int = 1
    ): Boolean {
        if (ic == null) {
            Log.w(tag, "setComposingText: InputConnection is null")
            return false
        }

        return try {
            ic.setComposingText(text, newCursorPosition)
        } catch (e: Throwable) {
            Log.e(tag, "setComposingText failed: text='$text', pos=$newCursorPosition", e)
            false
        }
    }

    /** 写入并记录 ASR 流式预览，供识别终态安全替换。 */
    fun setStreamingPreview(ic: InputConnection?, text: CharSequence): Boolean {
        if (ic == null) return setComposingText(null, text)
        val previewText = text.toString()
        val previous = streamingPreviewOwnership?.takeIf { it.inputConnection.get() === ic }
        val prevPreview = previous?.text
        val sameText = prevPreview == previewText
        val anchor = previous?.anchorBeforeCursor
            ?: captureStreamingPreviewAnchor(ic, previewText.length)
        val success = setComposingText(ic, text)
        val replacedExpected = anchor?.plus(previewText)
            ?.takeIf { it.length <= STREAMING_PREVIEW_VERIFY_MAX }
        val appendedText = previous?.text?.plus(previewText)
        val appendedExpected = if (anchor != null && appendedText != null) {
            (anchor + appendedText).takeIf { it.length <= STREAMING_PREVIEW_VERIFY_MAX }
        } else {
            null
        }
        val verifyLength = maxOf(replacedExpected?.length ?: 0, appendedExpected?.length ?: 0)
        val actual = if (success && previewText.isNotEmpty() && verifyLength > 0) {
            ic.getTextBeforeCursor(verifyLength, 0)?.toString()
        } else {
            null
        }
        val verify = when {
            actual != null && actual == replacedExpected -> "replaced"
            actual != null && actual == appendedExpected -> "appended"
            else -> "unknown"
        }
        val ownedText = when (verify) {
            "replaced" -> previewText
            "appended" -> appendedText
            else -> null
        }
        streamingPreviewOwnership = if (success && anchor != null && ownedText != null) {
            StreamingPreviewOwnership(WeakReference(ic), anchor, ownedText)
        } else {
            null
        }
        logPreviewWrite(
            ic = ic,
            previewText = previewText,
            prevPreview = prevPreview,
            sameText = sameText,
            verify = verify,
            ownedText = ownedText,
            success = success
        )
        return success
    }

    /**
     * 仅在归属可确认时移除流式预览，再写入识别终态。
     *
     * 先结束 composing，再删除并重写，是为了替换被宿主静默固化为普通正文的流式预览。
     */
    fun replaceStreamingPreview(ic: InputConnection?, replacement: CharSequence): Boolean {
        if (ic == null) return setComposingText(null, replacement)
        val hadOwnership = streamingPreviewOwnership?.takeIf { it.inputConnection.get() === ic }
        val ownership = hadOwnership?.takeIf { matchesOwnedPreview(ic, it) }
        val ownedFp = ownership?.text?.let { StreamingPreviewDiag.fingerprint(it) }
        streamingPreviewOwnership = null
        if (ownership == null) {
            val ok = setComposingText(ic, replacement)
            logPreviewReplace(
                ic = ic,
                replacement = replacement.toString(),
                path = "ownership_miss",
                ok = ok,
                ownedFp = hadOwnership?.text?.let { StreamingPreviewDiag.fingerprint(it) }
            )
            return ok
        }

        val removed = try {
            ic.finishComposingText() && ic.deleteSurroundingText(ownership.text.length, 0)
        } catch (e: Throwable) {
            Log.e(tag, "replaceStreamingPreview failed to remove preview", e)
            false
        }
        if (!removed) {
            Log.w(tag, "replaceStreamingPreview could not remove owned preview; using composing replacement")
            val ok = setComposingText(ic, replacement)
            logPreviewReplace(
                ic = ic,
                replacement = replacement.toString(),
                path = "delete_failed",
                ok = ok,
                ownedFp = ownedFp
            )
            return ok
        }

        if (setComposingText(ic, replacement)) {
            logPreviewReplace(
                ic = ic,
                replacement = replacement.toString(),
                path = "owned_delete",
                ok = true,
                ownedFp = ownedFp
            )
            return true
        }
        restoreStreamingPreview(ic, ownership)
        logPreviewReplace(
            ic = ic,
            replacement = replacement.toString(),
            path = "restore",
            ok = false,
            ownedFp = ownedFp
        )
        return false
    }

    /**
     * 完成组合文本（将预览文本固化为最终提交）
     */
    fun finishComposingText(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "finishComposingText: InputConnection is null")
            return false
        }
        val ownedFp = streamingPreviewOwnership
            ?.takeIf { it.inputConnection.get() === ic }
            ?.text
            ?.let { StreamingPreviewDiag.fingerprint(it) }
        return try {
            val ok = ic.finishComposingText()
            logCommitOrFinish(
                event = "finish",
                ic = ic,
                text = streamingPreviewOwnership
                    ?.takeIf { it.inputConnection.get() === ic }
                    ?.text,
                ok = ok,
                extra = mapOf("owned" to ownedFp)
            )
            ok
        } catch (e: Throwable) {
            Log.e(tag, "finishComposingText failed", e)
            false
        } finally {
            clearStreamingPreviewOwnership(ic)
        }
    }

    /** 当前输入连接是否仍持有可复用的流式预览归属。 */
    fun hasStreamingPreviewOwnershipFor(ic: InputConnection?): Boolean {
        if (ic == null) return false
        return streamingPreviewOwnership?.inputConnection?.get() === ic
    }

    /** 清理输入会话结束后不可再复用的流式预览归属。 */
    fun resetStreamingPreviewState(reason: String) {
        val hadOwnership = streamingPreviewOwnership != null
        streamingPreviewOwnership = null
        logDiagBase(
            "preview_ownership_reset",
            diagBase() + mapOf(
                "reason" to reason,
                "hadOwnership" to hadOwnership
            )
        )
    }

    /**
     * 删除光标周围的文本
     * @param beforeLength 删除光标前的字符数
     * @param afterLength 删除光标后的字符数
     */
    fun deleteSurroundingText(ic: InputConnection?, beforeLength: Int, afterLength: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "deleteSurroundingText: InputConnection is null")
            return false
        }
        return try {
            ic.deleteSurroundingText(beforeLength, afterLength)
            true
        } catch (e: Throwable) {
            Log.e(tag, "deleteSurroundingText failed: before=$beforeLength, after=$afterLength", e)
            false
        }
    }

    private fun matchesOwnedPreview(
        ic: InputConnection,
        ownership: StreamingPreviewOwnership
    ): Boolean {
        val expected = ownership.anchorBeforeCursor + ownership.text
        return ic.getTextBeforeCursor(expected.length, 0)?.toString() == expected
    }

    private fun captureStreamingPreviewAnchor(ic: InputConnection, previewLength: Int): String? {
        val maxLength = minOf(
            STREAMING_PREVIEW_ANCHOR_MAX,
            STREAMING_PREVIEW_VERIFY_MAX - previewLength
        )
        if (maxLength <= 0) return null
        return ic.getTextBeforeCursor(maxLength, 0)?.toString()
    }

    private fun restoreStreamingPreview(
        ic: InputConnection,
        ownership: StreamingPreviewOwnership
    ) {
        try {
            if (!ic.setComposingText(ownership.text, 1)) return
            if (matchesOwnedPreview(ic, ownership)) {
                streamingPreviewOwnership = ownership
            }
        } catch (e: Throwable) {
            Log.e(tag, "replaceStreamingPreview failed to restore preview", e)
        }
    }

    private fun clearStreamingPreviewOwnership(ic: InputConnection) {
        if (streamingPreviewOwnership?.inputConnection?.get() === ic) {
            streamingPreviewOwnership = null
        }
    }

    /**
     * 获取光标前的文本
     * @param n 最多获取的字符数
     * @param flags 标志位
     * @return 光标前的文本，失败时返回 null
     */
    fun getTextBeforeCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextBeforeCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextBeforeCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextBeforeCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    /**
     * 获取光标后的文本
     */
    fun getTextAfterCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextAfterCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextAfterCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextAfterCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    /**
     * 获取当前选中的文本
     */
    fun getSelectedText(ic: InputConnection?, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getSelectedText: InputConnection is null")
            return null
        }
        return try {
            ic.getSelectedText(flags)
        } catch (e: Throwable) {
            Log.e(tag, "getSelectedText failed: flags=$flags", e)
            null
        }
    }

    /**
     * 设置选区范围
     */
    fun setSelection(ic: InputConnection?, start: Int, end: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "setSelection: InputConnection is null")
            return false
        }
        return try {
            ic.setSelection(start, end)
            true
        } catch (e: Throwable) {
            Log.e(tag, "setSelection failed: start=$start, end=$end", e)
            false
        }
    }

    /**
     * 选择全部文本（基于上下文长度估算）
     */
    fun selectAll(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "selectAll: InputConnection is null")
            return false
        }
        return try {
            val beforeLen = getTextBeforeCursor(ic, 10000)?.length ?: 0
            val afterLen = getTextAfterCursor(ic, 10000)?.length ?: 0
            ic.setSelection(0, beforeLen + afterLen)
            true
        } catch (e: Throwable) {
            Log.e(tag, "selectAll failed", e)
            false
        }
    }

    /**
     * 获取撤销快照：捕获当前光标前后的文本
     */
    fun captureUndoSnapshot(ic: InputConnection?): UndoSnapshot? {
        if (ic == null) {
            Log.w(tag, "captureUndoSnapshot: InputConnection is null")
            return null
        }
        return try {
            val before = ic.getTextBeforeCursor(10000, 0)
            val after = ic.getTextAfterCursor(10000, 0)
            if (before != null && after != null) {
                UndoSnapshot(before, after)
            } else {
                Log.w(tag, "captureUndoSnapshot: before or after is null")
                null
            }
        } catch (e: Throwable) {
            Log.e(tag, "captureUndoSnapshot failed", e)
            null
        }
    }

    /**
     * 恢复撤销快照
     */
    fun restoreSnapshot(ic: InputConnection?, snapshot: UndoSnapshot): Boolean {
        if (ic == null) {
            Log.w(tag, "restoreSnapshot: InputConnection is null")
            return false
        }
        return try {
            val before = snapshot.beforeCursor.toString()
            val after = snapshot.afterCursor.toString()

            ic.beginBatchEdit()
            // 清空当前内容
            val currBeforeLen = getTextBeforeCursor(ic, 10000)?.length ?: 0
            val currAfterLen = getTextAfterCursor(ic, 10000)?.length ?: 0
            ic.deleteSurroundingText(currBeforeLen, currAfterLen)

            // 恢复快照内容
            ic.commitText(before + after, 1)
            val sel = before.length
            ic.setSelection(sel, sel)
            ic.finishComposingText()
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "restoreSnapshot failed", e)
            false
        }
    }

    /**
     * 发送回车键或执行编辑器动作
     *
     * 根据 EditorInfo 的 imeOptions 判断行为：
     * - 对于 IME_ACTION_SEND/GO/SEARCH/DONE/NEXT/PREVIOUS，执行 performEditorAction
     * - 对于多行输入框或 IME_ACTION_NONE/UNSPECIFIED，发送普通回车键
     *
     * @param ic InputConnection 实例
     * @param editorInfo 当前编辑器信息，用于判断应执行的动作
     */
    fun sendEnter(ic: InputConnection?, editorInfo: EditorInfo? = null): Boolean {
        if (ic == null) {
            Log.w(tag, "sendEnter: InputConnection is null")
            return false
        }
        return try {
            val imeOptions = editorInfo?.imeOptions ?: 0
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val isMultiLine = (editorInfo?.inputType ?: 0) and
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val flagNoEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

            // 根据 action 类型和输入框特性决定行为
            val shouldPerformAction = when {
                // 多行且设置了 NO_ENTER_ACTION 标志，发送普通回车
                isMultiLine && flagNoEnterAction -> false
                // 特定的 action 类型需要执行 performEditorAction
                action == EditorInfo.IME_ACTION_SEND ||
                    action == EditorInfo.IME_ACTION_GO ||
                    action == EditorInfo.IME_ACTION_SEARCH ||
                    action == EditorInfo.IME_ACTION_DONE ||
                    action == EditorInfo.IME_ACTION_NEXT ||
                    action == EditorInfo.IME_ACTION_PREVIOUS -> true
                // 其他情况发送普通回车
                else -> false
            }

            if (shouldPerformAction) {
                Log.d(tag, "sendEnter: performEditorAction($action)")
                ic.performEditorAction(action)
            } else {
                Log.d(tag, "sendEnter: sendKeyEvent(KEYCODE_ENTER)")
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendEnter failed", e)
            false
        }
    }

    /**
     * 发送退格键（删除光标前的一个字符）
     */
    fun sendBackspace(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "sendBackspace: InputConnection is null")
            return false
        }

        return try {
            // 先结束任何悬浮的 composing，避免目标应用将退格当作"撤销整段组合文本"
            ic.finishComposingText()

            // 若有选区，按退格语义应删除选区内容
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
                return true
            }

            // 对部分应用，使用硬件 DEL 事件能更稳定地保持光标位置
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendBackspace failed, trying fallback", e)
            // 兜底：删除光标前一个字符
            try {
                ic.deleteSurroundingText(1, 0)
                true
            } catch (e2: Throwable) {
                Log.e(tag, "sendBackspace fallback failed", e2)
                false
            }
        }
    }

    /**
     * 清空所有文本（使用快照精确删除，避免误删）
     */
    fun clearAllText(ic: InputConnection?, snapshot: UndoSnapshot?): Boolean {
        if (ic == null) {
            Log.w(tag, "clearAllText: InputConnection is null")
            return false
        }

        if (snapshot == null) {
            Log.w(tag, "clearAllText: snapshot is null, using max deletion")
            return try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
                true
            } catch (e: Throwable) {
                Log.e(tag, "clearAllText max deletion failed", e)
                false
            }
        }

        return try {
            val beforeLen = snapshot.beforeCursor.length
            val afterLen = snapshot.afterCursor.length
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.finishComposingText()
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "clearAllText with snapshot failed", e)
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
                true
            } catch (e2: Throwable) {
                Log.e(tag, "clearAllText fallback failed", e2)
                false
            }
        }
    }

    /**
     * 替换指定文本：在光标前查找 oldText 并替换为 newText
     * @return 是否成功替换
     */
    fun replaceText(ic: InputConnection?, oldText: String, newText: String): Boolean {
        if (ic == null) {
            Log.w(tag, "replaceText: InputConnection is null")
            return false
        }
        if (oldText.isEmpty()) {
            Log.w(tag, "replaceText: oldText is empty")
            return false
        }

        return try {
            val before = getTextBeforeCursor(ic, 10000)?.toString()
            val after = getTextAfterCursor(ic, 10000)?.toString()

            ic.beginBatchEdit()
            var replaced = false

            // 尝试在光标前查找并替换
            if (!before.isNullOrEmpty() && before.endsWith(oldText)) {
                ic.deleteSurroundingText(oldText.length, 0)
                ic.commitText(newText, 1)
                replaced = true
            }
            // 尝试在光标后查找并替换
            else if (!after.isNullOrEmpty() && after.startsWith(oldText)) {
                ic.deleteSurroundingText(0, oldText.length)
                ic.commitText(newText, 1)
                replaced = true
            }
            // 尝试在整个上下文中查找
            else if (before != null && after != null) {
                val combined = before + after
                val pos = combined.lastIndexOf(oldText)
                if (pos >= 0) {
                    val end = pos + oldText.length
                    ic.setSelection(end, end)
                    // 重新获取光标位置后的文本
                    val before2 = getTextBeforeCursor(ic, 10000)?.toString()
                    if (!before2.isNullOrEmpty() && before2.endsWith(oldText)) {
                        ic.deleteSurroundingText(oldText.length, 0)
                        ic.commitText(newText, 1)
                        replaced = true
                    }
                }
            }

            ic.finishComposingText()
            ic.endBatchEdit()

            if (!replaced) {
                Log.w(tag, "replaceText: text not found in context, old='$oldText'")
            }
            replaced
        } catch (e: Throwable) {
            Log.e(tag, "replaceText failed: old='$oldText', new='$newText'", e)
            false
        }
    }

    private fun logPreviewWrite(
        ic: InputConnection,
        previewText: String,
        prevPreview: String?,
        sameText: Boolean,
        verify: String,
        ownedText: String?,
        success: Boolean
    ) {
        val recording = DebugLogManager.isRecording()
        if (!recording) return

        val snapshot = if (verify == "unknown") {
            StreamingPreviewDiag.editorSnapshot(ic)
        } else {
            emptyMap()
        }
        val composingLen = snapshot["composingLen"] as? Int
        val mismatch = verify == "appended" || (verify == "unknown" && composingLen == 0)
        val data = diagBase() + snapshot + StreamingPreviewDiag.shape(prevPreview, previewText) + mapOf(
            "sameText" to sameText,
            "verify" to verify,
            "ok" to success,
            "owned" to ownedText?.let { StreamingPreviewDiag.fingerprint(it) },
            "ic" to ic.javaClass.simpleName
        )
        if (mismatch) {
            logDiagBase("preview_host_mismatch", data)
        }
        logDiag("preview_write", data)
        StreamingPreviewDiag.maybeWarnDup("insert", "preview_write", prevPreview, previewText, diagBase())
    }

    private fun logPreviewReplace(
        ic: InputConnection,
        replacement: String,
        path: String,
        ok: Boolean,
        ownedFp: String?
    ) {
        val snapshot = if (DebugLogManager.isRecording()) {
            StreamingPreviewDiag.editorSnapshot(ic)
        } else {
            emptyMap()
        }
        logDiagBase(
            "preview_replace",
            diagBase() + snapshot + mapOf(
                "path" to path,
                "ok" to ok,
                "owned" to ownedFp,
                "next" to StreamingPreviewDiag.fingerprint(replacement),
                "ic" to ic.javaClass.simpleName
            )
        )
    }

    private fun logCommitOrFinish(
        event: String,
        ic: InputConnection,
        text: String?,
        ok: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        if (!DebugLogManager.isRecording()) return
        val snapshot = StreamingPreviewDiag.editorSnapshot(ic)
        logDiag(
            event,
            diagBase() + snapshot + extra + mapOf(
                "ok" to ok,
                "fp" to StreamingPreviewDiag.fingerprint(text),
                "ic" to ic.javaClass.simpleName
            )
        )
    }

    private fun diagBase(): Map<String, Any?> = mapOf(
        "pkg" to diagHostPkg,
        "session" to diagSession
    )

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.log(category = "insert", event = event, data = data)
        } catch (_: Throwable) { }
    }

    private fun logDiagBase(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.logBase(category = "insert", event = event, data = data)
        } catch (_: Throwable) { }
    }
}
