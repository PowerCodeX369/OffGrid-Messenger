package com.offgrid.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.ArrayDeque
import java.util.regex.Pattern

private val Sapphire = Color(0xFF2563EB)
private val Navy = Color(0xFF0C172B)
private val PaleBlue = Color(0xFFEAF1FF)
private val Aqua = Color(0xFF60B9EE)
private val mainHandler = Handler(Looper.getMainLooper())

data class ChatItem(val id:String,val text:String,val outgoing:Boolean,val timestamp:Long,
    val status:String="sent",val kind:Int=OgmCodec.TEXT,val bytes:ByteArray?=null,
    val mime:String="",val width:Int=0,val height:Int=0,val durationMs:Int=0,val progress:Int=0, val speedKiBs:Float=0f, val edited:Boolean=false, val deleted:Boolean=false)
private data class Transfer(val frames:List<ByteArray>,val messageId:String,val control:Boolean=false,
    val media:Boolean=false,
    var index:Int=0,var session:String="",var packetId:String="",var attempts:Int=0,
    var bleToken:Int?=null,
    var mediaWindow:MediaTransferWindow?=null)

class MainActivity : ComponentActivity() {
    private lateinit var secure: SecureKeyStore
    private var link: BleSessionHolder? = null
    private lateinit var history: EncryptedHistory
    private lateinit var notifications: OffGridNotifications
    private val historyWorker = Executors.newSingleThreadExecutor()
    private var scheduledHistory: Runnable? = null
    private var appResumed by mutableStateOf(false)
    private var chatScreenVisible by mutableStateOf(true)
    private var notificationsEnabled by mutableStateOf(true)
    private var notificationPreview by mutableStateOf(false)
    private var keepBleBackground by mutableStateOf(false)
    private var accessibleVoiceTap by mutableStateOf(false)
    private val assembler = OgmAssembler { secure.readPairingKey() }
    private val items = mutableStateListOf<ChatItem>()
    private val pending = ArrayDeque<Transfer>()
    private var isSending = false
    private var activeMedia:MediaTransferWindow?=null
    private var fastPhotos by mutableStateOf(true)
    private val mediaAcks = RadioMediaAckBatcher(
        { action, timeout -> mainHandler.postDelayed(action, timeout) },
        { action -> mainHandler.removeCallbacks(action) },
        { command -> link?.send(command) }
    )
    private var ackTimeout: Runnable? = null
    private var connected by mutableStateOf(false)
    private var radioLink by mutableStateOf<RadioLink?>(null)
    private var state by mutableStateOf("Not connected")
    private var trustedAddress by mutableStateOf<String?>(null)
    private var pairingSaved by mutableStateOf(false)
    private var details by mutableStateOf(false)
    private var dark by mutableStateOf(false)
    private var contactName by mutableStateOf("Nearby node")
    private var avatarBytes by mutableStateOf<ByteArray?>(null)
    private val bleDiagnostics = mutableStateListOf<String>()
    private var pickerHandled = false
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private lateinit var voiceRecorder:VoiceNoteRecorder
    private var recording by mutableStateOf(false)
    private var playingId by mutableStateOf<String?>(null)
    private var voicePlayer:MediaPlayer?=null
    private var voicePlaybackFile:File?=null
    private var recordAutoStop:Runnable?=null
    private val playedReceipts=mutableSetOf<String>()
    private val hiddenMessageIds=mutableSetOf<String>()
    private var activityDestroyed=false

    private fun bleTrace(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        bleDiagnostics.add("$time  $message")
        while (bleDiagnostics.size > 24) bleDiagnostics.removeAt(0)
    }

