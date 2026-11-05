package com.aid.usageguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aid.usageguard.service.AccessibilityRestorer
import com.aid.usageguard.service.KeepAliveService
import kotlin.concurrent.thread

/** 开机 / 应用更新后：恢复无障碍开关并拉起前台保活服务（v1.8.3）。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i("BootReceiver", "onReceive: $action")
        KeepAliveService.start(context)
        val appContext = context.applicationContext
        thread(name = "boot-restore") {
            runCatching { AccessibilityRestorer.tryRestore(appContext) }
                .onSuccess { Log.i("BootReceiver", "accessibility restore: $it") }
                .onFailure { Log.e("BootReceiver", "accessibility restore failed", it) }
        }
    }
}
