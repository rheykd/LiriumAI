package com.localmodels.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localmodels.app.MainActivity
import com.localmodels.app.R

class ServerService : Service() {

    private var server: LanServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("запуск"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        if (intent?.action == ACTION_RESTART) {
            server?.stop()
            server = null
        }
        if (server == null) {
            val prefs = Prefs(this)
            val s = LanServer(this, prefs.lanPassword, prefs.lanPort)
            try {
                s.start()
                server = s
                updateNotification(LanHost.address.removePrefix("http://"))
            } catch (e: Exception) {
                LanHost.error = e.message ?: "не удалось запустить сервер"
                LanHost.running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            updateNotification(LanHost.address.removePrefix("http://"))
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "локальный сервер", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("сервер / $text")
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_RESTART = "com.localmodels.app.RESTART_SERVER"

        fun restart(context: Context) {
            context.startForegroundService(Intent(context, ServerService::class.java).setAction(ACTION_RESTART))
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
            LanHost.running = false
            LanHost.address = ""
        }
    }
}
