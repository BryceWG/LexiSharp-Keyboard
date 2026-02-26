package com.brycewg.asrkb.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 将“目标文本”（可能以大块/很快更新）以打字机效果平滑输出到 UI。
 *
 * - 不阻塞上游流式读取：submit 仅更新目标文本引用；
 * - 动态加速以追赶积压，尽量避免 UI 落后太多；
 * - 默认在闲置一段时间后自动停止（也可显式 cancel）。
 */
class TypewriterTextAnimator(
    private val scope: CoroutineScope,
    private val onEmit: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val frameDelayMs: Long = 20L,
    private val idleStopDelayMs: Long = 600L,
    private val normalTargetFrames: Int = 24,
    private val rushTargetFrames: Int = 10,
    private val normalMaxStep: Int = 4,
    private val rushMaxStep: Int = 64,
) {
    companion object {
        private const val TAG = "TypewriterTextAnimator"
    }

    private val targetRef = AtomicReference("")
    private val running = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var emittedText: String = ""

    @Volatile
    private var rushMode: Boolean = false

    @Volatile
    private var lastTarget: String = ""

    @Volatile
    private var idleMs: Long = 0L

    fun submit(target: String, rush: Boolean = false) {
        if (rush) rushMode = true
        targetRef.set(target)
        startIfNeeded()
    }

    fun cancel() {
        job?.cancel()
    }

    fun currentText(): String = emittedText

    private fun startIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(dispatcher) {
            try {
                while (isActive) {
                    val frameStartMs = try {
                        SystemClock.uptimeMillis()
                    } catch (_: Throwable) {
                        0L
                    }
                    val target = targetRef.get()

                    if (target != lastTarget) {
                        lastTarget = target
                        idleMs = 0L
                    } else if (emittedText == target) {
                        idleMs += frameDelayMs
                    } else {
                        idleMs = 0L
                    }

                    // 闲置时自动停止，避免忘记 cancel 导致常驻循环
                    if (idleMs >= idleStopDelayMs && emittedText == target) break

                    if (target.isEmpty()) {
                        if (emittedText.isNotEmpty()) {
                            emittedText = ""
                            emitSafely(emittedText)
                        }
                        delayByFrame(frameStartMs)
                        continue
                    }

                    if (emittedText == target) {
                        delayByFrame(frameStartMs)
                        continue
                    }

                    // 目标文本出现回退/重写：直接同步到目标，避免反复退格造成闪烁
                    if (!target.startsWith(emittedText)) {
                        emittedText = target
                        emitSafely(emittedText)
                        delayByFrame(frameStartMs)
                        continue
                    }

                    val backlog = target.length - emittedText.length
                    val step = computeStep(backlog)
                    var nextEnd = (emittedText.length + step).coerceAtMost(target.length)
                    nextEnd = adjustSurrogateEndIndex(target, nextEnd)
                    val next = target.substring(0, nextEnd)

                    if (next != emittedText) {
                        emittedText = next
                        emitSafely(emittedText)
                    }

                    delayByFrame(frameStartMs)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "Typewriter loop failed", t)
            } finally {
                running.set(false)
                job = null
                idleMs = 0L
            }
        }
    }

    private suspend fun delayByFrame(frameStartMs: Long) {
        val spentMs = if (frameStartMs > 0L) {
            val nowMs = try {
                SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            if (nowMs >= frameStartMs) nowMs - frameStartMs else 0L
        } else {
            0L
        }
        val delayMs = (frameDelayMs - spentMs).coerceAtLeast(0L)
        delay(delayMs)
    }

    private fun emitSafely(text: String) {
        try {
            onEmit(text)
        } catch (t: Throwable) {
            Log.w(TAG, "Emit callback failed", t)
        }
    }

    private fun computeStep(backlog: Int): Int {
        val targetFrames = if (rushMode) rushTargetFrames else normalTargetFrames
        val maxStep = if (rushMode) rushMaxStep else normalMaxStep
        val frames = targetFrames.coerceAtLeast(1)
        val step = ((backlog + frames - 1) / frames).coerceAtLeast(1)
        return step.coerceAtMost(maxStep.coerceAtLeast(1))
    }

    private fun adjustSurrogateEndIndex(text: String, endExclusive: Int): Int {
        if (endExclusive <= 0 || endExclusive >= text.length) return endExclusive
        val prev = text[endExclusive - 1]
        if (Character.isHighSurrogate(prev)) {
            val next = text[endExclusive]
            if (Character.isLowSurrogate(next)) {
                return endExclusive + 1
            }
        }
        return endExclusive
    }
}
