package com.aid.usageguard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.ApplicationInfo
import com.aid.usageguard.agent.proactive.ProactiveAgent
import com.aid.usageguard.data.Container
import com.aid.usageguard.domain.monitor.MonitorEngine
import com.aid.usageguard.notify.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 真机验收用的调试入口（**只在 debug 构建生效**，release 直接 return）。
 *
 * adb 用法：
 *   adb shell am broadcast -a com.aid.usageguard.DEBUG_RUN_MONITORS
 *   adb shell am broadcast -a com.aid.usageguard.DEBUG_RUN_SYNC
 *   adb shell am broadcast -a com.aid.usageguard.DEBUG_RUN_PROACTIVE
 *
 * 为什么需要它：监控/主动分析是 15/60 分钟的周期任务，真机验收时不必等到周期点。
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val action = intent.action ?: return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                Container.init(appContext)
                when (action) {
                    ACTION_RUN_MONITORS -> {
                        val triggers = MonitorEngine(Container.usage, Container.monitors).evaluate()
                        if (triggers.isNotEmpty()) {
                            NotificationHelper.notifyMonitorTriggers(appContext, triggers)
                        }
                        Log.i(TAG, "debug monitors: ${triggers.size} trigger(s) ${triggers.map { it.message }}")
                    }

                    ACTION_RUN_SYNC -> {
                        val result = runCatching { Container.usage.refresh() }
                        Log.i(TAG, "debug sync: $result")
                    }

                    ACTION_RUN_MAINTENANCE -> {
                        val summary = Container.maintenance.prune()
                        Log.i(TAG, "debug maintenance: deleted=${summary.totalDeleted} $summary")
                    }

                    ACTION_RUN_PROACTIVE -> {
                        val insight = ProactiveAgent(
                            usage = Container.usage,
                            insights = Container.insights,
                            memory = Container.memory,
                            settings = Container.settings,
                        ).maybeGenerate()
                        if (insight != null) NotificationHelper.notifyProactiveInsight(appContext, insight)
                        Log.i(TAG, "debug proactive: ${insight?.title}")
                    }

                    else -> Log.w(TAG, "unknown debug action: $action")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "debug action failed: $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugReceiver"
        const val ACTION_RUN_MONITORS = "com.aid.usageguard.DEBUG_RUN_MONITORS"
        const val ACTION_RUN_SYNC = "com.aid.usageguard.DEBUG_RUN_SYNC"
        const val ACTION_RUN_PROACTIVE = "com.aid.usageguard.DEBUG_RUN_PROACTIVE"
        const val ACTION_RUN_MAINTENANCE = "com.aid.usageguard.DEBUG_RUN_MAINTENANCE"
    }
}
