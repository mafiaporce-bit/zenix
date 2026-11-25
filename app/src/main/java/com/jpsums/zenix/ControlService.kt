package com.jpsums.zenix

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Zenix phone control": lets Zenix scroll, swipe, tap and search in whatever app is on screen,
 * and press Back / Home, when Jim tells her to by voice. It never acts by itself.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null

        fun isOn(c: Context): Boolean {
            if (instance != null) return true
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains(c.packageName + "/") && list.contains("ControlService")
        }
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** The app that is on screen right now. */
    fun frontPackage(): String = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    /** Runs one command; the answer comes back through done(ok, message). */
    fun run(action: String, arg: String, done: (Boolean, String) -> Unit) {
        main.post {
            try {
                when (action) {
                    "scroll_down" -> scroll(true, arg == "big", done)
                    "scroll_up" -> scroll(false, arg == "big", done)
                    "swipe_left" -> sideSwipe(true, done)
                    "swipe_right" -> sideSwipe(false, done)
                    "back" -> global(GLOBAL_ACTION_BACK, "Went back.", done)
                    "home" -> global(GLOBAL_ACTION_HOME, "Home screen.", done)
                    "recents" -> global(GLOBAL_ACTION_RECENTS, "Recent apps are open.", done)
                    "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS, "Notifications are open.", done)
                    "quick_settings" -> global(GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings are open.", done)
                    "screenshot" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.", done) else done(false, "Screenshots by voice need a newer Android.")
                    "lock" -> if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN, "Screen locked.", done) else done(false, "Locking by voice needs a newer Android.")
                    "tap" -> tap(arg, done)
                    "tap_first" -> tapFirst(done)
                    "search" -> search(arg, done)
                    "youtube_first" -> youtubeFirst(0, done)
                    "close_app" -> closeFront(done)
                    else -> done(false, "I don't know that screen action.")
                }
            } catch (e: Exception) { done(false, "That didn't work on this screen.") }
        }
    }

    // ---------- gestures ----------
    private fun screen(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else { val dm = resources.displayMetrics; Pair(dm.widthPixels, dm.heightPixels) }
    }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, okMsg: String, done: (Boolean, String) -> Unit) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done(true, okMsg) }
            override fun onCancelled(gestureDescription: GestureDescription?) { done(false, "The screen didn't take the swipe.") }
        }, null)
        if (!sent) done(false, "The screen didn't take the swipe.")
    }

    /** Zenix's floating circle (plus a margin): her own swipes must not start on it or cross it. */
    private fun orbBox(): Rect? = try {
        VoiceService.orbBounds()?.let { val m = (20 * resources.displayMetrics.density).toInt(); Rect(it.left - m, it.top - m, it.right + m, it.bottom + m) }
    } catch (_: Exception) { null }

    /** "Scroll down" moves the page up, like a finger swiping from low to high. */
    private fun scroll(down: Boolean, big: Boolean, done: (Boolean, String) -> Unit) {
        val (w, h) = screen()
        val a = if (big) 0.82f else 0.70f; val b = if (big) 0.18f else 0.32f
        val from = if (down) h * a else h * b; val to = if (down) h * b else h * a
        val o = orbBox(); val top = minOf(from, to); val bot = maxOf(from, to)
        val x = listOf(0.5f, 0.3f, 0.7f).map { w * it }.firstOrNull { cx -> o == null || !(cx >= o.left && cx <= o.right && o.bottom >= top && o.top <= bot) } ?: (w * 0.5f)
        stroke(x, from, x, to, if (big) 140L else 280L, if (down) "Scrolled down." else "Scrolled up.", done)
    }

    private fun sideSwipe(left: Boolean, done: (Boolean, String) -> Unit) {
        val (w, h) = screen()
        val o = orbBox()
        val y = listOf(0.5f, 0.36f, 0.64f).map { h * it }.firstOrNull { cy -> o == null || !(cy >= o.top && cy <= o.bottom) } ?: (h * 0.5f)
        val from = if (left) w * 0.85f else w * 0.15f; val to = if (left) w * 0.15f else w * 0.85f
        stroke(from, y, to, y, 220L, if (left) "Swiped left." else "Swiped right.", done)
    }

    private fun global(action: Int, okMsg: String, done: (Boolean, String) -> Unit) {
        if (performGlobalAction(action)) done(true, okMsg) else done(false, "The phone didn't do that.")
    }

    private fun dbg(what: String, text: String) = try {
        MainActivity.instance?.get()?.runJs("window.zenixDbg && window.zenixDbg(${org.json.JSONObject.quote(what)}, ${org.json.JSONObject.quote(text.take(400))})")
    } catch (_: Exception) { }

    /** Everything readable on screen right now, across the windows a finger could touch. */
    private fun screenNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { for (win in windows) { val r = win.root ?: continue; if (r.packageName?.toString() != packageName) out.addAll(all(r, 1500)) } } catch (_: Exception) { }
        if (out.isEmpty()) out.addAll(all(rootInActiveWindow))
        return out
    }

    /** The card of one app in Recent apps: the biggest thing on screen that carries the app's name. */
    private fun recentCard(name: String): AccessibilityNodeInfo? {
        val want = name.trim().lowercase()
        if (want.isEmpty()) return null
        val word = Regex("(^|[^a-z0-9])" + Regex.escape(want) + "([^a-z0-9]|$)")
        return screenNodes().filter { n ->
            if (!n.isVisibleToUser || isEdit(n)) return@filter false
            val pkg = n.packageName?.toString() ?: ""
            if (!(pkg.contains("launcher") || pkg.contains("systemui") || pkg.contains("recents") || pkg.contains("quickstep"))) return@filter false
            val l = ((n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")).lowercase()
            word.containsMatchIn(l)
        }.maxByOrNull { val r = bounds(it); r.width().toLong() * r.height() }
    }

    /**
     * "Close this app": open Recent apps, find THIS app's card by its name, and flick only that card away.
     * If the card can't be found or doesn't go, nothing else is touched.
     */
    private fun closeFront(done: (Boolean, String) -> Unit) {
        val front = frontPackage()
        if (front == packageName) { done(false, "Zenix is the app on the screen. Open the app you want closed, then tell me."); return }
        if (front.isEmpty() || front.contains("launcher")) { done(false, "No app is open on the screen."); return }
        val name = appName(front)
        if (name.contains("zenix", true)) { done(false, "I won't close myself."); return }
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) { done(false, "The phone didn't open Recent apps."); return }
        findCardThenSwipe(name, 0, done)
    }

    private fun leaveRecents() { try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Exception) { } }

    private fun findCardThenSwipe(name: String, tries: Int, done: (Boolean, String) -> Unit) {
        main.postDelayed({
            try {
                val card = recentCard(name)
                if (card == null) {
                    if (tries < 7) { findCardThenSwipe(name, tries + 1, done); return@postDelayed }   // Recent apps is still opening
                    dbg("close", "no card for '$name'. on screen: " + screenNodes().mapNotNull { n -> label(n).takeIf { it.isNotEmpty() && it.length < 40 } }.distinct().take(25).joinToString(" | "))
                    leaveRecents()
                    done(false, "I opened Recent apps but couldn't find $name there, so I didn't close anything.")
                    return@postDelayed
                }
                val (w, h) = screen()
                val r = bounds(card)
                val cx = r.exactCenterX()
                if (cx < w * 0.12f || cx > w * 0.88f) {   // the card is off to the side: too risky to swipe
                    dbg("close", "card for '$name' is off-centre: $r")
                    leaveRecents()
                    done(false, "I found $name in Recent apps but it wasn't in the middle, so I didn't close anything.")
                    return@postDelayed
                }
                // a small match is just the name above the card: the card itself is in the middle of the screen
                var cy = if (r.height() >= h * 0.25f) r.exactCenterY() else h * 0.5f
                var x = cx
                orbBox()?.let { o -> if (x >= o.left && x <= o.right && o.bottom >= cy - h * 0.6f && o.top <= cy) x = if (o.exactCenterX() > w / 2f) cx - w * 0.12f else cx + w * 0.12f }
                cy = cy.coerceIn(h * 0.3f, h * 0.75f)
                dbg("close", "swiping '$name' card $r from ${x.toInt()},${cy.toInt()}")
                stroke(x, cy, x, maxOf(h * 0.02f, cy - h * 0.6f), 190L, "Closed $name.") { _, _ ->
                    main.postDelayed({
                        try {
                            val still = recentCard(name)?.let { Math.abs(bounds(it).exactCenterX() - cx) < w * 0.15f } ?: false
                            leaveRecents()
                            if (still) { dbg("close", "card for '$name' is still there after the swipe"); done(false, "I tried to swipe $name away but it stayed. I didn't touch any other app.") }
                            else done(true, "Closed $name.")
                        } catch (_: Exception) { leaveRecents(); done(true, "Closed $name.") }
                    }, 750)
                }
            } catch (_: Exception) { leaveRecents(); done(false, "That didn't work on this screen.") }
        }, if (tries == 0) 900L else 350L)
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // ---------- reading the screen ----------
    private fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim() ?: ""
        return if (t.isNotEmpty()) t else (n.contentDescription?.toString()?.trim() ?: "")
    }
    private fun mine(n: AccessibilityNodeInfo) = n.packageName?.toString() == packageName
    private fun isEdit(n: AccessibilityNodeInfo) = n.isEditable || n.className?.toString()?.contains("EditText") == true

    private fun all(root: AccessibilityNodeInfo?, max: Int = 4000): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(); if (root == null) return out
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        while (stack.isNotEmpty() && out.size < max) {
            val n = stack.removeLast(); out.add(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun bounds(n: AccessibilityNodeInfo): Rect { val r = Rect(); n.getBoundsInScreen(r); return r }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        repeat(6) {
            val c = x ?: return@repeat
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            x = c.parent
        }
        // nothing "clickable" there: touch the middle of it
        val r = bounds(n); if (r.width() <= 0 || r.height() <= 0) return false
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()); lineTo(r.exactCenterX() + 1f, r.exactCenterY() + 1f) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    /** "Tap Like", "tap Follow", "tap Skip ad": the button whose name matches best. */
    private fun tap(what: String, done: (Boolean, String) -> Unit) {
        val want = what.trim().lowercase(); if (want.isEmpty()) { done(false, "Tap what?"); return }
        val (w, h) = screen()
        var best: AccessibilityNodeInfo? = null; var bestScore = 0
        // the phone's own search by text is quick; reading the whole screen is the fallback
        val quick = try { rootInActiveWindow?.findAccessibilityNodeInfosByText(what.trim()) } catch (_: Exception) { null }
        val pool = if (quick.isNullOrEmpty()) all(rootInActiveWindow) else quick
        for (n in pool) {
            if (!n.isVisibleToUser || isEdit(n) || mine(n)) continue
            val l = label(n).lowercase(); if (l.isEmpty() || l.length > 80) continue
            var score = when {
                l == want -> 100
                l.startsWith("$want ") || l.startsWith("$want,") -> 70
                Regex("\\b" + Regex.escape(want) + "\\b").containsMatchIn(l) -> 50 - minOf(30, l.length - want.length)
                else -> 0
            }
            if (score == 0) continue
            if (n.isClickable) score += 5
            val r = bounds(n); if (r.width() <= 0 || r.bottom <= 0 || r.top >= h || r.right <= 0 || r.left >= w) continue
            if (score > bestScore) { bestScore = score; best = n }
        }
        val n = best
        if (n == null) { done(false, "I can't see \"$what\" on the screen."); return }
        if (click(n)) done(true, "Tapped ${label(n).take(40)}.") else done(false, "I found \"$what\" but couldn't tap it.")
    }

    /** "Tap the first one / first video": the first big item in the main list on screen. */
    private fun tapFirst(done: (Boolean, String) -> Unit) {
        val (w, _) = screen()
        val minH = (56 * resources.displayMetrics.density).toInt()
        val lists = all(rootInActiveWindow).filter { it.isScrollable && it.isVisibleToUser && !mine(it) }
            .sortedByDescending { val r = bounds(it); r.width().toLong() * r.height() }
        for (list in lists) {
            for (i in 0 until list.childCount) {
                val c = list.getChild(i) ?: continue
                val r = bounds(c)
                if (!c.isVisibleToUser || r.height() < minH || r.width() < w * 0.4) continue
                if (click(c)) { done(true, "Opened the first one."); return }
            }
        }
        done(false, "I can't tell which one is first on this screen.")
    }

    /** After a YouTube search: start the first real video (not an ad). */
    private fun youtubeFirst(tries: Int, done: (Boolean, String) -> Unit) {
        val nodes = all(rootInActiveWindow).filter { n ->
            n.isVisibleToUser && n.packageName?.toString()?.contains("youtube") == true &&
                (n.contentDescription?.toString() ?: "").let { d -> d.contains("play video", true) || (d.contains("views", true) && d.length > 25) } &&
                !(n.contentDescription?.toString() ?: "").contains("sponsored", true)
        }.sortedBy { bounds(it).top }
        val n = nodes.firstOrNull()
        if (n != null && click(n)) { done(true, "Playing it."); return }
        if (tries < 8) { main.postDelayed({ try { youtubeFirst(tries + 1, done) } catch (_: Exception) { done(false, "YouTube opened, tap the video you want.") } }, 700); return }
        done(false, "YouTube shows the results. Tap the one you want.")
    }

    // ---------- search inside the app on screen ----------
    private fun searchBox(): AccessibilityNodeInfo? = all(rootInActiveWindow).firstOrNull { n ->
        n.isVisibleToUser && isEdit(n) && !mine(n) && run {
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() ?: "" else ""
            val id = n.viewIdResourceName ?: ""
            (hint + " " + label(n) + " " + id).contains("search", true) || (hint + " " + label(n)).contains("find", true)
        }
    }

    private fun search(query: String, done: (Boolean, String) -> Unit) {
        if (query.isBlank()) { done(false, "Search for what?"); return }
        val box = searchBox()
        if (box != null) { type(box, query, done); return }
        // no search box showing: look for the app's search button (the magnifier)
        val btn = all(rootInActiveWindow).firstOrNull { n ->
            n.isVisibleToUser && !isEdit(n) && !mine(n) && label(n).lowercase().let { l ->
                l == "search" || l.startsWith("search ") || l == "find" || l == "search facebook" || l == "search youtube" }
        }
        if (btn == null || !click(btn)) { done(false, "I can't see a search box in this app."); return }
        waitForBox(0, query, done)
    }

    private fun waitForBox(tries: Int, query: String, done: (Boolean, String) -> Unit) {
        main.postDelayed({
            try {
                val box = searchBox() ?: all(rootInActiveWindow).firstOrNull { it.isVisibleToUser && isEdit(it) && it.isFocused && !mine(it) }
                if (box != null) type(box, query, done)
                else if (tries < 5) waitForBox(tries + 1, query, done)
                else done(false, "The search opened but I couldn't type in it.")
            } catch (_: Exception) { done(false, "That didn't work on this screen.") }
        }, 500)
    }

    private fun type(box: AccessibilityNodeInfo, query: String, done: (Boolean, String) -> Unit) {
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) })
        if (!ok) { done(false, "I couldn't type in the search box."); return }
        main.postDelayed({
            try {
                val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: box
                val entered = Build.VERSION.SDK_INT >= 30 && target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (entered) { done(true, "Searching for $query."); return@postDelayed }
                // no Enter on this phone/app: tap the first suggestion that shows the words
                val sug = all(rootInActiveWindow).firstOrNull { n -> n.isVisibleToUser && !isEdit(n) && !mine(n) && label(n).equals(query, true) }
                if (sug != null && click(sug)) done(true, "Searching for $query.") else done(true, "I typed $query in the search box. Tap search to go.")
            } catch (_: Exception) { done(true, "I typed $query in the search box.") }
        }, 450)
    }
}
