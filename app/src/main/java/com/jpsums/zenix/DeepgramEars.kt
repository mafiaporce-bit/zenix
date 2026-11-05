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
        .build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var rec: AudioRecord? = null
    @Volatile private var sending = false

    private fun js(code: String) = activity.runJs(code)
    private fun q(v: String) = JSONObject.quote(v)

    fun open(key: String, lang: String) {
        if (ws != null) { js("window.zenixDGOpen && window.zenixDGOpen(true, '')"); return }
        val url = "wss://api.deepgram.com/v1/listen?model=nova-3&language=$lang&encoding=linear16&sample_rate=16000&channels=1" +
            "&smart_format=true&interim_results=true&endpointing=350&utterance_end_ms=1000&vad_events=true"
        val req = Request.Builder().url(url).header("Authorization", "Token $key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (startMic()) js("window.zenixDGOpen && window.zenixDGOpen(true, '')")
                else { close(); js("window.zenixDGOpen && window.zenixDGOpen(false, 'Microphone busy or not allowed')") }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                js("window.zenixDGMsg && window.zenixDGMsg(${q(text)})")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code?.toString() ?: ""
                val body = try { response?.body?.string() ?: "" } catch (_: Exception) { "" }
                cleanup()
                js("window.zenixDGOpen && window.zenixDGOpen(false, ${q("$code ${t.message ?: ""} $body".trim().take(220))})")
                js("window.zenixDGClosed && window.zenixDGClosed()")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
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
                val buf = ByteArray(3200) // 100 ms of audio
                var lastKeepAlive = System.currentTimeMillis()
                while (rec === r) {
                    val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    val socket = ws ?: break
                    if (sending && n > 0) {
                        socket.send(buf.toByteString(0, n))
                    } else if (System.currentTimeMillis() - lastKeepAlive > 4000) {
                        socket.send("{\"type\":\"KeepAlive\"}")
                        lastKeepAlive = System.currentTimeMillis()
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    fun setSending(on: Boolean) { sending = on }

    fun close() {
        val socket = ws
        try { socket?.send("{\"type\":\"CloseStream\"}") } catch (_: Exception) {}
        try { socket?.close(1000, null) } catch (_: Exception) {}
        cleanup()
    }

    private fun cleanup() {
        sending = false
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
