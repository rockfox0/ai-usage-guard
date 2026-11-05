package com.aid.usageguard.service

/**
 * 实时提示的本地文案兜底：AI 未配置 / 调用失败 / 关闭「用 AI 生成」时使用。
 * 保持一句话、口语化、不说教。
 */
object LocalHints {

    private val TEMPLATES = listOf(
        "已经用「{app}」{minutes} 分钟了，要不要起来走两步？",
        "「{app}」开了 {minutes} 分钟，眼睛和脖子都该休息一下。",
        "提醒一下：你已经连续用「{app}」{minutes} 分钟。",
        "{minutes} 分钟过去了，现在这件事是你本来想做的吗？",
        "看一眼远处 20 秒，再回来继续「{app}」。",
        "要不要给自己定个「再 10 分钟就停」的小目标？",
    )

    fun forApp(appName: String, minutes: Long, intervalMinutes: Int): String {
        val template = TEMPLATES[(minutes / intervalMinutes.coerceAtLeast(1)).toInt() % TEMPLATES.size]
        return template
            .replace("{app}", appName)
            .replace("{minutes}", minutes.toString())
    }
}
