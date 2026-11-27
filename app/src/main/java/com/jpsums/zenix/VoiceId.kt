package com.jpsums.zenix

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Jim's voice fingerprint. Everything stays on the phone.
 *
 * A small offline "speaker" model turns a few seconds of speech into 128 numbers that describe the voice
 * (not the words). He records three sentences once; their average is his fingerprint. Later, the
 * "Hey Zenix" listener and the listener that runs while she talks compare what they hear with it.
 *
 * How close is "close enough" is learned from his own three recordings (how alike they are to each other),
 * so it adapts to his voice and his phone's microphone instead of using a guessed number.
 */
object VoiceId {
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
    private const val PREF_DL = "spk_download_id"
    private const val P = "zenix_voiceid"
    @Volatile var unpacking = false
    @Volatile var lastError = ""
    // the last comparison, for Settings and Live status
    @Volatile var lastScore = -1.0
    @Volatile var lastNeed = -1.0
    @Volatile var lastWhere = ""
    @Volatile var lastAt = 0L
    @Volatile var lastOk = false

    fun dir(c: Context) = File(c.filesDir, "vosk-spk")
    private fun zip(c: Context) = File(c.getExternalFilesDir("models"), "spk.zip")
    fun ready(c: Context) = File(dir(c), "final.ext.raw").exists() && File(dir(c), "mfcc.conf").exists()
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    @Volatile private var model: SpeakerModel? = null
    @Synchronized fun model(c: Context): SpeakerModel? {
        if (!ready(c)) return null
        return model ?: try { SpeakerModel(dir(c).absolutePath).also { model = it } } catch (e: Throwable) { lastError = "Fingerprint model error: ${e.message}"; null }
    }

    // ---------- settings ----------
    fun print(c: Context): DoubleArray? = prefs(c).getString("print", null)?.let { s ->
        try { JSONArray(s).let { a -> DoubleArray(a.length()) { a.getDouble(it) } } } catch (_: Exception) { null }
    }
    fun base(c: Context) = prefs(c).getFloat("base", 0f).toDouble()
    fun enrolled(c: Context) = print(c) != null && base(c) > 0
    fun onWake(c: Context) = prefs(c).getBoolean("onWake", false) && enrolled(c) && ready(c)
    fun barge(c: Context) = prefs(c).getBoolean("barge", false) && enrolled(c) && ready(c)
    fun level(c: Context) = prefs(c).getString("level", "normal") ?: "normal"
    fun set(c: Context, key: String, value: String) {
        val e = prefs(c).edit()
        when (key) {
            "onWake", "barge" -> e.putBoolean(key, value == "true")
            "level" -> if (value in listOf("relaxed", "normal", "strict")) e.putString("level", value)
        }
        e.apply()
    }
    fun forget(c: Context) { prefs(c).edit().remove("print").remove("base").putBoolean("onWake", false).putBoolean("barge", false).apply(); samples.clear() }

    /** How alike a new bit of speech must be, as a share of how alike his own recordings were to each other. */
    fun need(c: Context): Double {
        val f = when (level(c)) { "relaxed" -> 0.62; "strict" -> 0.85; else -> 0.74 }
        return base(c) * f
    }

