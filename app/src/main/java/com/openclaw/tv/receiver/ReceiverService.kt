package com.openclaw.tv.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openclaw.tv.R

class ReceiverService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.i("OpenClaw", "ReceiverService.onCreate")
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("OpenClaw", "ReceiverService.onStartCommand action=${intent?.action} startId=$startId")
        startForegroundIfNeeded()
        if (intent?.action == ACTION_REFRESH) {
            ReceiverRuntime.refresh(this)
        } else {
            ReceiverRuntime.start(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i("OpenClaw", "ReceiverService.onDestroy")
        ReceiverRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "openclaw.receiver"
        private const val NOTIFICATION_ID = 7000
        private const val ACTION_REFRESH = "com.openclaw.tv.receiver.action.REFRESH"

        fun start(context: Context, refresh: Boolean = false) {
            val intent = Intent(context, ReceiverService::class.java).apply {
                action = if (refresh) ACTION_REFRESH else null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "OpenClaw Receiver",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("OpenClaw TV Receiver")
        .setContentText("Waiting for AirPlay or DLNA media")
        .setSmallIcon(R.drawable.ic_app_icon)
        .setOngoing(true)
        .setSilent(true)
        .build()
}
