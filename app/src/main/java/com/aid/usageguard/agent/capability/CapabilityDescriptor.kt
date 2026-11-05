package com.aid.usageguard.agent.capability

/**
 * 一个「能力」= 完成某类用户目标所需的一组工具 + 使用示例。
 *
 * 设计目的（v1.9 Phase 1）：
 * - 不再把 34 个工具 schema 一次性发给模型，而是先按能力分片；
 * - 简单请求本地路由直接命中能力；复杂请求由模型调用 `discover_capabilities` 搜索、`load_capability` 加载；
 * - 能力只是「工具分组 + 描述」，不改变 TaskScope / 草案确认 / 预算等安全边界。
 */
data class CapabilityDescriptor(
    /** 稳定的能力 id（snake_case），用于 load_capability 参数与日志 */
    val id: String,
    /** 给模型/用户看的中文名 */
    val name: String,
    /** 这个能力能做什么（写给模型，尽量短） */
    val description: String,
    /** 分类：query / analysis / control / config / report */
    val category: String,
    /** 该能力包含的工具名（必须都在 ToolRegistry 中注册） */
    val toolNames: List<String>,
    /** 典型用户说法，用于本地路由与模型发现 */
    val examples: List<String>,
    /** 关键词（中文/英文，命中即认为相关） */
    val keywords: List<String>,
)
