package com.jpsums.zenix

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Reminder"
        val id = intent.getIntExtra("id", 1)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, MainActivity.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ZeniX reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) { }
    }
}
