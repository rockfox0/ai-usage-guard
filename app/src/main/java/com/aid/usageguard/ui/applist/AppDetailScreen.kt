package com.aid.usageguard.ui.applist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.domain.model.AppDetailModel
import com.aid.usageguard.domain.model.DailyTotal
import com.aid.usageguard.domain.model.SessionSlice
import com.aid.usageguard.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单个 App 的使用详情。
 * 数据来自 [Container.usage].appDetail(packageName)（今日 / 近 7 天 / 日均 / 启动次数 / 最近使用 /
 * 7 天分布 / 今日使用区间），暂停开关走 [Container.restrictions]。
 */
@Composable
fun AppDetailScreen(packageName: String, onBack: () -> Unit) {
    val viewModel: AppDetailViewModel = viewModel(
        key = "app_detail_$packageName",
        factory = vmFactory {
            AppDetailViewModel(
                packageName = packageName,
                usageSource = Container.usageSource,
                usage = Container.usage,
                settings = Container.settings,
                restrictions = Container.restrictions,
                quota = Container.quota,
                whitelist = Container.whitelist,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 重新进入同一 App 详情时刷新查询结果（采集由列表页/首页负责，这里只重查）
    LaunchedEffect(packageName) { viewModel.load() }
    val detail = state.detail

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Text(
                text = "应用详情",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { message ->
                        item(key = "error") { DetailErrorCard(message) { viewModel.refresh() } }
                    }
                    item(key = "header") {
                        AppHeaderCard(
                            packageName = packageName,
                            appName = detail?.appName ?: packageName,
                            state = state,
                            onSwitch = { checked -> viewModel.setSwitch(checked) },
                        )
                    }
                    state.notice?.let { notice ->
                        item(key = "notice") {
                            DetailNoticeCard(
                                text = notice,
                                isError = state.noticeIsError,
                                onDismiss = { viewModel.dismissNotice() },
                            )
                        }
                    }
                    item(key = "daily_limit") {
                        DailyLimitCard(
                            state = state,
                            onSave = { minutes -> viewModel.setQuota(minutes) },
                        )
                    }
                    if (detail == null) {
                        item(key = "empty") {
                            DetailInfoCard("暂无该应用的使用数据。授权「使用情况访问」并刷新后会显示统计。")
                        }
                    } else {
                        item(key = "stats") { StatsCard(detail) }
                        item(key = "week") { DetailWeekCard(detail.daily) }
                        item(key = "sessions") { SessionsCard(detail.sessions) }
                    }
                    item(key = "policy_note") {
                        Text(
                            text = "系统关键应用与白名单应用永不被限制",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 头部 / 统计

@Composable
private fun AppHeaderCard(
    packageName: String,
    appName: String,
    state: AppDetailUiState,
    onSwitch: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when {
        state.systemProtected -> colors.onSurfaceVariant
        state.whitelisted -> colors.primary
        state.restricted && !state.byDefault -> colors.error
        else -> colors.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = packageName, size = 48.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = packageName,
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = state.switchLabel,
                    fontSize = 11.sp,
                    color = if (state.systemProtected) {
                        colors.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        colors.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 6.dp),
                )
                Switch(
                    checked = state.checked,
                    onCheckedChange = onSwitch,
                    enabled = !state.systemProtected,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = state.statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.statusDetail,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 每日限额（v1.8）

/**
 * 单应用每日限额：显示今日已用 / 剩余，并提供「不限 / 15 / 30 / 60 / 90 / 120 / 180 / 240 / 自定义」。
 * 保存调 Container.quota.setLimit(pkg, minutes)，0 = 清除限额。
 */
@Composable
private fun DailyLimitCard(state: AppDetailUiState, onSave: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var showCustomDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "每日限额",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.quotaSaving) "保存中…" else state.quotaLimitLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.quotaUsageLabel,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            if (state.quotaBlocked) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.quotaDetail.ifBlank { "该应用今日额度已用完" },
                    fontSize = 11.sp,
                    color = colors.error,
                )
            }
            if (state.systemProtected) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "系统关键应用不会被拦截，这里设置的限额只作记录。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            LimitChipRow(
                options = listOf(0, 15, 30, 60),
                currentLimit = state.quotaLimitMinutes,
                enabled = !state.quotaSaving,
                onSelect = onSave,
            )
            Spacer(modifier = Modifier.height(6.dp))
            LimitChipRow(
                options = listOf(90, 120, 180, 240),
                currentLimit = state.quotaLimitMinutes,
                enabled = !state.quotaSaving,
                onSelect = onSave,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = false,
                    onClick = { showCustomDialog = true },
                    enabled = !state.quotaSaving,
                    label = { Text("自定义…", fontSize = 11.sp) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "选择「不限」= 清除限额",
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "超过后该应用今天会被拦截（与总时长无关）；单应用限额优先于白名单。",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }

    if (showCustomDialog) {
        CustomLimitDialog(
            initialLimit = state.quotaLimitMinutes,
            onDismiss = { showCustomDialog = false },
            onConfirm = { minutes ->
                showCustomDialog = false
                onSave(minutes)
            },
        )
    }
}

@Composable
private fun LimitChipRow(
    options: List<Int>,
    currentLimit: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { minutes ->
            val selected = if (minutes <= 0) currentLimit == null else currentLimit == minutes
            FilterChip(
                selected = selected,
                onClick = { onSelect(minutes) },
                enabled = enabled,
                label = { Text(if (minutes <= 0) "不限" else "$minutes", fontSize = 11.sp) },
            )
        }
    }
}

/** 自定义限额对话框：1–1440 整数，非法输入禁用保存并提示 */
@Composable
private fun CustomLimitDialog(
    initialLimit: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initialLimit?.toString() ?: "") }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..1440

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义每日限额", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(4) },
                    label = { Text("分钟（1-1440）", fontSize = 12.sp) },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank() && !valid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请输入 1-1440 之间的整数",
                        fontSize = 11.sp,
                        color = colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
            ) {
                Text("保存", fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", fontSize = 13.sp)
            }
        },
    )
}

@Composable
private fun StatsCard(detail: AppDetailModel) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "今日",
                    value = formatMinutes(detail.todayMinutes),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "近 7 天",
                    value = formatMinutes(detail.weekMinutes),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "日均",
                    value = formatMinutes(detail.averageMinutes),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启动次数", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${detail.launchCount} 次", fontSize = 12.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近使用", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    text = formatTimestamp(detail.lastUsed, "MM-dd HH:mm"),
                    fontSize = 12.sp,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
    }
}