    private fun acceptBleSelection(address: String?, origin: String) {
        val mac = address?.trim()?.uppercase(java.util.Locale.US)
        if (mac.isNullOrBlank() || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            bleTrace("$origin returned no valid Bluetooth MAC")
            state = "Could not read selected BLE device; choose it again"
            return
        }
        if (pickerHandled && trustedAddress == mac) return
        pickerHandled = true
        trustedAddress = mac
        secure.saveNodeAddress(mac)
        bleTrace("BLE device selected from $origin: $mac")
        reconnectIfPermitted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        secure = SecureKeyStore(this)
        history = EncryptedHistory(this)
        notifications = OffGridNotifications(this)
        val preferences=getSharedPreferences("offgrid_premium_24",MODE_PRIVATE)
        contactName=preferences.getString("contact_name","Nearby node") ?: "Nearby node"
        notifications.setContact(contactName,null)
        historyWorker.execute {
            runCatching {history.loadAvatar()}.onSuccess { data ->
                mainHandler.post {if(!activityDestroyed){avatarBytes=data;notifications.setContact(contactName,data)}}
            }
        }
        notificationsEnabled=preferences.getBoolean("notifications",true)
        notificationPreview=preferences.getBoolean("previews",false)
        keepBleBackground=preferences.getBoolean("ble_background",false)
        accessibleVoiceTap=preferences.getBoolean("accessible_voice_tap",false)
        historyWorker.execute {
            val hidden=runCatching { history.loadHiddenIds() }.getOrElse {
                mainHandler.post {bleTrace("Hidden message ledger unavailable: ${it.javaClass.simpleName}")}
                emptySet()
            }
            runCatching { history.load() }.onSuccess { stored ->
                mainHandler.post {
                    if(!activityDestroyed) {
                        hiddenMessageIds.addAll(hidden)
                        val present=items.map { it.id }.toSet()
                        val old=stored.filterNot {it.id in present || it.id in hiddenMessageIds}
                            .map { if(it.outgoing && it.status=="sending")it.copy(status="failed") else it }
                        items.addAll(0,old)
                        playedReceipts.addAll(old.filter { !it.outgoing && it.status=="played" }.map{it.id})
                        if(old.isNotEmpty())bleTrace("Restored ${old.size} encrypted messages from device")
                    }
                }
            }.onFailure { mainHandler.post { if(!activityDestroyed)bleTrace("Encrypted history unavailable: ${it.javaClass.simpleName}") } }
        }
        fastPhotos = getSharedPreferences("offgrid_media", MODE_PRIVATE).getBoolean("fast_photos", true)
        voiceRecorder = VoiceNoteRecorder(this)
        trustedAddress = secure.nodeAddress()
        pairingSaved = secure.readPairingKey()?.let { it.fill(0);true } ?: false
        dark = preferences.getBoolean("dark",(resources.configuration.uiMode and 0x30) == 0x20)
        link = BleSessionHolder
        BleSessionHolder.attach(this, {s,ready ->
            state=s;connected=ready
            if(ready) startNext() else {
                radioLink=null
                activeMedia?.cancelTransfer(); activeMedia=null
                pending.forEach { it.mediaWindow=null; it.index=0 }
                mediaAcks.clear()
                isSending=false; cancelAckTimeout()
                if(recording) {
                    recordAutoStop?.let(mainHandler::removeCallbacks);recordAutoStop=null
                    runCatching { voiceRecorder.finish(cancel=true) };recording=false
                }
            }
        }, ::consumeLine, ::bleTrace)
        setContent { OffGridScreen() }
        if(keepBleBackground) mainHandler.post { toggleBackground(true) }
        if(trustedAddress!=null && pairingSaved) mainHandler.post { reconnectIfPermitted() }
    }
    override fun onDestroy() {
        activityDestroyed=true
        stopVoicePlayback()
        if (recording) runCatching { voiceRecorder.finish(cancel=true) }
        recordAutoStop?.let(mainHandler::removeCallbacks)
        mediaWorker.shutdownNow()
        activeMedia?.cancelTransfer();activeMedia=null;mediaAcks.clear()
        scheduledHistory?.let(mainHandler::removeCallbacks)
        val finalSnapshot=items.toList()
        historyWorker.execute { runCatching { history.save(finalSnapshot) } }
        historyWorker.shutdown()
        BleSessionHolder.detach()
        if(!keepBleBackground && !isChangingConfigurations) link?.stop()
        cancelAckTimeout();super.onDestroy()
    }
    override fun onResume() { super.onResume(); appResumed=true }
    override fun onPause() { appResumed=false; super.onPause() }

