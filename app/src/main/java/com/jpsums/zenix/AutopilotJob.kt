package com.jpsums.zenix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Autopilot: "remind me … when the internet is back / when the internet is gone".
 * Android runs this check about every 15 minutes (it may delay it to save battery). It only posts a reminder,
 * it never does anything else by itself.
 * Whether there is internet is found out with one real, tiny request. The phone's own "connected" flag is not
 * used: on a real phone it said "no internet" while the internet worked.
 */
class AutopilotJob : JobService() {
    companion object {
        private const val ID = 29001
        private const val CHANNEL = "autopilot290"
        private fun prefs(c: Context) = c.getSharedPreferences("autopilot290", Context.MODE_PRIVATE)

        /** Saves the rules the page shows and (re)schedules the background check. An empty list stops it. */
        fun save(c: Context, raw: String): Boolean {
            if (raw.length > 40000) return false
            return try {
                val a = JSONArray(raw)
                if (a.length() > 50) return false
                val ids = HashSet<String>()
                var armed = 0
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    if (r.optString("kind") !in listOf("online", "offline") || r.optString("text").length > 300 || r.optString("id").isBlank()) return false
                    ids.add(r.getString("id"))
                    if (!r.optBoolean("fired")) armed++
                }
                val p = prefs(c)
                // forget "already fired" marks of rules that no longer exist
                val fired = p.getStringSet("fired", emptySet())!!.filter { it in ids }.toSet()
                val seen = p.getStringSet("seen", emptySet())!!.filter { it in ids }.toSet()
                if (!p.edit().putString("rules", a.toString()).putStringSet("fired", fired).putStringSet("seen", seen).commit()) return false
                val scheduler = c.getSystemService(JobScheduler::class.java)
                if (armed == 0 || fired.containsAll(ids)) { scheduler.cancel(ID); return true }   // nothing left to wait for
                scheduler.schedule(JobInfo.Builder(ID, ComponentName(c, AutopilotJob::class.java))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS
            } catch (_: Exception) { false }
        }

        fun fired(c: Context): String = JSONArray(prefs(c).getStringSet("fired", emptySet())!!.toList()).toString()

        /** One real request: does the internet answer? (true / false, or null when that could not be told) */
        private fun internetWorks(): Boolean? {
            var good = 0; var bad = 0
            for (u in listOf("https://www.gstatic.com/generate_204", "https://api.anthropic.com/v1/models")) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 4000; c.requestMethod = "HEAD"; c.useCaches = false
                    c.instanceFollowRedirects = false
                    val code = c.responseCode
                    c.disconnect()
                    if (code in 100..599) good++ else bad++
                } catch (_: Exception) { bad++ }
                if (good > 0) return true
            }
            return if (bad > 0) false else null
        }
    }

    @Volatile private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        thread(name = "zenix-autopilot") {
            try { runCheck() } catch (_: Exception) { }
            if (!stopped) try { jobFinished(params, false) } catch (_: Exception) { }
        }
        return true   // the check runs on its own thread
    }

    private fun runCheck() {
        val p = prefs(this)
        val a = JSONArray(p.getString("rules", "[]"))
        val done = p.getStringSet("fired", emptySet())!!.toMutableSet()
        var waiting = false
        for (i in 0 until a.length()) { val r = a.getJSONObject(i); if (!r.optBoolean("fired") && r.optString("id") !in done) waiting = true }
        if (!waiting) { getSystemService(JobScheduler::class.java).cancel(ID); return }
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return   // nothing could be shown: keep the rules for later
        val online = internetWorks() ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Zenix Autopilot", NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(this, 0, android.content.Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val seen = p.getStringSet("seen", emptySet())!!.toMutableSet()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i); val id = r.getString("id")
            if (id in done || r.optBoolean("fired")) continue
            // a rule waits for a change: "when the internet is back" needs the internet to have been seen gone first
            if ((r.getString("kind") == "online") != online) { if (seen.add(id)) p.edit().putStringSet("seen", seen.toSet()).commit(); continue }
            if (!r.optBoolean("seen", true) && id !in seen) continue
            val text = r.getString("text")
            val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (online) "The internet is back" else "The internet is gone")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
            try { nm.notify(id.hashCode(), n) } catch (_: SecurityException) { continue }
            done.add(id)
            p.edit().putStringSet("fired", done.toSet()).commit()
        }
    }

    override fun onStopJob(params: JobParameters): Boolean { stopped = true; return true }
}
