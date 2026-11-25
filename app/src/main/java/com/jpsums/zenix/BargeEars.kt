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

    fun start() { val my = ++gen; if (!ready()) return; thread(name = "zenix-barge") { run(my) } }
    fun stop() { gen++ }

    @SuppressLint("MissingPermission")
    private fun open(source: Int, size: Int): AudioRecord? = try {
        val r = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (_: Exception) { null }

    /** The command at the end of what was heard, or null. A few stray sounds around it are fine; a stream of them is her own voice leaking in. */
    private fun command(raw: String, finished: Boolean): String? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val unk = tokens.count { it == "[unk]" }
        if (unk > 2) return null
        if (tokens.last() == "[unk]") return null
        val words = tokens.filter { it != "[unk]" }
        for (n in minOf(3, words.size) downTo 1) {
            val cmd = phrases[words.takeLast(n).joinToString(" ")] ?: continue
            // "end" sounds like "and": it only counts when said cleanly on its own
            if (cmd == "end" && (unk > 0 || !finished || words.size != n)) return null
            return cmd
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
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            ar = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION, size) ?: open(MediaRecorder.AudioSource.VOICE_RECOGNITION, size) ?: return
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(ar.audioSessionId)?.also { it.enabled = true } } catch (_: Throwable) { }
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(ar.audioSessionId)?.also { it.enabled = true } } catch (_: Throwable) { }
            ar.startRecording()
            val buf = ShortArray(1600)   // a tenth of a second
            var last = ""; var hits = 0; var lastFire = 0L
            while (my == gen) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val finished = rec.acceptWaveForm(buf, n)
                val raw = try {
                    if (finished) JSONObject(rec.result).optString("text") else JSONObject(rec.partialResult).optString("partial")
                } catch (_: Exception) { "" }
                val cmd = command(raw, finished)
                var fire: String? = null
                if (finished) { fire = cmd; last = ""; hits = 0 }
                else if (cmd != null) {
                    if (cmd == last) hits++ else { last = cmd; hits = 1 }
                    if (hits >= 4) fire = cmd          // the word stayed the last thing heard for ~0.4 s
                } else { last = ""; hits = 0 }
                if (fire != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFire > 1200) {
                        lastFire = now
                        activity.runJs("window.zenixBarge && window.zenixBarge(${JSONObject.quote(fire)})")
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
