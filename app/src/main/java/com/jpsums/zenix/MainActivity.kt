package com.jpsums.zenix

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    private lateinit var webView: WebView
    lateinit var brain: OfflineBrain
    lateinit var updater: Updater
    lateinit var ears: DeepgramEars
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, android.webkit.GeolocationPermissions.Callback>? = null
    private var pageReady = false
    private var pendingWake = false
    private var pendingUpdate = false
    private var pendingRoutine: String? = null
    private var lastRoutine = ""
    private var lastRoutineAt = 0L
    private var overLock = false

    companion object {
        const val CHANNEL_ID = "zenix_reminders"
        const val VOICE_CHANNEL_ID = "zenix_voice"
        @Volatile var instance: java.lang.ref.WeakReference<MainActivity>? = null
        @Volatile var inForeground = false
        private const val PERM_REQUEST = 42
        private const val PERM_GEO = 43
        private const val PICK_BACKUP = 77
        private const val PICK_RING = 78
        const val UPDATE_CHANNEL_ID = "zenix_updates"
        val ALL_PERMISSIONS: Array<String>
            get() {
                val list = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
                if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
                return list.toTypedArray()
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0D0B17")
        createNotificationChannel()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        instance = java.lang.ref.WeakReference(this)
        webView = WebView(this)
        setContentView(webView)
        // Keep Maya's page running at full speed while another app is open
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(true)
        }
        brain = OfflineBrain(this)
        updater = Updater(this)
        ears = DeepgramEars(this)
        webView.addJavascriptInterface(ZenixBridge(this), "ZenixNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                if (pendingWake) { pendingWake = false; view.postDelayed({ onWake() }, 900) }
                if (pendingUpdate) { pendingUpdate = false; view.postDelayed({ runJs("window.zenixStartUpdate && window.zenixStartUpdate()") }, 1200) }
                pendingRoutine?.let { r -> pendingRoutine = null; view.postDelayed({ runRoutine(r) }, 1100) }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            /** The page wants the microphone (voice chat) or the camera ("what's this?"). */
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val missing = webNeeds(request).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (webNeeds(request).isEmpty()) request.deny()
                    else if (missing.isEmpty()) answerWeb(request)
                    else {
                        pendingWebPermission?.let { try { it.deny() } catch (_: Exception) { } }
                        pendingWebPermission = request
                        requestPermissions(missing.toTypedArray(), PERM_REQUEST)
                    }
                }
            }

            /** The map asked "where am I?": pass on Android's location permission (asking Jim the first time). */
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                runOnUiThread {
                    if (hasLocation()) callback.invoke(origin, true, false)
                    else {
                        pendingGeo?.let { (o, cb) -> try { cb.invoke(o, false, false) } catch (_: Exception) { } }
                        pendingGeo = origin to callback
                        try {
                            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERM_GEO)
                        } catch (_: Exception) { pendingGeo = null; callback.invoke(origin, false, false) }
                    }
                }
            }
        }

        if (intent?.getBooleanExtra("wake", false) == true) { pendingWake = true; showOverLock(true) }
        if (intent?.getBooleanExtra("update", false) == true) pendingUpdate = true
        intent?.getStringExtra("routine")?.let { pendingRoutine = it; intent.removeExtra("routine"); if (isLocked()) showOverLock(true) }
        Routines.scheduleAll(this)
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        requestAllPermissions()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("wake", false)) { showOverLock(true); if (pageReady) onWake() else pendingWake = true }
        if (intent.getBooleanExtra("update", false)) { if (pageReady) runJs("window.zenixStartUpdate && window.zenixStartUpdate()") else pendingUpdate = true }
        intent.getStringExtra("routine")?.let { r ->
            intent.removeExtra("routine")
            if (isLocked()) showOverLock(true)
            if (pageReady) runRoutine(r) else pendingRoutine = r
        }
    }

    /** Lets Maya appear (and wake the screen) on top of the lock screen after "Hey Maya", without unlocking. */
    fun showOverLock(on: Boolean) {
        runOnUiThread {
            overLock = on
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on) }
            else {
                @Suppress("DEPRECATION")
                val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                if (on) window.addFlags(f) else window.clearFlags(f)
            }
        }
    }

    /** Android permissions behind what the page asked for. */
    private fun webNeeds(request: PermissionRequest): List<String> {
        val out = ArrayList<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) out.add(Manifest.permission.RECORD_AUDIO)
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) out.add(Manifest.permission.CAMERA)
        return out
    }

    /** Gives the page the parts Jim has allowed (or says no). */
    private fun answerWeb(request: PermissionRequest) {
        val ok = ArrayList<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ok.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        try { if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray()) } catch (_: Exception) { }
    }

    fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocked(): Boolean = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    /** Shows the fingerprint / face / PIN prompt; the page hears back via window.zenixUnlocked(ok). */
    fun askUnlock() {
        runOnUiThread {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (!km.isKeyguardLocked) { runJs("window.zenixUnlocked && window.zenixUnlocked(true)"); return@runOnUiThread }
            if (Build.VERSION.SDK_INT >= 26) km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { runJs("window.zenixUnlocked && window.zenixUnlocked(true)") }
                override fun onDismissCancelled() { runJs("window.zenixUnlocked && window.zenixUnlocked(false)") }
                override fun onDismissError() { runJs("window.zenixUnlocked && window.zenixUnlocked(false)") }
            })
        }
    }

    /** The chat that "Hey Maya" started is over: hand the screen back to the lock screen. */
    fun wakeChatEnded() {
        runOnUiThread {
            if (!overLock) return@runOnUiThread
            val locked = isLocked()
            showOverLock(false)
            if (locked) moveTaskToBack(true)
        }
    }

    /** "Hey Maya" was heard: start a voice chat. */
    fun onWake() {
        clearWakeAlert()
        runJs("window.zenixWake && window.zenixWake()")
    }

    /** A routine's time has come (morning brief, a timed reminder…): Maya carries it out and says it aloud. */
    fun runRoutine(json: String) {
        val now = System.currentTimeMillis()
        if (json == lastRoutine && now - lastRoutineAt < 20_000) return   // the alert and the direct opening can both arrive
        lastRoutine = json; lastRoutineAt = now
        if (!pageReady) { pendingRoutine = json; return }
        runJs("window.zenixRoutine && window.zenixRoutine(${org.json.JSONObject.quote(json)})")
    }

    private fun clearWakeAlert() {
        try { getSystemService(NotificationManager::class.java).cancel(WakeWordService.ALERT_ID) } catch (_: Exception) { }
    }

    fun wakeCommand(action: String?) {
        val i = android.content.Intent(this, WakeWordService::class.java)
        if (action != null) i.action = action
        try { if (action == null) startForegroundService(i) else startService(i) } catch (_: Exception) { }
    }

    fun pickBackupFile() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, PICK_BACKUP) } catch (_: Exception) { runJs("window.toast && toast('No file picker found')") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_RING) {
            if (resultCode == RESULT_OK) {
                @Suppress("DEPRECATION")
                val uri = data?.getParcelableExtra<android.net.Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                val isDefault = uri == null || uri == android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                AlarmRinger.setSound(this, if (isDefault) null else uri)
                runJs("window.renderRing && window.renderRing()")
            }
            return
        }
        if (requestCode != PICK_BACKUP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        kotlin.concurrent.thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                runJs("window.zenixBackupPicked && window.zenixBackupPicked(${org.json.JSONObject.quote(text)})")
            } catch (e: Exception) { runJs("window.zenixBackupPicked && window.zenixBackupPicked('')") }
        }
    }

    /** Android's own list of alarm sounds. */
    fun pickRingSound() {
        val i = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Maya alarm sound")
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmRinger.sound(this))
        try { startActivityForResult(i, PICK_RING) } catch (_: Exception) { runJs("window.toast && toast('No sound picker found on this phone')") }
    }

    fun requestAllPermissions() {
        val missing = ALL_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERM_REQUEST)
    }

    /** Called when a voice chat starts/ends: keeps the mic alive while other apps are open. */
    fun setKeepAwake(on: Boolean) {
        runOnUiThread {
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                try { startForegroundService(android.content.Intent(this, VoiceService::class.java)) } catch (_: Exception) { }
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stopService(android.content.Intent(this, VoiceService::class.java))
            }
        }
    }

    /** "End chat" tapped in the notification. */
    fun endChatFromNotification() {
        runOnUiThread { webView.evaluateJavascript("window.zenixEnd && window.zenixEnd()", null) }
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        clearWakeAlert()
        VoiceService.current?.hideOrb()
    }

    override fun onPause() {
        inForeground = false
        VoiceService.current?.showOrb()
        super.onPause()
    }

    fun runJs(code: String) {
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(code, null) }
    }

    override fun onDestroy() {
        if (::brain.isInitialized) brain.shutdown()
        if (::ears.isInitialized) ears.close()
        stopService(android.content.Intent(this, VoiceService::class.java))
        if (instance?.get() === this) { instance = null; inForeground = false }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_GEO) {
            pendingGeo?.let { (origin, cb) -> try { cb.invoke(origin, hasLocation(), false) } catch (_: Exception) { } }
            pendingGeo = null
            return
        }
        pendingWebPermission?.let { req -> pendingWebPermission = null; answerWeb(req) }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Maya reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Reminders set by Maya"
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(UPDATE_CHANNEL_ID) == null) {
            val ch = NotificationChannel(UPDATE_CHANNEL_ID, "Maya updates", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Tells you when a new Maya is ready"
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(VOICE_CHANNEL_ID) == null) {
            val ch = NotificationChannel(VOICE_CHANNEL_ID, "Maya voice chat", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows while Maya is listening"
            nm.createNotificationChannel(ch)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