    private fun scheduleHistorySave() {
        scheduledHistory?.let(mainHandler::removeCallbacks)
        val save=Runnable {
            if(!activityDestroyed) {
                val snapshot=items.toList()
                historyWorker.execute { runCatching { history.save(snapshot) }.onFailure {
                    Log.e("OffGridHistory","Encrypted history write failed",it)
                } }
            }
        }
        scheduledHistory=save
        mainHandler.postDelayed(save,250L)
    }
    private fun markVisible(ids:Set<String>) {
        if(!appResumed || !connected || ids.isEmpty())return
        var changed=false
        ids.forEach { id ->
            val index=items.indexOfFirst {it.id==id && !it.outgoing && it.status=="delivered"}
            if(index>=0){
                controlReceipt(OgmCodec.READ,id)
                items[index]=items[index].copy(status="read")
                changed=true
            }
        }
        if(changed)scheduleHistorySave()
    }
    private fun toggleBackground(enable:Boolean) {
        if(enable && !hasBluetoothPermission()){
            state="Grant Nearby devices permission before enabling background connection";return
        }
        val prefs=getSharedPreferences("offgrid_premium_24",MODE_PRIVATE)
        try {
            // Save before starting: onStartCommand can run before this method returns.
            prefs.edit().putBoolean("ble_background",enable).apply()
            if(enable) ContextCompat.startForegroundService(this,Intent(this,OffGridConnectionService::class.java))
            else stopService(Intent(this,OffGridConnectionService::class.java))
            keepBleBackground=enable
        } catch(ex:Exception){
            prefs.edit().putBoolean("ble_background",false).apply()
            state="Background service unavailable: ${ex.message}"
            keepBleBackground=false
        }
    }
    private fun retryFailed(item:ChatItem) {
        if(!connected || !item.outgoing || item.status!="failed")return
        val index=items.indexOfFirst {it.id==item.id}
        if(index<0)return
        items[index]=item.copy(status="sending",progress=0)
        when(item.kind){
            OgmCodec.TEXT -> pushEnvelope(item.kind,item.id,item.text.toByteArray(Charsets.UTF_8))
            OgmCodec.IMAGE -> item.bytes?.let {
                pushEnvelope(item.kind,item.id,MediaPayload.photoBody(item.mime,it,item.width,item.height),
                    width=item.width,height=item.height)
            }
            OgmCodec.VOICE -> item.bytes?.let {
                val dims=MediaPayload.durationParts(item.durationMs)
                pushEnvelope(item.kind,item.id,MediaPayload.voiceBody(item.mime,it,item.durationMs),
                    width=dims.first,height=dims.second)
            }
        }
        scheduleHistorySave()
    }
    private fun deleteForMe(item:ChatItem) {
        val idx=items.indexOfFirst { it.id==item.id }
        if(idx>=0) {
            if(playingId==item.id)stopVoicePlayback()
            // Suppress duplicate radio delivery and stale history restores first.
            hiddenMessageIds.add(item.id)
            items.removeAt(idx)
            val deletedSnapshot=hiddenMessageIds.toSet()
            historyWorker.execute {runCatching {history.saveHiddenIds(deletedSnapshot)} }
            scheduleHistorySave()
            state="Message removed from this device"
        }
    }
    private fun editOutgoing(item:ChatItem,updated:String) {
        if(!connected || !item.outgoing || item.deleted || item.kind!=OgmCodec.TEXT ||
            item.status !in listOf("delivered","read","played") ||
            updated.isBlank() || updated.codePointCount(0,updated.length)>MessageMutation.MAX_EDIT_CHARS)return
        val idx=items.indexOfFirst {it.id==item.id}
        if(idx<0)return
        val body=MessageMutation.editPayload(item.id,updated)
        pushEnvelope(OgmCodec.EDIT,OgmCodec.newId(),body,control=true)
        items[idx]=items[idx].copy(text=updated,edited=true)
        scheduleHistorySave()
    }
    private fun deleteForEveryone(item:ChatItem) {
        if(!connected || !item.outgoing || item.deleted ||
            item.status !in listOf("delivered","read","played"))return
        val idx=items.indexOfFirst {it.id==item.id}
        if(idx<0)return
        pushEnvelope(OgmCodec.DELETE,OgmCodec.newId(),MessageMutation.deletePayload(item.id),control=true)
        items[idx]=items[idx].copy(text="You deleted this message",bytes=null,deleted=true,edited=false)
        scheduleHistorySave()
    }

    private fun cancelVoiceRecording() {
        if(!recording)return
        recordAutoStop?.let(mainHandler::removeCallbacks);recordAutoStop=null
        runCatching { voiceRecorder.finish(cancel=true) }
        recording=false
        state="Voice recording cancelled"
    }
    private fun hasBluetoothPermission():Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun reconnectIfPermitted() {
        if(!hasBluetoothPermission()){state="Bluetooth permission required";return}
        if(!pairingSaved){state="Enter pairing key";return}
        trustedAddress?.let { link?.connect(it) }
    }

