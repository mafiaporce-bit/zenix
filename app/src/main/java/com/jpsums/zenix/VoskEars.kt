package com.jpsums.zenix

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import kotlin.concurrent.thread

/**
 * Zenix's own offline ears. Uses the small listener that "Hey Zenix" already downloaded, so she can
 * understand whole sentences with no internet even when the phone has no offline voice pack.
 * Talks to the page the same way the phone's listener does (zenixOnListening / Partial / Speech / SpeechError).
 */
class VoskEars(private val activity: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var rec: Recognizer? = null
    private var session = 0          // main thread only
    private var delivered = false
    private var lastPartial = ""

    /** Small sounds the listener makes up out of background noise. */
    private val noise = setOf("the", "a", "huh", "uh", "um", "hmm", "oh", "ah", "i", "and", "but", "it", "he", "in")

    fun ready(): Boolean = WakeWordService.modelReady(activity)

    private fun js(code: String) = activity.runJs(code)
    private fun err(code: Int) = js("window.zenixOnSpeechError && window.zenixOnSpeechError($code)")

    fun start() = main.post {
        val my = ++session
        stopEngine()
        if (!ready()) { err(-1); return@post }
        thread {
            try {
                val m = model ?: Model(WakeWordService.modelDir(activity).absolutePath).also { model = it }
                val r = Recognizer(m, 16000.0f)
                main.post {
                    if (my != session) { try { r.close() } catch (_: Exception) { }; return@post }
                    try {
                        val s = SpeechService(r, 16000.0f)
                        delivered = false; lastPartial = ""
                        s.startListening(listener(my))
                        speech = s; rec = r
                        js("window.zenixOnListening && window.zenixOnListening()")
                    } catch (e: Exception) { try { r.close() } catch (_: Exception) { }; err(3) }   // the microphone is busy
                }
            } catch (e: Throwable) { main.post { if (my == session) err(-2) } }
        }
    }

    private fun words(hyp: String?, key: String): String = try {
        JSONObject(hyp ?: "{}").optString(key).replace("[unk]", " ").replace(Regex("\\s+"), " ").trim()
    } catch (_: Exception) { "" }

    private fun listener(my: Int) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (my != session || delivered) return
            val t = words(hypothesis, "partial")
            if (t.isEmpty() || t == lastPartial || t in noise) return
            lastPartial = t
            js("window.zenixOnPartial && window.zenixOnPartial(${JSONObject.quote(t)})")
        }
        // a pause after speaking: that's the sentence
        override fun onResult(hypothesis: String?) {
            if (my != session || delivered) return
            val t = words(hypothesis, "text")
            if (t.isEmpty() || t in noise) { lastPartial = ""; return }   // silence or a noise: keep listening
            deliver(t)
        }
        // asked to finish now (the page decided he stopped talking)
        override fun onFinalResult(hypothesis: String?) {
            if (my != session || delivered) return
            val t = words(hypothesis, "text")
            deliver(if (t in noise) "" else t)
        }
        override fun onError(exception: Exception?) { if (my == session && !delivered) { stopEngine(); err(8) } }
        override fun onTimeout() {}
    }

    private fun deliver(text: String) {
        delivered = true
        stopEngine()
        js("window.zenixOnSpeech && window.zenixOnSpeech(${JSONObject.quote(text)})")
    }

    /** Stop now and hand over what was heard so far. */
    fun finish() = main.post {
        val s = speech ?: return@post
        if (delivered) return@post
        try { s.stop() } catch (_: Exception) { }   // the last words arrive through onFinalResult
    }

    /** Stop and forget (the chat ended, or other ears are taking over). */
    fun cancel() = main.post { session++; stopEngine() }

    private fun stopEngine() {
        val s = speech; speech = null
        val r = rec; rec = null
        try { s?.stop() } catch (_: Exception) { }
        try { s?.shutdown() } catch (_: Exception) { }
        try { r?.close() } catch (_: Exception) { }
    }
}
