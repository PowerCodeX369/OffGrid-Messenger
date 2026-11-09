package com.offgrid.messenger

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.content.pm.ServiceInfo
import android.Manifest
import android.content.pm.PackageManager

/** Opt-in connected-device foreground service.
 * Keeps process priority up while the user switches apps or multitasks.
 * Does not bypass the OS's background start limits or guarantee reconnection after force-stop.
 */
class OffGridConnectionService:Service() {
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val note=OffGridNotifications(this).connection()
        if(Build.VERSION.SDK_INT>=29) startForeground(
            OffGridNotifications.PERSISTENT_ID,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        ) else {
            @Suppress("DEPRECATION")
            startForeground(OffGridNotifications.PERSISTENT_ID,note)
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val optedIn=getSharedPreferences("offgrid_premium_24",MODE_PRIVATE)
            .getBoolean("ble_background",false)
        if(!optedIn){stopSelf();return START_NOT_STICKY}
        val permitted=Build.VERSION.SDK_INT<31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        if(permitted) runCatching { BleSessionHolder.ensureConnected(this) }
            .onFailure { android.util.Log.w("OffGridBLE", "Foreground reconnect unavailable", it) }
        // The system may recreate a killed service (but not after a force-stop).
        // Android / Samsung retain the right to terminate background activity.
        return START_STICKY
    }
    override fun onTaskRemoved(rootIntent:Intent?) {
        // Don't stop the user-enabled connected-device service when Recents is cleared.
        // This is NOT a workaround for force-stop or Samsung battery restrictions.
        super.onTaskRemoved(rootIntent)
    }
}
