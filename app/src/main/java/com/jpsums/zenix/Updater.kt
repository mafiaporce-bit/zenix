package com.jpsums.zenix

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Checks GitHub for a newer Maya and installs it with one tap. */
class Updater(private val activity: MainActivity) {

    companion object {
        const val BASE = "https://github.com/mafiaporce-bit/zenix/releases/download/latest/"
        const val APK_NAME = "ZeniX-update.apk"
    }

    private var latestCode = 0L
    private var latestName = ""
    /** True while a download is running, so a second tap never restarts it. */
    @Volatile private var busy = false

    private fun currentCode(): Long {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    private fun apkFile(): File? = activity.getExternalFilesDir("updates")?.let { File(it, APK_NAME) }

    /** Version number inside a downloaded APK (0 when the file is missing or broken). */
    private fun apkCode(f: File): Long = try {
        if (!f.exists() || f.length() < 100_000) 0L
        else activity.packageManager.getPackageArchiveInfo(f.absolutePath, 0)?.let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L
    } catch (_: Exception) { 0L }

    private fun js(code: String) = activity.runJs(code)

    /** Runs in the background; tells the screen via window.zenixUpdateAvailable(name). */
    fun check(manual: Boolean = false) {
        thread {
            try {
                val conn = URL(BASE + "version.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt)
                latestCode = j.optLong("versionCode", 0)
                latestName = j.optString("versionName", "")
                if (latestCode > currentCode()) {
                    js("window.zenixUpdateAvailable && window.zenixUpdateAvailable(${JSONObject.quote(latestName)}, $manual)")
                    notifyOnce(latestCode, latestName)
                } else {
                    // already up to date: an old downloaded installer is no use any more
                    if (!busy) try { apkFile()?.delete() } catch (_: Exception) { }
                    if (manual) js("window.zenixUpToDate && window.zenixUpToDate(${JSONObject.quote(latestName)})")
                }
            } catch (_: Exception) {
                if (manual) js("window.zenixUpdateCheckFailed && window.zenixUpdateCheckFailed()")
            }
        }
    }

    /** A phone notification (once per version) so the update can't be missed. */
    private fun notifyOnce(code: Long, name: String) {
        val prefs = activity.getSharedPreferences("zenix", Context.MODE_PRIVATE)
        if (prefs.getLong("notified_update", 0L) >= code) return
        if (MainActivity.inForeground) return   // the banner in the app is enough
        prefs.edit().putLong("notified_update", code).apply()
        try {
            val open = android.app.PendingIntent.getActivity(activity, 21,
                Intent(activity, MainActivity::class.java).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(activity, MainActivity.UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("New Maya v$name is ready")
                .setContentText("Tap to update")
                .setContentIntent(open).setAutoCancel(true).build()
            activity.getSystemService(android.app.NotificationManager::class.java).notify(31, n)
        } catch (_: Exception) { }
    }

    /**
     * "Update now" tapped. Answers: "permission" (Android is asking to allow installs),
     * "downloading" (progress follows via window.zenixUpdateProgress), "ready" (installer opened), "failed".
     */
    fun start(): String {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.runOnUiThread { try { activity.startActivity(i) } catch (_: Exception) { } }
            return "permission"
        }
        if (busy) return "downloading"                      // a second tap must not start over
        val f = apkFile() ?: return "failed"
        val have = apkCode(f)
        if (have > currentCode() && have >= latestCode) {   // already downloaded: just open the installer again
            return if (install()) "ready" else "failed"
        }
        try { f.delete() } catch (_: Exception) { }
        val dm = activity.getSystemService(DownloadManager::class.java) ?: return "failed"
        val id = try {
            dm.enqueue(DownloadManager.Request(Uri.parse(BASE + "ZeniX.apk"))
                .setTitle("Maya update")
                .setDescription("Downloading the new Maya")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(activity, "updates", APK_NAME))
        } catch (_: Exception) { return "failed" }
        busy = true
        watch(dm, id)
        return "downloading"
    }

    /** Follows the download and keeps the screen informed, so it never looks stuck. */
    private fun watch(dm: DownloadManager, id: Long) {
        thread {
            var lastPct = -1
            var waitingSince = 0L
            try {
                while (busy) {
                    Thread.sleep(600)
                    var status = -1; var reason = 0; var done = 0L; var total = 0L
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        }
                    }
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            busy = false
                            js("window.zenixUpdateProgress && window.zenixUpdateProgress(100, '')")
                            if (!install()) failed("The download finished but the installer didn't open.")
                            return@thread
                        }
                        DownloadManager.STATUS_FAILED -> { busy = false; failed(why(reason)); return@thread }
                        -1 -> { busy = false; failed("The download was cancelled."); return@thread }
                        DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                            val now = System.currentTimeMillis()
                            if (waitingSince == 0L) waitingSince = now
                            if (now - waitingSince > 4000) {
                                val note = if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) "Waiting for Wi-Fi…" else "Waiting for the internet…"
                                js("window.zenixUpdateProgress && window.zenixUpdateProgress(${maxOf(lastPct, 0)}, ${JSONObject.quote(note)})")
                            }
                            if (now - waitingSince > 90_000) {
                                busy = false
                                try { dm.remove(id) } catch (_: Exception) { }
                                failed("The download couldn't start. Check the internet and try again."); return@thread
                            }
                        }
                        else -> {
                            waitingSince = 0L
                            val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0
                            if (pct != lastPct) { lastPct = pct; js("window.zenixUpdateProgress && window.zenixUpdateProgress($pct, '')") }
                        }
                    }
                }
            } catch (_: Exception) {
                busy = false
                failed("The download stopped. Try again.")
            }
        }
    }

    private fun why(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space on the phone for the update."
        DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
            "The internet dropped during the download. Try again."
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ALREADY_EXISTS ->
            "The phone couldn't save the update file."
        else -> if (reason in 400..599) "The update server answered with an error ($reason). Try again in a minute." else "The download didn't finish. Try again."
    }

    private fun failed(msg: String) = js("window.zenixUpdateFailed && window.zenixUpdateFailed(${JSONObject.quote(msg)})")

    /** Opens Android's installer for the downloaded file. */
    private fun install(): Boolean {
        val f = apkFile()
        if (f == null || !f.exists() || f.length() < 100_000) return false
        return try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.runOnUiThread {
                try { activity.startActivity(i) }
                catch (_: Exception) { failed("The installer didn't open. Use \"Download in browser\" instead.") }
            }
            js("window.zenixUpdateReady && window.zenixUpdateReady()")
            true
        } catch (_: Exception) { false }
    }

    /** Plan B: let the phone's browser download the APK. */
    fun openInBrowser(): Boolean = try {
        activity.runOnUiThread {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "ZeniX.apk")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
        true
    } catch (_: Exception) { false }
}
