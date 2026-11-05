package com.jpsums.zenix

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything ZeniX can do on the phone. Each method returns a JSON string {ok, message}. */
class ZenixBridge(private val activity: MainActivity) {

    private fun ok(msg: String) = JSONObject().put("ok", true).put("message", msg).toString()
    private fun fail(msg: String) = JSONObject().put("ok", false).put("message", msg).toString()
    private fun has(p: String) = activity.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.runOnUiThread { activity.startActivity(intent) }
        true
    } catch (e: Exception) { false }

    // ---------- basics ----------
    @JavascriptInterface fun hasMicPermission(): String = has(Manifest.permission.RECORD_AUDIO).toString()
    @JavascriptInterface fun requestPermissions(): String { activity.runOnUiThread { activity.requestAllPermissions() }; return ok("Asking for permissions.") }
    @JavascriptInterface fun keepAwake(on: String): String { activity.setKeepAwake(on == "true"); return ok("ok") }

    // ---------- contacts ----------
    private data class Contact(val name: String, val number: String)

    private fun findContact(query: String): Contact? {
        val q = query.trim()
        val digits = q.filter { it.isDigit() || it == '+' }
        if (digits.length >= 6 && digits.length >= q.replace(" ", "").length - 2) return Contact(q, digits)
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        var best: Contact? = null
        activity.contentResolver.query(uri, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (name.equals(q, ignoreCase = true)) return Contact(name, num)
                if (best == null) best = Contact(name, num)
            }
        }
        return best
    }

    private fun nameForNumber(number: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return number
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            activity.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else number
            } ?: number
        } catch (e: Exception) { number }
    }

    /** International digits for WhatsApp, assuming UAE numbers when local. */
    private fun intlDigits(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.substring(2)
        else if (d.startsWith("0") && d.length == 10) d = "971" + d.substring(1)
        return d
    }

    private fun lastDigits(n: String) = n.filter { it.isDigit() }.takeLast(9)

    // ---------- alarms & reminders ----------
    @JavascriptInterface
    fun setAlarm(hour: String, minute: String, label: String): String {
        val h = hour.toIntOrNull() ?: return fail("I didn't get the hour.")
        val m = minute.toIntOrNull() ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (launch(i)) ok("Alarm set for %02d:%02d.".format(h, m)) else fail("The Clock app didn't accept the alarm.")
    }

    @JavascriptInterface
    fun setReminder(text: String, minutes: String): String {
        val mins = minutes.toLongOrNull() ?: return fail("I didn't get when to remind you.")
        val at = System.currentTimeMillis() + mins * 60_000
        val id = (at / 1000 % Int.MAX_VALUE).toInt()
        val intent = Intent(activity, ReminderReceiver::class.java).putExtra("text", text).putExtra("id", id)
        val pi = PendingIntent.getBroadcast(activity, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = activity.getSystemService(AlarmManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at))
            ok("Reminder set for $time: $text")
        } catch (e: Exception) { fail("Couldn't set the reminder: ${e.message}") }
    }

    // ---------- apps & web ----------
    @JavascriptInterface
    fun openApp(name: String): String {
        val pm = activity.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val want = name.lowercase(Locale.ROOT).replace(" ", "").removeSuffix("app")
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).replace(" ", "") == want }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).replace(" ", "").startsWith(want) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).replace(" ", "").contains(want) }
            ?: return fail("I couldn't find an app called $name on this phone.")
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return fail("That app can't be opened directly.")
        return if (launch(launchIntent)) ok("Opened ${match.loadLabel(pm)}.") else fail("Couldn't open $name.")
    }

    @JavascriptInterface
    fun openUrl(url: String): String =
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ok("Opened it on your screen.") else fail("Couldn't open the browser.")

    // ---------- calls & messages ----------
    @JavascriptInterface
    fun makeCall(contact: String): String {
        val c = findContact(contact) ?: return fail(
            if (has(Manifest.permission.READ_CONTACTS)) "I couldn't find $contact in your contacts." else "Allow Contacts permission so I can find $contact.")
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        val i = if (has(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        return if (launch(i)) ok("Calling ${c.name}.") else fail("Couldn't start the call.")
    }

    @JavascriptInterface
    fun sendMessage(contact: String, message: String, app: String): String {
        when (app.lowercase(Locale.ROOT)) {
            "messenger" -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message).setPackage("com.facebook.orca")
                return if (launch(i)) ok("Messenger is open with the message ready. Pick $contact and tap send.")
                else fail("Messenger isn't installed.")
            }
            "whatsapp" -> {
                val c = findContact(contact) ?: return fail("I couldn't find $contact's number.")
                val url = "https://api.whatsapp.com/send?phone=${intlDigits(c.number)}&text=${Uri.encode(message)}"
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
                return if (launch(i) ) ok("WhatsApp chat with ${c.name} is open with the message. Tap send.")
                else if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ok("WhatsApp is open with the message. Tap send.")
                else fail("WhatsApp isn't installed.")
            }
            else -> {
                val c = findContact(contact) ?: return fail("I couldn't find $contact's number.")
                if (has(Manifest.permission.SEND_SMS)) {
                    return try {
                        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(SmsManager::class.java)
                                              else @Suppress("DEPRECATION") SmsManager.getDefault()
                        sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(message), null, null)
                        ok("Text sent to ${c.name}.")
                    } catch (e: Exception) { fail("The text didn't go through: ${e.message}") }
                }
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", message)
                return if (launch(i)) ok("Messages app is open with the text to ${c.name}. Tap send.") else fail("Couldn't open Messages.")
            }
        }
    }

    @JavascriptInterface
    fun readMessages(contact: String, count: String): String {
        if (!has(Manifest.permission.READ_SMS)) return fail("I can't read texts in this version, Google blocks that for apps outside the Play Store. Open your Messages app to check.")
        val n = (count.toIntOrNull() ?: 5).coerceIn(1, 15)
        val filter = if (contact.isNotBlank()) findContact(contact)?.let { lastDigits(it.number) } else null
        val out = StringBuilder()
        var found = 0
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        activity.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"),
            null, null, "date DESC")?.use { c ->
            while (c.moveToNext() && found < n) {
                val addr = c.getString(0) ?: ""
                if (filter != null && lastDigits(addr) != filter) continue
                out.append("From ${nameForNumber(addr)} (${fmt.format(Date(c.getLong(2)))}): ${c.getString(1)}\n")
                found++
            }
        }
        return if (found == 0) ok(if (filter != null) "No texts from $contact." else "No text messages found.") else ok(out.toString().trim())
    }

    // ---------- email ----------
    @JavascriptInterface
    fun composeEmail(to: String, subject: String, body: String): String {
        var address = to.trim()
        if (address.isNotEmpty() && !address.contains("@") && has(Manifest.permission.READ_CONTACTS)) {
            activity.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$address%"), null)?.use { c ->
                if (c.moveToFirst()) address = c.getString(0)
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        if (address.contains("@")) i.putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        return if (launch(i)) ok("Your email app is open with the email ready. Tap send.") else fail("No email app found.")
    }

    // ---------- calendar ----------
    @JavascriptInterface
    fun checkCalendar(daysFromToday: String, numDays: String): String {
        if (!has(Manifest.permission.READ_CALENDAR)) return fail("Allow Calendar permission so I can check your schedule.")
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
            add(java.util.Calendar.DAY_OF_YEAR, daysFromToday.toIntOrNull() ?: 0)
        }.timeInMillis
        val end = start + (numDays.toIntOrNull() ?: 1).coerceIn(1, 31) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val out = StringBuilder()
        activity.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.length < 1800) {
                val title = c.getString(0) ?: "(no title)"
                val whenTxt = if (c.getInt(2) == 1) "all day " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(c.getLong(1)))
                              else fmt.format(Date(c.getLong(1)))
                val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                out.append("$whenTxt: $title$loc\n")
            }
        }
        return ok(if (out.isEmpty()) "Nothing on the calendar for that time." else out.toString().trim())
    }

    @JavascriptInterface
    fun addCalendarEvent(title: String, startIso: String, durationMinutes: String, location: String): String {
        val startMs = try { OffsetDateTime.parse(startIso).toInstant().toEpochMilli() } catch (e: Exception) { return fail("I didn't understand the date.") }
        val endMs = startMs + (durationMinutes.toLongOrNull() ?: 60L) * 60_000
        if (has(Manifest.permission.WRITE_CALENDAR) && has(Manifest.permission.READ_CALENDAR)) {
            var calId = -1L
            activity.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (calId == -1L) calId = c.getLong(0)
                    if (c.getInt(1) == 1) { calId = c.getLong(0); break }
                }
            }
            if (calId != -1L) {
                try {
                    val v = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, startMs)
                        put(CalendarContract.Events.DTEND, endMs)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                    }
                    activity.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
                    return ok("Added \"$title\" on ${SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(startMs))}.")
                } catch (_: Exception) { }
            }
        }
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        return if (launch(i)) ok("The calendar is open with the event filled in. Tap save.") else fail("Couldn't open the calendar.")
    }
}
