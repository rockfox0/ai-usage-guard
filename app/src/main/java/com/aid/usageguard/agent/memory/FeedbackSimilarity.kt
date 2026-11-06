package com.aid.usageguard.agent.memory

/**
 * 反馈相似度（v1.9，纯函数）：
 * 判断「新建议」是否与用户**拒绝过**的建议高度相似，避免原样重复被拒方案。
 *
 * 规则保守：只按「建议文本里的关键词」与拒绝理由做包含匹配，长度 <3 的词忽略，
 * 宁可漏判（再尝试一次）也不误伤（把正常建议当成被拒方案而不敢提）。
 */
object FeedbackSimilarity {

    private const val MIN_TOKEN_LENGTH = 3

    fun isSimilar(suggestion: String, rejectedReasons: List<String>): Boolean {
        if (suggestion.isBlank() || rejectedReasons.isEmpty()) return false
        val tokens = suggestion.lowercase()
            .split(Regex("[\\s，,。.；;：:、()（）\\[\\]【】]+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }
        if (tokens.isEmpty()) return false
        return rejectedReasons.any { reason ->
            val text = reason.lowercase()
            tokens.any { token -> text.contains(token) }
        }
    }

    /** 给提示词用的一句话（无被拒记录时返回空串） */
    fun summariseRejected(rejectedReasons: List<String>, limit: Int = 4): String {
        val cleaned = rejectedReasons.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return ""
        return "用户拒绝过：" + cleaned.take(limit).joinToString("；")
    }
}
