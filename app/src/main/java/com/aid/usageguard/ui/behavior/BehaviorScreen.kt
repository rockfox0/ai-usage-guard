package com.aid.usageguard.ui.behavior

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.UsageEventSource
import com.aid.usageguard.domain.model.BehaviorEventModel
import com.aid.usageguard.domain.model.BehaviorSeverity
import com.aid.usageguard.notify.NotificationHelper
import com.aid.usageguard.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 行为分析页：异常事件时间线（按严重级着色） + 手动「立即分析」。
 *
 * 事件由本地规则引擎（[com.aid.usageguard.domain.behavior.BehaviorAnalyzer]）产出，
 * 后台 Worker 分析出的结果会通过 Room Flow 自动出现在这里。
 *
 * 布局约定与报告页一致：填满父容器、不重复套 Theme / 窗口 insets，滚动内边距自己管。
 */
@Composable
fun BehaviorScreen(onBack: () -> Unit) {
    val viewModel: BehaviorViewModel = viewModel(
        factory = vmFactory { BehaviorViewModel(Container.behavior) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = remember { UsageEventSource.todayDate() }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }
    val dateFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    val lastRunFormat = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }

    Column(modifier = Modifier.fillMaxSize()) {
        BehaviorHeader(
            analyzing = state.analyzing,
            onBack = onBack,
            onAnalyze = viewModel::analyzeNow,
        )

        if (state.analyzing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "今日 ${state.todayCount} 条",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (state.lastRunAt > 0L) {
                Text(
                    text = "上次分析 ${lastRunFormat.format(Date(state.lastRunAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.events.isNotEmpty()) {
                Text(
                    text = "共 ${state.events.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }

        when {
            state.loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.events.isEmpty() -> BehaviorEmptyState(
                modifier = Modifier.weight(1f),
                analyzing = state.analyzing,
                onAnalyze = viewModel::analyzeNow,
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.events, key = { it.id }) { event ->
                    BehaviorEventCard(
                        event = event,
                        timeText = if (event.date == today) {
                            "今天 " + timeFormat.format(Date(event.timestamp))
                        } else {
                            dateFormat.format(Date(event.timestamp))
                        },
                    )
                }
                item(key = "footnote") {
                    Text(
                        text = "以上结论由本地规则引擎在设备上生成，不会上传原始使用记录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 顶部

@Composable
private fun BehaviorHeader(
    analyzing: Boolean,
    onBack: () -> Unit,
    onAnalyze: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 10.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "行为分析",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "本地规则引擎 · 后台每小时自动检测",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAnalyze, enabled = !analyzing) {
            Text(if (analyzing) "分析中…" else "立即分析")
        }
    }
}

// ---------------------------------------------------------------------- 事件卡片

@Composable
private fun BehaviorEventCard(event: BehaviorEventModel, timeText: String) {
    val colors = MaterialTheme.colorScheme
    val accent = severityAccent(event.severity)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // 左侧严重级色条
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeverityChip(
                        text = NotificationHelper.typeLabel(event.eventType),
                        container = accent.copy(alpha = ACCENT_ALPHA),
                        content = accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = NotificationHelper.severityLabel(event.severity),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = event.description,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp,
                )
                event.packageName?.let { pkg ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 严重级配色：HIGH 用主题 error，MEDIUM 用琥珀色（深色下提亮），LOW 用中性灰 */
@Composable
private fun severityAccent(severity: BehaviorSeverity): Color {
    val dark = isSystemInDarkTheme()
    return when (severity) {
        BehaviorSeverity.HIGH -> MaterialTheme.colorScheme.error
        BehaviorSeverity.MEDIUM -> if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        BehaviorSeverity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

// ---------------------------------------------------------------------- 空态

@Composable
private fun BehaviorEmptyState(
    modifier: Modifier,
    analyzing: Boolean,
    onAnalyze: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "暂未发现异常使用",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "本地引擎会持续观察使用模式：用量激增、单次连续过久、深夜使用、" +
                "1 小时内频繁打开、超出每日目标、连续多日增长。发现异常时会在这里提醒你。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onAnalyze, enabled = !analyzing) {
            Text(if (analyzing) "分析中…" else "立即分析")
        }
    }
}

private const val ACCENT_ALPHA = 0.14f
