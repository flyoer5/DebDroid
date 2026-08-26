package com.debdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.debdroid.app.DebDroidApp
import com.debdroid.app.MainActivity
import com.debdroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 保活前台服务（FR-K1/K2，architecture.md §3.4）：
 * 会话存活期间常驻通知；可选唤醒锁；START_STICKY 支持被杀后由系统重启。
 * 系统重启（intent==null）且开启「自动恢复会话」时，恢复一个会话（FR-S4）。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startInForeground()
        observe()
        // intent==null：系统经 START_STICKY 重启了本服务（进程此前被杀）——
        // 按设置恢复终端会话（FR-S4）。
        if (intent == null) {
            scope.launch {
                runCatching {
                    delay(1500) // 等冷启动初始化完成
                    val app = application as DebDroidApp
                    val s = app.settingsRepository.settings.first()
                    if (s.keepForeground && s.keepRestore) {
                        app.sessionManager.ensureSession(s)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        // targetSdk 28 < 34：两参 startForeground 即可，无需 FGS type
        startForeground(NOTIFICATION_ID, buildNotification(0))
    }

    private fun observe() {
        if (observing) return
        observing = true
        val app = application as DebDroidApp
        scope.launch {
            combine(app.sessionManager.sessions, app.settingsRepository.settings) { s, set -> s to set }
                .collect { (sessions, settings) ->
                    if (!settings.keepForeground && sessions.isEmpty()) {
                        stopSelf()
                        return@collect
                    }
                    updateNotification(sessions.size)
                    updateWakeLock(sessions.isNotEmpty() && settings.keepWakelock)
                }
        }
    }

    private fun updateNotification(sessionCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(sessionCount))
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(
            R.string.notification_sessions,
            sessionCount,
            getString(R.string.notification_sessions_unit),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateWakeLock(acquire: Boolean) {
        if (acquire && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "debdroid:keepalive").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!acquire && wakeLock != null) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun stopEverything() {
        updateWakeLock(false)
        observing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        updateWakeLock(false)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 任务移除后保持后台运行（通知仍在）
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "debdroid_service"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.debdroid.app.STOP"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
