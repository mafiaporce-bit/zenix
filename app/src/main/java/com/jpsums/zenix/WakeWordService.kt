package com.jpsums.zenix

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** One extra call into the listening engine: is this word in its dictionary? (-1 = no) */
interface VoskExtra : com.sun.jna.Library { fun vosk_model_find_word(model: com.sun.jna.Pointer, word: String): Int }

/**
 * Always-on "Hey Zenix" listener. Runs Vosk fully on the phone and only reacts to the wake
 * phrase; nothing is sent anywhere. When heard, it pauses itself (frees the mic) and opens Zenix.
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL = "zenix_wake"
        const val ACTION_PAUSE = "com.jpsums.zenix.WAKE_PAUSE"
        const val ACTION_RESUME = "com.jpsums.zenix.WAKE_RESUME"
        const val ACTION_OFF = "com.jpsums.zenix.WAKE_OFF"
        private const val NOTIF_ID = 9
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val PREF_DL = "vosk_download_id"
        @Volatile var current: WakeWordService? = null
        @Volatile var unpacking = false
        @Volatile var lastError = ""
        @Volatile var lastHeard = ""
        @Volatile var lastHeardAt = 0L
        @Volatile var activeNames = ""
        // the last time she woke up, and the last near-miss she ignored (shown in Live status, to tune it)
        @Volatile var lastWake = ""
        @Volatile var lastWakeAt = 0L
        @Volatile var lastReject = ""
        @Volatile var lastRejectAt = 0L
        private const val PREF_SENS = "wake_sens"
        fun sens(c: Context): String = c.getSharedPreferences("zenix", Context.MODE_PRIVATE).getString(PREF_SENS, "normal") ?: "normal"
        fun setSens(c: Context, v: String) { if (v in listOf("low", "normal", "high")) c.getSharedPreferences("zenix", Context.MODE_PRIVATE).edit().putString(PREF_SENS, v).apply() }
        const val ALERT_CHANNEL = "zenix_wake_alert"
        const val ALERT_ID = 11

        fun modelDir(c: Context) = File(c.filesDir, "vosk-model")
        fun zipFile(c: Context) = File(c.getExternalFilesDir("models"), "vosk.zip")
        fun modelReady(c: Context) = File(modelDir(c), "am/final.mdl").exists()

        fun status(c: Context): String {
            val o = JSONObject()
            var state = if (modelReady(c)) "ready" else if (unpacking) "unpacking" else "none"
            var pct = if (state == "ready") 100L else 0L
            val id = c.getSharedPreferences("zenix", Context.MODE_PRIVATE).getLong(PREF_DL, -1L)
            if (state == "none" && id != -1L) {
                c.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (cur.moveToFirst()) {
                        val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(1)
                        pct = done * 100 / total
                        state = when (st) {
                            DownloadManager.STATUS_SUCCESSFUL -> { unpack(c); "unpacking" }
                            DownloadManager.STATUS_FAILED -> "failed"
                            else -> "downloading"
                        }
                    }
                }
            }
            o.put("state", state).put("percent", pct).put("running", current != null)
                .put("listening", current?.isListening() == true).put("error", lastError)
                .put("heard", lastHeard).put("heardAgo", if (lastHeardAt > 0) (System.currentTimeMillis() - lastHeardAt) / 1000 else -1)
                .put("names", activeNames).put("sens", sens(c))
                .put("lastWake", lastWake).put("lastWakeAgo", if (lastWakeAt > 0) (System.currentTimeMillis() - lastWakeAt) / 1000 else -1)
                .put("lastReject", lastReject).put("lastRejectAgo", if (lastRejectAt > 0) (System.currentTimeMillis() - lastRejectAt) / 1000 else -1)
            return o.toString()
        }

        fun download(c: Context) {
            if (modelReady(c)) return
            zipFile(c).delete()
            val req = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Zenix wake word")
                .setDescription("Downloading the \"Hey Zenix\" listener (about 40 MB)")
                .setDestinationInExternalFilesDir(c, "models", "vosk.zip")
            val id = c.getSystemService(DownloadManager::class.java).enqueue(req)
            c.getSharedPreferences("zenix", Context.MODE_PRIVATE).edit().putLong(PREF_DL, id).apply()
        }

        /** Unzips the model into app storage (drops the top folder from the zip). */
        fun unpack(c: Context) {
            if (unpacking || modelReady(c)) return
            unpacking = true
            thread {
                try {
                    val out = modelDir(c); out.deleteRecursively(); out.mkdirs()
                    ZipInputStream(zipFile(c).inputStream().buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            val rel = e.name.substringAfter('/', "")
                            if (rel.isNotEmpty()) {
                                val f = File(out, rel)
                                if (!f.canonicalPath.startsWith(out.canonicalPath)) throw SecurityException("bad zip entry")
                                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                            }
                            e = zip.nextEntry
                        }
                    }
                    zipFile(c).delete()
                    c.getSharedPreferences("zenix", Context.MODE_PRIVATE).edit().remove(PREF_DL).apply()
                } catch (ex: Exception) { lastError = "Unpack failed: ${ex.message}" }
                unpacking = false
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recog: Recognizer? = null
    @Volatile private var paused = false
    private var lastTrigger = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    fun isListening() = speech != null && !paused

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Zenix", NotificationManager.IMPORTANCE_MIN))
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            // silent but high priority: lets Zenix open over the lock screen when she hears her name
            val ch = NotificationChannel(ALERT_CHANNEL, "Hey Zenix heard you", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null); ch.enableVibration(false)
            ch.description = "Opens Zenix when you say Hey Zenix"
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { pause(); return START_STICKY }
            ACTION_RESUME -> { resume(); return START_STICKY }
        }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Say \"Hey Zenix\"")
            .setContentText("Zenix is ready when you need her")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { lastError = "Couldn't start: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "maya:wake").apply { setReferenceCounted(false); acquire() }
        }
        paused = false
        startListening()
        return START_STICKY
    }

    // ---------- what she listens for ----------
    private val greetWords = listOf("hey", "hi", "okay", "hello")
    // "Zenix" in every way it can come out of the listener; only the ones it really knows are used
    // ("xanax", "zen x", "zen ex" and "zen next" were dropped: ordinary talk kept turning into them)
    private val nameWords = listOf("zenix", "xenix", "zenics", "zennix", "zinnix")
    private val soundAlikes = listOf("zen nix", "zen ix", "zee nix", "xen ix")

    /** True/false if the listener's dictionary has the word; null if that can't be checked. */
    private val voskExtra: VoskExtra? by lazy {
        try { com.sun.jna.Native.load("vosk", VoskExtra::class.java) } catch (_: Throwable) { null }
    }
    private fun knows(m: Model, word: String): Boolean? = try {
        voskExtra?.let { it.vosk_model_find_word(m.pointer, word) >= 0 }
    } catch (_: Throwable) { null }

    /** Builds the short list of phrases to listen for ("Zenix" alone or with hey/hi/okay/hello), using only words the listener really knows. */
    private var wakeNames: List<String> = emptyList()
    private fun grammarFor(m: Model): String {
        val canCheck = knows(m, "hey") != null
        val greets = if (canCheck) greetWords.filter { knows(m, it) == true }.ifEmpty { listOf("hey") } else greetWords
        val names = if (canCheck) (nameWords + soundAlikes).filter { p -> p.split(" ").all { knows(m, it) == true } }
                    else nameWords + soundAlikes
        wakeNames = names.ifEmpty { listOf("zen nix") }
        activeNames = wakeNames.joinToString(", ")
        val arr = org.json.JSONArray()
        for (n in wakeNames) { arr.put(n); for (g in greets) arr.put("$g $n") }
        arr.put("[unk]")
        return arr.toString()
    }

    private val wakeRegex: Regex get() =
        Regex("^(?:(?:hey|hi|okay|hello) )?(?:" + wakeNames.joinToString("|") { Regex.escape(it) } + ")$")

    private fun startListening() {
        if (speech != null || paused) return
        if (!modelReady(this)) { lastError = "Wake word not downloaded yet"; return }
        thread {
            try {
                if (model == null) model = Model(modelDir(this).absolutePath)
                val rec = Recognizer(model!!, 16000.0f, grammarFor(model!!))
                try { rec.setWords(true) } catch (_: Throwable) { }   // gives a confidence score per word
                main.post {
                    if (paused || speech != null) { rec.close(); return@post }
                    try {
                        val s = SpeechService(rec, 16000.0f)
                        s.startListening(listener)
                        speech = s; recog = rec
                        lastError = ""
                    } catch (e: Exception) { lastError = "Mic busy: ${e.message}"; rec.close() }
                }
            } catch (e: Throwable) { lastError = "Wake model error: ${e.message}" }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { check(hypothesis, "partial") }
        override fun onResult(hypothesis: String?) { check(hypothesis, "text") }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { lastError = exception?.message ?: "error"; stopEngine(); main.postDelayed({ startListening() }, 1500) }
        override fun onTimeout() {}
    }

    // when the current stretch of sound began, and when the one before it ended (to tell a call from a conversation)
    private var segStart = 0L
    private var prevEnd = 0L

    private fun check(hyp: String?, key: String) {
        val text = try { JSONObject(hyp ?: "{}").optString(key) } catch (_: Exception) { "" }
        val now = System.currentTimeMillis()
        val clean = text.replace("[unk]", " ").trim().replace(Regex("\\s+"), " ")
        if (clean.isNotEmpty()) { lastHeard = clean; lastHeardAt = now }   // shown in Settings, to check she can hear you
        if (key != "text") { if (text.isNotBlank() && segStart == 0L) segStart = now; return }
        // Only a clear, finished "Hey Zenix" counts: no half-heard guesses (partials).
        val started = if (segStart > 0) segStart else now
        val quietBefore = started - prevEnd
        segStart = 0L
        if (text.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 2) prevEnd = now   // a stretch of talking (a lone noise doesn't count)
        if (!wakeRegex.matches(clean)) return
        if (now - lastTrigger < 3000) return
        if (now < quietUntil) return   // just after a chat ended: that was the tail of it, not a new "Hey Zenix"
        verdict(hyp, text, clean, quietBefore, now)?.let { why ->
            lastReject = "\"$clean\" ignored: $why"; lastRejectAt = now
            return
        }
        lastWake = "\"$clean\"" + confNote(hyp); lastWakeAt = now
        lastTrigger = now
        triggered()
    }

    private fun confNote(hyp: String?): String {
        val c = lowConf(hyp) ?: return ""
        return " (sure: ${(c * 100).toInt()}%)"
    }
    private fun lowConf(hyp: String?): Double? {
        val words = try { JSONObject(hyp ?: "{}").optJSONArray("result") } catch (_: Exception) { null } ?: return null
        if (words.length() == 0) return null
        var low = 1.0
        for (k in 0 until words.length()) low = minOf(low, words.optJSONObject(k)?.optDouble("conf", 1.0) ?: 1.0)
        return low
    }

    /**
     * null = wake up; otherwise why not. How strict depends on the sensitivity he picked:
     *  low    = only "Hey Zenix", said after a quiet moment, very sure of every word
     *  normal = "Hey Zenix" (sure) or a very clear "Zenix" on its own; not in the middle of talking
     *  high   = the old, easy-going rules
     */
    private fun verdict(hyp: String?, text: String, clean: String, quietBefore: Long, now: Long): String? {
        val sens = sens(this)
        val greeted = clean.contains(' ') && clean.substringBefore(' ') in greetWords
        val unk = Regex("\\[unk\\]").findAll(text).count()
        val media = try { getSystemService(android.media.AudioManager::class.java).isMusicActive } catch (_: Exception) { false }
        val need = when (sens) {
            "low" -> 0.9
            "high" -> 0.65
            else -> if (greeted) 0.8 else 0.9
        } + (if (media) 0.05 else 0.0)
        val maxUnk = when (sens) { "low" -> 0; "high" -> 2; else -> if (greeted) 1 else 0 }
        val quietNeed = when (sens) { "low" -> 1200L; "high" -> 0L; else -> 700L }
        if (sens == "low" && !greeted) return "only \"Hey Zenix\" wakes her on Low"
        if (unk > maxUnk) return "other words were said with it"
        if (quietNeed > 0 && quietBefore < quietNeed) return "it came in the middle of talking"
        val c = lowConf(hyp)
        if (c != null && c < minOf(need, 0.97)) return "not sure enough (${(c * 100).toInt()}%, needs ${(minOf(need, 0.97) * 100).toInt()}%)"
        // a name squeezed out of a tiny blip of sound, or dragged out of a long one, is not someone calling her
        val words = try { JSONObject(hyp ?: "{}").optJSONArray("result") } catch (_: Exception) { null }
        if (words != null && words.length() > 0 && sens != "high") {
            val last = words.optJSONObject(words.length() - 1)
            val d = (last?.optDouble("end", 0.0) ?: 0.0) - (last?.optDouble("start", 0.0) ?: 0.0)
            if (d in 0.0001..0.22) return "too short to be her name"
            if (d > 1.4) return "too long to be her name"
        }
        return null
    }

    private fun triggered() {
        pause()   // free the microphone for the conversation
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        val asleep = !pm.isInteractive
        val locked = km.isKeyguardLocked
        if (asleep) try {   // wake the screen (Zenix then shows over the lock screen)
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "maya:screen").acquire(8000)
        } catch (_: Exception) { }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("wake", true)
        // Driving with another app in front (the map): she answers by voice without covering it
        val open = MainActivity.instance?.get()
        if (open != null && !asleep && !locked && !MainActivity.inForeground && Driving.isOn(this)) {
            open.onWake()
            main.postDelayed({ if (current === this && paused && VoiceService.current == null) resume() }, 15000)
            return
        }
        try { startActivity(i) } catch (_: Exception) { }
        // Screen off, locked, or no "Display over other apps": Android may ignore the line above,
        // so also raise a silent full-screen alert, the same way an incoming call opens its screen.
        if (!MainActivity.inForeground && (asleep || locked || !Settings.canDrawOverlays(this))) try {
            val pi = PendingIntent.getActivity(this, 4, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle("Zenix heard you").setContentText("Tap to talk")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true).setContentIntent(pi)
                .setAutoCancel(true).setTimeoutAfter(20000).build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (_: Exception) { }
        // If no voice chat has started after a while (Zenix couldn't open, or nothing was said), listen again.
        main.postDelayed({ if (current === this && paused && VoiceService.current == null) resume() }, 15000)
    }

    private fun stopEngine() {
        val s = speech; speech = null
        val r = recog; recog = null
        try { s?.stop() } catch (_: Exception) {}
        try { s?.shutdown() } catch (_: Exception) {}
        try { r?.close() } catch (_: Exception) {}
    }

    fun pause() { paused = true; stopEngine() }
    fun resume() { if (!paused) return; paused = false; quietUntil = System.currentTimeMillis() + 8000; main.postDelayed({ startListening() }, 400) }
    @Volatile private var quietUntil = 0L

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        stopEngine()
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { wakeLock?.release() } catch (_: Exception) {}
        if (current === this) current = null
        super.onDestroy()
    }
}
