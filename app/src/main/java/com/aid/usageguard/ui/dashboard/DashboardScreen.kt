package com.aid.usageguard.ui.dashboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.domain.model.AppUsageSummary
import com.aid.usageguard.domain.model.DailyTotal
import com.aid.usageguard.domain.score.TimeSlot
import com.aid.usageguard.domain.score.UsageScore
import com.aid.usageguard.ui.vmFactory
import kotlin.math.roundToInt

/**
 * 首页 Dashboard。
 * 数据全部来自 [Container.usage] / [Container.behavior] / [Container.settings] / [Container.agentRuntime]，
 * 由 [DashboardViewModel] 先 refresh() 再查询，本文件只负责渲染与系统跳转。
 *
 * 注意：根导航壳已经用 Scaffold 处理系统栏 insets 并套好 UsageGuardTheme，
 * 这里不再套主题、不再使用 Scaffold，只填满可用区域。
 */
@Composable
fun DashboardScreen(
    onOpenAppDetail: (String) -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenBehavior: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DashboardViewModel = viewModel(
        factory = vmFactory {
            DashboardViewModel(
                usage = Container.usage,
                scoreRepository = Container.score,
                behavior = Container.behavior,
                settings = Container.settings,
                agentRuntime = Container.agentRuntime,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次进入页面都确保有一次采集+查询（ViewModel 内部已做并发去重）
    LaunchedEffect(Unit) { viewModel.refresh() }
    var accessibilityOn by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // 从系统设置页返回时：重新读取无障碍状态并刷新数据
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityOn = isAccessibilityServiceEnabled(context)
        viewModel.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            refreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (state.loading) {
                LoadingState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { message ->
                        item(key = "error") {
                            ErrorCard(message = message, onRetry = { viewModel.refresh() })
                        }
                    }
                    if (!state.hasUsageAccess) {
                        item(key = "usage_permission") {
                            UsagePermissionCard(
                                onOpen = { openSystemSettings(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                            )
                        }
                    }
                    item(key = "today") { TodayCard(state) }
                    item(key = "score") { TodayScoreCard(state.score) }
                    if (state.hasUsageAccess && state.isEmpty && state.score == null) {
                        item(key = "empty") {
                            InfoCard(text = "今天还没有记录到任何使用时长。点右上角刷新按钮重新统计。")
                        }
                    }
                    item(key = "week") { WeekTrendCard(state.weekTotals, state.weekAverageMinutes) }
                    item(key = "top_apps") {
                        TopAppsCard(
                            apps = state.topApps,
                            totalMinutes = state.todayMinutes,
                            onOpenAppDetail = onOpenAppDetail,
                        )
                    }
                    item(key = "distribution") { DistributionCard(state.distribution) }
                    item(key = "behavior") {
                        BehaviorCard(count = state.anomalyCount, onOpenBehavior = onOpenBehavior)
                    }
                    item(key = "report") {
                        EntryCard(
                            title = "AI 今日日报",
                            subtitle = if (state.agentConfigured) {
                                "由已配置的 LLM 生成今日使用总结"
                            } else {
                                "未配置 LLM 时使用本地规则生成总结"
                            },
                            action = "查看日报",
                            onClick = onOpenReport,
                        )
                    }
                    item(key = "assistant") {
                        EntryCard(
                            title = "AI 助手",
                            subtitle = "就你的使用习惯直接提问",
                            action = "开始对话",
                            onClick = onOpenAssistant,
                        )
                    }
                    item(key = "agent_status") { AgentStatusCard(configured = state.agentConfigured) }
                    if (!accessibilityOn) {
                        item(key = "accessibility") {
                            AccessibilityCard(
                                onOpen = {
                                    openSystemSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 顶部与通用件

@Composable
private fun DashboardHeader(refreshing: Boolean, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "使用总览",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
            )
            Text(
                text = UsageEventSource.todayDate(),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(text = trailing, fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 自绘进度条：不依赖任何图表库，颜色跟随 MaterialTheme */
@Composable
private fun FractionBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = MaterialTheme.colorScheme
    val safe = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(colors.surfaceVariant),
    ) {
        if (safe > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safe)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height / 2))
                    .background(color),
            )
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = colors.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                fontSize = 12.sp,
                color = colors.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) {
                Text("重试", fontSize = 12.sp, color = colors.onErrorContainer)
            }
        }
    }
}

// ---------------------------------------------------------------- 今日总览

@Composable
private fun TodayCard(state: DashboardUiState) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("今日使用", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatMinutes(state.todayMinutes),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.isOverGoal) colors.error else colors.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FractionBar(
                fraction = state.goalProgress,
                height = 10.dp,
                color = if (state.isOverGoal) colors.error else colors.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.targetMinutes > 0L) {
                        "目标 ${formatMinutes(state.targetMinutes)} · 完成 ${(state.goalProgress * 100).roundToInt()}%"
                    } else {
                        "尚未设置每日使用目标"
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.isOverGoal) {
                    Text("已超目标", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.error)
                }
            }
            if (state.targetMinutes > 0L || state.focusWindowActive) {
                Spacer(modifier = Modifier.height(4.dp))
                val quotaText = when {
                    state.isOverGoal && state.afterBudgetOnlyWhitelist ->
                        "额度已用完，仅白名单可用"
                    state.isOverGoal -> "今日额度已用完"
                    state.targetMinutes > 0L ->
                        "额度剩余 ${formatMinutes((state.targetMinutes - state.todayMinutes).coerceAtLeast(0L))}"
                    else -> ""
                }
                Text(
                    text = when {
                        state.focusWindowActive && quotaText.isNotBlank() -> "$quotaText · 专注时段生效中"
                        state.focusWindowActive -> "专注时段生效中（仅白名单可用）"
                        else -> quotaText
                    },
                    fontSize = 11.sp,
                    color = if (state.isOverGoal) colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 今日评分

/**
 * 今日评分卡片：大号分数 + 等级 chip + 个性化词条 + 判断句 + 5 段时段分布。
 * 数据来自 [UsageScore]（ViewModel 里由 Container.score.todayScore() 读取）。
 */
@Composable
private fun TodayScoreCard(score: UsageScore?) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(title = "今日评分")
            Spacer(modifier = Modifier.height(10.dp))
            if (score == null || score.totalMinutes <= 0L) {
                Text(
                    text = "今天还没有使用记录，先正常用一会儿",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
            } else {
                val accent = scoreGradeColor(score.grade)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${score.score}",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    GradeChip(grade = score.grade, color = accent)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "目标完成 ${(score.targetRatio * 100).roundToInt()}%",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (score.headline.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = score.headline,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                }
                if (score.verdict.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = score.verdict,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SlotBars(score)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "深夜 ${score.lateNightMinutes} 分钟 · 最长连续 ${score.longestSessionMinutes} 分钟 · 打开 ${score.launchCount} 次",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 等级 chip：优秀/良好/一般/偏多/超负荷，按分数语义着色 */
@Composable
private fun GradeChip(grade: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = grade,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/** 5 段时段分布：横向条形按当日总时长占比，深夜用警示色 */
@Composable
private fun SlotBars(score: UsageScore) {
    val colors = MaterialTheme.colorScheme
    val total = score.slots.sumOf { it.minutes }.coerceAtLeast(1L)
    score.slots.forEach { item ->
        val isNight = item.slot == TimeSlot.NIGHT
        val fraction = item.minutes.toFloat() / total.toFloat()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.slot.label.substringBefore(' '),
                fontSize = 10.sp,
                color = if (isNight && item.minutes > 0L) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
                FractionBar(
                    fraction = fraction,
                    height = 6.dp,
                    color = if (isNight) colors.error else colors.primary.copy(alpha = 0.70f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${item.minutes}",
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 7 天趋势

@Composable
private fun WeekTrendCard(week: List<DailyTotal>, averageMinutes: Long) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle(
                title = "最近 7 天趋势",
                trailing = if (week.isEmpty()) null else "日均 ${formatMinutes(averageMinutes)}",
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (week.isEmpty()) {
                EmptyLine("暂无趋势数据")
            } else {
                WeekBarChart(week = week)
            }
        }
    }
}

/** 自绘柱状图：7 根柱子按当日分钟数等比缩放，今天用主色高亮 */
@Composable
private fun WeekBarChart(week: List<DailyTotal>) {
    val colors = MaterialTheme.colorScheme
    val maxMinutes = maxOf(week.maxOfOrNull { it.minutes } ?: 0L, 1L)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEachIndexed { index, day ->
            val isToday = index == week.lastIndex
            val fraction = (day.minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
            val barHeight = maxOf(2.dp, 92.dp * fraction)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (day.minutes > 0L) day.minutes.toString() else "",
                    fontSize = 9.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier.height(92.dp).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.56f)
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (isToday) colors.primary else colors.secondary.copy(alpha = 0.65f)),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = UsageEventSource.dayLabel(day.date),
                    fontSize = 9.sp,
                    color = if (isToday) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Top 5 / 分布

@Composable
private fun TopAppsCard(
    apps: List<AppUsageSummary>,
    totalMinutes: Long,
    onOpenAppDetail: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("今日使用最长的 5 个 App")
            Spacer(modifier = Modifier.height(6.dp))
            if (apps.isEmpty()) {
                EmptyLine("今天还没有使用记录")
            } else {
                apps.forEachIndexed { index, app ->
                    val share = if (totalMinutes > 0L) app.minutes.toFloat() / totalMinutes.toFloat() else 0f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenAppDetail(app.packageName) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(colors.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.primary,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = app.appName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${app.minutes} 分钟 · ${(share * 100).roundToInt()}%",
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            FractionBar(fraction = share, height = 6.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DistributionCard(shares: List<UsageShare>) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("今日使用分布")
            Spacer(modifier = Modifier.height(6.dp))
            if (shares.isEmpty()) {
                EmptyLine("暂无分布数据")
            } else {
                shares.forEach { share ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = share.label,
                            fontSize = 12.sp,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            FractionBar(
                                fraction = share.share,
                                height = 6.dp,
                                color = colors.secondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(share.share * 100).roundToInt()}%",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 行为 / 入口 / Agent

@Composable
private fun BehaviorCard(count: Int, onOpenBehavior: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenBehavior() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (count > 0) colors.error.copy(alpha = 0.12f) else colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (count > 0) colors.error else colors.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "今日异常行为",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = if (count > 0) "检测到 $count 条需要关注的行为" else "未检测到异常使用行为",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看异常行为",
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryCard(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 11.sp, color = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = action,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentStatusCard(configured: Boolean) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (configured) colors.primary else colors.error),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (configured) "Agent 已配置 LLM" else "Agent 未配置 LLM",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = if (configured) {
                        "日报与 AI 助手会调用已配置的模型接口"
                    } else {
                        "前往设置填写模型与 API Key 后启用 AI 能力，当前走本地规则"
                    },
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 权限引导

@Composable
private fun UsagePermissionCard(onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.onErrorContainer,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "未授予「使用情况访问」权限",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "没有该权限时无法读取系统使用统计，首页所有数字都会是 0。",
                fontSize = 11.sp,
                color = colors.onErrorContainer,
            )
            TextButton(onClick = onOpen) {
                Text("去授权", fontSize = 12.sp, color = colors.onErrorContainer)
            }
        }
    }
}

@Composable
private fun AccessibilityCard(onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                text = "无障碍服务未开启",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "暂停 App 的拦截能力依赖无障碍服务，未开启时暂停开关不会生效。",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
            TextButton(onClick = onOpen) {
                Text("去开启", fontSize = 12.sp, color = colors.primary)
            }
        }
    }
}

// ---------------------------------------------------------------- 工具

/** 评分等级语义色：优秀/良好/一般/偏多/超负荷 → 绿/蓝/黄/橙/红 */
private fun scoreGradeColor(grade: String): Color = when (grade) {
    "优秀" -> Color(0xFF22C55E)
    "良好" -> Color(0xFF3B82F6)
    "一般" -> Color(0xFFEAB308)
    "偏多" -> Color(0xFFF97316)
    "超负荷" -> Color(0xFFEF4444)
    else -> Color(0xFF3B82F6)
}

/** 分钟数 -> 人类可读文本，例如 "2 小时 5 分钟" / "45 分钟" */
private fun formatMinutes(minutes: Long): String {
    if (minutes <= 0L) return "0 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0L && rest > 0L -> "$hours 小时 $rest 分钟"
        hours > 0L -> "$hours 小时"
        else -> "$rest 分钟"
    }
}

/** 读取系统里本应用的无障碍服务是否已启用（真实系统状态，非缓存） */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabled = try {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    } catch (e: Settings.SettingNotFoundException) {
        0
    }
    if (enabled != 1) return false
    val services = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return services.split(':').any { it.trim().startsWith("${context.packageName}/") }
}

private fun openSystemSettings(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
