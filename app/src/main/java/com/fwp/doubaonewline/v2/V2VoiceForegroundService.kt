package com.fwp.doubaonewline.v2

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

/**
 * Keeps microphone access valid when the V2 activity is covered or the screen is locked.
 * The Speech SDK owns the recorder; this service keeps the app process in the microphone
 * foreground-service state required by recent Android versions.
 */
class V2VoiceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "V2 实时语音",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, V2Activity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_usb)
                .setContentTitle("豆包助手")
                .setContentText("V2 实时语音正在运行")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "v2_realtime_voice"
        private const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, V2VoiceForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, V2VoiceForegroundService::class.java))
        }
    }
}
