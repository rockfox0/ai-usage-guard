package com.aid.usageguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（不引第三方库）：
 * - `# 标题` → 加粗
 * - `**加粗**` → 加粗
 * - `` `代码` `` → 等宽
 * - `- ` / `* ` 列表 → 圆点
 * 用于 AI 助手回答与报告正文，避免把 `**` 原样显示给用户。
 */
@Composable
fun MarkdownLiteText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val annotated = remember(text) { buildMarkdownLite(text) }
    Text(
        text = annotated,
        modifier = modifier,
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = color,
        style = style.copy(fontSize = fontSize, lineHeight = lineHeight),
    )
}

private fun buildMarkdownLite(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.replace("\r\n", "\n").split('\n')
    var inTable = false
    var tableHeaderPending = false
    lines.forEachIndexed { index, raw ->
        var line = raw.trimEnd()
        val trimmed = line.trim()

        // Markdown 表格：| a | b | —— 手机窄屏不适合表格，渲染成「a · b」并加粗表头
        if (trimmed.length > 2 && trimmed.startsWith("|") && trimmed.endsWith("|")) {
            val cells = trimmed.trim('|').split('|').map { it.trim() }
            val isSeparator = cells.isNotEmpty() && cells.all { Regex("^:?-{2,}:?$").matches(it) }
            if (isSeparator) {
                inTable = true
                tableHeaderPending = false
                return@forEachIndexed
            }
            if (!inTable) {
                inTable = true
                tableHeaderPending = true
            }
            val joined = cells.filter { it.isNotEmpty() }.joinToString(" · ")
            if (tableHeaderPending) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(joined) }
                tableHeaderPending = false
            } else {
                appendInline(joined)
            }
            if (index != lines.lastIndex) append('\n')
            return@forEachIndexed
        }
        inTable = false

        val headingMatch = Regex("^#{1,6}\\s+").find(line)
        val isHeading = headingMatch != null
        if (headingMatch != null) line = line.substring(headingMatch.range.last + 1)

        val bulletMatch = Regex("^[-*]\\s+").find(line)
        if (bulletMatch != null) line = "• " + line.substring(bulletMatch.range.last + 1)

        if (isHeading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(line) }
        } else {
            appendInline(line)
        }
        if (index != lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInline(line: String) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(line.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(line[i])
                    i++
                }
            }

            line[i] == '`' -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }

            else -> {
                append(line[i])
                i++
            }
        }
    }
}
