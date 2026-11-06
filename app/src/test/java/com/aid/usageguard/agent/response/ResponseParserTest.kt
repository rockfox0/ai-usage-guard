package com.aid.usageguard.agent.response

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.9 Phase 4：结构化回复只接受白名单 block；任何非法内容整体回退 Markdown。 */
class ResponseParserTest {

    private val validJson = """
        {"title":"本周概览","blocks":[
          {"type":"statistic","title":"概览","items":[{"label":"日均","value":"180 分钟"},{"label":"较上周","value":"-10%"}]},
          {"type":"insight","title":"夜间使用偏高","severity":"medium","detail":"22 点后占 35%"},
          {"type":"recommendation","title":"设置夜间目标","detail":"22 点后只允许白名单","requires_confirmation":true},
          {"type":"comparison","title":"与上周对比","rows":[{"label":"日均","left":"180","right":"200"}]},
          {"type":"chart","chart":"daily_usage_7d","title":"最近 7 天"},
          {"type":"follow_up","label":"和上周对比","prompt":"帮我对比最近一周和上一周"}
        ]}
    """.trimIndent()

    @Test
    fun `合法结构可以解析出全部白名单类型`() {
        val response = ResponseParser.parse(validJson)
        assertNotNull(response)
        assertEquals("本周概览", response!!.title)
        assertEquals(6, response.blocks.size)
        assertTrue(response.blocks[0] is ResponseBlock.StatisticCard)
        assertTrue(response.blocks[1] is ResponseBlock.InsightCard)
        assertTrue(response.blocks[2] is ResponseBlock.RecommendationCard)
        assertTrue((response.blocks[2] as ResponseBlock.RecommendationCard).requiresConfirmation)
        assertTrue(response.blocks[3] is ResponseBlock.ComparisonBlock)
        assertTrue(response.blocks[4] is ResponseBlock.ChartBlock)
        assertTrue(response.blocks[5] is ResponseBlock.FollowUpAction)
    }

    @Test
    fun `未知 block 类型整体回退`() {
        val json = """{"blocks":[{"type":"text","text":"ok"},{"type":"hack","html":"<script>"}]}"""
        assertNull(ResponseParser.parse(json))
    }

    @Test
    fun `超过六块整体回退`() {
        val blocks = (1..7).joinToString(",") { """{"type":"text","text":"块$it"}""" }
        assertNull(ResponseParser.parse("""{"blocks":[$blocks]}"""))
    }

    @Test
    fun `空 blocks 或坏 JSON 返回 null`() {
        assertNull(ResponseParser.parse("""{"blocks":[]}"""))
        assertNull(ResponseParser.parse("not json"))
        assertNull(ResponseParser.parse("""{"title":"没有 blocks"}"""))
    }

    @Test
    fun `图表只允许白名单类型`() {
        assertNull(ResponseParser.parse("""{"blocks":[{"type":"chart","chart":"evil_chart","title":"x"}]}"""))
        assertNotNull(ResponseParser.parse("""{"blocks":[{"type":"chart","chart":"daily_usage_7d","title":"x"}]}"""))
    }

    @Test
    fun `extract 剥离 json 块并保留正文`() {
        val text = "本周日均 180 分钟，比上周少 10%。\n\n```json\n$validJson\n```"
        val extracted = ResponseParser.extract(text)
        assertNotNull(extracted)
        assertEquals("本周日均 180 分钟，比上周少 10%。", extracted!!.text)
        assertEquals(6, extracted.response.blocks.size)
    }

    @Test
    fun `无围栏的裸 JSON 也能解析（模型有时不加围栏）`() {
        val text = """本周日均 180 分钟。{"title":"概览","blocks":[{"type":"statistic","title":"概览","items":[{"label":"日均","value":"180 分钟"}]}]}"""
        val extracted = ResponseParser.extract(text)
        assertNotNull(extracted)
        assertEquals(1, extracted!!.response.blocks.size)
        assertEquals("本周日均 180 分钟。", extracted.text)
    }

    @Test
    fun `没有 json 块时不产生结构化回复`() {
        assertNull(ResponseParser.extract("今天用了 20 分钟。"))
    }

    @Test
    fun `json 块解析失败时不影响正文`() {
        val text = "正文内容。\n```json\n{\"blocks\":[{\"type\":\"unknown\"}]}\n```"
        assertNull(ResponseParser.extract(text))
    }

    @Test
    fun `对比行最多六行`() {
        val rows = (1..9).joinToString(",") { """{"label":"项$it","left":"1","right":"2"}""" }
        val response = ResponseParser.parse("""{"blocks":[{"type":"comparison","title":"对比","rows":[$rows]}]}""")
        assertNotNull(response)
        val block = response!!.blocks[0] as ResponseBlock.ComparisonBlock
        assertTrue(block.rows.size <= 6)
    }
}
