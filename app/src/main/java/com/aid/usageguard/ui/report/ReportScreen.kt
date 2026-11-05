package com.aid.usageguard.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.domain.model.ReportModel
import com.aid.usageguard.domain.score.TimeSlot
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.domain.score.WeekScore
import com.aid.usageguard.ui.MarkdownLiteText
import com.aid.usageguard.ui.vmFactory
import com.aid.usageguard.work.AgentScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用报告页：今日评分 / 本周总结 + 生成日报/周报 + 最新与历史报告（标注 AI / 本地来源）+ 清空。
 *
 * 说明：
 * - 根布局填满父容器且**不**再套 Theme（外壳已套 UsageGuardTheme），也不重复消费窗口 insets
 *   （由导航壳统一处理），自己用 LazyColumn 的 contentPadding 管理滚动内边距；
 * - 进来顺便调用一次 [AgentScheduler.ensureScheduled]，保证后台排队存在（幂等）。
 */
@Composable
fun ReportScreen() {
    val viewModel: ReportViewModel = viewModel(
        factory = vmFactory {
            ReportViewModel(Container.reports, Container.settings, Container.score)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AgentScheduler.ensureScheduled(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ReportHeader(
            loading = state.loading,
            deletable = state.history.isNotEmpty(),
            onRefresh = viewModel::refresh,
            onDelete = { confirmClear = true },
        )

        if (state.loading) {
            CenteredBox(modifier = Modifier.weight(1f)) { CircularProgressIndicator() }
        } else {
            val latestIds = setOfNotNull(state.latestDaily?.id, state.latestWeekly?.id)
            val earlier = state.history.filter { it.id !in latestIds }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---- 顶部两张评分卡（数据来自 Container.score）----
                item(key = "today_score") { TodayScoreCard(state.todayScore) }
                item(key = "week_score") { WeekScoreCard(state.weekScore) }

                // ---- 生成按钮 ----
                item(key = "generate_row") {
                    GenerateRow(
                        generating = state.generating,
                        onGenerateDaily = { viewModel.generate(ReportModel.TYPE_DAILY) },
                        onGenerateWeekly = { viewModel.generate(ReportModel.TYPE_WEEKLY) },
                    )
                }

                state.message?.let { message ->
                    item(key = "message") { HintText(message) }
                }
                if (!state.aiEnabled) {
                    item(key = "ai_off") { HintText("AI 分析已关闭，报告将由本地规则生成（可在设置里打开）。") }
                }

                // ---- 最新 / 历史报告（来源标签等与之前一致）----
                state.latestDaily?.let { report ->
                    item(key = "latest_daily_header") { SectionHeader("最新日报") }
                    item(key = "latest_daily") {
                        ReportCard(
                            report = report,
                            timeText = viewModel.formatTime(report.createdAt),
                            isExpanded = expanded[report.id] == true,
                            onToggle = { expanded[report.id] = expanded[report.id] != true },
                        )
                    }
                }
                state.latestWeekly?.let { report ->
                    item(key = "latest_weekly_header") { SectionHeader("最新周报") }
                    item(key = "latest_weekly") {
                        ReportCard(
                            report = report,
                            timeText = viewModel.formatTime(report.createdAt),
                            isExpanded = expanded[report.id] == true,
                            onToggle = { expanded[report.id] = expanded[report.id] != true },
                        )
                    }
                }
                if (earlier.isEmpty()) {
                    item(key = "no_reports") {
                        HintText(
                            "还没有生成过报告：点上面的「生成日报 / 生成周报」，" +
                                "AI 会结合今日评分与使用数据给出总结；AI 不可用时自动降级为本地规则。",
                        )
                    }
                } else {
                    item(key = "history_header") { SectionHeader("历史报告") }
                    items(earlier, key = { it.id }) { report ->
                        ReportCard(
                            report = report,
                            timeText = viewModel.formatTime(report.createdAt),
                            isExpanded = expanded[report.id] == true,
                            onToggle = { expanded[report.id] = expanded[report.id] != true },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部报告？") },
            text = { Text("历史日报与周报都会被删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.deleteAll()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------- 今日评分卡

@Composable
private fun TodayScoreCard(score: UsageScore?) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日评分",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (score != null) {
                    GradeChip(text = score.grade, accent = gradeColor(score.score))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (score == null) {
                HintText("还没有算出今天的评分：确认已授予「使用情况访问权限」，或稍后刷新。")
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = score.score.toString(),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor(score.score),
                    )
                    Text(
                        text = "分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(bottom = 2.dp)) {
                        Text(
                            text = score.headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = score.verdict,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            lineHeight = 18.sp,
                            maxLines = 3,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SlotDistribution(score)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "今日 ${fmtMinutes(score.totalMinutes)}（目标 ${score.targetMinutes} 分钟）" +
                        " · 深夜 ${fmtMinutes(score.lateNightMinutes)}" +
                        " · 最长连续 ${fmtMinutes(score.longestSessionMinutes)}" +
                        " · 打开 ${score.launchCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/** 时段分布横向条：按各时段分钟数占比着色，下面一行是时段 + 分钟 */
@Composable
private fun SlotDistribution(score: UsageScore) {
    val colors = MaterialTheme.colorScheme
    val visible = score.slots.filter { it.minutes > 0 }
    if (visible.isEmpty()) {
        Text(
            text = "今天还没有记录到前台使用，暂无时段分布。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        return
    }
    val total = visible.sumOf { it.minutes }.toFloat().coerceAtLeast(1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        visible.forEach { slot ->
            Box(
                modifier = Modifier
                    .weight((slot.minutes / total).coerceAtLeast(0.03f))
                    .fillMaxHeight()
                    .background(slotColor(slot.slot)),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        visible.forEach { slot ->
            Text(
                text = "${shortSlotLabel(slot.slot)} ${fmtMinutes(slot.minutes)}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------- 本周总结卡

@Composable
private fun WeekScoreCard(week: WeekScore?) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本周总结",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (week != null) {
                    TrendChip(week.trend)
                    Spacer(Modifier.width(6.dp))
                    GradeChip(text = week.grade, accent = gradeColor(week.averageScore))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (week == null) {
                HintText("本周总结暂不可用，稍后刷新重试。")
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = week.averageScore.toString(),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor(week.averageScore),
                    )
                    Text(
                        text = "分",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f).padding(bottom = 2.dp)) {
                        Text(
                            text = week.headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "日均 ${fmtMinutes(week.averageMinutes)}" +
                                " · 超目标 ${week.overTargetDays} 天" +
                                " · 深夜 ${week.lateNightDays} 天",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = week.verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
                week.bestDay?.let { best ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append("最好 ").append(UsageEventSource.dayLabel(best.date))
                                .append("（").append(best.score).append(" 分）")
                            week.worstDay?.let { worst ->
                                append(" · 最需留意 ").append(UsageEventSource.dayLabel(worst.date))
                                    .append("（").append(worst.score).append(" 分）")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 顶部区域

@Composable
private fun ReportHeader(
    loading: Boolean,
    deletable: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "使用报告",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "每一份报告都会标注来源：AI 分析 / 本地规则",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
        if (deletable) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "清空报告")
            }
        }
    }
}

@Composable
private fun GenerateRow(
    generating: String?,
    onGenerateDaily: () -> Unit,
    onGenerateWeekly: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GenerateButton(
            text = "生成日报",
            running = generating == ReportModel.TYPE_DAILY,
            enabled = generating == null,
            modifier = Modifier.weight(1f),
            onClick = onGenerateDaily,
        )
        GenerateButton(
            text = "生成周报",
            running = generating == ReportModel.TYPE_WEEKLY,
            enabled = generating == null,
            modifier = Modifier.weight(1f),
            onClick = onGenerateWeekly,
        )
    }
}

@Composable
private fun GenerateButton(
    text: String,
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
            Text("生成中…", maxLines = 1)
        } else {
            Text(text, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------- 报告卡片

@Composable
private fun ReportCard(
    report: ReportModel,
    timeText: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isAi = report.source == ReportViewModel.SOURCE_AI
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceChip(
                    text = if (isAi) "AI" else "本地",
                    container = if (isAi) colors.primaryContainer else colors.surfaceVariant,
                    content = if (isAi) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = ReportViewModel.typeLabel(report.reportType),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 报告正文走轻量 Markdown 渲染（标题/加粗/列表/代码），与 AI 助手页保持一致；
            // 折叠态仍用截断后的预览文本，展开态渲染完整正文
            MarkdownLiteText(
                text = if (isExpanded) report.content else previewOf(report.content),
                style = MaterialTheme.typography.bodyMedium,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                lineHeight = 21.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "数据日期 ${report.relatedDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (isExpanded) "收起" else "展开全文",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 评分等级徽标（优秀/良好/一般/偏多/超负荷） */
@Composable
private fun GradeChip(text: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.14f), contentColor = accent, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 一周趋势徽标（上升 / 下降 / 持平） */
@Composable
private fun TrendChip(trend: String) {
    val dark = isSystemInDarkTheme()
    val accent = when (trend) {
        "下降" -> if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        "上升" -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = accent.copy(alpha = 0.14f), contentColor = accent, shape = RoundedCornerShape(50)) {
        Text(
            text = "趋势 $trend",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 17.sp,
    )
}

@Composable
private fun CenteredBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------------- 工具

/** 分数 → 颜色：≥90 绿、≥75 主题色、≥60 琥珀、其余红 */
@Composable
private fun gradeColor(score: Int): Color {
    val dark = isSystemInDarkTheme()
    return when {
        score >= 90 -> if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        score >= 75 -> MaterialTheme.colorScheme.primary
        score >= 60 -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        else -> MaterialTheme.colorScheme.error
    }
}

private fun slotColor(slot: TimeSlot): Color = when (slot) {
    TimeSlot.EARLY -> Color(0xFF38BDF8)
    TimeSlot.MORNING -> Color(0xFF34D399)
    TimeSlot.AFTERNOON -> Color(0xFFFBBF24)
    TimeSlot.EVENING -> Color(0xFFA78BFA)
    TimeSlot.NIGHT -> Color(0xFFFB7185)
}

private fun shortSlotLabel(slot: TimeSlot): String = when (slot) {
    TimeSlot.EARLY -> "清晨"
    TimeSlot.MORNING -> "上午"
    TimeSlot.AFTERNOON -> "下午"
    TimeSlot.EVENING -> "晚上"
    TimeSlot.NIGHT -> "深夜"
}

private fun fmtMinutes(minutes: Long): String = when {
    minutes <= 0L -> "0 分钟"
    minutes < 60L -> "$minutes 分钟"
    minutes % 60L == 0L -> "${minutes / 60L} 小时"
    else -> "${minutes / 60L} 小时 ${minutes % 60L} 分钟"
}

/**
 * 折叠态预览：截断到合理长度与行数，避免长报告把列表撑爆。
 * （MarkdownLiteText 不暴露 maxLines，所以这里从文本侧限制）
 */
private fun previewOf(content: String): String {
    val trimmed = content.trim()
    val byChars = if (trimmed.length <= 320) trimmed else trimmed.take(320).trimEnd() + "…"
    val lines = byChars.lines()
    return if (lines.size <= 6) byChars else lines.take(6).joinToString("\n").trimEnd() + "\n…"
}
