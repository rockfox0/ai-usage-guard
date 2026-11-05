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
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            "daily_reminder" to "每日使用提醒",
            "goal_exceeded" to "超过使用目标",
            "anomaly" to "异常使用提醒",
            "ai_report" to "AI 使用报告",
        )
        channels.forEach { (id, name) ->
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
