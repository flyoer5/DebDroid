package com.debdroid.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.debdroid.app.DebDroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启（FR-K2 keepBoot）。goAsync + IO 协程读 DataStore，避免主线程阻塞 ANR。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as DebDroidApp
                val enabled = runCatching { app.settingsRepository.settings.first().keepBoot }
                    .getOrDefault(false)
                if (enabled) runCatching { KeepAliveService.start(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
