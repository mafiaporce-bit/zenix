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
    private var pageReady = false
    private var pendingWake = false
    private var pendingUpdate = false

    companion object {
        const val CHANNEL_ID = "zenix_reminders"
        const val VOICE_CHANNEL_ID = "zenix_voice"
        @Volatile var instance: java.lang.ref.WeakReference<MainActivity>? = null
        @Volatile var inForeground = false
        private const val PERM_REQUEST = 42
        private const val PICK_BACKUP = 77
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
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingWebPermission = request
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQUEST)
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }

        if (intent?.getBooleanExtra("wake", false) == true) pendingWake = true
        if (intent?.getBooleanExtra("update", false) == true) pendingUpdate = true
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        requestAllPermissions()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("wake", false)) { if (pageReady) onWake() else pendingWake = true }
        if (intent.getBooleanExtra("update", false)) { if (pageReady) runJs("window.zenixStartUpdate && window.zenixStartUpdate()") else pendingUpdate = true }
    }

    /** "Hey Maya" was heard: start a voice chat. */
    fun onWake() { runJs("window.zenixWake && window.zenixWake()") }

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
        if (requestCode != PICK_BACKUP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        kotlin.concurrent.thread {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                runJs("window.zenixBackupPicked && window.zenixBackupPicked(${org.json.JSONObject.quote(text)})")
            } catch (e: Exception) { runJs("window.zenixBackupPicked && window.zenixBackupPicked('')") }
        }
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
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingWebPermission?.let { req ->
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                req.deny()
            }
            pendingWebPermission = null
        }
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
