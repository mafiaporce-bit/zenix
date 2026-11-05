package com.jpsums.zenix

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * "Auto-send": after Jim has said YES to a message, Maya opens the chat with the text ready
 * and this service taps Send for him. It only works inside the messaging apps listed in
 * res/xml/maya_accessibility.xml, and only for a few seconds after Maya asks it to.
 */
class SendHelperService : AccessibilityService() {

    data class Job(val app: String, val contact: String, val text: String, val group: Boolean, val deadline: Long,
                   var stage: Int = 0, var stageAt: Long = 0L, var countAt: Long = 0L)

    companion object {
        @Volatile var instance: SendHelperService? = null
        @Volatile private var job: Job? = null

        fun isOn(c: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains(c.packageName + "/") && list.contains("SendHelperService")
        }

        /** Called right before Maya opens the chat. Returns false if auto-send is off. */
        fun arm(c: Context, app: String, contact: String, text: String, group: Boolean = false): Boolean {
            if (!isOn(c)) return false
            job = Job(app, contact, text, group, System.currentTimeMillis() + 30_000)
            instance?.kick()
            return true
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val j = job ?: return
            if (System.currentTimeMillis() > j.deadline) { finish(false, "I couldn't find the Send button in time, so the message is ready for you to tap Send."); return }
            try { step(j) } catch (_: Exception) { }
            if (job != null) main.postDelayed(this, 350)
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (job != null) kick() }

    fun kick() { main.removeCallbacks(tick); main.postDelayed(tick, 250) }

    private fun step(j: Job) {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: ""
        when {
            pkg.startsWith("com.facebook.orca") || pkg.startsWith("com.facebook.mlite") -> messenger(j, root)
            pkg.startsWith("com.whatsapp") && j.group -> whatsappGroup(j, root, pkg)
            pkg.startsWith("com.whatsapp") -> {
                // chat opens with the text already typed; make sure it's there, then tap Send
                val box = findAll(root) { it.className?.toString()?.contains("EditText") == true }.lastOrNull()
                val typed = box?.text?.toString() ?: ""
                if (typed.isBlank() && box != null) box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.text) })
                val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull() ?: findSendButton(root)
                if (send != null && countdownThenTap(j, send)) finish(true, "Sent to ${j.contact} on WhatsApp ✓")
            }
            pkg.contains("messaging") || pkg.contains("mms") -> {
                val send = findSendButton(root)
                if (send != null && countdownThenTap(j, send)) finish(true, "Text sent to ${j.contact} ✓")
            }
        }
    }

    /** Messenger's share sheet: search the name, then tap the Send button on that person's row. */
    private fun messenger(j: Job, root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (j.stage == 0) {
            val search = findAll(root) { it.className?.toString()?.contains("EditText") == true }.firstOrNull() ?: return
            search.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.contact) })
            j.stage = 1; j.stageAt = now; return
        }
        if (j.stage == 1 && now - j.stageAt > 900) {
            val name = j.contact.trim().lowercase()
            val first = name.split(" ").first()
            val label = { n: AccessibilityNodeInfo -> (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim() ?: "" }
            val notBox = { n: AccessibilityNodeInfo -> n.className?.toString()?.contains("EditText") != true }
            var rows = findAll(root) { n -> notBox(n) && label(n).contains(name) }
            if (rows.isEmpty()) {
                rows = findAll(root) { n -> notBox(n) && label(n).split(" ").firstOrNull() == first }
                if (rows.size > 1) { finish(false, "More than one ${j.contact} on Messenger, so pick the right one and tap Send."); return }
            }
            val r = rows.firstOrNull() ?: return
            // climb from the name to its row; the row must hold exactly ONE Send button (never someone else's)
            var p: AccessibilityNodeInfo? = r
            for (level in 0 until 5) {
                val node = p ?: break
                val sends = findAll(node) { n -> sendWords.matches(((n.contentDescription ?: n.text)?.toString() ?: "").trim()) && n.isVisibleToUser }
                if (sends.size > 1) break
                if (sends.size == 1) {
                    if (countdownThenTap(j, sends[0])) {
                        j.stage = 2
                        main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1200)
                        finish(true, "Sent to ${j.contact} on Messenger ✓")
                    }
                    return
                }
                p = node.parent
            }
        }
    }

    /** WhatsApp group (or anyone without a phone number): use WhatsApp's "Send to" list. */
    private fun whatsappGroup(j: Job, root: AccessibilityNodeInfo, pkg: String) {
        val now = System.currentTimeMillis()
        val isBox = { n: AccessibilityNodeInfo -> n.className?.toString()?.contains("EditText") == true }
        when (j.stage) {
            0 -> {
                val box = findAll(root, isBox).firstOrNull()
                if (box == null) {   // open the search first
                    findAll(root) { n -> (n.contentDescription?.toString() ?: "").equals("Search", true) }.firstOrNull()?.let { tap(it) }
                    return
                }
                box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.contact) })
                j.stage = 1; j.stageAt = now
            }
            1 -> if (now - j.stageAt > 900) {
                val name = j.contact.trim().lowercase()
                val label = { n: AccessibilityNodeInfo -> (n.text ?: n.contentDescription)?.toString()?.lowercase()?.trim() ?: "" }
                var rows = findAll(root) { n -> !isBox(n) && label(n) == name }
                if (rows.isEmpty()) rows = findAll(root) { n -> !isBox(n) && label(n).contains(name) }
                if (rows.isEmpty()) return
                if (rows.size > 1 && rows.map { label(it) }.distinct().size > 1) { finish(false, "More than one chat matches ${j.contact}, so pick the right one and tap Send."); return }
                if (tap(rows[0])) { j.stage = 2; j.stageAt = now }
            }
            2 -> {
                val send = findAll(root) { n -> (n.contentDescription?.toString() ?: "").trim().equals("Send", true) && n.isVisibleToUser }.lastOrNull()
                if (send != null && countdownThenTap(j, send)) { j.stage = 3; j.stageAt = now }
            }
            3 -> {
                // some versions open the chat with the text typed in; send it there too
                val entrySend = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
                val typed = findAll(root, isBox).lastOrNull()?.text?.toString()?.trim() ?: ""
                if (entrySend != null && typed.isNotEmpty() && typed == j.text.trim()) { tap(entrySend); finish(true, "Sent to ${j.contact} on WhatsApp ✓") }
                else if (now - j.stageAt > 3500) finish(true, "Sent to ${j.contact} on WhatsApp ✓")
            }
        }
    }

    /** Shows "Sending to X in 3s · tap to cancel", then taps. Returns true once it has tapped. */
    private fun countdownThenTap(j: Job, node: AccessibilityNodeInfo): Boolean {
        val now = System.currentTimeMillis()
        if (j.countAt == 0L) { j.countAt = now; showBubble(); updateBubble(j); return false }
        updateBubble(j)
        if (now - j.countAt < 3000) return false
        hideBubble()
        return tap(node)
    }

    private var bubble: TextView? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun showBubble() {
        if (bubble != null) return
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setPadding(dp(20), dp(13), dp(20), dp(13))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF27C3AED.toInt(), 0xF2D946EF.toInt())).apply { cornerRadius = dp(26).toFloat() }
            elevation = dp(10).toFloat()
            setOnClickListener { cancel() }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(84) }
        try { getSystemService(WindowManager::class.java).addView(tv, lp); bubble = tv } catch (_: Exception) { }
    }
    private fun updateBubble(j: Job) {
        val left = Math.max(1L, (3000 - (System.currentTimeMillis() - j.countAt) + 999) / 1000)
        bubble?.text = "Sending to ${j.contact} in ${left}s  ·  Tap to cancel"
    }
    private fun hideBubble() { bubble?.let { try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) { } }; bubble = null }
    private fun cancel() {
        val j = job ?: return
        job = null; main.removeCallbacks(tick); hideBubble()
        MainActivity.instance?.get()?.runJs("window.zenixSendResult && window.zenixSendResult(false, ${JSONObject.quote("Cancelled. Nothing was sent to ${j.contact}.")})")
    }

    private val sendWords = Regex("^(send|send message|send sms|send mms|ipadala|send now)$", RegexOption.IGNORE_CASE)

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findAll(root) { n ->
            val label = ((n.contentDescription ?: n.text)?.toString() ?: "").trim()
            sendWords.matches(label) && n.isVisibleToUser
        }.lastOrNull()

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        repeat(4) {
            if (x?.isClickable == true && x?.isEnabled == true) return x!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x?.parent
        }
        return false
    }

    private fun findAll(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 1500) {
            val n = stack.removeLast(); seen++
            if (test(n)) out.add(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return out   // top-to-bottom screen order
    }

    private fun finish(ok: Boolean, msg: String) {
        job = null
        main.removeCallbacks(tick)
        hideBubble()
        MainActivity.instance?.get()?.runJs("window.zenixSendResult && window.zenixSendResult($ok, ${JSONObject.quote(msg)})")
        if (ok) main.postDelayed({
            // come back to Maya so the chat can carry on
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) { }
        }, 1600)
    }
}
