package com.aid.usageguard.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aid.usageguard.agent.response.ComparisonRow
import com.aid.usageguard.agent.response.ResponseBlock
import com.aid.usageguard.agent.response.ResponseBlock.ChartBlock
import com.aid.usageguard.agent.response.ResponseBlock.ComparisonBlock
import com.aid.usageguard.agent.response.ResponseBlock.FollowUpAction
import com.aid.usageguard.agent.response.ResponseBlock.InsightCard
import com.aid.usageguard.agent.response.ResponseBlock.RecommendationCard
import com.aid.usageguard.agent.response.ResponseBlock.StatisticCard
import com.aid.usageguard.agent.response.ResponseBlock.Text as TextBlock
import com.aid.usageguard.agent.response.StatItem
import com.aid.usageguard.domain.model.DailyTotal
import java.time.LocalDate

/**
 * 结构化回复渲染（v1.9 Phase 4）。
 *
 * - 所有 block 类型来自本地白名单（AgentResponse.ResponseBlock），模型不能提供任意 UI；
 * - 图表数据来自本地聚合（[chartDaily]），不是模型填的；
 * - 解析失败时上层根本不会进入这里，直接显示 Markdown 文本。
 */
@Composable
fun StructuredResponseView(
    title: String?,
    blocks: List<ResponseBlock>,
    chartDaily: List<DailyTotal>,
    onFollowUp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            blocks.forEach { block ->
                when (block) {
                    is TextBlock -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    is StatisticCard -> StatisticBlock(block)
                    is InsightCard -> InsightBlock(block)
                    is RecommendationCard -> RecommendationBlock(block)
                    is ComparisonBlock -> ComparisonView(block)
                    is ChartBlock -> ChartView(block, chartDaily)
                    is FollowUpAction -> {
                        AssistChip(
                            onClick = { onFollowUp(block.prompt) },
                            label = { Text(block.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticBlock(block: StatisticCard) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            block.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        block.items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { item -> StatCell(item, Modifier.weight(1f)) }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(item: StatItem, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(item.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InsightBlock(block: InsightCard) {
    val color = when (block.severity) {
        "high" -> Color(0xFFD32F2F)
        "medium" -> Color(0xFFF57C00)
        "low" -> Color(0xFF388E3C)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp, end = 8.dp)
                .width(8.dp)
                .height(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Column {
            Text(block.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (block.detail.isNotBlank()) {
                Text(
                    block.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecommendationBlock(block: RecommendationCard) {
    Column {
        Text("💡 ${block.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (block.detail.isNotBlank()) {
            Text(
                block.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (block.requiresConfirmation) {
            Text(
                "需要你确认后才会生效（会先生成待确认操作）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ComparisonView(block: ComparisonBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            block.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        ComparisonHeader()
        block.rows.forEach { row -> ComparisonRowView(row) }
    }
}

@Composable
private fun ComparisonHeader() {
    Row(Modifier.fillMaxWidth()) {
        Text("项目", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
        Text("本次", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("对比", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ComparisonRowView(row: ComparisonRow) {
    Row(Modifier.fillMaxWidth()) {
        Text(row.label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
        Text(row.left, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(row.right, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

/** 本地数据图表：最近 7 天使用分钟数的简易柱状图 */
@Composable
private fun ChartView(block: ChartBlock, daily: List<DailyTotal>) {
    Column {
        Text(
            block.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (daily.isEmpty()) {
            Text("暂无本地图表数据", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val max = (daily.maxOfOrNull { it.minutes } ?: 1L).coerceAtLeast(1L)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            daily.forEach { day ->
                val ratio = day.minutes.toFloat() / max.toFloat()
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((72 * ratio).coerceAtLeast(2f).dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), RoundedCornerShape(4.dp)),
                    )
                    Text(
                        dayLabel(day.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun dayLabel(date: String): String = runCatching {
    LocalDate.parse(date).dayOfMonth.toString()
}.getOrDefault(date.takeLast(2))
