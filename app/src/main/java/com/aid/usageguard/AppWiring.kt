package com.aid.usageguard

import android.content.Context
import com.aid.usageguard.agent.runtime.AgentLoop
import com.aid.usageguard.data.Container
import com.aid.usageguard.domain.behavior.BehaviorAnalyzer
import com.aid.usageguard.work.AgentScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 依赖注入点：把 agent.runtime / domain.behavior / work 里的真实实现接到 Container。
 *
 * 单独一个文件是为了并行开发时由 Lead 统一收口依赖装配，
 * 避免各模块直接互相 new 造成循环依赖。
 */
object AppWiring {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 默认白名单候选：通讯、办公、支付等「限制会严重影响生活」的应用 */
    private val WHITELIST_CANDIDATES = listOf(
        "com.tencent.mm",                 // 微信
        "com.tencent.mobileqq",           // QQ
        "com.tencent.wework",             // 企业微信
        "com.alibaba.android.rimet",      // 钉钉
        "com.eg.android.AlipayGphone",    // 支付宝
        "com.tencent.wetype",             // 微信输入法
        "com.google.android.dialer",
        "com.android.dialer",
    )

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true

            // LLM Agent（Tool Calling）
            Container.attachAgent(
                AgentLoop(
                    context = context.applicationContext,
                    settings = Container.settings,
                    usage = Container.usage,
                    behavior = Container.behavior,
                    restrictions = Container.restrictions,
                    eventSource = Container.usageSource,
                    messageDao = Container.db.agentMessageDao(),
                    reports = Container.reports,
                ),
            )

            // 本地行为分析器
            Container.attachAnalyzer(
                BehaviorAnalyzer(
                    usage = Container.usage,
                    settings = Container.settings,
                ),
            )

            // WorkManager 定时任务（使用同步 / 异常分析 / 日报 / 周报）
            runCatching { AgentScheduler.ensureScheduled(context.applicationContext) }

            // 首次启用「默认限制」：把通讯/办公类应用预置进白名单，避免误伤
            scope.launch {
                runCatching {
                    val installed = Container.usageSource.launcherVisibleApps().map { it.first }.toSet()
                    Container.settings.seedAllowlistOnce(
                        WHITELIST_CANDIDATES.filter { it in installed },
                    )
                }
            }
        }
    }
}
