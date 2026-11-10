package com.aid.usageguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aid.usageguard.MainActivity
import com.aid.usageguard.data.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 前台保活服务（v1.8.3）：
 * - 常驻一条低优先级通知，提高进程存活率；
 * - 每 60 秒检查一次无障碍开关，若被系统/OEM 关闭则尝试自恢复；
 * - 无自恢复权限时发通知提醒用户（30 分钟内不重复打扰）。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L
    private var lastIngestAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { checkAndRestore() }
            // v1.9.5：每分钟刷新常驻通知里的「今日已用」，避免显示几小时前的旧数字
            runCatching { refreshTextAsync() }
            // v1.9.6：每 5 分钟做一次增量采集，App 没打开时数据/通知也不会落后太多
            val now = System.currentTimeMillis()
            if (now - lastIngestAt > 5 * 60_000L) {
                lastIngestAt = now
                ioScope.launch { runCatching { Container.usage.refresh() } }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { Container.init(this) }
        startForeground(NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
        refreshTextAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1_000L)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        // 被系统回收时尽量把自己拉起来（sticky 之外的双保险）
        runCatching {
            val restart = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restart)
            else startService(restart)
        }
        super.onDestroy()
    }

    private fun checkAndRestore() {
        if (AccessibilityRestorer.isEnabled(this)) return
        when (AccessibilityRestorer.tryRestore(this)) {
            AccessibilityRestorer.Result.RESTORED -> {
                Log.i(TAG, "restored accessibility from watchdog")
                notifyOnce("守护已自动恢复", "无障碍服务被系统关闭后已重新开启")
            }

            AccessibilityRestorer.Result.NO_PERMISSION,
            AccessibilityRestorer.Result.FAILED,
            -> {
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt > 30 * 60_000L) {
                    lastNotifyAt = now
                    notifyOnce(
                        "守护已停止，请手动开启",
                        "无障碍服务被关闭，导致限制功能失效。点击打开系统设置重新开启。",
                    )
                }
            }

            else -> Unit
        }
    }

    @Volatile
    private var cachedText: String = "守护运行中"

    private var foregroundStarted = false

    private fun buildNotification(text: String = cachedText): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("AI 使用管家守护中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * v1.9.5 修复：通知文案必须定期重新发通知，而不是只改内存里的 cachedText。
     * 旧实现只在服务启动时算一次文案，之后 never 更新通知——用户会看到几小时前的
     * 「今日已用 53 / 360 分钟」这种过期数字。
     */
    private fun refreshTextAsync() {
        ioScope.launch {
            val text = runCatching {
                val minutes = Container.usage.todayTotalMinutes()
                val target = Container.settings.current().dailyTargetMinutes
                "今日已用 $minutes / $target 分钟"
            }.getOrDefault("守护运行中")
            cachedText = text
            if (foregroundStarted) {
                runCatching {
                    val manager = getSystemService(NotificationManager::class.java) ?: return@runCatching
                    manager.notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
    }

    private fun notifyOnce(title: String, text: String) {
        ensureChannel(this)
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(cachedText)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(NOTIFY_ID, notification) }
    }

    companion object {
        private const val TAG = "KeepAliveService"
        const val CHANNEL_ID = "guard_alive"
        private const val NOTIFICATION_ID = 0x1041
        private const val NOTIFY_ID = 0x1042
        private const val CHECK_INTERVAL_MS = 60_000L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "守护常驻状态", NotificationManager.IMPORTANCE_LOW),
            )
        }

        /** 在 App 启动/开机后调用；失败（后台启动限制）不抛异常 */
        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w("KeepAliveService", "start failed: ${it.message}") }
        }
    }
}
