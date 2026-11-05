package com.aid.usageguard.agent.llm

import com.aid.usageguard.data.settings.AppSettings

/**
 * 模型路由（v1.9 Phase 7）。
 *
 * 不把「换模型」当成提升智能的唯一手段：多数请求仍用默认模型（快、便宜）；
 * 只有规划、复杂分析、子 Agent、报告这类「一次决定质量」的请求，才使用用户配置的
 * `aiPlannerModel`（为空时回退默认模型）。
 */
object ModelRouter {

    enum class Route {
        /** 直答 / 简单工具调用 */
        CHAT_DIRECT,

        /** 多步骤分析、目标型任务的主循环 */
        CHAT_ANALYSIS,

        /** 任务规划（一次 JSON 输出） */
        PLANNER,

        /** 只读专家子 Agent */
        SUB_AGENT,

        /** 日报 / 周报 */
        REPORT,
    }

    /** 返回实际使用的 model 名（永远非空） */
    fun modelFor(route: Route, settings: AppSettings): String {
        val fast = settings.aiModel.trim().ifBlank { AppSettings.DEFAULT_MODEL }
        val strong = settings.aiPlannerModel.trim().ifBlank { fast }
        return when (route) {
            Route.CHAT_DIRECT, Route.CHAT_ANALYSIS -> fast
            Route.PLANNER, Route.SUB_AGENT, Route.REPORT -> strong
        }
    }

    /** 界面/日志展示 */
    fun describe(route: Route, settings: AppSettings): String =
        "${modelFor(route, settings)}（${route.name.lowercase()}）"
}
