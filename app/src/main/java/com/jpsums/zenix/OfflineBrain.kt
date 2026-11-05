package com.jpsums.zenix

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Maya's offline side: an AI brain that runs on the phone (no internet, no credits),
 * plus the phone's own speech recognition and text-to-speech.
 */
class OfflineBrain(private val activity: MainActivity) {

    companion object {
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val MODEL_FILE = "zenix-brain.task"
        const val MODEL_BYTES = 1625493432L
        private const val PREF_DL = "brain_download_id"
    }

    private val prefs = activity.getSharedPreferences("zenix", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var llm: LlmInference? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var lastRms = 0L

    init {
        tts = TextToSpeech(activity) { status -> ttsReady = status == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { js("window.zenixOnSpeakDone && window.zenixOnSpeakDone()") }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { js("window.zenixOnSpeakDone && window.zenixOnSpeakDone()") }
        })
    }

    private fun js(code: String) = activity.runJs(code)
    private fun modelFile() = File(activity.getExternalFilesDir("models"), MODEL_FILE)
    private fun dm() = activity.getSystemService(DownloadManager::class.java)

    // ---------- model download ----------
    fun status(): String {
        val o = JSONObject()
        val f = modelFile()
        val id = prefs.getLong(PREF_DL, -1L)
        var state = if (f.exists() && f.length() >= MODEL_BYTES) "ready" else "none"
        var done = if (state == "ready") MODEL_BYTES else 0L
        if (id != -1L && state != "ready") {
            dm().query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    state = when (st) {
                        DownloadManager.STATUS_SUCCESSFUL -> "ready"
                        DownloadManager.STATUS_FAILED -> "failed"
                        DownloadManager.STATUS_PAUSED -> "paused"
                        else -> "downloading"
                    }
                }
            }
        }
        o.put("state", state)
        o.put("percent", if (MODEL_BYTES > 0) (done * 100 / MODEL_BYTES).coerceIn(0, 100) else 0)
        o.put("sizeMB", MODEL_BYTES / 1_000_000)
        o.put("loaded", llm != null)
        return o.toString()
    }

    fun download(): String {
        val f = modelFile()
        if (f.exists() && f.length() >= MODEL_BYTES) return status()
        if (f.exists()) f.delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Maya offline brain")
            .setDescription("Downloading Maya's on-phone AI (about 1.6 GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, "models", MODEL_FILE)
            .setAllowedOverMetered(true)
        val id = dm().enqueue(req)
        prefs.edit().putLong(PREF_DL, id).apply()
        return status()
    }

    fun deleteModel(): String {
        exec.execute { try { llm?.close() } catch (_: Exception) {}; llm = null }
        val id = prefs.getLong(PREF_DL, -1L)
        if (id != -1L) dm().remove(id)
        prefs.edit().remove(PREF_DL).apply()
        modelFile().delete()
        return status()
    }

    // ---------- thinking ----------
    /** Runs the on-phone brain in the background; replies via window.zenixOfflineReply(id, text). */
    fun ask(id: String, prompt: String) {
        exec.execute {
            try {
                var engine = llm
                if (engine == null) {
                    val f = modelFile()
                    if (!f.exists()) throw IllegalStateException("The offline brain isn't downloaded yet.")
                    js("window.zenixOfflineLoading && window.zenixOfflineLoading()")
                    val opts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(f.absolutePath)
                        .setMaxTokens(1280)
                        .build()
                    engine = LlmInference.createFromOptions(activity, opts)
                    llm = engine
                }
                val out = engine!!.generateResponse(prompt)
                js("window.zenixOfflineReply(${JSONObject.quote(id)}, ${JSONObject.quote(out ?: "")}, false)")
            } catch (e: Throwable) {
                js("window.zenixOfflineReply(${JSONObject.quote(id)}, ${JSONObject.quote(e.message ?: "Offline brain error")}, true)")
            }
        }
    }

    // ---------- listening ----------
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { js("window.zenixOnListening && window.zenixOnListening()") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val now = System.currentTimeMillis()
            if (now - lastRms > 70) { lastRms = now; val v = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f); js("window.zenixRms && window.zenixRms($v)") }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { js("window.zenixOnSpeechError && window.zenixOnSpeechError($error)") }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            js("window.zenixOnSpeech && window.zenixOnSpeech(${JSONObject.quote(text)})")
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            js("window.zenixOnPartial && window.zenixOnPartial(${JSONObject.quote(text)})")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private var netRecognizer: SpeechRecognizer? = null

    /** preferOffline = true uses the on-device recogniser (no internet); false uses Google's online one (better accuracy). */
    fun startListening(lang: String, preferOffline: Boolean = true) = activity.runOnUiThread {
        try {
            val r: SpeechRecognizer?
            if (preferOffline) {
                if (recognizer == null) {
                    recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                    } else if (SpeechRecognizer.isRecognitionAvailable(activity)) {
                        SpeechRecognizer.createSpeechRecognizer(activity)
                    } else null
                    recognizer?.setRecognitionListener(listener)
                }
                r = recognizer
            } else {
                if (netRecognizer == null && SpeechRecognizer.isRecognitionAvailable(activity)) {
                    netRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
                    netRecognizer?.setRecognitionListener(listener)
                }
                r = netRecognizer
            }
            if (r == null) { js("window.zenixOnSpeechError && window.zenixOnSpeechError(-1)"); return@runOnUiThread }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            r.startListening(i)
        } catch (e: Exception) {
            js("window.zenixOnSpeechError && window.zenixOnSpeechError(-2)")
        }
    }

    /** Stop now and deliver what was heard (faster than waiting for silence detection). */
    fun finishListening() = activity.runOnUiThread {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { netRecognizer?.stopListening() } catch (_: Exception) {}
    }

    fun stopListening() = activity.runOnUiThread {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { netRecognizer?.cancel() } catch (_: Exception) {}
    }

    // ---------- speaking ----------
    /** Optional chosen Google/Samsung voice name (e.g. "en-us-x-tpf-network"). */
    @Volatile var voiceName: String = ""
    @Volatile var rate: Float = 1.05f

    fun speak(text: String, lang: String) {
        val t = tts
        if (t == null || !ttsReady) { js("window.zenixOnSpeakDone && window.zenixOnSpeakDone()"); return }
        try {
            val chosen = if (voiceName.isNotEmpty() && !lang.startsWith("fil")) t.voices?.firstOrNull { it.name == voiceName } else null
            if (chosen != null) t.voice = chosen else t.language = Locale.forLanguageTag(lang)
            t.setSpeechRate(rate)
        } catch (_: Exception) {}
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "z" + System.currentTimeMillis())
    }

    /** High-quality English voices installed on the phone, best first. */
    fun listVoices(): String {
        val arr = org.json.JSONArray()
        val t = tts ?: return arr.toString()
        try {
            t.voices?.filter { it.locale.language == "en" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired })
                ?.take(16)
                ?.forEach { v ->
                    arr.put(JSONObject().put("name", v.name).put("locale", v.locale.toLanguageTag())
                        .put("quality", v.quality).put("network", v.isNetworkConnectionRequired))
                }
        } catch (_: Exception) {}
        return arr.toString()
    }

    fun stopSpeaking() { try { tts?.stop() } catch (_: Exception) {} }

    fun shutdown() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { netRecognizer?.destroy() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        exec.execute { try { llm?.close() } catch (_: Exception) {}; llm = null }
    }
}