    private fun cancelAckTimeout(){ackTimeout?.let(mainHandler::removeCallbacks);ackTimeout=null}
    private fun waitForAck(phase: String) {
        cancelAckTimeout()
        val runnable = Runnable {
            val job=pending.firstOrNull() ?: return@Runnable
            if(!connected)return@Runnable
            if(job.attempts>=3){
                pending.removeFirst();isSending=false
                items.indexOfFirst{it.id==job.messageId}.takeIf{it>=0}?.let{idx -> items[idx]=items[idx].copy(status="failed")}
                state="Radio confirmation timed out; retry message"
                startNext();return@Runnable
            }
            job.attempts++
            // Radio failed or the queued response disappeared. Re-submit the same encrypted fragment.
            job.bleToken=null;link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
            waitForAck("queued")
        }
        ackTimeout=runnable
        mainHandler.postDelayed(runnable,if(phase=="queued")7500 else 16000)
    }
    private fun startMediaWindow(job:Transfer) {
        isSending=true
        val sender = job.mediaWindow ?: MediaTransferWindow(
            frames = job.frames, window = 3,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            schedule = { alarm, delay -> mainHandler.postDelayed(alarm,delay) },
            cancel = { alarm -> mainHandler.removeCallbacks(alarm) },
            sendFrame = { frame -> link?.sendFrame(frame) },
            onProgress = { count, elapsedMs ->
                if(count % 8 == 0 || count == job.frames.size) {
                    val idx=items.indexOfFirst { it.id == job.messageId }
                    if(idx >= 0) {
                        val message=items[idx]
                        val bytes=(message.bytes?.size ?: 0).toDouble()
                        val rate=(bytes * count / job.frames.size / 1024.0 / (elapsedMs/1000.0)).toFloat()
                        items[idx]=message.copy(progress=100*count/job.frames.size, speedKiBs=rate)
                    }
                }
            },
            onFinished = { elapsedMs ->
                bleTrace("MEDIA_TX_COMPLETE kind=${if(job.frames.size>1)"batch" else "single"} frames=${job.frames.size} duration_ms=$elapsedMs")
                activeMedia=null;job.mediaWindow=null
                updateReceipt(job.messageId,"sent")
                pending.remove(job);isSending=false
                startNext()
            },
            onFailed = { reason ->
                bleTrace("MEDIA_TX_FAILED $reason")
                activeMedia=null;job.mediaWindow=null
                updateReceipt(job.messageId,"sent")
                pending.remove(job);isSending=false
                updateReceipt(job.messageId,"failed")
                state="Media transfer failed: $reason"
                startNext()
            },
            onPaused = {
                if(activeMedia === job.mediaWindow) {
                    activeMedia=null;isSending=false
                    // Retain completed window state. Urgent receipts go first.
                    val urgent=pending.firstOrNull { it.control }
                    if(urgent!=null) { pending.remove(urgent);pending.addFirst(urgent) }
                    startNext()
                }
            }
        ).also { job.mediaWindow=it }
        activeMedia=sender
        sender.startOrResume()
    }
    private fun startNext() {
        if(!connected || isSending)return
        // Acknowledge delivered/read/played promptly even during a large photo.
        val front=pending.firstOrNull()?:return
        if(!front.control) {
            val urgent=pending.firstOrNull { it.control }
            if(urgent!=null) { pending.remove(urgent);pending.addFirst(urgent) }
        }
        val t=pending.firstOrNull()?:return
        if(t.media && !t.control && t.frames.size>1) { startMediaWindow(t);return }
        isSending=true
        t.attempts=0
        t.bleToken=null;link?.send("MSGHEX ${OgmCodec.hex(t.frames[t.index])}")
        waitForAck("queued")
    }
    private fun advanceFrame() {
        cancelAckTimeout()
        if(pending.isEmpty())return
        val job=pending.first()
        job.index++
        if(!job.control && job.frames.size>1 && (job.index%8==0 || job.index==job.frames.size)) {
            val idx=items.indexOfFirst { it.id==job.messageId }
            if(idx>=0)items[idx]=items[idx].copy(progress=100*job.index/job.frames.size)
        }
        if(job.index<job.frames.size){
            // A long photo/voice transfer must not hold up an authenticated
            // delivered/read/played receipt. Interleave at an ACK boundary,
            // never in the middle of a radio frame or a GATT write.
            if(!job.control) {
                val urgent=pending.firstOrNull { it.control }
                if(urgent!=null) {
                    pending.remove(urgent)
                    pending.addFirst(urgent)
                    isSending=false
                    startNext()
                    return
                }
            }
            job.attempts=0
            job.bleToken=null;link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
            waitForAck("queued")
        } else {
            pending.removeFirst()
            if(!job.control)updateReceipt(job.messageId,"sent")
            isSending=false
            // Final radio ACK means all encrypted fragments reached remote browser MCU.
            // True delivered/read ticks come only from authenticated app receipts.
            startNext()
        }
    }
    private fun pushEnvelope(kind:Int,messageId:String,body:ByteArray,control:Boolean=false,width:Int=0,height:Int=0) {
        val secret=secure.readPairingKey() ?: return
        try {
            val frames=OgmCodec.seal(secret,kind,messageId,body,width,height)
            pending.addLast(Transfer(frames,messageId,control,kind==OgmCodec.IMAGE || kind==OgmCodec.VOICE))
            if(control) activeMedia?.pauseForReceipts()
            startNext()
        } catch (ex: Exception) { state="Encryption error: ${ex.message}" }
        finally { secret.fill(0) }
    }
    private fun sendText(text:String) {
        if(!connected)return
        val value=text.trim()
        if(value.isEmpty()||value.length>500)return
        val id=OgmCodec.newId()
        items.add(ChatItem(id,value,true,System.currentTimeMillis(),"sending"))
        scheduleHistorySave()
        pushEnvelope(OgmCodec.TEXT,id,value.toByteArray(Charsets.UTF_8))
    }
    private fun sendPhoto(uri:Uri) {
        // In-app CameraX captures only temporary JPEGs in our own cache.
        // Gallery/content URIs are never deleted.
        val capturedFile=if(uri.scheme=="file") uri.path?.let(::File)?.takeIf {
            it.parentFile?.canonicalPath==cacheDir.canonicalPath && it.name.startsWith("offgrid-camera-")
        } else null
        if(!connected) {
            capturedFile?.delete()
            state="Connect before sending a photo"
            return
        }
        state="Preparing private photo…"
        val targetBytes = if(fastPhotos) MediaPayload.FAST_PHOTO_TARGET else MediaPayload.ANDROID_PHOTO_TARGET
        mediaWorker.execute {
            val result=try {runCatching { PhotoPreparer.prepare(contentResolver,uri,targetBytes) }}
                finally {capturedFile?.delete()}
            mainHandler.post {
                if(activityDestroyed)return@post
                result.onSuccess { photo ->
                    if(!connected){state="Photo preparation finished, but node disconnected";return@onSuccess}
                    val id=OgmCodec.newId()
                    val body=MediaPayload.photoBody(photo.mime,photo.bytes,photo.width,photo.height)
                    items.add(ChatItem(id,"Photo",true,System.currentTimeMillis(),"sending",
                        OgmCodec.IMAGE,photo.bytes,photo.mime,photo.width,photo.height))
                    scheduleHistorySave()
                    pushEnvelope(OgmCodec.IMAGE,id,body,width=photo.width,height=photo.height)
                    state="Encrypted photo queued · ${photo.bytes.size/1024} KiB"
                }.onFailure { state="Photo not sent: ${it.message}" }
            }
        }
    }
    private fun startVoiceRecording() {
        if(!connected){state="Connect to record";return}
        try {
            voiceRecorder.start();recording=true
            state="Recording voice · release to send (30s max)"
            val timeout=Runnable { if(recording) finishVoiceRecording() }
            recordAutoStop=timeout
            mainHandler.postDelayed(timeout,30_000L)
        } catch(ex:Exception) { state="Microphone unavailable: ${ex.message}";recording=false }
    }
    private fun finishVoiceRecording() {
        if(!recording)return
        recordAutoStop?.let(mainHandler::removeCallbacks);recordAutoStop=null
        recording=false
        val result=runCatching { voiceRecorder.finish() }
        result.onSuccess { voice ->
            if(voice==null||!connected) { state="Recording discarded";return@onSuccess }
            try {
                val id=OgmCodec.newId()
                val body=MediaPayload.voiceBody(voice.mime,voice.bytes,voice.durationMs)
                val (w,h)=MediaPayload.durationParts(voice.durationMs)
                items.add(ChatItem(id,"Voice note",true,System.currentTimeMillis(),"sending",
                    OgmCodec.VOICE,voice.bytes,voice.mime,durationMs=voice.durationMs))
                scheduleHistorySave()
                pushEnvelope(OgmCodec.VOICE,id,body,width=w,height=h)
                state="Encrypted voice note queued · ${voice.bytes.size/1024} KiB"
            } catch(ex:Exception) { state="Voice not sent: ${ex.message}" }
        }.onFailure { state="Voice recording failed: ${it.message}" }
    }
    private fun stopVoicePlayback() {
        runCatching { voicePlayer?.release() }
        voicePlayer=null
        voicePlaybackFile?.delete();voicePlaybackFile=null
        playingId=null
    }
    private fun toggleVoicePlayback(item:ChatItem) {
        if(playingId==item.id){stopVoicePlayback();return}
        stopVoicePlayback()
        val raw=item.bytes ?: return
        try {
            val suffix=when(item.mime){"audio/mp4"->".m4a";"audio/ogg"->".ogg";else->".webm"}
            val file=File.createTempFile("offgrid-play-",suffix,cacheDir)
            voicePlaybackFile=file
            file.writeBytes(raw)
            val player=MediaPlayer()
            voicePlayer=player
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                stopVoicePlayback()
                // 'Played' is only sent after the listener finishes the audio.
                if(!item.outgoing && playedReceipts.add(item.id)) {
                    controlReceipt(OgmCodec.PLAYED,item.id)
                    items.indexOfFirst{it.id==item.id}.takeIf{it>=0}?.let{idx ->items[idx]=items[idx].copy(status="played")}
                    scheduleHistorySave()
                }
            }
            player.setOnErrorListener { _,_,_ -> stopVoicePlayback();state="Audio decoder error";true }
            player.prepare();player.start();playingId=item.id
        } catch(ex:Exception){ stopVoicePlayback();state="Cannot play ${item.mime}: ${ex.message}" }
    }

    private fun controlReceipt(kind:Int,targetId:String) {
        val id=OgmCodec.newId()
        pushEnvelope(kind,id,OgmCodec.unhex(targetId),control=true)
    }
    private fun updateReceipt(targetId:String,status:String) {
        val index=items.indexOfFirst { it.id==targetId && it.outgoing }
        if(index>=0) {
            val current=items[index]
            val next=ReceiptStates.advance(current.status,status)
            if(current.status!=next){items[index]=current.copy(status=next);scheduleHistorySave()}
        }
    }
    private fun consumeLine(line:String) {
        val split=line.split('|')
        when(split.firstOrNull()) {
            "READY" -> state="ESP32 ready · channel 6"
            "LINK" -> {
                radioLink=RadioLink.parse(line)
                // Do not replace the actual authenticated BLE connection state.
            }
            "RATE" -> {
                bleTrace("ESP32 radio setting: ${split.drop(1).joinToString(" ")}")
            }
            "BQUEUED" -> if(isSending){
                val reply=BleBinaryCodec.queued(line) ?: return
                if(activeMedia != null) {
                    activeMedia?.onBinaryQueued(reply.token,reply.session,reply.packetId)
                    return
                }
                val job=pending.firstOrNull() ?: return
                if(job.bleToken==reply.token){
                    job.session=reply.session;job.packetId=reply.packetId
                    waitForAck("delivered")
                }
            }
            "QUEUED" -> if(split.size>=4&&isSending){
                if(activeMedia != null) { activeMedia?.onQueued(split[3], split[1], split[2]);return }
                val job=pending.firstOrNull()?:return
                // MCU echoes the exact OGC4 frame as hex; reject stale queued responses.
                if(split[3].equals(OgmCodec.hex(job.frames[job.index]),ignoreCase=true)){
                    job.session=split[1];job.packetId=split[2]
                    waitForAck("delivered")
                }
            }
            "DELIVERED" -> if(split.size>=3&&isSending){
                if(activeMedia != null) { activeMedia?.onDelivered(split[1],split[2]);return }
                val job=pending.firstOrNull()?:return
                if(job.session==split[1]&&job.packetId==split[2])advanceFrame()
            }
            "FAILED" -> if(split.size>=3&&isSending){
                if(activeMedia != null) { activeMedia?.onFailed(split[1],split[2]);return }
                val job=pending.firstOrNull()?:return
                if(job.session==split[1]&&job.packetId==split[2]){
                    cancelAckTimeout()
                    if(job.attempts++ < 3){
                        job.bleToken=null;link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
                        waitForAck("queued")
                    } else {
                        pending.removeFirst();isSending=false
                        updateReceipt(job.messageId,"failed");startNext()
                    }
                }
            }
            "RX" -> if(split.size>=5){
                try {
                    val frame=OgmCodec.unhex(split[4]);val parsed=OgmCodec.parseFrame(frame)?:return
                    // Radio-level APPACK prevents transport congestion. The final
                    // encrypted DELIVERED/READ receipts confirm end-to-end handling.
                    if(parsed.bulk) mediaAcks.accept(split[1],split[2],split[3])
                    else link?.send("APPACK ${split[1]} ${split[2]} ${split[3]}")
                    val env=assembler.accept(split[1],split[2],frame)?:return
                    // A local deletion is permanent even when the sender retries a frame.
                    if(env.id in hiddenMessageIds && env.kind in listOf(OgmCodec.TEXT,OgmCodec.IMAGE,OgmCodec.VOICE)) {
                        controlReceipt(OgmCodec.DELIVERED,env.id)
                        return
                    }
                    when(env.kind) {
                        OgmCodec.TEXT -> {
                            val body=env.text.toString(Charsets.UTF_8)
                            if(body.length in 1..500 && items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,body,false,env.timestamp,"delivered"))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                scheduleHistorySave()
                                if(notificationsEnabled && (!appResumed || !chatScreenVisible))
                                    notifications.newMessage(items.last(),notificationPreview)
                            }
                        }
                        OgmCodec.IMAGE -> {
                            val photo=MediaPayload.unpackPhoto(env.text,env.width,env.height)
                            if(items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,"Photo",false,env.timestamp,"delivered",OgmCodec.IMAGE,
                                    photo.bytes,photo.mime,photo.width,photo.height))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                scheduleHistorySave()
                                if(notificationsEnabled && (!appResumed || !chatScreenVisible))
                                    notifications.newMessage(items.last(),notificationPreview)
                            }
                        }
                        OgmCodec.VOICE -> {
                            val ms=MediaPayload.durationFromParts(env.width,env.height)
                            val voice=MediaPayload.unpackVoice(env.text,ms)
                            if(items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,"Voice note",false,env.timestamp,"delivered",OgmCodec.VOICE,
                                    voice.bytes,voice.mime,durationMs=voice.durationMs))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                scheduleHistorySave()
                                if(notificationsEnabled && (!appResumed || !chatScreenVisible))
                                    notifications.newMessage(items.last(),notificationPreview)
                            }
                        }
                        OgmCodec.DELIVERED -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"delivered")
                        OgmCodec.READ -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"read")
                        OgmCodec.PLAYED -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"played")
                        OgmCodec.EDIT, OgmCodec.DELETE -> {
                            val target=MessageMutation.target(env.text)
                            val idx=items.indexOfFirst {it.id==target && !it.outgoing}
                            if(idx>=0) {
                                if(env.kind==OgmCodec.DELETE && playingId==target)stopVoicePlayback()
                                val changed=MessageMutation.apply(items[idx],env.kind,env.text)
                                if(changed!=null){items[idx]=changed;scheduleHistorySave()}
                            }
                        }
                    }
                } catch (ex: Exception) { Log.w("OffGrid", "Rejected invalid encrypted frame",ex) }
            }
            "ERROR" -> {
                val error=split.getOrNull(1) ?: "error"
                state=if(error in setOf("LR_NOT_SUPPORTED_BY_WIFI_DRIVER","BAD_RATE_MODE","BLE_DENIED"))
                    "Radio control rejected: $error" else "Node: $error"
                bleTrace(state)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSystemPairing(launch: (IntentSenderRequest) -> Unit) {
        if (!hasBluetoothPermission()) { state="Grant Nearby devices permission"; return }
        pickerHandled = false
        try {
            val companion = getSystemService(CompanionDeviceManager::class.java)
            val filter = BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("^OffGrid-.*"))
                .build()
            val request = AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(false)
                .build()
            val callback = object : CompanionDeviceManager.Callback() {
                @Deprecated("For Android 8-12")
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    runOnUiThread { launch(IntentSenderRequest.Builder(chooserLauncher).build()) }
                }
                override fun onAssociationPending(chooserLauncher: android.content.IntentSender) {
                    runOnUiThread { launch(IntentSenderRequest.Builder(chooserLauncher).build()) }
                }
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    // Android 13+ may deliver the chosen MAC here instead of in the Activity result.
                    runOnUiThread {
                        acceptBleSelection(associationInfo.deviceMacAddress?.toString(), "association callback")
                    }
                }
                override fun onFailure(error: CharSequence?) {
                    runOnUiThread {
                        bleTrace("Companion device association failed: $error")
                        state="BLE device selection failed: $error"
                    }
                }
            }
            // Modern executor overload reports AssociationInfo; old callback supports Android 8-12.
            if (Build.VERSION.SDK_INT >= 33) {
                companion.associate(request, mainExecutor, callback)
            } else {
                companion.associate(request, callback, Handler(Looper.getMainLooper()))
            }
            bleTrace("Companion device picker opened")
            state="Select your nearby OffGrid node"
        } catch (ex: Exception) {
            bleTrace("Device picker exception: ${ex.javaClass.simpleName}: ${ex.message}")
            state="Pairing unavailable: ${ex.message}"
        }
    }

    private fun addressFromPicker(data: Intent?): String? {
        if (data == null) return null
        // CompanionDeviceManager.EXTRA_DEVICE is NOT BluetoothDevice.EXTRA_DEVICE.
        // Selecting a BLE device uses the companion-specific key on older Android versions.
        val device = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            val old = data.getParcelableExtra<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)
            old
        }
        if (device != null) return device.address
        if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.deviceMacAddress?.toString()?.let { return it }
        }
        // Defensive fallback for vendor-specific companion chooser implementations.
        val legacy = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            val old = data.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            old
        }
        return legacy?.address
    }

    @Composable
    private fun OffGridScreen() {
        var keyInput by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(!pairingSaved) }
        val systemPicker=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if(result.resultCode==RESULT_OK){
                val address=addressFromPicker(result.data)
                if(address!=null)acceptBleSelection(address,"chooser result")
                else if(!pickerHandled)state="Could not read selected BLE address; select again"
            } else if(!pickerHandled)state="Device selection cancelled"
        }
        val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(!granted)state="Nearby devices permission denied"
            else if(trustedAddress==null && pairingSaved)startSystemPairing(systemPicker::launch)
            else reconnectIfPermitted()
        }
        val notificationsPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(!granted)state="Notification permission denied; enable in Android Settings"
        }
        var inAppCamera by remember { mutableStateOf(false) }
        val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(granted) inAppCamera=true else state="Camera permission denied; allow in Android Settings"
        }
        val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri:Uri? ->
            if(uri!=null)sendPhoto(uri)
        }
        val avatarPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri:Uri? ->
            if(uri!=null)mediaWorker.execute {
                runCatching {
                    val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,opts)}
                    val sample=BitmapFactory.Options().apply {
                        inSampleSize=(maxOf(opts.outWidth,opts.outHeight)/256).coerceAtLeast(1)
                    }
                    val bitmap=contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,sample)}
                        ?: error("Cannot decode contact image")
                    val target=android.graphics.Bitmap.createScaledBitmap(bitmap,160,160,true)
                    if(bitmap!==target)bitmap.recycle()
                    val output=java.io.ByteArrayOutputStream()
                    target.compress(android.graphics.Bitmap.CompressFormat.JPEG,78,output)
                    target.recycle()
                    output.toByteArray().also {require(it.size<=32768)}
                }.onSuccess {data ->
                    historyWorker.execute {runCatching {history.saveAvatar(data)}.onFailure {
                        Log.e("OffGridHistory","Could not encrypt contact avatar",it)
                    }}
                    mainHandler.post {if(!activityDestroyed){avatarBytes=data;notifications.setContact(contactName,data)}}
                }.onFailure {err -> mainHandler.post {state="Contact photo failed: ${err.message}"} }
            }
        }
        val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state=if(granted)"Microphone ready · hold mic button to record" else "Microphone permission required"
        }
        LaunchedEffect(inAppCamera) {
            if(inAppCamera) chatScreenVisible=false // camera must not mark off-screen chats read
        }
        val prefs=getSharedPreferences("offgrid_premium_24",MODE_PRIVATE)
        var notificationAsked by remember { mutableStateOf(false) }
        LaunchedEffect(connected,notificationsEnabled) {
            if(connected && notificationsEnabled && !notificationAsked && Build.VERSION.SDK_INT>=33 && !notifications.canNotify()) {
                notificationAsked=true
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SideEffect {
            val insets=WindowCompat.getInsetsController(window,window.decorView)
            insets.isAppearanceLightStatusBars=!dark && !inAppCamera
            insets.isAppearanceLightNavigationBars=!dark && !inAppCamera
        }
        OffGridPremiumTheme(dark) {
            if(inAppCamera) {
                InAppCameraScreen(
                    onCancel={inAppCamera=false},
                    onSend={capturedFile ->
                        inAppCamera=false
                        sendPhoto(Uri.fromFile(capturedFile))
                    }
                )
            } else PremiumMessenger(
                items=items.toList(),connected=connected,status=state,nodeAddress=trustedAddress,
                radioLink=radioLink,
                dark=dark,fastPhotos=fastPhotos,notifications=notificationsEnabled,
                privatePreview=notificationPreview,backgroundEnabled=keepBleBackground,
                recording=recording,playingId=playingId,appVisible=appResumed,
                accessibleVoiceTap=accessibleVoiceTap,
                diagnostics=bleDiagnostics.toList(),contactName=contactName,avatarBytes=avatarBytes,
                actions=PremiumActions(
                    sendText=::sendText,photo={photoPicker.launch("image/*")},
                    camera={
                        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
                            inAppCamera=true
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    micPress={
                        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                            startVoiceRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    micRelease={finishVoiceRecording()},micCancel={cancelVoiceRecording()},
                    playVoice=::toggleVoicePlayback,markVisible=::markVisible,
                    chatVisibility={chatScreenVisible=it},
                    connect={
                        if(!hasBluetoothPermission()) {
                            if(Build.VERSION.SDK_INT>=31)bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else if(!pairingSaved)showKey=true
                        else if(trustedAddress==null)startSystemPairing(systemPicker::launch)
                        else reconnectIfPermitted()
                    },
                    disconnect={link?.stop();state="Disconnected";radioLink=null},
                    setRadioMode={mode ->
                        if(connected && mode in RadioLink.choices) {
                            link?.send("RATE $mode")
                            // A LINK response reports the applied mode; do not fake the result.
                            mainHandler.postDelayed({if(connected)link?.send("STATUS")},450)
                        }
                    },
                    batterySettings={
                        val intent=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"))
                        runCatching {startActivity(intent)}.onFailure {bleTrace("Battery settings unavailable")}
                    },
                    chooseNode={startSystemPairing(systemPicker::launch)},
                    forgetNode={
                        link?.stop();secure.clearNodeAddress();trustedAddress=null
                        state="BLE device forgotten; AES pairing key kept"
                    },editKey={showKey=true},
                    deleteForMe=::deleteForMe,deleteForEveryone=::deleteForEveryone,
                    editMessage=::editOutgoing,
                    copyMessage={ item ->
                        val clipboard=getSystemService(android.content.ClipboardManager::class.java)
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message",item.text))
                    },
                    chooseAvatar={avatarPicker.launch("image/*")},
                    clearAvatar={
                        avatarBytes=null;notifications.setContact(contactName,null)
                        historyWorker.execute {runCatching {history.clearAvatar()}}
                    },
                    renameContact={value ->
                        contactName=value.take(36);notifications.setContact(contactName,avatarBytes)
                        prefs.edit().putString("contact_name",contactName).apply()
                    },
                    changeDark={dark=it;prefs.edit().putBoolean("dark",it).apply()},
                    changeFastPhotos={
                        fastPhotos=it;getSharedPreferences("offgrid_media",MODE_PRIVATE)
                            .edit().putBoolean("fast_photos",it).apply()
                    },
                    changeNotifications={
                        notificationsEnabled=it;prefs.edit().putBoolean("notifications",it).apply()
                        if(it && Build.VERSION.SDK_INT>=33 && !notifications.canNotify())
                            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    changePrivatePreview={notificationPreview=it;prefs.edit().putBoolean("previews",it).apply()},
                    changeBackground=::toggleBackground,
                    changeAccessibleVoiceTap={
                        accessibleVoiceTap=it;prefs.edit().putBoolean("accessible_voice_tap",it).apply()
                    },
                    clearHistory={
                        items.clear();playedReceipts.clear();hiddenMessageIds.clear()
                        historyWorker.execute { history.clear() }
                    },retry=::retryFailed
                )
            )
            if(showKey) {
                AlertDialog(onDismissRequest={if(pairingSaved)showKey=false},
                    title={Text("Secure node pairing")},
                    text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Enter the existing 43-character Base64URL key shared by both ESP32s. This isn't a new BLE PIN.")
                        OutlinedTextField(value=keyInput,onValueChange={keyInput=it.trim()},singleLine=true,
                            label={Text("OffGrid encryption key")},visualTransformation=PasswordVisualTransformation())
                        Text("Use the key shown in the trusted web interface. Do not share it.",
                            style=MaterialTheme.typography.bodySmall)
                    }},confirmButton={TextButton(onClick={
                        try {
                            val raw=OgmCodec.key(keyInput)
                            secure.savePairingKey(raw);raw.fill(0);pairingSaved=true;keyInput="";showKey=false
                            if(trustedAddress!=null)reconnectIfPermitted()
                            else if(hasBluetoothPermission())startSystemPairing(systemPicker::launch)
                            else if(Build.VERSION.SDK_INT>=31)bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } catch(_:Exception){state="Pairing key must be exactly 43 Base64URL characters"}
                    }){Text("Save & connect")}},
                    dismissButton=if(pairingSaved) {{TextButton(onClick={showKey=false}){Text("Cancel")}}} else null
                )
            }
        }
    }
}
