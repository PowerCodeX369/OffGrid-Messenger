package com.offgrid.messenger

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.content.pm.ServiceInfo

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
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int = START_NOT_STICKY
}