// ---------------------------------------------------------------- 7 天分布

@Composable
private fun DetailWeekCard(daily: List<DailyTotal>) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "最近 7 天分布",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (daily.isEmpty()) {
                Text(
                    text = "暂无分布数据",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
            } else {
                WeekBarChart(daily)
            }
        }
    }
}

/** 自绘柱状图（无第三方图表库）：按 7 天最大值等比缩放，今天高亮 */
@Composable
private fun WeekBarChart(daily: List<DailyTotal>) {
    val colors = MaterialTheme.colorScheme
    val maxMinutes = maxOf(daily.maxOfOrNull { it.minutes } ?: 0L, 1L)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        daily.forEachIndexed { index, day ->
            val isToday = index == daily.lastIndex
            val fraction = (day.minutes.toFloat() / maxMinutes.toFloat()).coerceIn(0f, 1f)
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
                    modifier = Modifier.height(88.dp).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.56f)
                            .height(maxOf(2.dp, 88.dp * fraction))
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

// ---------------------------------------------------------------- 今日使用区间

@Composable
private fun SessionsCard(sessions: List<SessionSlice>) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今日使用区间",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${sessions.size} 次",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(
                    text = "今日暂无使用区间记录",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
            } else {
                sessions.forEach { session ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatTimestamp(session.startTime, "HH:mm")} - ${formatTimestamp(session.endTime, "HH:mm")}",
                            fontSize = 12.sp,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${session.minutes} 分钟",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 通用小件

/** 白名单添加结果提示（成功 / 达到每日上限被拒），可关闭 */
@Composable
private fun DetailNoticeCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) colors.errorContainer else colors.primary.copy(alpha = 0.10f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                color = if (isError) colors.onErrorContainer else colors.onSurface,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DetailInfoCard(text: String) {
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
private fun DetailErrorCard(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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

// ---------------------------------------------------------------- 工具

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

private fun formatTimestamp(timestamp: Long, pattern: String): String {
    if (timestamp <= 0L) return "暂无"
    return try {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    } catch (e: Exception) {
        "暂无"
    }
}
