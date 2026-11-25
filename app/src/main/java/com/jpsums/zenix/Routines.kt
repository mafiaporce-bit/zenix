package com.jpsums.zenix

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Things Zenix does by herself at a set time: the morning brief, "every Friday remind me…",
 * a one-time "tell me when to leave tomorrow". Each routine is a short instruction that Zenix's
 * brain carries out (and speaks) when its time comes.
 * A routine: {id, title, prompt, hour, minute, days ("" = every day, else digits 1-7 with Sunday = 1), date ("" or yyyy-MM-dd for one time)}
 */
object Routines {
    private const val KEY = "routines"
    private fun prefs(c: Context) = c.getSharedPreferences("zenix", Context.MODE_PRIVATE)

    fun all(c: Context): JSONArray = try { JSONArray(prefs(c).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
    private fun save(c: Context, arr: JSONArray) { prefs(c).edit().putString(KEY, arr.toString()).apply() }

    fun find(c: Context, id: Int): JSONObject? {
        val arr = all(c)
        for (i in 0 until arr.length()) { val r = arr.optJSONObject(i) ?: continue; if (r.optInt("id") == id) return r }
        return null
    }

    fun add(c: Context, title: String, prompt: String, hour: Int, minute: Int, days: String, date: String): JSONObject {
        val arr = all(c)
        var id = 1
        for (i in 0 until arr.length()) id = maxOf(id, (arr.optJSONObject(i)?.optInt("id") ?: 0) + 1)
        val r = JSONObject().put("id", id).put("title", title).put("prompt", prompt)
            .put("hour", hour).put("minute", minute).put("days", days.filter { it in '1'..'7' }).put("date", date.trim())
        arr.put(r); save(c, arr); schedule(c, r)
        return r
    }

    fun delete(c: Context, id: Int): Boolean {
        val arr = all(c); val out = JSONArray(); var found = false
        for (i in 0 until arr.length()) { val r = arr.optJSONObject(i) ?: continue; if (r.optInt("id") == id) found = true else out.put(r) }
        if (found) { save(c, out); cancel(c, id) }
        return found
    }

    /** When this routine runs next (0 = never again). */
    fun nextTime(r: JSONObject, from: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        val date = r.optString("date")
        if (date.isNotBlank()) {
            return try {
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return 0L
                cal.time = d
                cal.set(Calendar.HOUR_OF_DAY, r.optInt("hour")); cal.set(Calendar.MINUTE, r.optInt("minute")); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > from) cal.timeInMillis else 0L
            } catch (_: Exception) { 0L }
        }
        val days = r.optString("days")
        cal.timeInMillis = from
        cal.set(Calendar.HOUR_OF_DAY, r.optInt("hour")); cal.set(Calendar.MINUTE, r.optInt("minute")); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        for (i in 0..8) {
            val dow = cal.get(Calendar.DAY_OF_WEEK).toString()
            if (cal.timeInMillis > from && (days.isEmpty() || days.contains(dow))) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return 0L
    }

    fun nextText(r: JSONObject): String {
        val t = nextTime(r); if (t == 0L) return "no more runs"
        return SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(t))
    }

    private fun pending(c: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(c, 500000 + id, Intent(c, RoutineReceiver::class.java).putExtra("rid", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context, r: JSONObject) {
        val at = nextTime(r); val id = r.optInt("id")
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        if (at == 0L) { am.cancel(pending(c, id)); return }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id))
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id))
        } catch (_: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, id)) } catch (_: Exception) { }
        }
    }

    fun cancel(c: Context, id: Int) { try { c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id)) } catch (_: Exception) { } }

    /** After a phone restart or an app update Android forgets alarms: set them all again (and drop finished one-time ones). */
    fun scheduleAll(c: Context) {
        val arr = all(c); val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (nextTime(r) == 0L) continue
            keep.put(r); schedule(c, r)
        }
        if (keep.length() != arr.length()) save(c, keep)
    }

    /** Its time has come: a notification always, and Zenix says it out loud when she can. */
    fun fire(c: Context, r: JSONObject, ring: Boolean = true) {
        val id = r.optInt("id"); val json = r.toString()
        if (ring && AlarmRinger.isOn(c)) {   // rings like an alarm; Stop then lets Zenix carry it out
            AlarmRinger.start(c, r.optString("title", "Zenix"), "Tap Stop and Zenix will tell you", json); return
        }
        val open = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("routine", json)
        val pi = PendingIntent.getActivity(c, 600000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pm = c.getSystemService(PowerManager::class.java)
        val km = c.getSystemService(android.app.KeyguardManager::class.java)
        val awake = pm.isInteractive && !km.isKeyguardLocked
        val nb = Notification.Builder(c, MainActivity.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(r.optString("title", "Zenix"))
            .setContentText("Tap to hear it from Zenix")
            .setAutoCancel(true).setContentIntent(pi)
        val act = MainActivity.instance?.get()
        val nm = c.getSystemService(NotificationManager::class.java)
        if (act != null && awake) {   // Zenix is already open somewhere: she just speaks, without covering the app in front
            try { nm.notify(700000 + id, nb.build()) } catch (_: Exception) { }
            act.runRoutine(json); return
        }
        if (!awake) {
            try {
                @Suppress("DEPRECATION")
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "maya:routine").acquire(8000)
            } catch (_: Exception) { }
            nb.setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(pi, true)
        }
        try { nm.notify(700000 + id, nb.build()) } catch (_: Exception) { }
        try { c.startActivity(open) } catch (_: Exception) { }
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra("rid", -1)
        if (id == -1) { Routines.scheduleAll(c); ReminderStore.restore(c); return }   // phone restarted or Zenix was updated
        val r = Routines.find(c, id) ?: return
        if (r.optString("date").isNotBlank()) Routines.delete(c, id) else Routines.schedule(c, r)
        Routines.fire(c, r)
    }
}
