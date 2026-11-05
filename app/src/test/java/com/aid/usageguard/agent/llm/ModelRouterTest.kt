package com.aid.usageguard.agent.llm

import com.aid.usageguard.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.9 Phase 7：模型路由——简单请求用快模型，规划/子 Agent/报告用配置的强模型。 */
class ModelRouterTest {

    private val fastOnly = AppSettings(aiModel = "deepseek-flash", aiPlannerModel = "")
    private val withPlanner = AppSettings(aiModel = "deepseek-flash", aiPlannerModel = "deepseek-reasoner")

    @Test
    fun `未配置规划模型时全部回退默认模型`() {
        ModelRouter.Route.entries.forEach { route ->
            assertEquals("deepseek-flash", ModelRouter.modelFor(route, fastOnly))
        }
    }

    @Test
    fun `规划 子 Agent 报告使用规划模型`() {
        assertEquals("deepseek-reasoner", ModelRouter.modelFor(ModelRouter.Route.PLANNER, withPlanner))
        assertEquals("deepseek-reasoner", ModelRouter.modelFor(ModelRouter.Route.SUB_AGENT, withPlanner))
        assertEquals("deepseek-reasoner", ModelRouter.modelFor(ModelRouter.Route.REPORT, withPlanner))
    }

    @Test
    fun `直答保持快模型`() {
        assertEquals("deepseek-flash", ModelRouter.modelFor(ModelRouter.Route.CHAT_DIRECT, withPlanner))
        assertEquals("deepseek-flash", ModelRouter.modelFor(ModelRouter.Route.CHAT_ANALYSIS, withPlanner))
    }

    @Test
    fun `模型名空白时回退默认`() {
        val blank = AppSettings(aiModel = "  ", aiPlannerModel = "   ")
        assertEquals(AppSettings.DEFAULT_MODEL, ModelRouter.modelFor(ModelRouter.Route.PLANNER, blank))
        assertEquals(AppSettings.DEFAULT_MODEL, ModelRouter.modelFor(ModelRouter.Route.CHAT_DIRECT, blank))
    }
}
