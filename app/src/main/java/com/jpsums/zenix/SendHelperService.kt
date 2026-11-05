package com.jpsums.zenix

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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

    data class Job(val app: String, val contact: String, val text: String, val deadline: Long, var stage: Int = 0, var stageAt: Long = 0L)

    companion object {
        @Volatile var instance: SendHelperService? = null
        @Volatile private var job: Job? = null

        fun isOn(c: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains(c.packageName + "/") && list.contains("SendHelperService")
        }

        /** Called right before Maya opens the chat. Returns false if auto-send is off. */
        fun arm(c: Context, app: String, contact: String, text: String): Boolean {
            if (!isOn(c)) return false
            job = Job(app, contact, text, System.currentTimeMillis() + 25_000)
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
            pkg.startsWith("com.whatsapp") -> {
                // chat opens with the text already typed; make sure it's there, then tap Send
                val box = findAll(root) { it.className?.toString()?.contains("EditText") == true }.lastOrNull()
                val typed = box?.text?.toString() ?: ""
                if (typed.isBlank() && box != null) box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.text) })
                val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull() ?: findSendButton(root)
                if (send != null && tap(send)) finish(true, "Sent to ${j.contact} on WhatsApp ✓")
            }
            pkg.contains("messaging") || pkg.contains("mms") -> {
                val send = findSendButton(root)
                if (send != null && tap(send)) finish(true, "Text sent to ${j.contact} ✓")
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
                if (sends.size == 1 && tap(sends[0])) {
                    j.stage = 2
                    main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1200)
                    finish(true, "Sent to ${j.contact} on Messenger ✓")
                    return
                }
                p = node.parent
            }
        }
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
