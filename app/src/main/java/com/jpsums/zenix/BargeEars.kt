package com.jpsums.zenix

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/** One copy of the small offline listener, shared by everything in the app that needs it. */
object VoskShared {
    private var model: Model? = null
    @Synchronized fun get(c: Context): Model =
        model ?: Model(WakeWordService.modelDir(c).absolutePath).also { model = it }
}

/**
 * Listens WHILE Zenix is talking, for a handful of words only: stop, pause, cancel, end, continue.
 * It records through the phone's "call" microphone path, which removes the phone's own speaker sound
 * (echo cancelling), so her own voice doesn't set it off. Nothing leaves the phone.
 * Tells the page through window.zenixBarge("stop" | "pause" | "continue" | "end").
 */
class BargeEars(private val activity: MainActivity) {
    @Volatile private var gen = 0

    private val phrases = mapOf(
        "stop" to "stop", "stop it" to "stop", "stop talking" to "stop", "cancel" to "stop",
        "pause" to "pause",
        "continue" to "continue", "resume" to "continue",
        "end" to "end", "end chat" to "end", "end the chat" to "end"
    )

    fun ready(): Boolean = WakeWordService.modelReady(activity) &&
        activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Volatile private var worker: Thread? = null
    fun start() {
        val my = ++gen
        if (!ready()) return
        worker = thread(name = "zenix-barge") { run(my) }
    }
    fun stop() { gen++ }
    /** Stops and waits until the microphone is really free again (a moment at most). */
    fun stopAndWait() {
        gen++
        val w = worker; worker = null
        try { w?.join(500) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, size: Int): AudioRecord? = try {
        val r = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (_: Exception) { null }

    /**
     * The newest command in what has been heard so far, with where it sits ("7:stop"), or null.
     * Her own voice may leak in as a stream of unknown sounds; that is fine, only the command words matter.
     */
    private fun command(raw: String, finished: Boolean): String? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (end in tokens.size downTo maxOf(1, tokens.size - 3)) {          // only something said just now
            for (n in 3 downTo 1) {
                if (end - n < 0) continue
                val cmd = phrases[tokens.subList(end - n, end).joinToString(" ")] ?: continue
                // a bare "end" sounds like "and": it only counts when said cleanly on its own; "end chat" always counts
                if (cmd == "end" && n == 1) {
                    val others = tokens.size - 1
                    if (!finished || others > 1) continue
                }
                return "$end:$cmd"
            }
        }
        return null
    }

    private fun run(my: Int) {
        var ar: AudioRecord? = null
        var rec: Recognizer? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            val grammar = JSONArray().apply { phrases.keys.forEach { put(it) }; put("[unk]") }.toString()
            rec = Recognizer(VoskShared.get(activity), 16000.0f, grammar)
            try { rec.setWords(true) } catch (_: Throwable) { }   // a confidence score per word: noise that only sounds a bit like "stop" is ignored
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            ar = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, size) ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION, size) ?: return
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(ar.audioSessionId)?.also { it.enabled = true } } catch (_: Throwable) { }
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } } catch (_: Throwable) { }
            ar.startRecording()
            val buf = ShortArray(800)   // a twentieth of a second
            var last = ""; var hits = 0; var lastFire = 0L; var lastShown = ""; var lastShownAt = 0L
            while (my == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val finished = rec.acceptWaveForm(buf, n)
                var confs: List<Double> = emptyList()
                val raw = try {
                    if (finished) JSONObject(rec.result).let { j ->
                        val arr = j.optJSONArray("result")
                        if (arr != null) confs = (0 until arr.length()).map { arr.getJSONObject(it).optDouble("conf", 1.0) }
                        j.optString("text")
                    } else JSONObject(rec.partialResult).optString("partial")
                } catch (_: Exception) { "" }
                // shown in Settings, so it's easy to see what this listener really hears
                val nowMs = SystemClock.elapsedRealtime()
                if (raw.isNotBlank() && raw != lastShown && nowMs - lastShownAt > 600) {
                    lastShown = raw; lastShownAt = nowMs
                    activity.runJs("window.zenixBargeHeard && window.zenixBargeHeard(${JSONObject.quote(raw.takeLast(70))})")
                }
                val hit = command(raw, finished)
                var fire: String? = null
                if (hit == null) { last = ""; hits = 0 }
                else if (finished) {
                    // the whole phrase heard: it counts only when the listener is sure of the command word itself
                    val at = hit.substringBefore(':').toIntOrNull() ?: 0
                    val c = confs.getOrNull(at - 1) ?: 1.0
                    if (c >= 0.8) fire = hit
                    else activity.runJs("window.zenixBargeHeard && window.zenixBargeHeard(${JSONObject.quote("(not sure, ignored) " + raw.takeLast(60))})")
                    last = ""; hits = 0
                }
                else {
                    if (hit == last) hits++ else { last = hit; hits = 1 }
                    // while she talks, only "stop" may act before the phrase is finished, and only after holding steady ~0.35 s;
                    // "pause" and "end" wait for the finished phrase with a good score ("continue" only matters while she is quiet)
                    val cmd = hit.substringAfter(':')
                    if ((cmd == "stop" && hits >= 7) || (cmd == "continue" && hits >= 4)) fire = hit
                }
                if (fire != null) {
                    if (nowMs - lastFire > 1200) {
                        lastFire = nowMs
                        activity.runJs("window.zenixBarge && window.zenixBarge(${JSONObject.quote(fire.substringAfter(':'))})")
                    }
                    if (!finished) try { rec.reset() } catch (_: Throwable) { }
                    last = ""; hits = 0
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { ar?.stop() } catch (_: Exception) { }
            try { aec?.release() } catch (_: Exception) { }
            try { ns?.release() } catch (_: Exception) { }
            try { ar?.release() } catch (_: Exception) { }
            try { rec?.close() } catch (_: Exception) { }
        }
    }
}
