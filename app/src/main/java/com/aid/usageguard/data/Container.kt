package com.aid.usageguard.data

import android.content.Context
import com.aid.usageguard.agent.contract.AgentRuntime
import com.aid.usageguard.agent.contract.StubAgentRuntime
import com.aid.usageguard.data.db.AppDatabase
import com.aid.usageguard.data.repo.BehaviorRepository
import com.aid.usageguard.data.repo.ReportRepository
import com.aid.usageguard.data.repo.ScoreRepository
import com.aid.usageguard.data.repo.UsageRepository
import com.aid.usageguard.data.settings.SettingsRepository
import com.aid.usageguard.domain.behavior.BehaviorAnalyzerContract
import com.aid.usageguard.restriction.AccessibilityRestrictionController
import com.aid.usageguard.restriction.AppRestrictionController

/**
 * 极简依赖容器（模块化单体，不引入 DI 框架）。
 * 所有 Repository 都是无状态/单例，UI 与 WorkManager 共用同一份实例。
 */
object Container {

    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var usageSource: UsageEventSource
        private set
    lateinit var ingester: UsageIngester
        private set
    lateinit var usage: UsageRepository
        private set
    lateinit var behavior: BehaviorRepository
        private set
    lateinit var reports: ReportRepository
        private set
    lateinit var score: ScoreRepository
        private set
    lateinit var restrictions: AppRestrictionController
        private set

    /** Agent 实现由 agent.runtime 包在 App.onCreate 里注入 */
    @Volatile
    var agentRuntime: AgentRuntime = StubAgentRuntime()
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            db = AppDatabase.get(appContext)
            settings = SettingsRepository(appContext)
            usageSource = UsageEventSource(appContext)
            ingester = UsageIngester(appContext, usageSource, db)
            usage = UsageRepository(appContext, usageSource, ingester, db)
            behavior = BehaviorRepository(db)
            reports = ReportRepository(db, usage, behavior, settings) { agentRuntime }
            score = ScoreRepository(db, usage, settings)
            restrictions = AccessibilityRestrictionController(db)
            initialized = true
        }
    }

    /** 接入真实 Agent 运行时（AgentLoop） */
    fun attachAgent(runtime: AgentRuntime) {
        agentRuntime = runtime
    }

    /** 接入真实行为分析器（BehaviorAnalyzer） */
    fun attachAnalyzer(analyzer: BehaviorAnalyzerContract) {
        behavior.analyzer = analyzer
    }
}
