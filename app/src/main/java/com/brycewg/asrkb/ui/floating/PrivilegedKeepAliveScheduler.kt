/**
 * Shizuku / root 增强保活的 Job 调度与一次性触发入口。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal object PrivilegedKeepAliveScheduler {

    private const val TAG = "PrivKeepAlive"
    private const val JOB_ID = 4103
    private const val PERIOD_MS = 15 * 60 * 1000L

    fun update(context: Context) {
        val prefs = Prefs(context)
        val enabled = prefs.floatingKeepAliveEnabled && prefs.floatingKeepAlivePrivilegedEnabled
        DebugLogManager.logPersistent(
            context,
            "keepalive",
            "scheduler_update",
            mapOf(
                "enabled" to enabled,
                "keepAlive" to prefs.floatingKeepAliveEnabled,
                "privileged" to prefs.floatingKeepAlivePrivilegedEnabled,
            ),
        )
        if (enabled) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, PrivilegedKeepAliveJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            // 确保在各种条件下都能执行，不受电量和设备空闲状态限制
            .setRequiresBatteryNotLow(false)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
            .build()

        val result = try {
            scheduler.schedule(info)
        } catch (t: Throwable) {
            Log.w(TAG, "schedule privileged keep-alive job failed", t)
            JobScheduler.RESULT_FAILURE
        }
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "schedule privileged keep-alive job not success: $result")
        }
        DebugLogManager.logPersistent(context, "keepalive", "scheduler_schedule", mapOf("result" to result))
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        try {
            scheduler.cancel(JOB_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel privileged keep-alive job failed", t)
        }
        DebugLogManager.logPersistent(context, "keepalive", "scheduler_cancel")
    }
}
