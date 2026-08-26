package com.debdroid.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.ui.AppRoot
import com.debdroid.app.ui.theme.DebDroidTheme
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DebDroidApp

        // 首次启动即请求存储权限：文件管理器/编辑器读写 /sdcard 必需（targetSdk 28 运行时权限）。
        // 此前仅进入文件屏才请求，用户未授权时文件管理器显示空目录（真机调试定位的 bug）。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                REQUEST_STORAGE,
            )
        }

        // 测试钩子：am start --es debdroid_route files 可直达文件屏（绕过菜单导航）
        val initialRoute = intent?.getStringExtra("debdroid_route")

        setContent {
            val settings by app.settingsRepository.settings.collectAsState(initial = AppSettings())
            DebDroidTheme(settings.themeMode) {
                AppRoot(initialRoute = initialRoute)
            }
        }
    }

    companion object {
        private const val REQUEST_STORAGE = 1001
    }
}
