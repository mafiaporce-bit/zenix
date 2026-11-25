package com.jpsums.zenix

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Deepgram live listening, done natively: records the mic (16 kHz PCM) and streams it to
 * Deepgram over a WebSocket. Transcripts go to the screen via window.zenixDGMsg(json).
 */
class DeepgramEars(private val activity: MainActivity) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(6, TimeUnit.SECONDS)     // a dead line is noticed within seconds, not minutes (slow mobile data still has time to answer)
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var sending = false

    private fun js(code: String) = activity.runJs(code)
    private fun q(v: String) = JSONObject.quote(v)

    @Volatile private var flux = false

    /** withMic = false opens the line only (to check Deepgram is reachable) and leaves the microphone to whoever has it. */
    fun open(key: String, lang: String, useFlux: Boolean = false, terms: List<String> = emptyList(), withMic: Boolean = true) {
        if (ws != null) { js("window.zenixDGOpen && window.zenixDGOpen(true, '')"); return }
        flux = useFlux
        openWithMic = withMic
        val url = if (useFlux)
            "wss://api.deepgram.com/v2/listen?model=flux-general-en&encoding=linear16&sample_rate=16000&eot_threshold=0.75&eager_eot_threshold=0.5&eot_timeout_ms=4000"
        else "wss://api.deepgram.com/v1/listen?model=nova-3&language=$lang&encoding=linear16&sample_rate=16000&channels=1" +
            "&smart_format=true&interim_results=true&endpointing=350&utterance_end_ms=1000&vad_events=true"
        // names and words she should get right ("Zenix", his family, his places): Deepgram listens for them specially
        val boost = terms.map { it.trim() }.filter { it.length in 2..40 }.distinct().take(45)
            .joinToString("") { "&keyterm=" + java.net.URLEncoder.encode(it, "UTF-8") }
        val req = Request.Builder().url(url + boost).header("Authorization", "Token $key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!openWithMic) { lendIdle(); js("window.zenixDGOpen && window.zenixDGOpen(true, '')"); return }
                if (startMic()) js("window.zenixDGOpen && window.zenixDGOpen(true, '')")
                else { close(); js("window.zenixDGOpen && window.zenixDGOpen(false, 'Microphone busy or not allowed')") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val cur = ws; if (cur != null && cur !== webSocket) return   // a line that was already given up on: ignore it (no doubled commands)
                js("window.zenixDGMsg && window.zenixDGMsg(${q(text)})")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val cur0 = ws; if (cur0 != null && cur0 !== webSocket) return
                val code = response?.code?.toString() ?: ""
                val body = try { response?.body?.string() ?: "" } catch (_: Exception) { "" }
                cleanup()
                js("window.zenixDGOpen && window.zenixDGOpen(false, ${q("$code ${t.message ?: ""} $body".trim().take(220))})")
                js("window.zenixDGClosed && window.zenixDGClosed()")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val cur0 = ws; if (cur0 != null && cur0 !== webSocket) return
                cleanup()
                js("window.zenixDGClosed && window.zenixDGClosed()")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startMic(): Boolean {
        return try {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            r.startRecording()
            rec = r
            thread(name = "zenix-dg-mic") {
                val buf = ByteArray(if (flux) 2560 else 3200) // 80 ms (Flux) or 100 ms of audio
                val silence = ByteArray(buf.size)
                var lastKeepAlive = System.currentTimeMillis()
                var lp1 = 0.0; var lp2 = 0.0
                while (rec === r) {
                    val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    val socket = ws ?: break
                    if (sending && n > 0) {
                        remember(buf, n)
                        socket.send(buf.toByteString(0, n))
                        // loudness, and how much of it is low / middle / high notes (the orb moves differently for each)
                        var sum = 0.0; var lo = 0.0; var mi = 0.0; var hi = 0.0
                        var k = 0
                        while (k + 1 < n) {
                            val v = ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xff)).toShort().toDouble()
                            lp1 += 0.145 * (v - lp1)      // below about 400 Hz
                            lp2 += 0.544 * (v - lp2)      // below about 2 kHz
                            val m = lp2 - lp1; val h = v - lp2
                            sum += v * v; lo += lp1 * lp1; mi += m * m; hi += h * h
                            k += 2
                        }
                        val cnt = (n / 2).coerceAtLeast(1)
                        fun lv(x: Double, gain: Double) = "%.2f".format(java.util.Locale.US, (Math.sqrt(x / cnt) / 32768.0 * gain).coerceIn(0.0, 1.0))
                        js("window.zenixRms && window.zenixRms(${lv(sum, 9.0)}, ${lv(lo, 12.0)}, ${lv(mi, 15.0)}, ${lv(hi, 30.0)})")
                    } else if (flux && n > 0) {
                        socket.send(silence.toByteString(0, n))   // Flux likes a continuous stream; send silence while Zenix talks
                    } else if (System.currentTimeMillis() - lastKeepAlive > 4000) {
                        socket.send("{\"type\":\"KeepAlive\"}")
                        lastKeepAlive = System.currentTimeMillis()
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    fun setSending(on: Boolean) { if (on && !sending) forget(); sending = on }

    // The last four seconds of what he said, kept in memory only. If the line dies in the middle of a command,
    // the offline listener is given this so the start of the command isn't lost. Never saved, never sent anywhere else.
    @Volatile private var openWithMic = true
    private val ring = ByteArray(16000 * 2 * 4)
    private var ringPos = 0
    private var ringFill = 0
    private fun forget() = synchronized(ring) { ringPos = 0; ringFill = 0 }
    private fun remember(b: ByteArray, n: Int) = synchronized(ring) {
        var i = 0
        while (i < n) { ring[ringPos] = b[i]; ringPos = (ringPos + 1) % ring.size; i++ }
        ringFill = minOf(ring.size, ringFill + n)
    }
    fun recent(): ShortArray = synchronized(ring) {
        val n = ringFill and 1.inv()
        val out = ShortArray(n / 2)
        var p = (ringPos - n + ring.size * 2) % ring.size
        if (p % 2 != 0) p = (p + 1) % ring.size
        for (k in out.indices) {
            val lo = ring[p].toInt() and 0xff; val hi = ring[(p + 1) % ring.size].toInt()
            out[k] = ((hi shl 8) or lo).toShort()
            p = (p + 2) % ring.size
        }
        out
    }
    fun isOpen(): Boolean = ws != null
    fun hasMic(): Boolean = rec != null
    /** The line is open but someone else has the microphone: keep the line alive. */
    private fun lendIdle() {
        lent = true
        thread(name = "zenix-dg-idle") {
            val silence = ByteArray(2560)
            var lastKeepAlive = 0L
            while (lent && rec == null) {
                val socket = ws ?: break
                try {
                    if (flux) { socket.send(silence.toByteString(0, silence.size)); Thread.sleep(80) }
                    else {
                        if (System.currentTimeMillis() - lastKeepAlive > 4000) { socket.send("{\"type\":\"KeepAlive\"}"); lastKeepAlive = System.currentTimeMillis() }
                        Thread.sleep(150)
                    }
                } catch (_: Exception) { break }
            }
        }
    }

    /**
     * While Zenix talks, another listener needs the microphone (the one that hears "stop" / "pause").
     * Two listeners on one microphone starve each other, so this one lets go of it; the line to Deepgram stays open.
     */
    @Volatile private var lent = false
    fun lendMic() {
        if (ws == null || lent) return
        val r = rec ?: return
        rec = null
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        lendIdle()
    }
    /** She finished talking: take the microphone back. */
    fun takeMicBack() {
        if (!lent) return
        lent = false
        if (ws != null && rec == null && !startMic()) {
            close()
            js("window.zenixDGClosed && window.zenixDGClosed()")
        }
    }

    fun close() {
        val socket = ws
        try { socket?.send("{\"type\":\"CloseStream\"}") } catch (_: Exception) {}
        try { socket?.close(1000, null) } catch (_: Exception) {}
        cleanup()
    }

    private fun cleanup() {
        sending = false
        lent = false
        val r = rec
        rec = null
        ws = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
    }

    /** Checks a key with Deepgram and reports back via window.zenixDGTest(ok, message). */
    fun test(key: String) {
        thread {
            try {
                val req = Request.Builder().url("https://api.deepgram.com/v1/projects").header("Authorization", "Token $key").build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val msg = when {
                        resp.isSuccessful -> "ok"
                        resp.code == 401 || resp.code == 403 -> "Deepgram didn't accept this key (${resp.code}). Copy it again from console.deepgram.com."
                        else -> "Deepgram said ${resp.code}: ${body.take(120)}"
                    }
                    js("window.zenixDGTest && window.zenixDGTest(${resp.isSuccessful}, ${q(msg)})")
                }
            } catch (e: Exception) {
                js("window.zenixDGTest && window.zenixDGTest(false, ${q("Can't reach Deepgram: ${e.message ?: "network error"}")})")
            }
        }
    }
}
