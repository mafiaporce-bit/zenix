package com.jpsums.zenix

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Driving mode: on while the phone is connected to Jim's car Bluetooth (or switched on by hand).
 * While it's on, Maya reads new messages aloud and may read / reply with the phone still locked.
 */
object Driving {
    private const val NOTIF = 12
    private fun prefs(c: Context) = c.getSharedPreferences("zenix", Context.MODE_PRIVATE)

    fun carAddress(c: Context): String = prefs(c).getString("car_addr", "") ?: ""
    fun carName(c: Context): String = prefs(c).getString("car_name", "") ?: ""
    fun setCar(c: Context, address: String, name: String) { prefs(c).edit().putString("car_addr", address).putString("car_name", name).apply() }

    /** Switched on by the car is forgotten after 8 hours, in case a "disconnected" signal was ever missed. */
    fun isOn(c: Context): Boolean {
        val p = prefs(c)
        if (!p.getBoolean("driving", false)) return false
        if (p.getBoolean("driving_manual", false)) return true
        return System.currentTimeMillis() - p.getLong("driving_at", 0L) < 8 * 3600_000L
    }

    fun set(c: Context, on: Boolean, manual: Boolean) {
        val was = isOn(c)
        prefs(c).edit().putBoolean("driving", on).putBoolean("driving_manual", on && manual).putLong("driving_at", System.currentTimeMillis()).apply()
        val nm = c.getSystemService(NotificationManager::class.java)
        try {
            if (on) {
                val open = PendingIntent.getActivity(c, 5, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                nm.notify(NOTIF, Notification.Builder(c, MainActivity.VOICE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle("Maya driving mode is on")
                    .setContentText("New messages are read aloud. Say \"Hey Maya\" to reply.")
                    .setOngoing(true).setContentIntent(open).build())
            } else nm.cancel(NOTIF)
        } catch (_: Exception) { }
        if (was != on) MainActivity.instance?.get()?.runJs("window.zenixDriving && window.zenixDriving($on)")
    }
}

/** Hears the car's Bluetooth connect and disconnect. */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val car = Driving.carAddress(c); if (car.isEmpty()) return
        val dev: BluetoothDevice? = try {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (_: Exception) { null }
        if (dev == null || !dev.address.equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Driving.set(c, true, false)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Driving.set(c, false, false)
        }
    }
}
