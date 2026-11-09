package com.offgrid.messenger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/** Android system owns notification rendering; no untrusted custom RemoteViews. */
class OffGridNotifications(private val context:Context) {
    companion object {
        const val MESSAGES = "offgrid_messages_v24"
        const val CONNECTION = "offgrid_connection_v24"
        const val PERSISTENT_ID = 2400
    }
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(MESSAGES,"Private messages",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Encrypted messages arriving from your nearby OffGrid node"
            enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(CONNECTION,"BLE connection",NotificationManager.IMPORTANCE_LOW).apply {
            description="Connection to your OffGrid ESP32 while the app is in the background"
        })
    }
    private fun intent():PendingIntent {
        val open=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_chat",true)
        }
        return PendingIntent.getActivity(context,240,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun canNotify():Boolean = Build.VERSION.SDK_INT<33 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED

    fun newMessage(message:ChatItem,showPreview:Boolean) {
        if(!canNotify())return
        val body=if(!showPreview)"Open OffGrid to view your encrypted message" else when(message.kind) {
            OgmCodec.IMAGE -> "📷 Photo received"
            OgmCodec.VOICE -> "🎤 Voice message received"
            else -> message.text.take(160)
        }
        val sender=Person.Builder().setName("Nearby node").setImportant(true).build()
        val me=Person.Builder().setName("You").build()
        val style=NotificationCompat.MessagingStyle(me).setConversationTitle("OffGrid · Nearby node")
            .addMessage(body,message.timestamp,sender)
        val notification=NotificationCompat.Builder(context,MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nearby node")
            .setContentText(body)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF367CDB.toInt())
            .setAutoCancel(true)
            .setGroup("offgrid_chat")
            .setContentIntent(intent())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context,MESSAGES)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("OffGrid")
                .setContentText("New encrypted message").build())
            .build()
        try { NotificationManagerCompat.from(context).notify((message.id.hashCode() and 0x7fffffff).coerceAtLeast(1),notification) }
        catch (_:SecurityException) { /* permission can be revoked while app runs */ }
    }
    fun connection():Notification = NotificationCompat.Builder(context,CONNECTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("OffGrid · BLE active")
        .setContentText("Keeping your private node connection available")
        .setContentIntent(intent())
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
}
