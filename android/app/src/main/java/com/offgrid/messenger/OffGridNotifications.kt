package com.offgrid.messenger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** One compact, system-rendered conversation notification (no duplicate app-logo image).
 * Android always shows the small status-bar app icon; the sender's photo appears
 * through the Person/shortcut icon where the OS supports conversation UI.
 */
class OffGridNotifications(private val context:Context) {
    companion object {
        const val MESSAGES="offgrid_messages_v24"
        const val CONNECTION="offgrid_connection_v24"
        const val PERSISTENT_ID=2400
        private const val CHAT_ID=2401
        private const val SHORTCUT_ID="offgrid_nearby_node"
    }
    private val manager=context.getSystemService(NotificationManager::class.java)
    private var contactName="Nearby node"
    private var avatar:ByteArray?=null
    private val recent=ArrayDeque<NotificationCompat.MessagingStyle.Message>()
    init {
        manager.createNotificationChannel(NotificationChannel(MESSAGES,"Private messages",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Messages received from your private OffGrid contact";enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(CONNECTION,"BLE connection",NotificationManager.IMPORTANCE_LOW).apply {
            description="Keeps your connection to an OffGrid ESP32 active"
        })
    }
    fun setContact(name:String,photo:ByteArray?) {contactName=name.take(36).ifBlank {"Nearby node"};avatar=photo}
    private fun icon():IconCompat? = avatar?.let { raw -> runCatching {
        val bitmap=BitmapFactory.decodeByteArray(raw,0,raw.size)
        bitmap?.let {IconCompat.createWithAdaptiveBitmap(it)}
    }.getOrNull() }
    private fun contact():Person = Person.Builder().setName(contactName)
        .setKey("offgrid-peer").setImportant(true).apply {icon()?.let {setIcon(it)}}.build()
    private fun openChat():PendingIntent {
        val intent=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_chat",true)
        }
        return PendingIntent.getActivity(context,240,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun canNotify():Boolean = Build.VERSION.SDK_INT<33 ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
    private fun publishShortcut(sender:Person) {
        runCatching {
            val shortcut=ShortcutInfoCompat.Builder(context,SHORTCUT_ID)
                .setShortLabel(contactName)
                .setLongLived(true)
                .setIntent(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .setPerson(sender)
                .setIcon(icon() ?: IconCompat.createWithResource(context,R.drawable.ic_notification))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context,shortcut)
        }
    }
    fun newMessage(message:ChatItem,showPreview:Boolean) {
        if(!canNotify())return
        val text=if(!showPreview)"New encrypted message" else when(message.kind) {
            OgmCodec.IMAGE -> "Photo"
            OgmCodec.VOICE -> "Voice message"
            else -> message.text.take(150)
        }
        val sender=contact()
        publishShortcut(sender)
        recent.addLast(NotificationCompat.MessagingStyle.Message(text,message.timestamp,sender))
        while(recent.size>4)recent.removeFirst()
        val style=NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        recent.forEach {style.addMessage(it)}
        val notification=NotificationCompat.Builder(context,MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contactName)
            .setContentText(text)
            .setStyle(style)
            .setShortcutId(SHORTCUT_ID)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF2367CD.toInt())
            .setAutoCancel(true)
            .setContentIntent(openChat())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context,MESSAGES)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("OffGrid")
                .setContentText("New encrypted message").build())
            .build()
        try {NotificationManagerCompat.from(context).notify(CHAT_ID,notification)}
        catch (_:SecurityException) {}
    }
    fun connection():Notification = NotificationCompat.Builder(context,CONNECTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("OffGrid · BLE active")
        .setContentText("Private node connection running")
        .setContentIntent(openChat()).setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()
}
