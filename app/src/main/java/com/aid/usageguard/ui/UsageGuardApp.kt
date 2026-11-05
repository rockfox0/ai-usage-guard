package com.aid.usageguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aid.usageguard.data.Container
import com.aid.usageguard.data.settings.AppSettings
import com.aid.usageguard.ui.applist.AppDetailScreen
import com.aid.usageguard.ui.applist.AppListScreen
import com.aid.usageguard.ui.assistant.AssistantScreen
import com.aid.usageguard.ui.behavior.BehaviorScreen
import com.aid.usageguard.ui.dashboard.DashboardScreen
import com.aid.usageguard.ui.report.ReportScreen
import com.aid.usageguard.ui.settings.SettingsScreen

private enum class Tab(val label: String) {
    Dashboard("首页"),
    Apps("应用"),
    Assistant("AI 助手"),
    Report("报告"),
    Settings("设置"),
}

/**
 * 应用根壳：5 个一级页面 + 应用详情 / 行为事件两个二级页面。
 * 宽度 ≥600dp 时底部导航自动换成侧边 NavigationRail。
 */
@Composable
fun UsageGuardApp() {
    val settings by Container.settings.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings.DEFAULT,
    )
    val darkTheme = when (settings.themeMode) {
        AppSettings.THEME_DARK -> true
        AppSettings.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }

    UsageGuardTheme(darkTheme = darkTheme) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var detailPackage by rememberSaveable { mutableStateOf<String?>(null) }
        var showBehavior by rememberSaveable { mutableStateOf(false) }

        BackHandler(enabled = detailPackage != null) { detailPackage = null }
        BackHandler(enabled = detailPackage == null && showBehavior) { showBehavior = false }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail) {
                        NavigationBar {
                            Tab.entries.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Icon(item.icon(), contentDescription = item.label) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(modifier = Modifier.fillMaxSize()) {
                    if (useRail) {
                        NavigationRail {
                            Tab.entries.forEachIndexed { index, item ->
                                NavigationRailItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { Icon(item.icon(), contentDescription = item.label) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                        val current = detailPackage
                        when {
                            current != null -> AppDetailScreen(
                                packageName = current,
                                onBack = { detailPackage = null },
                            )

                            showBehavior -> BehaviorScreen(onBack = { showBehavior = false })

                            else -> when (tab) {
                                0 -> DashboardScreen(
                                    onOpenAppDetail = { detailPackage = it },
                                    onOpenAssistant = { tab = 2 },
                                    onOpenReport = { tab = 3 },
                                    onOpenBehavior = { showBehavior = true },
                                )

                                1 -> AppListScreen(onOpenAppDetail = { detailPackage = it })
                                2 -> AssistantScreen()
                                3 -> ReportScreen()
                                else -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Dashboard -> Icons.Filled.Home
    Tab.Apps -> Icons.AutoMirrored.Filled.List
    Tab.Assistant -> Icons.Filled.Star
    Tab.Report -> Icons.Filled.Email
    Tab.Settings -> Icons.Filled.Settings
}
