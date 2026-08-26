package com.debdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.ui.AppRoot
import com.debdroid.app.ui.theme.DebDroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DebDroidApp

        // 测试钩子：am start --es debdroid_route files 可直达文件屏（绕过菜单导航）
        val initialRoute = intent?.getStringExtra("debdroid_route")

        setContent {
            val settings by app.settingsRepository.settings.collectAsState(initial = AppSettings())
            DebDroidTheme(settings.themeMode) {
                AppRoot(initialRoute = initialRoute)
            }
        }
    }
}
