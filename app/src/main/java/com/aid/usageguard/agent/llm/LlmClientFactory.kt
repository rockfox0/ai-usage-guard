package com.aid.usageguard.agent.llm

import com.aid.usageguard.data.settings.AppSettings

/**
 * 按用户配置（格式 / BaseURL / Key / 模型）创建 LLM 客户端。
 * 没有填 Key 时返回 null，由调用方走本地降级或返回「未配置」。
 */
object LlmClientFactory {

    /**
     * @param modelOverride 覆盖模型名（v1.9 Phase 7 模型路由）；null 用设置里的默认模型
     */
    fun create(settings: AppSettings, modelOverride: String? = null): LlmClient? {
        val apiKey = settings.aiApiKey.trim()
        if (apiKey.isEmpty()) return null
        val base = normalizeApiBase(settings.aiBaseUrl)
        if (base.isEmpty()) return null
        val model = modelOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: settings.aiModel.trim().ifBlank { AppSettings.DEFAULT_MODEL }

        return when (settings.aiFormat.trim().lowercase()) {
            AppSettings.FORMAT_ANTHROPIC -> AnthropicClient(
                modelName = model,
                baseUrl = base,
                apiKey = apiKey,
            )

            else -> OpenAiCompatibleClient(
                modelName = model,
                baseUrl = base,
                apiKey = apiKey,
            )
        }
    }

    /** 界面展示用：`deepseek-chat · OpenAI 兼容` */
    fun describe(settings: AppSettings): String {
        val model = settings.aiModel.trim().ifBlank { AppSettings.DEFAULT_MODEL }
        val format = if (settings.aiFormat.trim().lowercase() == AppSettings.FORMAT_ANTHROPIC) {
            "Anthropic 兼容"
        } else {
            "OpenAI 兼容"
        }
        return "$model · $format"
    }
}
