package com.jpsums.zenix

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rings like an alarm when a reminder or routine is due: loud looping sound (alarm volume, so it plays
 * on silent), vibration, the screen wakes and a full-screen card shows Stop and Snooze.
 */
class AlarmRinger : Service() {

    companion object {
        const val CH = "zenix_ring"
        private const val NID = 13
        const val ACTION_STOP = "com.jpsums.zenix.RING_STOP"
        const val ACTION_SNOOZE = "com.jpsums.zenix.RING_SNOOZE"
        @Volatile var current: AlarmRinger? = null
        @Volatile var title = ""
        @Volatile var text = ""
        @Volatile var routine: String? = null

        private fun prefs(c: Context) = c.getSharedPreferences("zenix", Context.MODE_PRIVATE)
        fun isOn(c: Context) = prefs(c).getBoolean("ring_on", true)
        fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("ring_on", on).apply()
        fun setSound(c: Context, uri: Uri?) = prefs(c).edit().putString("ring_uri", uri?.toString()).apply()

        fun sound(c: Context): Uri = prefs(c).getString("ring_uri", null)?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        fun soundName(c: Context): String = try {
            if (prefs(c).getString("ring_uri", null) == null) "Phone's alarm sound"
            else RingtoneManager.getRingtone(c, sound(c))?.getTitle(c) ?: "Alarm sound"
        } catch (_: Exception) { "Alarm sound" }

        fun start(c: Context, title: String, text: String, routine: String?) {
            val i = Intent(c, AlarmRinger::class.java).putExtra("title", title).putExtra("text", text)
            if (routine != null) i.putExtra("routine", routine)
            try { c.startForegroundService(i) } catch (_: Exception) {
                // Android refused a background start: fall back to a normal loud notification
                ReminderReceiver.plain(c, title, text, (System.currentTimeMillis() / 1000 % 100000).toInt())
            }
        }

        fun stop(c: Context) { try { c.startService(Intent(c, AlarmRinger::class.java).setAction(ACTION_STOP)) } catch (_: Exception) { c.stopService(Intent(c, AlarmRinger::class.java)) } }

        /** Rings again in a few minutes. */
        fun snooze(c: Context, title: String, text: String, routine: String?, minutes: Int = 10) {
            val at = System.currentTimeMillis() + minutes * 60_000L
            val id = ReminderStore.newId()
            val i = Intent(c, ReminderReceiver::class.java).putExtra("text", text).putExtra("title", title).putExtra("id", id)
            if (routine != null) i.putExtra("routine", routine)
            val pi = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val am = c.getSystemService(AlarmManager::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                ReminderStore.save(c, id, at, i)
            } catch (_: Exception) { }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var screenLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finishRing(false); return START_NOT_STICKY }
            ACTION_SNOOZE -> { finishRing(true); return START_NOT_STICKY }
        }
        title = intent?.getStringExtra("title") ?: "Zenix reminder"
        text = intent?.getStringExtra("text") ?: ""
        routine = intent?.getStringExtra("routine")
        current = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Zenix alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null); ch.enableVibration(false); ch.setBypassDnd(true)
            ch.description = "Reminders and routines that ring like an alarm"
            nm.createNotificationChannel(ch)
        }
        val card = Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val full = PendingIntent.getActivity(this, 41, card, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 42, Intent(this, AlarmRinger::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozePi = PendingIntent.getService(this, 43, Intent(this, AlarmRinger::class.java).setAction(ACTION_SNOOZE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text.ifBlank { "Zenix" })
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .setFullScreenIntent(full, true).setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", snoozePi).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NID, n)
        } catch (_: Exception) { ReminderReceiver.plain(this, title, text, NID + 1); stopSelf(); return START_NOT_STICKY }
        // wake the screen and show the card
        try {
            @Suppress("DEPRECATION")
            screenLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "maya:ring").apply { acquire(60_000) }
        } catch (_: Exception) { }
        try { startActivity(card) } catch (_: Exception) { }
        RingActivity.instance?.refresh()
        play()
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ finishRing(false, timedOut = true) }, 3 * 60_000L)   // never ring forever
        return START_NOT_STICKY
    }

    private fun play() {
        stopSound()
        val am = getSystemService(AudioManager::class.java)
        try {   // too quiet to hear? bring the alarm volume up while it rings, then put it back
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM); val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (cur < max * 6 / 10) { savedVolume = cur; am.setStreamVolume(AudioManager.STREAM_ALARM, max * 7 / 10, 0) }
        } catch (_: Exception) { }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmRinger, sound(this@AlarmRinger))
                isLooping = true; prepare(); start()
            }
        } catch (_: Exception) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    setDataSource(this@AlarmRinger, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)); isLooping = true; prepare(); start()
                }
            } catch (_: Exception) { }
        }
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (_: Exception) { }
    }

    private fun stopSound() {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        try { getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) { }
        if (savedVolume >= 0) { try { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) } catch (_: Exception) { }; savedVolume = -1 }
    }

    /** Stop (and maybe snooze). A routine then gets carried out by Zenix. */
    fun finishRing(snooze: Boolean, timedOut: Boolean = false) {
        main.removeCallbacksAndMessages(null)
        stopSound()
        val t = title; val x = text; val r = routine
        if (snooze) snooze(this, t, x, r)
        else if (r != null && !timedOut) {
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("routine", r))
            } catch (_: Exception) { }
        }
        if (timedOut) ReminderReceiver.plain(this, "Missed: $t", x, NID + 2)
        MainActivity.instance?.get()?.runJs("window.zenixRingDone && window.zenixRingDone(${org.json.JSONObject.quote(t)}, ${if (snooze) "'snooze'" else if (timedOut) "'missed'" else "'stop'"})")
        RingActivity.instance?.finish()
        try { screenLock?.release() } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (current === this) current = null
        stopSelf()
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); stopSound(); if (current === this) current = null; super.onDestroy() }
}

