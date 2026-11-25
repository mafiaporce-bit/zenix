package com.jpsums.zenix

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        /** A normal notification (used when alarm sounds are switched off). */
        fun plain(context: Context, title: String, text: String, id: Int) {
            val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(context, MainActivity.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open).build()
            try { context.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Reminder"
        val title = intent.getStringExtra("title") ?: "Zenix reminder"
        val id = intent.getIntExtra("id", 1)
        val routine = intent.getStringExtra("routine")
        if (AlarmRinger.isOn(context)) { AlarmRinger.start(context, title, text, routine); return }
        if (routine != null) {   // snoozed routine with alarm sounds now off: let Zenix do it
            try { Routines.fire(context, org.json.JSONObject(routine), false) } catch (_: Exception) { }
            return
        }
        plain(context, title, text, id)
    }
}
