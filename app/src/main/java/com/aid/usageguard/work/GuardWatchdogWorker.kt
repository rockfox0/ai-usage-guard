package com.aid.usageguard.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.service.AccessibilityRestorer
import com.aid.usageguard.service.KeepAliveService

/**
 * 保活看门狗（v1.8.3）：每 15 分钟检查一次无障碍开关，
 * 被 OEM 清理后尝试自动恢复，并重新拉起前台保活服务。
 */
class GuardWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        KeepAliveService.start(applicationContext)
        val result = runCatching { AccessibilityRestorer.tryRestore(applicationContext) }
            .getOrElse { AccessibilityRestorer.Result.FAILED }
        return when (result) {
            AccessibilityRestorer.Result.RESTORED -> Result.success()
            AccessibilityRestorer.Result.ALREADY_ON -> Result.success()
            AccessibilityRestorer.Result.NO_PERMISSION -> Result.success()
            AccessibilityRestorer.Result.FAILED -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ug_guard_watchdog"
    }
}