    // ---------- comparing ----------
    fun cos(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0; var x = 0.0; var y = 0.0
        for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i] }
        return if (x > 0 && y > 0) d / Math.sqrt(x * y) else 0.0
    }
    /** The voice numbers in a listener result, if there was enough speech to judge. */
    fun vector(result: String?, minFrames: Int): DoubleArray? = try {
        val j = JSONObject(result ?: "{}")
        val a = j.optJSONArray("spk")
        if (a == null || a.length() < 16 || j.optInt("spk_frames", 0) < minFrames) null
        else DoubleArray(a.length()) { a.getDouble(it) }
    } catch (_: Exception) { null }

    /** null = can't tell (too little speech); true/false = his voice or not. Remembers the result for Live status. */
    fun isHim(c: Context, v: DoubleArray?, where: String, short: Boolean = false): Boolean? {
        val p = print(c) ?: return null
        if (v == null) return null
        // a second or less of speech ("Hey Zenix") gives rougher numbers than a whole sentence: a little more slack
        val s = cos(v, p); val n = need(c) * (if (short) 0.88 else 1.0)
        lastScore = s; lastNeed = n; lastWhere = where; lastAt = System.currentTimeMillis(); lastOk = s >= n
        return s >= n
    }

    /** a score as a share of his own recordings' likeness, e.g. 82 */
    fun pct(c: Context, v: Double): Long { val b = base(c); return if (b > 0) Math.round(v / b * 100) else 0 }

    // ---------- recording his voice ----------
    private val samples = ArrayList<DoubleArray>()
    @Volatile private var busy = false

    /** Records `secs` seconds with the phone's microphone and returns his voice numbers (or why not). */
    @SuppressLint("MissingPermission")
    private fun record(c: Context, secs: Int, stop: () -> Boolean = { false }): Pair<DoubleArray?, String> {
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null to "Allow the microphone for Zenix first."
        if (!WakeWordService.modelReady(c)) return null to "The offline listener isn't downloaded yet. Turn on \"Hey Zenix\" once on Wi-Fi."
        val spk = model(c) ?: return null to "The voice fingerprint isn't downloaded yet."
        var ar: AudioRecord? = null; var rec: Recognizer? = null
        try {
            rec = Recognizer(VoskShared.get(c), 16000f, spk)
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (ar.state != AudioRecord.STATE_INITIALIZED) return null to "The microphone is busy."
            ar.startRecording()
            val buf = ShortArray(1600)
            val parts = ArrayList<Pair<DoubleArray, Int>>()
            fun take(r: String) { try { val j = JSONObject(r); val a = j.optJSONArray("spk"); val f = j.optInt("spk_frames", 0)
                if (a != null && f > 0) parts.add(DoubleArray(a.length()) { a.getDouble(it) } to f) } catch (_: Exception) {} }
            val end = SystemClock.elapsedRealtime() + secs * 1000L
            while (SystemClock.elapsedRealtime() < end && !stop()) {
                val n = ar.read(buf, 0, buf.size); if (n <= 0) continue
                if (rec.acceptWaveForm(buf, n)) take(rec.result)
            }
            take(rec.finalResult)
            val frames = parts.sumOf { it.second }
            if (frames < 120) return null to "I didn't hear enough speech. Hold the phone normally and speak the whole sentence."
            val avg = DoubleArray(parts[0].first.size)
            for ((v, f) in parts) for (i in avg.indices) if (i < v.size) avg[i] += v[i] * f / frames
            return avg to ""
        } catch (e: Throwable) { return null to "Recording failed: ${e.message}" }
        finally {
            try { ar?.stop() } catch (_: Exception) {}
            try { ar?.release() } catch (_: Exception) {}
            try { rec?.close() } catch (_: Exception) {}
        }
    }

    /** Sample number i (0, 1, 2) of the three sentences. Answers through window.zenixVoiceRec(i, ok, message). */
    fun recordSample(c: Context, i: Int, secs: Int, js: (String) -> Unit) {
        if (busy) return
        busy = true
        thread(name = "zenix-voiceprint") {
            val (v, why) = record(c, secs)
            busy = false
            if (i == 0) samples.clear()
            if (v == null) { js("window.zenixVoiceRec && window.zenixVoiceRec($i, false, ${JSONObject.quote(why)})"); return@thread }
            while (samples.size > i) samples.removeAt(samples.size - 1)
            samples.add(v)
            js("window.zenixVoiceRec && window.zenixVoiceRec($i, true, '')")
        }
    }

    /** After the three sentences: his fingerprint, and how alike his own recordings were (the yardstick for later). */
    fun finish(c: Context): String {
        if (samples.size < 3) return JSONObject().put("ok", false).put("message", "Record all three sentences first.").toString()
        val avg = DoubleArray(samples[0].size)
        for (v in samples) for (i in avg.indices) avg[i] += v[i] / samples.size
        // each recording compared with the other two: that is how alike his voice is with itself on this phone
        var self = 0.0
        for (k in samples.indices) {
            val others = DoubleArray(avg.size)
            for ((j, v) in samples.withIndex()) if (j != k) for (i in others.indices) others[i] += v[i] / (samples.size - 1)
            self += cos(samples[k], others) / samples.size
        }
        if (self < 0.2) return JSONObject().put("ok", false).put("message", "The three recordings sound too different from each other. Try again somewhere quiet.").toString()
        prefs(c).edit().putString("print", JSONArray(avg.toList()).toString()).putFloat("base", self.toFloat())
            .putBoolean("onWake", true).putBoolean("barge", true).apply()
        samples.clear()
        return JSONObject().put("ok", true).put("message", "Got your voice.").put("base", self).toString()
    }

    /** "Test my voice": records a few seconds and says how close it is. Answers through window.zenixVoiceTest(ok, message, scorePct, needPct). */
    fun test(c: Context, secs: Int, js: (String) -> Unit) {
        if (busy) return
        busy = true
        thread(name = "zenix-voicetest") {
            val (v, why) = record(c, secs)
            busy = false
            val p = print(c)
            if (v == null || p == null) { js("window.zenixVoiceTest && window.zenixVoiceTest(false, ${JSONObject.quote(if (p == null) "Record your voice first." else why)}, 0, 0)"); return@thread }
            val him = isHim(c, v, "test") == true
            val pct = Math.round(lastScore / base(c) * 100); val needPct = Math.round(lastNeed / base(c) * 100)
            js("window.zenixVoiceTest && window.zenixVoiceTest(true, ${JSONObject.quote(if (him) "That's you." else "That doesn't sound like your recording.")}, $pct, $needPct)")
        }
    }

    // ---------- download ----------
    fun status(c: Context): String {
        var state = if (ready(c)) "ready" else if (unpacking) "unpacking" else "none"
        var pct = if (state == "ready") 100L else 0L
        val id = c.getSharedPreferences("zenix", Context.MODE_PRIVATE).getLong(PREF_DL, -1L)
        if (state == "none" && id != -1L) try {
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
        } catch (_: Exception) {}
        val b = base(c)
        return JSONObject().put("state", state).put("percent", pct).put("enrolled", enrolled(c))
            .put("onWake", prefs(c).getBoolean("onWake", false)).put("barge", prefs(c).getBoolean("barge", false)).put("level", level(c))
            .put("lastScore", if (lastScore >= 0 && b > 0) Math.round(lastScore / b * 100) else -1)
            .put("lastNeed", if (lastNeed >= 0 && b > 0) Math.round(lastNeed / b * 100) else -1)
            .put("lastWhere", lastWhere).put("lastOk", lastOk)
            .put("lastAgo", if (lastAt > 0) (System.currentTimeMillis() - lastAt) / 1000 else -1)
            .put("listenerReady", WakeWordService.modelReady(c)).put("error", lastError).toString()
    }

    fun download(c: Context) {
        if (ready(c)) return
        zip(c).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Zenix voice fingerprint")
            .setDescription("So Zenix knows your voice (about 13 MB)")
            .setDestinationInExternalFilesDir(c, "models", "spk.zip")
        val id = c.getSystemService(DownloadManager::class.java).enqueue(req)
        c.getSharedPreferences("zenix", Context.MODE_PRIVATE).edit().putLong(PREF_DL, id).apply()
    }

    private fun unpack(c: Context) {
        if (unpacking || ready(c)) return
        unpacking = true
        thread {
            try {
                val out = dir(c); out.deleteRecursively(); out.mkdirs()
                ZipInputStream(zip(c).inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val rel = e.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val f = File(out, rel)
                            if (!f.canonicalPath.startsWith(out.canonicalPath)) throw SecurityException("bad zip entry")
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                        }
                        e = z.nextEntry
                    }
                }
                zip(c).delete()
                c.getSharedPreferences("zenix", Context.MODE_PRIVATE).edit().remove(PREF_DL).apply()
            } catch (ex: Exception) { lastError = "Unpack failed: ${ex.message}" }
            unpacking = false
        }
    }
}
