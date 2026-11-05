package com.aid.usageguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aid.usageguard.data.Container

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Container.init(this)
        createNotificationChannels()
        // Agent 与行为分析器在 integration 阶段注入（见 AppWiring.kt）
        AppWiring.install(this)
        // 保活：前台服务 + 无障碍自恢复（v1.8.3）
        com.aid.usageguard.service.KeepAliveService.start(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            "daily_reminder" to "每日使用提醒",
            "goal_exceeded" to "超过使用目标",
            "anomaly" to "异常使用提醒",
            "ai_report" to "AI 使用报告",
            "guard_alive" to "守护常驻状态",
        )
        channels.forEach { (id, name) ->
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
