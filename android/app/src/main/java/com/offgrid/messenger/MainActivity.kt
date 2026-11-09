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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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

data class ChatItem(val id:String,val text:String,val outgoing:Boolean,val timestamp:Long,var status:String="sent")
private data class Transfer(val frames:List<ByteArray>,val messageId:String,val control:Boolean=false,
    var index:Int=0,var session:String="",var packetId:String="",var attempts:Int=0)

class MainActivity : ComponentActivity() {
    private lateinit var secure: SecureKeyStore
    private var link: BleNodeClient? = null
    private val assembler = OgmAssembler { secure.readPairingKey() }
    private val items = mutableStateListOf<ChatItem>()
    private val pending = ArrayDeque<Transfer>()
    private var isSending = false
    private var ackTimeout: Runnable? = null
    private var connected by mutableStateOf(false)
    private var state by mutableStateOf("Not connected")
    private var trustedAddress by mutableStateOf<String?>(null)
    private var pairingSaved by mutableStateOf(false)
    private var details by mutableStateOf(false)
    private var dark by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureKeyStore(this)
        trustedAddress = secure.nodeAddress()
        pairingSaved = secure.readPairingKey()?.let { it.fill(0);true } ?: false
        dark = (resources.configuration.uiMode and 0x30) == 0x20
        link = BleNodeClient(this, {secure.readPairingKey()}, {s,ready ->
            state=s;connected=ready
            if(ready) startNext() else { isSending=false; cancelAckTimeout() }
        }, ::consumeLine)
        setContent { OffGridScreen() }
        if(trustedAddress!=null && pairingSaved) mainHandler.post { reconnectIfPermitted() }
    }
    override fun onDestroy() { link?.stop();cancelAckTimeout();super.onDestroy() }
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
            link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
            waitForAck("queued")
        }
        ackTimeout=runnable
        mainHandler.postDelayed(runnable,if(phase=="queued")7500 else 16000)
    }
    private fun startNext() {
        if(!connected || isSending)return
        val t = pending.firstOrNull()?:return
        isSending=true
        t.attempts=0
        link?.send("MSGHEX ${OgmCodec.hex(t.frames[t.index])}")
        waitForAck("queued")
    }
    private fun advanceFrame() {
        cancelAckTimeout()
        if(pending.isEmpty())return
        val job=pending.first()
        job.index++
        if(job.index<job.frames.size){
            job.attempts=0
            link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
            waitForAck("queued")
        } else {
            pending.removeFirst()
            isSending=false
            // Final radio ACK means all encrypted fragments reached remote browser MCU.
            // True delivered/read ticks come only from authenticated app receipts.
            startNext()
        }
    }
    private fun pushEnvelope(kind:Int,messageId:String,body:ByteArray,control:Boolean=false) {
        val secret=secure.readPairingKey() ?: return
        try {
            val frames=OgmCodec.seal(secret,kind,messageId,body)
            pending.addLast(Transfer(frames,messageId,control))
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
    private fun controlReceipt(kind:Int,targetId:String) {
        val id=OgmCodec.newId()
        pushEnvelope(kind,id,OgmCodec.unhex(targetId),control=true)
    }
    private fun updateReceipt(targetId:String,status:String){
        val index=items.indexOfFirst { it.id==targetId&&it.outgoing }
        if(index>=0){
            val current=items[index]
            if(current.status!="read")items[index]=current.copy(status=status)
        }
    }
    private fun consumeLine(line:String) {
        val split=line.split('|')
        when(split.firstOrNull()) {
            "READY" -> state="ESP32 ready · channel 6"
            "LINK" -> if(connected)state="Connected · encrypted · adaptive LR"
            "QUEUED" -> if(split.size>=4&&isSending){
                val job=pending.firstOrNull()?:return
                // MCU echoes the exact OGC4 frame as hex; reject stale queued responses.
                if(split[3].equals(OgmCodec.hex(job.frames[job.index]),ignoreCase=true)){
                    job.session=split[1];job.packetId=split[2]
                    waitForAck("delivered")
                }
            }
            "DELIVERED" -> if(split.size>=3&&isSending){
                val job=pending.firstOrNull()?:return
                if(job.session==split[1]&&job.packetId==split[2])advanceFrame()
            }
            "FAILED" -> if(split.size>=3&&isSending){
                val job=pending.firstOrNull()?:return
                if(job.session==split[1]&&job.packetId==split[2]){
                    cancelAckTimeout()
                    if(job.attempts++ < 3){
                        link?.send("MSGHEX ${OgmCodec.hex(job.frames[job.index])}")
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
                    link?.send("APPACK ${split[1]} ${split[2]} ${split[3]}")
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
                        OgmCodec.DELIVERED -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"delivered")
                        OgmCodec.READ -> if(env.text.size==8) updateReceipt(OgmCodec.hex(env.text),"read")
                    }
                } catch (ex: Exception) { Log.w("OffGrid", "Rejected invalid encrypted frame",ex) }
            }
            "ERROR" -> state="Node: ${split.getOrNull(1) ?: "error"}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSystemPairing(launch:(IntentSenderRequest)->Unit) {
        if(!hasBluetoothPermission()){state="Grant Nearby devices permission";return}
        try {
            val manager=getSystemService(CompanionDeviceManager::class.java)
            val filter=BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("^OffGrid-.*")).build()
            val req=AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
            manager.associate(req,object:CompanionDeviceManager.Callback(){
                @Deprecated("Legacy callback for Android 8-12")
                override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                    runOnUiThread { launch(IntentSenderRequest.Builder(chooserLauncher).build()) }
                }
                override fun onAssociationPending(chooserLauncher: android.content.IntentSender) {
                    runOnUiThread { launch(IntentSenderRequest.Builder(chooserLauncher).build()) }
                }
                override fun onFailure(error:CharSequence?) { runOnUiThread {state="Pairing cancelled: $error"} }
            },Handler(Looper.getMainLooper()))
            state="Select your nearby OffGrid node"
        } catch (ex: Exception) { state="Pairing unavailable: ${ex.message}" }
    }

    @Composable
    private fun OffGridScreen(){
        val colors=if(dark) darkColorScheme(primary=Color(0xFF82ACFF),background=Navy,surface=Color(0xFF15243A),onSurface=Color(0xFFF2F6FD))
            else lightColorScheme(primary=Sapphire,background=Color(0xFFF7F9FE),surface=Color.White,onSurface=Color(0xFF192B42))
        var keyInput by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(!pairingSaved) }
        val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(granted)reconnectIfPermitted() else state="Nearby devices permission denied"
        }
        val systemPicker=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if(result.resultCode==RESULT_OK){
                @Suppress("DEPRECATION")
                val device = if(Build.VERSION.SDK_INT>=33)
                    result.data?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)
                else result.data?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                // Android 13+ may return AssociationInfo rather than the old BluetoothDevice extra.
                val associatedMac = if(Build.VERSION.SDK_INT>=33)
                    result.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION,AssociationInfo::class.java)
                        ?.deviceMacAddress?.toString() else null
                val mac=device?.address ?: associatedMac
                if(!mac.isNullOrBlank()){
                    trustedAddress=mac;secure.saveNodeAddress(mac)
                    reconnectIfPermitted()
                } else state="Device selection returned no ESP32; retry pairing"
            }
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
                                        Text("Stage 2.0 includes encrypted text. Camera, voice, notifications and walkie-talkie follow after BLE testing.",style=MaterialTheme.typography.bodySmall)
                                        TextButton(onClick={showKey=true}){Text("Update pairing key")}
                                        TextButton(onClick={link?.stop();state="Disconnected"}){Text("Disconnect")}
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
                                                Text(item.text,style=MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.height(6.dp))
                                                Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically){
                                                    Text(java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(item.timestamp)),
                                                        style=MaterialTheme.typography.labelSmall,color=colors.onSurface.copy(alpha=.48f))
                                                    if(item.outgoing){
                                                        Spacer(Modifier.width(4.dp))
                                                        Icon(if(item.status=="read")Icons.Default.CheckCircle else Icons.Default.Check,
                                                            item.status,tint=if(item.status=="read")colors.primary else colors.onSurface.copy(alpha=.55f),
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
                                    FilledIconButton(onClick={
                                        if(draft.isNotBlank()){sendText(draft);draft=""}
                                        else state="Voice recording arrives in Stage 2.2"
                                    },enabled=connected,modifier=Modifier.size(53.dp),
                                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=colors.primary)){
                                        if(draft.isBlank())Icon(Icons.Default.Mic,"Voice coming soon",tint=Color.White)
                                        else Icon(Icons.AutoMirrored.Filled.Send,"Send",tint=Color.White)
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
