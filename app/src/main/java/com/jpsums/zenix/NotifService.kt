package com.jpsums.zenix

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lets Zenix see message notifications (WhatsApp, Messenger, texts, email…) so she can read them
 * out, sum up what Jim missed, and answer through the notification's own Reply button.
 * Nothing leaves the phone except what Jim asks Zenix about.
 */
class NotifService : NotificationListenerService() {

    data class Msg(val key: String, val pkg: String, val app: String, val who: String, val group: String, val text: String, val time: Long)

    companion object {
        @Volatile var instance: NotifService? = null
        private val store = ArrayList<Msg>()
        private val APPS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.orca" to "Messenger", "com.facebook.mlite" to "Messenger",
            "com.samsung.android.messaging" to "Messages", "com.google.android.apps.messaging" to "Messages",
            "org.telegram.messenger" to "Telegram", "com.viber.voip" to "Viber", "im.thebot.messenger" to "BOTIM",
            "com.instagram.android" to "Instagram", "com.snapchat.android" to "Snapchat",
            "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook", "com.samsung.android.email.provider" to "Email")
        private val CHAT_APPS = setOf("WhatsApp", "WhatsApp Business", "Messenger", "Messages", "Telegram", "Viber", "BOTIM")

        fun isOn(c: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

        private fun prefs(c: Context) = c.getSharedPreferences("zenix", Context.MODE_PRIVATE)
        private fun has(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && (a.contains(b, true) || b.contains(a, true))

        /** New messages as plain lines for Zenix to read or sum up. */
        fun recent(c: Context, app: String, who: String, minutes: Int, onlyNew: Boolean): String {
            if (!isOn(c)) return JSONObject().put("ok", false).put("message", "Notification access for Zenix is off. Tell Jim to turn it on in Settings, Messages & notifications.").toString()
            instance?.seed()
            val since = if (minutes > 0) System.currentTimeMillis() - minutes * 60_000L
                        else if (onlyNew && who.isBlank() && app.isBlank()) maxOf(prefs(c).getLong("notif_read_at", 0L), System.currentTimeMillis() - 48 * 3600_000L)
                        else System.currentTimeMillis() - 24 * 3600_000L   // asking about one person or app: the last day
            val list = synchronized(store) { store.filter { m ->
                m.time > since && (app.isBlank() || has(m.app, app)) && (who.isBlank() || has(m.who, who) || has(m.group, who)) } }
                .sortedBy { it.time }.takeLast(30)
            if (who.isBlank() && app.isBlank()) prefs(c).edit().putLong("notif_read_at", System.currentTimeMillis()).apply()
            if (list.isEmpty()) return JSONObject().put("ok", true).put("message", if (who.isBlank() && app.isBlank() && minutes <= 0) "No new messages since he last asked." else "No messages found.").toString()
            val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
            val sb = StringBuilder()
            for (m in list) sb.append("${m.app} · ${m.who}${if (m.group.isNotBlank()) " in group \"${m.group}\"" else ""} (${fmt.format(Date(m.time))}): ${m.text.take(400)}\n")
            return JSONObject().put("ok", true).put("message", sb.toString().trim()).toString()
        }

        /** Answers through the notification's Reply button (no need to open the app). */
        fun reply(c: Context, who: String, app: String, text: String): String {
            val fail = { m: String -> JSONObject().put("ok", false).put("message", m).toString() }
            val svc = instance ?: return fail(if (isOn(c)) "Zenix's message reader is still starting. Use send_message instead." else "Notification access for Zenix is off, so use send_message instead.")
            if (who.isBlank() || text.isBlank()) return fail("Who is the reply for, and what should it say?")
            val active = try { svc.activeNotifications ?: emptyArray() } catch (_: Exception) { emptyArray<StatusBarNotification>() }
            val found = ArrayList<Triple<StatusBarNotification, Notification.Action, String>>()
            for (sbn in active.sortedByDescending { it.postTime }) {
                val appName = APPS[sbn.packageName] ?: continue
                if (app.isNotBlank() && !has(appName, app)) continue
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
                if (!has(title, who) && !has(conv, who)) continue
                val act = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: continue
                found.add(Triple(sbn, act, "${if (conv.isNotBlank()) conv else title} on $appName"))
            }
            if (found.isEmpty()) return fail("There's no open notification from $who to answer. Use send_message instead.")
            val names = found.map { it.third }.distinct()
            if (names.size > 1) return fail("More than one chat matches $who: ${names.joinToString(", ")}. Ask Jim which one.")
            val (_, act, name) = found[0]
            return try {
                val intent = Intent(); val b = Bundle()
                for (ri in act.remoteInputs) b.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(act.remoteInputs, intent, b)
                act.actionIntent.send(svc, 0, intent)
                JSONObject().put("ok", true).put("message", "Reply sent to $name.").toString()
            } catch (e: Exception) { fail("The reply didn't go through (${e.message}). Use send_message instead.") }
        }
    }

    private var seeded = false
    private val lastSaid = HashMap<String, Long>()

    override fun onListenerConnected() { instance = this; seed() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    /** Picks up what's already in the notification shade (after Zenix restarts). */
    fun seed() {
        if (seeded) return
        try { activeNotifications?.forEach { take(it, false) }; seeded = true } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) try { take(sbn, true) } catch (_: Exception) { } }

    private fun take(sbn: StatusBarNotification, live: Boolean) {
        val app = APPS[sbn.packageName] ?: return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val isGroup = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val fresh = ArrayList<Msg>()
        val styled = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (styled != null && styled.isNotEmpty()) {
            for (p in styled) {
                val b = p as? Bundle ?: continue
                val text = b.getCharSequence("text")?.toString()?.trim() ?: continue
                val person = if (android.os.Build.VERSION.SDK_INT >= 28) (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString() else null
                val sender = (person ?: b.getCharSequence("sender")?.toString())?.trim()
                if (sender.isNullOrEmpty()) continue                       // Jim's own reply shown in the same notification
                if (text.isEmpty()) continue
                fresh.add(Msg(sbn.key, sbn.packageName, app, sender, if (isGroup) conv.ifBlank { title } else "", text, b.getLong("time", sbn.postTime)))
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""
            if (title.isEmpty() || text.isEmpty()) return
            if (title.equals(app, true) || Regex("^\\d+ (new )?(messages|chats)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return   // "5 messages from 2 chats"
            fresh.add(Msg(sbn.key, sbn.packageName, app, title, "", text, sbn.postTime))
        }
        val added = ArrayList<Msg>()
        synchronized(store) {
            for (m in fresh) {
                if (store.any { it.pkg == m.pkg && it.who == m.who && it.text == m.text && Math.abs(it.time - m.time) < 120_000 }) continue
                store.add(m); added.add(m)
            }
            while (store.size > 300) store.removeAt(0)
        }
        // Driving: say the newest one out loud
        val m = added.lastOrNull() ?: return
        if (!live || app !in CHAT_APPS || !Driving.isOn(this)) return
        val now = System.currentTimeMillis(); val k = m.pkg + m.who + m.group
        if (now - (lastSaid[k] ?: 0L) < 6000) return
        lastSaid[k] = now
        val q = { v: String -> JSONObject.quote(v) }
        MainActivity.instance?.get()?.runJs("window.zenixIncoming && window.zenixIncoming(${q(m.app)}, ${q(m.who)}, ${q(m.group)}, ${q(m.text.take(400))})")
    }
}
