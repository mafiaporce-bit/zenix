package com.jpsums.zenix

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private var downloadId = -1L
    private var latestCode = 0L
    private var latestName = ""

    private fun currentCode(): Long {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun currentName(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""

    /** Runs in the background; tells the screen via window.zenixUpdateAvailable(name). */
    fun check() {
        thread {
            try {
                val conn = URL(BASE + "version.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt)
                latestCode = j.optLong("versionCode", 0)
                latestName = j.optString("versionName", "")
                if (latestCode > currentCode()) {
                    activity.runJs("window.zenixUpdateAvailable && window.zenixUpdateAvailable(${JSONObject.quote(latestName)})")
                }
            } catch (_: Exception) { /* offline or no release yet */ }
        }
    }

    fun start(): String {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.runOnUiThread { activity.startActivity(i) }
            return "permission"
        }
        val dir = activity.getExternalFilesDir("updates") ?: return "failed"
        File(dir, APK_NAME).delete()
        val dm = activity.getSystemService(DownloadManager::class.java)
        val req = DownloadManager.Request(Uri.parse(BASE + "ZeniX.apk"))
            .setTitle("Maya update")
            .setDescription("Downloading the new Maya")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, "updates", APK_NAME)
        downloadId = dm.enqueue(req)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                try { activity.unregisterReceiver(this) } catch (_: Exception) {}
                install()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else activity.registerReceiver(receiver, filter)
        return "downloading"
    }

    private fun install() {
        val f = File(activity.getExternalFilesDir("updates"), APK_NAME)
        if (!f.exists() || f.length() < 100_000) {
            activity.runJs("window.zenixUpdateFailed && window.zenixUpdateFailed()")
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.runOnUiThread { activity.startActivity(i) }
    }
}
