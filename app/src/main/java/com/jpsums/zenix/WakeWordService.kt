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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Always-on "Hey Maya" listener. Runs Vosk fully on the phone and only reacts to the wake
 * phrase; nothing is sent anywhere. When heard, it pauses itself (frees the mic) and opens Maya.
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
            return o.toString()
        }

        fun download(c: Context) {
            if (modelReady(c)) return
            zipFile(c).delete()
            val req = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("Maya wake word")
                .setDescription("Downloading the \"Hey Maya\" listener (about 40 MB)")
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hey Maya", NotificationManager.IMPORTANCE_MIN))
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
            .setContentTitle("Say \"Hey Maya\"")
            .setContentText("Maya is ready when you need her")
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

    private fun startListening() {
        if (speech != null || paused) return
        if (!modelReady(this)) { lastError = "Wake word not downloaded yet"; return }
        thread {
            try {
                if (model == null) model = Model(modelDir(this).absolutePath)
                val rec = Recognizer(model!!, 16000.0f, "[\"hey maya\", \"hi maya\", \"okay maya\", \"maya\", \"[unk]\"]")
                main.post {
                    if (paused || speech != null) { rec.close(); return@post }
                    try {
                        val s = SpeechService(rec, 16000.0f)
                        s.startListening(listener)
                        speech = s
                        lastError = ""
                    } catch (e: Exception) { lastError = "Mic busy: ${e.message}"; rec.close() }
                }
            } catch (e: Exception) { lastError = "Wake model error: ${e.message}" }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { check(hypothesis, "partial") }
        override fun onResult(hypothesis: String?) { check(hypothesis, "text") }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { lastError = exception?.message ?: "error"; stopEngine(); main.postDelayed({ startListening() }, 1500) }
        override fun onTimeout() {}
    }

    private fun check(hyp: String?, key: String) {
        val text = try { JSONObject(hyp ?: "{}").optString(key) } catch (_: Exception) { "" }
        if (!text.contains("maya")) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 3000) return
        lastTrigger = now
        triggered()
    }

    private fun triggered() {
        pause()   // free the microphone for the conversation
        try {   // wake the screen if the phone is asleep (Maya then shows over the lock screen)
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "maya:screen").acquire(8000)
            }
        } catch (_: Exception) { }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("wake", true)
        val opened = try { startActivity(i); true } catch (_: Exception) { false }
        val act = MainActivity.instance?.get()
        if (act != null && MainActivity.inForeground) act.onWake()
        if (!opened) {
            // fallback: a tap-to-talk notification
            val pi = PendingIntent.getActivity(this, 4, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, MainActivity.VOICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle("Maya heard you").setContentText("Tap to talk")
                .setContentIntent(pi).setAutoCancel(true).build()
            getSystemService(NotificationManager::class.java).notify(11, n)
            main.postDelayed({ resume() }, 8000)
        }
    }

    private fun stopEngine() {
        val s = speech; speech = null
        try { s?.stop() } catch (_: Exception) {}
        try { s?.shutdown() } catch (_: Exception) {}
    }

    fun pause() { paused = true; stopEngine() }
    fun resume() { if (!paused) return; paused = false; main.postDelayed({ startListening() }, 400) }

    override fun onDestroy() {
        stopEngine()
        try { model?.close() } catch (_: Exception) {}
        model = null
        try { wakeLock?.release() } catch (_: Exception) {}
        if (current === this) current = null
        super.onDestroy()
    }
}
