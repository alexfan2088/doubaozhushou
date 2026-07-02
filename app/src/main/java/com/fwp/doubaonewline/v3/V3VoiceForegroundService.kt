package com.fwp.doubaonewline.v3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fwp.doubaonewline.R

class V3VoiceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "V3 本地语音",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, V3Activity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle("豆包助手 V3")
                .setContentText("DeepSeek 本地语音正在运行")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "v3_local_voice"
        private const val NOTIFICATION_ID = 3003

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, V3VoiceForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, V3VoiceForegroundService::class.java))
        }
    }
}
