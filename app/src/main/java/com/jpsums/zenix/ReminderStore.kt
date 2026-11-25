package com.jpsums.zenix

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

internal object ReminderStore {
    private fun prefs(c: Context) = c.getSharedPreferences("zenix_pending_reminders", Context.MODE_PRIVATE)
    fun newId(): Int = java.util.UUID.randomUUID().hashCode() and 0x7fffffff
    fun save(c: Context, id: Int, at: Long, intent: Intent) {
        val data = JSONObject().put("at", at).put("text", intent.getStringExtra("text") ?: "Reminder")
            .put("title", intent.getStringExtra("title") ?: "Zenix reminder").put("alarm", intent.getBooleanExtra("alarm", false))
        intent.getStringExtra("routine")?.let { data.put("routine", it) }
        check(prefs(c).edit().putString(id.toString(), data.toString()).commit()) { "Reminder storage unavailable" }
    }
    fun remove(c: Context, id: Int) { prefs(c).edit().remove(id.toString()).apply() }
    fun restore(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        for ((key, value) in prefs(c).all) try {
            val id = key.toInt(); val j = JSONObject(value as String)
            val at = maxOf(j.getLong("at"), System.currentTimeMillis() + 1000)
            val intent = Intent(c, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", j.getString("text"))
                .putExtra("title", j.getString("title")).putExtra("alarm", j.optBoolean("alarm"))
            if(j.has("routine")) intent.putExtra("routine", j.getString("routine"))
            val pi = PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else if (j.optBoolean("alarm")) {
                val show = PendingIntent.getActivity(c, id, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) { /* Retain entry for a future restore attempt. */ }
    }
}
