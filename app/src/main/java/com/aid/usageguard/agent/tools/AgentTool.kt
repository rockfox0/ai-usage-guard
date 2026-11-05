package com.aid.usageguard.agent.tools

import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.data.repo.UsageRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个本地工具。
 *
 * 安全约束：
 * - 只能做「查询本地聚合数据」或「生成建议」这类确定性动作；
 * - 不允许执行任意代码、Shell、文件写入、系统设置修改；
 * - 模型给出的参数必须先经过 [ToolExecutor][com.aid.usageguard.agent.executor.ToolExecutor]
 *   的 JSON Schema 校验才会进入 [execute]。
 */
interface AgentTool {
    /** 工具名（发给模型，必须与 ToolRegistry 白名单一致） */
    val name: String

    /** 给模型看的能力说明 */
    val description: String

    /** JSON Schema（`type=object`），用于向模型声明参数并做本地校验 */
    val parameters: JSONObject

    /**
     * 执行工具并返回结构化结果。
     * 可预期的业务错误（例如应用不存在）抛 [ToolArgumentException]，
     * 执行器会把它转成错误信息回传模型，让模型修正后重试。
     */
    suspend fun execute(arguments: JSONObject): JSONObject
}

/** 工具参数 / 业务错误：会被回传给模型，而不是让整个任务失败。 */
class ToolArgumentException(message: String) : Exception(message)

/** 构造简单 JSON Schema 的小工具，避免每个工具手写一堆 JSONObject。 */
object JsonSchema {

    fun objectSchema(
        properties: List<Pair<String, JSONObject>>,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply { properties.forEach { (name, schema) -> put(name, schema) } },
        )
        if (required.isNotEmpty()) put("required", JSONArray(required))
        put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String>? = null): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        if (!enum.isNullOrEmpty()) put("enum", JSONArray(enum))
    }

    fun integer(
        description: String,
        minimum: Int? = null,
        maximum: Int? = null,
    ): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }

    fun boolean(description: String): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
    }

    /** 字符串数组（例如 AI 关注点 focus） */
    fun stringArray(description: String, enum: List<String>? = null): JSONObject = JSONObject().apply {
        put("type", "array")
        put("description", description)
        put(
            "items",
            JSONObject().apply {
                put("type", "string")
                if (!enum.isNullOrEmpty()) put("enum", JSONArray(enum))
            },
        )
    }
}

/** 数值/时间的统一格式化（工具输出给模型看，要求中英文混排也紧凑）。 */
internal object ToolFormat {

    fun minutes(value: Long): String {
        if (value <= 0L) return "0 分钟"
        val hours = value / 60
        val rest = value % 60
        return when {
            hours <= 0L -> "$rest 分钟"
            rest <= 0L -> "$hours 小时"
            else -> "$hours 小时 $rest 分钟"
        }
    }

    fun timeOfDay(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }

    fun clock(hour: Int): String = String.format(Locale.US, "%02d:00", hour.coerceIn(0, 23))
}

/** 解析模型给的「包名或应用名」：先当包名，再按应用名匹配（今日排行 + 桌面应用）。 */
internal suspend fun resolvePackageName(
    usage: UsageRepository,
    source: UsageEventSource,
    input: String,
): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // 1. 直接就是已安装包名
    if (source.appNameOf(trimmed) != null) return trimmed

    // 2. 按应用名匹配
    val candidates = LinkedHashMap<String, String>()
    runCatching { usage.todayRanking(30) }.getOrDefault(emptyList()).forEach { app ->
        candidates[app.packageName] = app.appName
    }
    runCatching { source.launcherVisibleApps() }.getOrDefault(emptyList()).forEach { (pkg, name) ->
        candidates.putIfAbsent(pkg, name)
    }
    candidates.entries.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.let { return it.key }
    candidates.entries.firstOrNull { it.value.contains(trimmed, ignoreCase = true) }?.let { return it.key }
    return null
}

private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")

/** 读取日期参数；缺省用 [default]；格式不对抛 [ToolArgumentException]（会回传模型）。 */
internal fun JSONObject.dateArg(default: String, key: String = "date"): String {
    val raw = stringArg(key) ?: return default
    if (!DATE_PATTERN.matches(raw)) {
        throw ToolArgumentException("参数 $key 需要 yyyy-MM-dd 格式，例如 $default")
    }
    return raw
}

/** 可选字符串参数：空白/"null"/null 都视为缺省。 */
internal fun JSONObject.stringArg(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && it != "null" }
}

/** 可选整数参数：只有真正的 JSON 数字才认。 */
internal fun JSONObject.intArg(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return (opt(key) as? Number)?.toInt()
}

/** 可选布尔参数。 */
internal fun JSONObject.boolArg(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> null
    }
}

/**
 * 可选字符串参数，但**允许显式传空串**（用于清空昵称 / 自定义要求）。
 * 返回 null 表示未提供；返回 "" 表示明确要求清空。
 */
internal fun JSONObject.optionalRawString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return if (value == "null") null else value
}

/** 可选字符串数组参数（例如 focus）；返回 null 表示未提供，空列表表示明确清空。 */
internal fun JSONObject.arrayArg(key: String): List<String>? {
    if (!has(key) || isNull(key)) return null
    val array = optJSONArray(key) ?: return null
    val values = ArrayList<String>(array.length())
    for (index in 0 until array.length()) {
        val element = array.opt(index)
        if (element !is String) {
            throw ToolArgumentException("参数 $key 需要字符串数组，第 ${index + 1} 项不是字符串")
        }
        val value = element.trim()
        if (value.isNotEmpty()) values.add(value)
    }
    return values
}
