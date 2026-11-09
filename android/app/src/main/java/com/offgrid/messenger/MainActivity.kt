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
    val mime:String="",val width:Int=0,val height:Int=0,val durationMs:Int=0,val progress:Int=0, val speedKiBs:Float=0f)
private data class Transfer(val frames:List<ByteArray>,val messageId:String,val control:Boolean=false,
    val media:Boolean=false,
    var index:Int=0,var session:String="",var packetId:String="",var attempts:Int=0,
    var bleToken:Int?=null,
    var mediaWindow:MediaTransferWindow?=null)

class MainActivity : ComponentActivity() {
    private lateinit var secure: SecureKeyStore
    private var link: BleNodeClient? = null
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
    private var state by mutableStateOf("Not connected")
    private var trustedAddress by mutableStateOf<String?>(null)
    private var pairingSaved by mutableStateOf(false)
    private var details by mutableStateOf(false)
    private var dark by mutableStateOf(false)
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
        secure = SecureKeyStore(this)
        fastPhotos = getSharedPreferences("offgrid_media", MODE_PRIVATE).getBoolean("fast_photos", true)
        voiceRecorder = VoiceNoteRecorder(this)
        trustedAddress = secure.nodeAddress()
        pairingSaved = secure.readPairingKey()?.let { it.fill(0);true } ?: false
        dark = (resources.configuration.uiMode and 0x30) == 0x20
        link = BleNodeClient(this, {secure.readPairingKey()}, {s,ready ->
            state=s;connected=ready
            if(ready) startNext() else {
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
        if(trustedAddress!=null && pairingSaved) mainHandler.post { reconnectIfPermitted() }
    }
    override fun onDestroy() {
        activityDestroyed=true
        stopVoicePlayback()
        if (recording) runCatching { voiceRecorder.finish(cancel=true) }
        recordAutoStop?.let(mainHandler::removeCallbacks)
        mediaWorker.shutdownNow()
        activeMedia?.cancelTransfer();activeMedia=null;mediaAcks.clear()
        link?.stop();cancelAckTimeout();super.onDestroy()
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
                pending.remove(job);isSending=false
                startNext()
            },
            onFailed = { reason ->
                bleTrace("MEDIA_TX_FAILED $reason")
                activeMedia=null;job.mediaWindow=null
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
        pushEnvelope(OgmCodec.TEXT,id,value.toByteArray(Charsets.UTF_8))
    }
    private fun sendPhoto(uri:Uri) {
        if(!connected)return
        state="Preparing private photo…"
        val targetBytes = if(fastPhotos) MediaPayload.FAST_PHOTO_TARGET else MediaPayload.ANDROID_PHOTO_TARGET
        mediaWorker.execute {
            val result=runCatching { PhotoPreparer.prepare(contentResolver,uri,targetBytes) }
            mainHandler.post {
                if(activityDestroyed)return@post
                result.onSuccess { photo ->
                    if(!connected){state="Photo preparation finished, but node disconnected";return@onSuccess}
                    val id=OgmCodec.newId()
                    val body=MediaPayload.photoBody(photo.mime,photo.bytes,photo.width,photo.height)
                    items.add(ChatItem(id,"Photo",true,System.currentTimeMillis(),"sending",
                        OgmCodec.IMAGE,photo.bytes,photo.mime,photo.width,photo.height))
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
            state="Recording voice · tap stop to encrypt and send (30s max)"
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
                if(!item.outgoing && playedReceipts.add(item.id))controlReceipt(OgmCodec.PLAYED,item.id)
            }
            player.setOnErrorListener { _,_,_ -> stopVoicePlayback();state="Audio decoder error";true }
            player.prepare();player.start();playingId=item.id
        } catch(ex:Exception){ stopVoicePlayback();state="Cannot play ${item.mime}: ${ex.message}" }
    }

    private fun controlReceipt(kind:Int,targetId:String) {
        val id=OgmCodec.newId()
        pushEnvelope(kind,id,OgmCodec.unhex(targetId),control=true)
    }
    private fun updateReceipt(targetId:String,status:String){
        val index=items.indexOfFirst { it.id==targetId&&it.outgoing }
        if(index>=0){
            val current=items[index]
            if(current.status!="played" && (current.status!="read" || status=="played"))
                items[index]=current.copy(status=status)
        }
    }
    private fun consumeLine(line:String) {
        val split=line.split('|')
        when(split.firstOrNull()) {
            "READY" -> state="ESP32 ready · channel 6"
            "LINK" -> if(connected)state="Connected · encrypted · adaptive LR"
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
                    when(env.kind) {
                        OgmCodec.TEXT -> {
                            val body=env.text.toString(Charsets.UTF_8)
                            if(body.length in 1..500 && items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,body,false,env.timestamp,"read"))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                controlReceipt(OgmCodec.READ,env.id)
                            }
                        }
                        OgmCodec.IMAGE -> {
                            val photo=MediaPayload.unpackPhoto(env.text,env.width,env.height)
                            if(items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,"Photo",false,env.timestamp,"read",OgmCodec.IMAGE,
                                    photo.bytes,photo.mime,photo.width,photo.height))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                controlReceipt(OgmCodec.READ,env.id)
                            }
                        }
                        OgmCodec.VOICE -> {
                            val ms=MediaPayload.durationFromParts(env.width,env.height)
                            val voice=MediaPayload.unpackVoice(env.text,ms)
                            if(items.none{it.id==env.id}){
                                items.add(ChatItem(env.id,"Voice note",false,env.timestamp,"read",OgmCodec.VOICE,
                                    voice.bytes,voice.mime,durationMs=voice.durationMs))
                                controlReceipt(OgmCodec.DELIVERED,env.id)
                                controlReceipt(OgmCodec.READ,env.id)
                            }
                        }
                        OgmCodec.DELIVERED -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"delivered")
                        OgmCodec.READ -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"read")
                        OgmCodec.PLAYED -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"played")
                    }
                } catch (ex: Exception) { Log.w("OffGrid", "Rejected invalid encrypted frame",ex) }
            }
            "ERROR" -> state="Node: ${split.getOrNull(1) ?: "error"}"
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
    private fun OffGridScreen(){
        val colors=if(dark) darkColorScheme(primary=Color(0xFF82ACFF),background=Navy,surface=Color(0xFF15243A),onSurface=Color(0xFFF2F6FD))
            else lightColorScheme(primary=Sapphire,background=Color(0xFFF7F9FE),surface=Color.White,onSurface=Color(0xFF192B42))
        var keyInput by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(!pairingSaved) }
        val systemPicker=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val address=addressFromPicker(result.data)
                if (address != null) acceptBleSelection(address, "chooser result")
                else if (!pickerHandled) {
                    bleTrace("Chooser returned no device; keys=${result.data?.extras?.keySet()?.joinToString()}")
                    state="Device selected but BLE address unavailable; select again"
                }
            } else if (!pickerHandled) {
                bleTrace("Chooser cancelled / failed: code=${result.resultCode}")
                state="Device selection cancelled"
            }
        }
        val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) state="Nearby devices permission denied"
            else if (trustedAddress == null && pairingSaved) startSystemPairing(systemPicker::launch)
            else reconnectIfPermitted()
        }
        val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri:Uri? ->
            if(uri!=null)sendPhoto(uri)
        }
        val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(granted)startVoiceRecording() else state="Microphone permission is required to record voice notes"
        }
        MaterialTheme(colorScheme=colors,shapes=Shapes(extraLarge=RoundedCornerShape(26.dp),large=RoundedCornerShape(22.dp))){
            Surface(modifier=Modifier.fillMaxSize(),color=colors.background){
                BoxWithConstraints(Modifier.fillMaxSize()){
                    val wide=maxWidth>=750.dp
                    Row(Modifier.fillMaxSize()){
                        if(wide) {
                            Surface(Modifier.width(278.dp).fillMaxHeight(),color=colors.surface,shadowElevation=1.dp){
                                Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                                    Text("OffGrid",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=colors.primary)
                                    Text("MESSENGER",style=MaterialTheme.typography.labelMedium,color=colors.onSurface.copy(alpha=.56f))
                                    HorizontalDivider()
                                    Text("Nearby conversation",fontWeight=FontWeight.SemiBold)
                                    Text(state,style=MaterialTheme.typography.bodySmall,color=colors.onSurface.copy(alpha=.66f))
                                    Text("Completely offline. Encrypted end to end.",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            VerticalDivider()
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()){
                            Surface(Modifier.fillMaxWidth(),color=colors.surface,tonalElevation=2.dp){
                                Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                                    Surface(shape=CircleShape,color=colors.primary.copy(alpha=.12f),modifier=Modifier.size(43.dp)){
                                        Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Bluetooth,null,tint=colors.primary)}
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)){
                                        Text(if(wide) "Nearby node" else "OffGrid",fontWeight=FontWeight.SemiBold)
                                        Text(state,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                                            color=if(connected)Color(0xFF278875) else colors.onSurface.copy(alpha=.56f))
                                    }
                                    IconButton(onClick={dark=!dark}){Text(if(dark)"☀" else "☾",color=colors.primary)}
                                    IconButton(onClick={details=!details}){Icon(Icons.Default.MoreVert,"More")}
                                }
                            }
                            if(details){
                                Surface(color=colors.surface,tonalElevation=4.dp){
                                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                        Text("Saved device: ${trustedAddress?:"None"}",style=MaterialTheme.typography.bodySmall)
                                        Text("AES-256-GCM · ESP-NOW Long Range · BLE bridge",style=MaterialTheme.typography.bodySmall)
                                        Text("Phase 2.2: encrypted media pipeline · bounded radio window · grouped media ACKs",style=MaterialTheme.typography.bodySmall)
                                        Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Fast photos",style=MaterialTheme.typography.bodyMedium)
                                                Text("~28 KiB uploads (off = quality mode, ~48 KiB)",style=MaterialTheme.typography.labelSmall)
                                            }
                                            Switch(checked=fastPhotos,onCheckedChange={ value ->
                                                fastPhotos=value
                                                getSharedPreferences("offgrid_media",MODE_PRIVATE).edit().putBoolean("fast_photos",value).apply()
                                            })
                                        }
                                        TextButton(onClick={showKey=true}){Text("Update pairing key")}
                                        TextButton(onClick={link?.stop();state="Disconnected"}){Text("Disconnect")}
                                        TextButton(onClick={startSystemPairing(systemPicker::launch)}){Text("Choose another ESP32")}
                                        TextButton(onClick={
                                            link?.stop()
                                            secure.clearNodeAddress()
                                            trustedAddress=null
                                            state="Saved node cleared; pairing key retained"
                                            bleTrace("Forgot BLE address only; AES key preserved")
                                        }){Text("Forget BLE device (keep key)")}
                                        HorizontalDivider()
                                        Text("BLE diagnostics",style=MaterialTheme.typography.titleSmall)
                                        bleDiagnostics.takeLast(10).forEach { entry ->
                                            Text(entry,style=MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            if(!connected){
                                Surface(Modifier.fillMaxWidth(),color=colors.primary.copy(alpha=.055f)){
                                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                                        Text("Pair once, then connect automatically when you open the app.",style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                                        TextButton(onClick={
                                            if(!hasBluetoothPermission()){
                                                if(Build.VERSION.SDK_INT>=31) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                            } else if(!pairingSaved)showKey=true
                                            else if(trustedAddress==null) startSystemPairing(systemPicker::launch)
                                            else reconnectIfPermitted()
                                        }){Text(if(trustedAddress==null)"Pair" else "Connect")}
                                    }
                                }
                            }
                            val lazy=rememberLazyListState()
                            LaunchedEffect(items.size){if(items.isNotEmpty())lazy.animateScrollToItem(items.lastIndex)}
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=lazy,contentPadding=PaddingValues(horizontal=18.dp, vertical=20.dp),
                                verticalArrangement=Arrangement.spacedBy(10.dp)){
                                if(items.isEmpty())item {
                                    Column(Modifier.fillMaxWidth().padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally){
                                        Icon(Icons.Default.Lock,null,tint=colors.primary.copy(alpha=.6f),modifier=Modifier.size(36.dp))
                                        Spacer(Modifier.height(14.dp))
                                        Text("A private space for your conversation",fontWeight=FontWeight.SemiBold)
                                        Spacer(Modifier.height(6.dp))
                                        Text("No internet · No cloud · Just your radios",style=MaterialTheme.typography.bodySmall,color=colors.onSurface.copy(alpha=.55f))
                                    }
                                }
                                items(items,key={it.id}){item ->
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(item.outgoing)Arrangement.End else Arrangement.Start){
                                        val fill=if(item.outgoing)colors.primary.copy(alpha=.11f) else colors.surface
                                        Surface(color=fill,shape=RoundedCornerShape(21.dp),shadowElevation=1.dp,
                                            modifier=Modifier.widthIn(max=340.dp)){
                                            Column(Modifier.padding(horizontal=14.dp, vertical=10.dp)){
                                                when(item.kind) {
                                                    OgmCodec.IMAGE -> {
                                                        val bitmap=remember(item.id) {
                                                            item.bytes?.let { data -> runCatching {
                                                                BitmapFactory.decodeByteArray(data,0,data.size)?.asImageBitmap()
                                                            }.getOrNull() }
                                                        }
                                                        if(bitmap!=null) {
                                                            androidx.compose.foundation.Image(bitmap,contentDescription="Encrypted photo",contentScale=ContentScale.Fit,
                                                                modifier=Modifier.fillMaxWidth().heightIn(max=260.dp))
                                                        } else Text("Photo unavailable",style=MaterialTheme.typography.bodyMedium)
                                                        Text("${item.width} × ${item.height} · encrypted photo",style=MaterialTheme.typography.labelSmall)
                                                    }
                                                    OgmCodec.VOICE -> {
                                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                                            IconButton(onClick={toggleVoicePlayback(item)}) {
                                                                Icon(if(playingId==item.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                                    if(playingId==item.id)"Stop voice" else "Play voice")
                                                            }
                                                            Text("Voice note · ${item.durationMs/1000}s",style=MaterialTheme.typography.bodyMedium)
                                                        }
                                                    }
                                                    else -> Text(item.text,style=MaterialTheme.typography.bodyMedium)
                                                }
                                                if(item.outgoing && item.progress in 1..99 && item.kind!=OgmCodec.TEXT) {
                                                    LinearProgressIndicator(progress={item.progress/100f},modifier=Modifier.fillMaxWidth())
                                                    Text("Transferring ${item.progress}% · ${"%.1f".format(java.util.Locale.US,item.speedKiBs)} KiB/s",style=MaterialTheme.typography.labelSmall)
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){
                                                    Text(java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(item.timestamp)),
                                                        style=MaterialTheme.typography.labelSmall,color=colors.onSurface.copy(alpha=.48f))
                                                    if(item.outgoing){
                                                        Spacer(Modifier.width(4.dp))
                                                        Icon(if(item.status=="read"||item.status=="played")Icons.Default.CheckCircle else Icons.Default.Check,
                                                            item.status,tint=if(item.status=="read"||item.status=="played")colors.primary else colors.onSurface.copy(alpha=.55f),
                                                            modifier=Modifier.size(15.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Surface(color=colors.surface,tonalElevation=1.dp){
                                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.Bottom,
                                    horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    OutlinedTextField(value=draft,onValueChange={if(it.length<=500)draft=it},modifier=Modifier.weight(1f),
                                        placeholder={Text(if(connected)"Message" else "Connect to send",style=MaterialTheme.typography.bodyMedium)},
                                        enabled=connected,shape=RoundedCornerShape(25.dp),maxLines=4,
                                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                                        keyboardActions=KeyboardActions(onSend={if(draft.isNotBlank()){sendText(draft);draft=""}}))
                                    IconButton(onClick={photoPicker.launch("image/*")},enabled=connected && !recording) {
                                        Icon(Icons.Default.Image,"Send a photo",tint=colors.primary)
                                    }
                                    FilledIconButton(onClick={
                                        if(recording)finishVoiceRecording()
                                        else if(draft.isNotBlank()){sendText(draft);draft=""}
                                        else if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
                                            startVoiceRecording()
                                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                    },enabled=connected,modifier=Modifier.size(53.dp),
                                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=colors.primary)){
                                        if(recording) Icon(Icons.Default.Stop,"Finish and send voice",tint=Color.White)
                                        else if(draft.isNotBlank())Icon(Icons.AutoMirrored.Filled.Send,"Send",tint=Color.White)
                                        else Icon(Icons.Default.Mic,"Record voice",tint=Color.White)
                                    }
                                }
                            }
                        }
                    }
                    if(showKey){
                        AlertDialog(onDismissRequest={if(pairingSaved)showKey=false},
                            title={Text("Secure node pairing")},
                            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                                Text("Enter the same 43-character pairing key stored on both ESP32 nodes. The key is wrapped by Android Keystore.")
                                OutlinedTextField(value=keyInput,onValueChange={keyInput=it.trim()},singleLine=true,
                                    label={Text("OffGrid key")},visualTransformation=PasswordVisualTransformation())
                                Text("Retrieve the key locally from your trusted web app; never share it with other people.",style=MaterialTheme.typography.bodySmall)
                            }},
                            confirmButton={TextButton(onClick={
                                try {
                                    val raw=OgmCodec.key(keyInput)
                                    secure.savePairingKey(raw);raw.fill(0);pairingSaved=true;keyInput="";showKey=false
                                    if(trustedAddress!=null)reconnectIfPermitted()
                                    else if(hasBluetoothPermission())startSystemPairing(systemPicker::launch)
                                    else if(Build.VERSION.SDK_INT>=31)bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                } catch (_:Exception){state="Pairing key must be exactly 43 base64url characters"}
                            }){Text("Save & connect")}},
                            dismissButton=if(pairingSaved) {{TextButton(onClick={showKey=false}){Text("Cancel")}}} else null
                        )
                    }
                }
            }
        }
    }
}
