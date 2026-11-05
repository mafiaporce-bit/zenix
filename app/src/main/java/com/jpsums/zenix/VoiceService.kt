package com.jpsums.zenix

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground "voice call" service. While it runs, Android lets ZeniX keep using
 * the microphone even when Jim switches to Facebook, YouTube or any other app.
 */
class VoiceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            MainActivity.instance?.get()?.endChatFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endChat = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, MainActivity.VOICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ZeniX is listening")
            .setContentText("Keep talking — she can hear you in any app")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "End chat", endChat).build())
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_END = "com.jpsums.zenix.END_CHAT"
        private const val NOTIF_ID = 7
    }
}
