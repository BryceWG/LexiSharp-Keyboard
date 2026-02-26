/**
 * 通过 JobScheduler 周期性拉起前台保活服务，降低部分系统“后台不可启动前台服务”限制带来的失败概率。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class PrivilegedKeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "PrivKeepAlive"
    }

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false
        DebugLogManager.logPersistent(this, "keepalive", "job_start")
        if (!PrivilegedKeepAliveStarter.tryAcquirePrivilegedStartWindow(this, "job")) {
            jobFinished(params, false)
            return false
        }
        scope.launch {
            try {
                val prefs = Prefs(this@PrivilegedKeepAliveJobService)
                if (!prefs.floatingKeepAliveEnabled || !prefs.floatingKeepAlivePrivilegedEnabled) {
                    DebugLogManager.logPersistent(this@PrivilegedKeepAliveJobService, "keepalive", "job_skip", mapOf("reason" to "pref_disabled"))
                    jobFinished(params, false)
                    return@launch
                }

                val result = PrivilegedKeepAliveStarter.tryStartKeepAliveByShizuku(this@PrivilegedKeepAliveJobService)
                    ?: PrivilegedKeepAliveStarter.tryStartKeepAliveByRoot(this@PrivilegedKeepAliveJobService)
                    ?: PrivilegedKeepAliveStarter.startKeepAliveFallback(this@PrivilegedKeepAliveJobService)

                if (!result.ok) {
                    Log.w(TAG, "start keep-alive by ${result.method} failed: exit=${result.exitCode}, err=${result.stderr}")
                }
                DebugLogManager.logPersistent(
                    this@PrivilegedKeepAliveJobService,
                    "keepalive",
                    "job_result",
                    mapOf("method" to result.method.name.lowercase(), "ok" to result.ok, "exit" to result.exitCode),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "privileged keep-alive job failed", t)
                DebugLogManager.logPersistent(this@PrivilegedKeepAliveJobService, "keepalive", "job_error", mapOf("msg" to t.message))
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.coroutineContext.cancelChildren()
        DebugLogManager.logPersistent(this, "keepalive", "job_stop")
        return true
    }

    override fun onDestroy() {
        // 取消整个协程作用域，而不仅仅是子协程
        scope.cancel()
        super.onDestroy()
    }
}
