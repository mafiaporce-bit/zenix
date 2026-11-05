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

/** Everything Maya can do on the phone. Each method returns a JSON string {ok, message}. */
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

    /** Saves the PIN-locked backup into Downloads/Maya (survives clearing Maya's data). */
    @JavascriptInterface fun saveBackup(name: String, text: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Maya")
                }
                val uri = activity.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return fail("Couldn't create the backup file.")
                activity.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ok("saved")
            } else {
                shareText("Maya backup", text)
            }
        } catch (e: Exception) { fail("Couldn't save the backup: ${e.message}") }
    }

    /** Opens the file picker so Jim can choose a backup; the text goes to window.zenixBackupPicked. */
    @JavascriptInterface fun pickBackup(): String {
        activity.runOnUiThread { activity.pickBackupFile() }
        return ok("picking")
    }

    @JavascriptInterface fun shareText(title: String, text: String): String {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        return if (launch(Intent.createChooser(i, title))) ok("Choose where to save it.") else fail("Couldn't share.")
    }

    // ---------- app updates ----------
    @JavascriptInterface fun checkUpdate(): String { activity.updater.check(); return ok("checking") }
    @JavascriptInterface fun checkUpdateNow(): String { activity.updater.check(true); return ok("checking") }
    @JavascriptInterface fun startUpdate(): String = activity.updater.start()
    @JavascriptInterface fun appVersion(): String = activity.updater.currentName()

    // ---------- offline brain ----------
    @JavascriptInterface fun offlineStatus(): String = activity.brain.status()
    @JavascriptInterface fun offlineDownload(): String = activity.brain.download()
    @JavascriptInterface fun offlineDelete(): String = activity.brain.deleteModel()
    @JavascriptInterface fun offlineAsk(id: String, prompt: String): String { activity.brain.ask(id, prompt); return ok("thinking") }
    @JavascriptInterface fun startListening(lang: String): String { activity.brain.startListening(lang); return ok("listening") }
    @JavascriptInterface fun startListeningOnline(lang: String): String { activity.brain.startListening(lang, false); return ok("listening") }
    @JavascriptInterface fun stopListening(): String { activity.brain.stopListening(); return ok("stopped") }
    @JavascriptInterface fun speak(text: String, lang: String): String { activity.brain.speak(text, lang); return ok("speaking") }
    @JavascriptInterface fun stopSpeaking(): String { activity.brain.stopSpeaking(); return ok("stopped") }
    // ---------- "Hey Maya" wake word ----------
    @JavascriptInterface fun wakeStatus(): String = WakeWordService.status(activity)
    @JavascriptInterface fun wakeDownload(): String { WakeWordService.download(activity); return WakeWordService.status(activity) }
    @JavascriptInterface fun wakeEnable(on: String): String {
        if (on == "true") activity.wakeCommand(null) else activity.wakeCommand(WakeWordService.ACTION_OFF)
        return ok("ok")
    }
    @JavascriptInterface fun wakePause(): String { if (WakeWordService.current != null) activity.wakeCommand(WakeWordService.ACTION_PAUSE); return ok("ok") }
    @JavascriptInterface fun wakeResume(): String { if (WakeWordService.current != null) activity.wakeCommand(WakeWordService.ACTION_RESUME); return ok("ok") }

    // ---------- Deepgram ears (native) ----------
    @JavascriptInterface fun dgOpen(key: String, lang: String): String { activity.ears.open(key, lang); return ok("opening") }
    @JavascriptInterface fun dgOpenFlux(key: String): String { activity.ears.open(key, "en", true); return ok("opening") }
    @JavascriptInterface fun dgSending(on: String): String { activity.ears.setSending(on == "true"); return ok("ok") }
    @JavascriptInterface fun dgClose(): String { activity.ears.close(); return ok("closed") }
    @JavascriptInterface fun dgTest(key: String): String { activity.ears.test(key); return ok("testing") }

    /** Deepgram Aura voice: fetches MP3 in the background, returns it via window.zenixDG(id, base64, error). */
    @JavascriptInterface fun dgSpeak(id: String, text: String, model: String, key: String): String {
        kotlin.concurrent.thread {
            val q = { v: String -> JSONObject.quote(v) }
            try {
                val url = java.net.URL("https://api.deepgram.com/v1/speak?model=" + java.net.URLEncoder.encode(model, "UTF-8") + "&encoding=mp3")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 8000; c.readTimeout = 20000
                c.setRequestProperty("Authorization", "Token $key")
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = try { c.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    activity.runJs("window.zenixDG && window.zenixDG(${q(id)}, '', ${q("$code $err".take(200))})")
                } else {
                    val bytes = c.inputStream.use { it.readBytes() }
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    activity.runJs("window.zenixDG && window.zenixDG(${q(id)}, '$b64', '')")
                }
            } catch (e: Exception) {
                activity.runJs("window.zenixDG && window.zenixDG(${q(id)}, '', ${q(e.message ?: "network error")})")
            }
        }
        return ok("fetching")
    }

    /** ElevenLabs text-to-speech for one sentence; MP3 goes back to the page as base64. */
    @JavascriptInterface fun elSpeak(id: String, text: String, voiceId: String, key: String, model: String): String {
        kotlin.concurrent.thread {
            val q = { v: String -> JSONObject.quote(v) }
            try {
                val url = java.net.URL("https://api.elevenlabs.io/v1/text-to-speech/" + java.net.URLEncoder.encode(voiceId, "UTF-8") + "?output_format=mp3_44100_64")
                val c = url.openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 8000; c.readTimeout = 25000
                c.setRequestProperty("xi-api-key", key)
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "audio/mpeg")
                val expressive = model.startsWith("eleven_v3") || model.startsWith("eleven_v4")
                val vs = if (expressive) JSONObject().put("stability", 0.5)
                         else JSONObject().put("stability", 0.45).put("similarity_boost", 0.75).put("speed", 1.05)
                val body = JSONObject().put("text", text).put("model_id", model).put("voice_settings", vs)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = try { c.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    activity.runJs("window.zenixEL && window.zenixEL(${q(id)}, '', ${q("$code $err".take(300))})")
                } else {
                    val bytes = c.inputStream.use { it.readBytes() }
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    activity.runJs("window.zenixEL && window.zenixEL(${q(id)}, '$b64', '')")
                }
            } catch (e: Exception) {
                activity.runJs("window.zenixEL && window.zenixEL(${q(id)}, '', ${q(e.message ?: "network error")})")
            }
        }
        return ok("fetching")
    }

    /** Lists the voices in Jim's ElevenLabs account (also works as a key test) plus credits left. */
    @JavascriptInterface fun elVoices(key: String): String {
        kotlin.concurrent.thread {
            val q = { v: String -> JSONObject.quote(v) }
            fun get(path: String): Pair<Int, String> {
                val c = java.net.URL("https://api.elevenlabs.io$path").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                c.setRequestProperty("xi-api-key", key)
                val code = c.responseCode
                val txt = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                return code to txt
            }
            try {
                val (code, txt) = get("/v1/voices")
                val list = org.json.JSONArray()
                var err = ""
                if (code in 200..299) {
                    val arr = JSONObject(txt).optJSONArray("voices") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val v = arr.getJSONObject(i)
                        val labels = v.optJSONObject("labels")
                        list.put(JSONObject().put("id", v.optString("voice_id")).put("name", v.optString("name"))
                            .put("gender", labels?.optString("gender") ?: "").put("lang", labels?.optString("language") ?: "")
                            .put("cat", v.optString("category")))
                    }
                } else err = "$code ${txt.take(200)}"
                var left = -1L
                try {
                    val (sc, st) = get("/v1/user/subscription")
                    if (sc in 200..299) { val s = JSONObject(st); left = s.optLong("character_limit") - s.optLong("character_count") }
                } catch (_: Exception) { }
                activity.runJs("window.zenixELVoices && window.zenixELVoices(${q(list.toString())}, ${q(err)}, $left)")
            } catch (e: Exception) {
                activity.runJs("window.zenixELVoices && window.zenixELVoices('[]', ${q(e.message ?: "network error")}, -1)")
            }
        }
        return ok("checking")
    }

    @JavascriptInterface fun finishListening(): String { activity.brain.finishListening(); return ok("ok") }
    @JavascriptInterface fun listVoices(): String = activity.brain.listVoices()
    @JavascriptInterface fun setPhoneVoice(name: String, rate: String): String {
        activity.brain.voiceName = name; activity.brain.rate = rate.toFloatOrNull() ?: 1.05f; return ok("ok")
    }

    // ---------- floating orb ----------
    @JavascriptInterface fun hasOverlay(): String = android.provider.Settings.canDrawOverlays(activity).toString()
    @JavascriptInterface fun requestOverlay(): String {
        val i = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
        return if (launch(i)) ok("Opening the setting.") else fail("Couldn't open the setting.")
    }
    @JavascriptInterface fun setOrbMode(mode: String): String { VoiceService.current?.setSpeaking(mode == "speaking"); return ok("ok") }

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
        if (best != null) return best
        // Try each word on its own ("call Mark from work" -> "Mark")
        for (word in q.split(" ").filter { it.length >= 3 }) {
            activity.contentResolver.query(uri, proj,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$word%"), null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0); val num = c.getString(1)
                    if (name != null && num != null && c.count == 1) return Contact(name, num)
                }
            }
        }
        return null
    }

    /** Close-match contact names, so Maya can ask "did you mean…". */
    private fun suggestContacts(query: String): List<String> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashSet<String>()
        val words = query.trim().split(" ").filter { it.length >= 2 }
        for (w in words) {
            val key = if (w.length > 3) w.substring(0, 3) else w
            activity.contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                arrayOf("%$key%"), null)?.use { c ->
                while (c.moveToNext() && out.size < 5) c.getString(0)?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun notFound(contact: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return fail("Allow Contacts permission so I can find $contact.")
        val s = suggestContacts(contact)
        return if (s.isEmpty()) fail("I couldn't find $contact in your contacts.")
               else fail("I couldn't find exactly $contact. Close matches: ${s.joinToString(", ")}. Ask Jim which one.")
    }

    @JavascriptInterface
    fun openCallHistory(): String {
        val i = Intent(Intent.ACTION_VIEW).setType(android.provider.CallLog.Calls.CONTENT_TYPE)
        if (launch(i)) return ok("Your call history is open on the screen.")
        return if (launch(Intent(Intent.ACTION_DIAL))) ok("The Phone app is open. Tap Recents for your call history.")
               else fail("Couldn't open the Phone app.")
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
        val c = findContact(contact) ?: return notFound(contact)
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        val i = if (has(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        return if (launch(i)) ok("Calling ${c.name}.") else fail("Couldn't start the call.")
    }

    @JavascriptInterface
    fun sendMessage(contact: String, message: String, app: String): String {
        when (app.lowercase(Locale.ROOT)) {
            "messenger" -> {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message).setPackage("com.facebook.orca")
                val auto = SendHelperService.arm(activity, "messenger", contact, message)
                return if (launch(i)) ok(if (auto) "Sending it to $contact on Messenger now." else "Messenger is open with the message ready. Pick $contact and tap send.")
                else fail("Messenger isn't installed.")
            }
            "whatsapp" -> {
                val c = findContact(contact) ?: return notFound(contact)
                val url = "https://api.whatsapp.com/send?phone=${intlDigits(c.number)}&text=${Uri.encode(message)}"
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.whatsapp")
                val auto = SendHelperService.arm(activity, "whatsapp", c.name, message)
                return if (launch(i) ) ok(if (auto) "Sending it to ${c.name} on WhatsApp now." else "WhatsApp chat with ${c.name} is open with the message. Tap send.")
                else if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ok(if (auto) "Sending it to ${c.name} on WhatsApp now." else "WhatsApp is open with the message. Tap send.")
                else fail("WhatsApp isn't installed.")
            }
            else -> {
                val c = findContact(contact) ?: return notFound(contact)
                if (has(Manifest.permission.SEND_SMS)) {
                    return try {
                        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) activity.getSystemService(SmsManager::class.java)
                                              else @Suppress("DEPRECATION") SmsManager.getDefault()
                        sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(message), null, null)
                        ok("Text sent to ${c.name}.")
                    } catch (e: Exception) { fail("The text didn't go through: ${e.message}") }
                }
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", message)
                val auto = SendHelperService.arm(activity, "sms", c.name, message)
                return if (launch(i)) ok(if (auto) "Sending the text to ${c.name} now." else "Messages app is open with the text to ${c.name}. Tap send.") else fail("Couldn't open Messages.")
            }
        }
    }

    /** Auto-send (accessibility) status and setup screens. */
    @JavascriptInterface fun autoSendOn(): String = if (SendHelperService.isOn(activity)) "true" else "false"
    @JavascriptInterface fun openAutoSendSettings(): String =
        if (launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))) ok("Find Maya auto-send and turn it on.") else fail("Couldn't open Accessibility settings.")
    @JavascriptInterface fun openAppInfo(): String =
        if (launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)))) ok("ok") else fail("Couldn't open app info.")

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
