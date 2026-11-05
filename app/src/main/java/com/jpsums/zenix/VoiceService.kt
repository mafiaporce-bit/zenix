package com.jpsums.zenix

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Foreground "voice call" service. While it runs, Android lets Maya keep using
 * the microphone in any app, and it shows her floating orb over other apps.
 */
class VoiceService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var orb: OrbView? = null
    private var params: WindowManager.LayoutParams? = null
    private var speaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onDestroy() {
        removeOrb()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            MainActivity.instance?.get()?.endChatFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endChat = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, MainActivity.VOICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Maya is listening")
            .setContentText("Keep talking — she can hear you in any app")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "End chat", endChat).build())
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MainActivity.inForeground) showOrb()
        return START_NOT_STICKY
    }

    // ---------- floating orb ----------
    @SuppressLint("ClickableViewAccessibility")
    fun showOrb() = main.post {
        if (orb != null || !Settings.canDrawOverlays(this)) return@post
        val size = (72 * resources.displayMetrics.density).toInt()
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX ?: (resources.displayMetrics.widthPixels - size - (8 * resources.displayMetrics.density).toInt())
            y = savedY ?: (resources.displayMetrics.heightPixels / 2 - size / 2)
        }
        val view = OrbView(this)
        view.setSpeaking(speaking)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    if (moved) {
                        p.x = startX + dx; p.y = startY + dy
                        savedX = p.x; savedY = p.y
                        try { windowManager().updateViewLayout(v, p) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { startActivity(i) } catch (_: Exception) { }
                    }
                    true
                }
                else -> false
            }
        }
        try {
            windowManager().addView(view, p)
            orb = view; params = p
        } catch (_: Exception) { }
    }

    fun hideOrb() = main.post { removeOrb() }

    fun setSpeaking(on: Boolean) = main.post {
        speaking = on
        orb?.setSpeaking(on)
    }

    private fun removeOrb() {
        orb?.let { try { windowManager().removeView(it) } catch (_: Exception) { } }
        orb = null; params = null
    }

    private fun windowManager() = getSystemService(WindowManager::class.java)

    companion object {
        const val ACTION_END = "com.jpsums.zenix.END_CHAT"
        private const val NOTIF_ID = 7
        @Volatile var current: VoiceService? = null
        private var savedX: Int? = null
        private var savedY: Int? = null
    }
}