/** The full-screen card while a reminder rings (shows over the lock screen). */
class RingActivity : Activity() {
    companion object { @Volatile var instance: RingActivity? = null }
    private lateinit var tTitle: TextView
    private lateinit var tText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK); setPadding(dp(28), dp(90), dp(28), dp(48))
        }
        val time = TextView(this).apply {
            text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date()); textSize = 64f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        val bell = TextView(this).apply { text = "⏰  Zenix"; textSize = 15f; setTextColor(Color.parseColor("#B8B8B8")); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(36)) }
        tTitle = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        tText = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#C8C8C8")); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        val spacer = android.view.View(this)
        fun button(label: String, fill: IntArray, onTap: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; setTextColor(if (fill[0] == Color.WHITE) Color.BLACK else Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, fill).apply { cornerRadius = dp(32).toFloat() }
            setOnClickListener { onTap() }
        }
        val stop = button("Stop", intArrayOf(Color.WHITE, Color.parseColor("#B8B8B8"))) { AlarmRinger.current?.finishRing(false) ?: run { AlarmRinger.stop(this); finish() } }
        val snooze = button("Snooze 10 min", intArrayOf(Color.parseColor("#1C1C1C"), Color.parseColor("#1C1C1C"))) { AlarmRinger.current?.finishRing(true) ?: finish() }
        root.addView(time); root.addView(bell); root.addView(tTitle); root.addView(tText)
        root.addView(spacer, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(snooze, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(14) })
        setContentView(root)
        refresh()
    }

    fun refresh() = runOnUiThread {
        if (!::tTitle.isInitialized) return@runOnUiThread
        tTitle.text = AlarmRinger.title.ifBlank { "Reminder" }
        tText.text = AlarmRinger.text
        if (AlarmRinger.current == null) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* use Stop or Snooze */ }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}
