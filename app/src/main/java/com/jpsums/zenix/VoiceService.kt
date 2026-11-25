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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * Foreground "voice call" service. While it runs, Android lets Zenix keep using
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
            .setContentTitle("Zenix is listening")
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

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var overX = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; overX = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) { moved = true; showCloseTarget() }
                    if (moved) {
                        p.x = startX + dx; p.y = startY + dy
                        // near the ✕ at the bottom: snap onto it
                        val now = nearClose(p.x + size / 2, p.y + size / 2)
                        if (now != overX) { overX = now; highlightClose(now); if (now) buzz(25) }
                        if (overX) { val (cx, cy) = closeCenter(); p.x = cx - size / 2; p.y = cy - size / 2 }
                        try { windowManager().updateViewLayout(v, p) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideCloseTarget()
                    if (moved && overX) {
                        buzz(60)
                        savedX = null; savedY = null
                        removeOrb()
                        MainActivity.instance?.get()?.endChatFromNotification()   // dropped on ✕: close the circle and end the chat
                    } else if (moved) {
                        savedX = p.x; savedY = p.y
                    } else if (e.action == MotionEvent.ACTION_UP) {
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

    // ---------- the ✕ that appears at the bottom while the circle is dragged ----------
    private var closeView: TextView? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun closeCenter(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels / 2, dm.heightPixels - dp(150))
    }
    private fun nearClose(x: Int, y: Int): Boolean {
        val (cx, cy) = closeCenter()
        val dx = (x - cx).toDouble(); val dy = (y - cy).toDouble()
        return Math.sqrt(dx * dx + dy * dy) < dp(80)
    }
    private fun showCloseTarget() {
        if (closeView != null) return
        val sz = dp(64)
        val tv = TextView(this).apply {
            text = "✕"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1B1733.toInt()); setStroke(dp(2), 0x99FFFFFF.toInt()) }
            alpha = 0f
        }
        val (cx, cy) = closeCenter()
        val lp = WindowManager.LayoutParams(sz, sz, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = cx - sz / 2; y = cy - sz / 2 }
        try { windowManager().addView(tv, lp); closeView = tv; tv.animate().alpha(1f).setDuration(150).start() } catch (_: Exception) { }
        // keep the circle above the ✕
        orb?.let { o -> params?.let { pp -> try { windowManager().removeView(o); windowManager().addView(o, pp) } catch (_: Exception) { } } }
    }
    private fun highlightClose(on: Boolean) {
        closeView?.let { v ->
            v.animate().scaleX(if (on) 1.3f else 1f).scaleY(if (on) 1.3f else 1f).setDuration(120).start()
            (v.background as? GradientDrawable)?.setColor(if (on) 0xEEE5484D.toInt() else 0xCC1B1733.toInt())
        }
    }
    private fun hideCloseTarget() {
        closeView?.let { try { windowManager().removeView(it) } catch (_: Exception) { } }
        closeView = null
    }
    private fun buzz(ms: Long) {
        try {
            val vib = getSystemService(android.os.Vibrator::class.java)
            vib?.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    fun setSpeaking(on: Boolean) = main.post {
        speaking = on
        orb?.setSpeaking(on)
    }

    private fun removeOrb() {
        hideCloseTarget()
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

        /** Where the floating circle is on the screen right now (null when it isn't showing). */
        fun orbBounds(): android.graphics.Rect? {
            val s = current ?: return null
            val p = s.params ?: return null
            if (s.orb == null) return null
            return android.graphics.Rect(p.x, p.y, p.x + p.width, p.y + p.height)
        }
    }
}
