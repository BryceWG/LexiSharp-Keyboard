package com.brycewg.asrkb.asr

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地模型加载协调器：
 * - 全局同一时刻只允许一个“加载任务”执行（跨 vendor 串行化）。
 * - 相同 key 的请求去重（不会新建加载，也不会打断当前加载）。
 * - 不同 key 的请求会取消当前加载，并以新 key 重新加载。
 */
internal object LocalModelLoadCoordinator {
    private const val TAG = "LocalModelLoadCoordinator"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()

    private var runningKey: String? = null
    private var runningJob: Job? = null
    private var pendingKey: String? = null
    private var pendingJob: Job? = null

    fun request(key: String, loader: suspend () -> Unit) {
        scope.launch {
            val currentJob = coroutineContext[Job]
            if (currentJob == null) return@launch

            var shouldRunNow = false
            var jobToCancel: Job? = null
            var pendingToCancel: Job? = null
            val dedup = stateMutex.withLock {
                val running = runningJob
                val pending = pendingJob
                val sameRunning = running?.isActive == true && runningKey == key
                val samePending = pending?.isActive == true && pendingKey == key
                if (sameRunning || samePending) return@withLock true

                pendingToCancel = pending?.takeIf { it.isActive && it != currentJob }
                pendingKey = key
                pendingJob = currentJob

                jobToCancel = running?.takeIf { !it.isCompleted }
                if (jobToCancel == null) {
                    runningKey = key
                    runningJob = currentJob
                    pendingKey = null
                    pendingJob = null
                    shouldRunNow = true
                }
                false
            }

            if (dedup) return@launch

            pendingToCancel?.cancel()
            if (!shouldRunNow) {
                jobToCancel?.cancelAndJoin()
                val promoted = stateMutex.withLock {
                    if (pendingJob != currentJob || pendingKey != key) return@withLock false
                    runningKey = key
                    runningJob = currentJob
                    pendingKey = null
                    pendingJob = null
                    true
                }
                if (!promoted) return@launch
            }

            try {
                loader()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "Local model load failed (key=$key)", t)
            } finally {
                stateMutex.withLock {
                    if (runningJob == currentJob) {
                        runningJob = null
                        runningKey = null
                    }
                    if (pendingJob == currentJob) {
                        pendingJob = null
                        pendingKey = null
                    }
                }
            }
        }
    }

    fun cancel() {
        scope.launch {
            val (running, pending) = stateMutex.withLock {
                runningKey = null
                pendingKey = null
                val running = runningJob
                val pending = pendingJob
                pendingJob = null
                running to pending
            }

            pending?.cancelAndJoin()
            running?.cancelAndJoin()
            stateMutex.withLock {
                if (runningJob == running && (running == null || running.isCompleted)) {
                    runningJob = null
                    runningKey = null
                }
                if (pendingJob == pending && (pending == null || pending.isCompleted)) {
                    pendingJob = null
                    pendingKey = null
                }
            }
        }
    }
}
