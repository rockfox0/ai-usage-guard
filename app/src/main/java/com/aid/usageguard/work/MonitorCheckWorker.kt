package com.aid.usageguard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aid.usageguard.data.Container
import com.aid.usageguard.domain.monitor.MonitorEngine
import com.aid.usageguard.notify.NotificationHelper

/**
 * 自定义监控检查（v1.9 Phase 5，周期 15 分钟）：
 * 只读本地已采集数据判断阈值，**不调用模型**；同一规则同一天只通知一次。
 */
class MonitorCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            WorkerSupport.prepare(applicationContext)
            val engine = MonitorEngine(Container.usage, Container.monitors)
            val triggers = engine.evaluate()
            if (triggers.isNotEmpty()) {
                runCatching {
                    NotificationHelper.notifyMonitorTriggers(applicationContext, triggers)
                }
            }
            Log.i(TAG, "monitor check: ${triggers.size} trigger(s)")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "monitor check failed (attempt=$runAttemptCount)", t)
            WorkerSupport.retryOrGiveUp(runAttemptCount)
        }
    }

    companion object {
        private const val TAG = "MonitorCheckWorker"
    }
}
