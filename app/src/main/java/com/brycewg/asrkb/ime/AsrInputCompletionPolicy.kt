/**
 * 语音输入完成后的后续动作判定。
 *
 * 「输入完成」指后处理结束且 composing 预览已固化。
 *
 * 归属模块：ime
 */
internal object AsrInputCompletionPolicy {
    fun shouldNotifyInputSolidified(
        committedText: String,
        sessionStillRunning: Boolean
    ): Boolean = committedText.isNotEmpty() && !sessionStillRunning
}
