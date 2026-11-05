package com.aid.usageguard

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aid.usageguard.ui.UsageGuardApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge：内容绘制到系统栏后面，由 Compose 侧处理 insets
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 避免系统给底部导航栏叠加半透明底色，让 NavigationBar 自身的颜色通到底部
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            UsageGuardApp()
        }
    }
}
