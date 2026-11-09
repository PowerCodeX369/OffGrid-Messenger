package com.offgrid.messenger

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BlueGlow = Color(0xFF61D9FF)
private val VividBlue = Color(0xFF4386FF)
private val Midnight = Color(0xFF080E20)
private val Ink = Color(0xFF162437)

@Composable
fun OffGridPremiumTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary=Color(0xFF83BAFF), onPrimary=Midnight, primaryContainer=Color(0xFF173766),
        background=Midnight, surface=Color(0xFF101B30), onSurface=Color(0xFFEDF4FF),
        onSurfaceVariant=Color(0xFF9FB0CA), secondary=BlueGlow
    ) else lightColorScheme(
        primary=Color(0xFF2568DF), onPrimary=Color.White, primaryContainer=Color(0xFFDDEBFF),
        background=Color(0xFFF3F7FF), surface=Color.White, onSurface=Ink,
        onSurfaceVariant=Color(0xFF61728B), secondary=Color(0xFF057C9A)
    )
    MaterialTheme(colorScheme=colors, shapes=Shapes(
        small=RoundedCornerShape(12.dp), medium=RoundedCornerShape(18.dp),
        large=RoundedCornerShape(24.dp), extraLarge=RoundedCornerShape(30.dp)
    ), content=content)
}

/** Translucent acrylic approximation, not an unrestricted backdrop blur.
 * Uses GPU-friendly gradients and a restrained border for stable resizing. */
@Composable
private fun GlassCard(
    modifier:Modifier=Modifier, dark:Boolean, radius:Dp=22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape=RoundedCornerShape(radius)
    val upper=if(dark) Color(0xFF2B4368).copy(alpha=.72f) else Color.White.copy(alpha=.91f)
    val lower=if(dark) Color(0xFF172943).copy(alpha=.82f) else Color(0xFFE6F0FF).copy(alpha=.82f)
    Column(modifier.clip(shape).background(Brush.linearGradient(listOf(upper,lower)))
        .border(BorderStroke(1.dp, if(dark) Color.White.copy(alpha=.14f) else Color.White.copy(alpha=.9f)),shape), content=content)
}

private fun timeOf(millis:Long)=SimpleDateFormat("h:mm a",Locale.getDefault()).format(Date(millis))

/** The presentation state is separated from BLE/OGM4 so the proven transport stays intact. */
data class PremiumActions(
    val sendText:(String)->Unit, val photo:()->Unit,
    val micPress:()->Unit, val micRelease:()->Unit, val micCancel:()->Unit,
    val playVoice:(ChatItem)->Unit, val markVisible:(Set<String>)->Unit,
    val chatVisibility:(Boolean)->Unit,
    val connect:()->Unit, val disconnect:()->Unit, val chooseNode:()->Unit,
    val forgetNode:()->Unit, val editKey:()->Unit,
    val changeDark:(Boolean)->Unit, val changeFastPhotos:(Boolean)->Unit,
    val changeNotifications:(Boolean)->Unit, val changePrivatePreview:(Boolean)->Unit,
    val changeBackground:(Boolean)->Unit, val changeAccessibleVoiceTap:(Boolean)->Unit,
    val clearHistory:()->Unit,
    val retry:(ChatItem)->Unit
)

@Composable
fun PremiumMessenger(
    items:List<ChatItem>, connected:Boolean, status:String, nodeAddress:String?,
    dark:Boolean, fastPhotos:Boolean, notifications:Boolean, privatePreview:Boolean,
    backgroundEnabled:Boolean, recording:Boolean, playingId:String?, appVisible:Boolean,
    accessibleVoiceTap:Boolean,
    diagnostics:List<String>, actions:PremiumActions
) {
    var page by rememberSaveableString("chat")
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var enlargedPhoto by remember { mutableStateOf<ChatItem?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var draft by rememberSaveableString("")
    val textColor=MaterialTheme.colorScheme.onSurface
    val bg=if(dark) listOf(Midnight,Color(0xFF111D32),Color(0xFF091125))
        else listOf(Color(0xFFEAF1FF),Color(0xFFF9FBFF),Color(0xFFE6F2FF))
    BoxWithConstraints(Modifier.fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))
        .background(Brush.linearGradient(bg))) {
        val wide=maxWidth>=720.dp
        val micro=maxWidth<355.dp
        if(!wide && page=="chat") BackHandler { page="chats" }
        if(page=="settings") BackHandler { page=if(wide)"chat" else "chats" }
        LaunchedEffect(page,wide) { actions.chatVisibility(page=="chat" || (wide && page!="settings")) }
        Row(Modifier.fillMaxSize()) {
            if(wide) {
                NavigationPane(
                    modifier=Modifier.widthIn(min=250.dp,max=314.dp).fillMaxHeight().navigationBarsPadding(),dark=dark,
                    items=items,connected=connected,status=status,selected=page,
                    openChat={page="chat"},openSettings={page="settings"},onConnect=actions.connect,
                    query=query,queryChange={query=it}
                )
                VerticalDivider(color=textColor.copy(alpha=.08f))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    page=="settings" -> SettingsPane(
                        dark=dark,fastPhotos=fastPhotos,notifications=notifications,
                        privatePreview=privatePreview,backgroundEnabled=backgroundEnabled,
                        accessibleVoiceTap=accessibleVoiceTap,
                        connected=connected,nodeAddress=nodeAddress, diagnostics=diagnostics,
                        onBack={page=if(wide)"chat" else "chats"}, actions=actions,
                        onShowDiagnostics={showDiagnostics=true},onClear={confirmErase=true}
                    )
                    page=="chats" && !wide -> NavigationPane(
                        modifier=Modifier.fillMaxSize(),dark=dark,items=items,connected=connected,
                        status=status,selected=page,openChat={page="chat"},
                        openSettings={page="settings"},onConnect=actions.connect,
                        query=query,queryChange={query=it}
                    )
                    else -> {
                        ChatTopBar(
                            dark=dark,connected=connected,status=status,
                            showBack=!wide,compact=micro,onBack={page="chats"},
                            onInfo={page="settings"},onSearch={showSearch=!showSearch}
                        )
                        AnimatedVisibility(showSearch) {
                            OutlinedTextField(
                                query,{query=it},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),
                                singleLine=true,leadingIcon={Icon(Icons.Default.Search,null)},
                                trailingIcon={IconButton(onClick={query="";showSearch=false}){Icon(Icons.Default.Close,"Close search")}},
                                placeholder={Text("Search this conversation")},shape=RoundedCornerShape(24.dp)
                            )
                        }
                        // Keyboard insets apply here, BELOW the fixed top app bar.
                        // Only the LazyColumn receives scrolling; the toolbar never translates.
                        Column(Modifier.weight(1f).fillMaxWidth().imePadding().navigationBarsPadding()) {
                            AnimatedVisibility(!connected,enter=fadeIn(),exit=fadeOut()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp),
                                    verticalAlignment=Alignment.CenterVertically) {
                                    Text("Offline · connect your node",style=MaterialTheme.typography.labelMedium,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                                    TextButton(onClick=actions.connect){Text("Connect")}
                                }
                            }
                            ChatTimeline(items=items,filter=if(showSearch)query else "",dark=dark,
                                playingId=playingId,connected=connected,appVisible=appVisible,
                                openPhoto={enlargedPhoto=it},playVoice=actions.playVoice,
                                onVisible=actions.markVisible,onRetry=actions.retry,
                                modifier=Modifier.fillMaxWidth().weight(1f))
                            GlassComposer(
                                draft=draft,onDraft={draft=it.take(500)},connected=connected,
                                recording=recording,small=micro,dark=dark,
                                tapMode=accessibleVoiceTap,
                                onSend={if(draft.isNotBlank()){actions.sendText(draft);draft=""}},
                                onPhoto=actions.photo,onPress=actions.micPress,
                                onRelease=actions.micRelease,onCancel=actions.micCancel
                            )
                        }
                    }
                }
            }
        }
    }
    enlargedPhoto?.let { FullPhoto(item=it,onClose={enlargedPhoto=null}) }
    if(showDiagnostics) AlertDialog(
        onDismissRequest={showDiagnostics=false},title={Text("BLE diagnostics")},
        text={Column(Modifier.heightIn(max=420.dp)) {
            diagnostics.takeLast(18).forEach { Text(it,style=MaterialTheme.typography.labelSmall) }
        }},confirmButton={TextButton(onClick={showDiagnostics=false}){Text("Close")}}
    )
    if(confirmErase) AlertDialog(onDismissRequest={confirmErase=false},
        title={Text("Clear encrypted history?")},
        text={Text("This permanently deletes saved messages and media from this phone. Your ESP32 pairing key is preserved.")},
        confirmButton={TextButton(onClick={actions.clearHistory();confirmErase=false}){Text("Delete history")}},
        dismissButton={TextButton(onClick={confirmErase=false}){Text("Cancel")}}
    )
}

@Composable
private fun rememberSaveableString(initial:String): MutableState<String> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun ChatTopBar(dark:Boolean,connected:Boolean,status:String,showBack:Boolean,compact:Boolean,onBack:()->Unit,onInfo:()->Unit,onSearch:()->Unit) {
    GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=0.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=if(showBack)8.dp else 16.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically) {
            if(showBack) IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back to chats") }
            NodeAvatar(44.dp,dark)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("Nearby node",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1)
                Text(if(connected)"Encrypted · nearby" else status,
                    style=MaterialTheme.typography.labelSmall,
                    color=if(connected)Color(0xFF49BEA6) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(!compact) IconButton(onClick=onSearch){Icon(Icons.Default.Search,"Search messages")}
            IconButton(onClick=onInfo){Icon(Icons.Default.MoreVert,"Conversation options")}
        }
    }
}

@Composable
private fun NodeAvatar(size:Dp,dark:Boolean) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(
        listOf(if(dark) Color(0xFF0C536E) else Color(0xFF2D8BBF), Color(0xFF155DAD)))),
        contentAlignment=Alignment.Center) {
        Icon(Icons.Default.Bluetooth,contentDescription=null,tint=Color.White,modifier=Modifier.size(size*.51f))
    }
}

@Composable
private fun NavigationPane(
    modifier:Modifier,dark:Boolean,items:List<ChatItem>,connected:Boolean,status:String,
    selected:String,openChat:()->Unit,openSettings:()->Unit,onConnect:()->Unit,
    query:String,queryChange:(String)->Unit
) {
    GlassCard(modifier,dark=dark,radius=0.dp) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=19.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("OffGrid",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,
                color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
            IconButton(onClick=openSettings){Icon(Icons.Default.Settings,"Settings")}
        }
        Text("PRIVATE MESSENGER",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=22.dp,bottom=17.dp))
        OutlinedTextField(query,queryChange,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp),
            singleLine=true,leadingIcon={Icon(Icons.Default.Search,null)},
            placeholder={Text("Search chats")},shape=RoundedCornerShape(24.dp))
        Spacer(Modifier.height(14.dp))
        if("nearby node".contains(query,ignoreCase=true) || items.any{it.text.contains(query,ignoreCase=true)}) {
            val latest=items.lastOrNull()
            Row(Modifier.fillMaxWidth().clickable(onClick=openChat).padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                NodeAvatar(55.dp,dark)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Nearby node",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        latest?.let {Text(timeOf(it.timestamp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(latest?.let{when(it.kind){OgmCodec.IMAGE->"📷 Photo";OgmCodec.VOICE->"🎤 Voice message";else->it.text}} ?: "Encrypted off-grid conversation",
                        maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal=18.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))
        if(!connected) TextButton(onClick=onConnect,modifier=Modifier.padding(10.dp)) {
            Icon(Icons.Default.Bluetooth,null);Spacer(Modifier.width(6.dp));Text("Connect to node")
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Default.LockOutline,null,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Offline · End-to-end encrypted",style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatTimeline(
    items:List<ChatItem>,filter:String,dark:Boolean,playingId:String?,connected:Boolean,appVisible:Boolean,
    openPhoto:(ChatItem)->Unit,playVoice:(ChatItem)->Unit,onVisible:(Set<String>)->Unit,
    onRetry:(ChatItem)->Unit,modifier:Modifier
) {
    val visibleItems=remember(items,filter) { if(filter.isBlank())items else items.filter {
        it.text.contains(filter,ignoreCase=true) || (it.kind==OgmCodec.IMAGE && "photo".contains(filter,true)) ||
            (it.kind==OgmCodec.VOICE && "voice".contains(filter,true))
    } }
    val lazy=rememberLazyListState()
    LaunchedEffect(visibleItems.size) {
        val isNearBottom = lazy.layoutInfo.totalItemsCount <= 2 ||
            lazy.layoutInfo.totalItemsCount - (lazy.firstVisibleItemIndex + lazy.layoutInfo.visibleItemsInfo.size) <= 3
        if(visibleItems.isNotEmpty() && isNearBottom) lazy.animateScrollToItem(visibleItems.size)
    }
    LaunchedEffect(lazy,visibleItems,connected,appVisible) {
        snapshotFlow { lazy.layoutInfo.visibleItemsInfo.map { it.index } }.collect { indices ->
            if(connected && appVisible) onVisible(indices.mapNotNull { visibleItems.getOrNull(it-1)?.takeIf { entry -> !entry.outgoing }?.id }.toSet())
        }
    }
    LazyColumn(modifier,state=lazy,contentPadding=PaddingValues(horizontal=12.dp,vertical=16.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(bottom=18.dp),contentAlignment=Alignment.Center) {
                GlassCard(dark=dark,radius=17.dp) {
                    Row(Modifier.padding(horizontal=14.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.LockOutline,null,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Encrypted locally · No servers",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if(visibleItems.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top=76.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.Forum,null,modifier=Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary.copy(alpha=.56f))
                Spacer(Modifier.height(12.dp))
                Text(if(filter.isBlank())"Your private conversation starts here" else "No matching messages",
                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text("Text, photos and voice—without the internet",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visibleItems,key={it.id}) { message ->
            ChatBubble(message,dark,playingId==message.id,openPhoto,playVoice,onRetry)
        }
    }
}

@Composable
private fun ChatBubble(message:ChatItem,dark:Boolean,playing:Boolean,openPhoto:(ChatItem)->Unit,
                       playVoice:(ChatItem)->Unit,onRetry:(ChatItem)->Unit) {
    val incoming=if(dark) Color(0xFF1B2942) else Color(0xFFFFFFFF)
    val outgoing=if(dark) Color(0xFF173D62) else Color(0xFFDDEEFF)
    val shape=RoundedCornerShape(topStart=19.dp,topEnd=19.dp,
        bottomStart=if(message.outgoing)19.dp else 6.dp,
        bottomEnd=if(message.outgoing)6.dp else 19.dp)
    Row(Modifier.fillMaxWidth().animateContentSize(),horizontalArrangement=if(message.outgoing)Arrangement.End else Arrangement.Start) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bound = (maxWidth*.84f).coerceAtMost(350.dp)
            Column(Modifier.align(if(message.outgoing)Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max=bound).clip(shape)
                .background(Brush.linearGradient(if(message.outgoing)
                    listOf(outgoing,outgoing.copy(alpha=.88f)) else listOf(incoming,incoming.copy(alpha=.94f))))
                .border(1.dp,if(dark)Color.White.copy(alpha=.09f) else Color(0xFFCCDFEF),shape)
                .padding(10.dp)) {
                when(message.kind) {
                    OgmCodec.IMAGE -> ResponsivePhoto(message,bound,openPhoto)
                    OgmCodec.VOICE -> VoiceBubble(message,playing,playVoice)
                    else -> Text(message.text,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface)
                }
                if(message.outgoing && message.progress in 1..99 && message.kind!=OgmCodec.TEXT) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress={message.progress/100f},modifier=Modifier.fillMaxWidth().height(3.dp))
                    Text("${message.progress}% · ${"%.1f".format(Locale.US,message.speedKiBs)} KiB/s",
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(timeOf(message.timestamp),style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(message.outgoing) ReceiptIcon(message)
                    if(message.outgoing && message.status=="failed") {
                        TextButton(onClick={onRetry(message)},contentPadding=PaddingValues(0.dp)) {Text("Retry")}
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponsivePhoto(message:ChatItem,maxBubble:Dp,onOpen:(ChatItem)->Unit) {
    val bitmap=remember(message.id,message.bytes) {
        message.bytes?.let { bytes -> runCatching {
            BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()
        }.getOrNull() }
    }
    val ratio=(message.width.toFloat()/message.height.coerceAtLeast(1)).coerceIn(.2f,5f)
    val maxH=286.dp
    val maxW=(maxBubble-20.dp).coerceAtLeast(100.dp)
    val width= minOf(maxW, maxH*ratio).coerceAtLeast(90.dp)
    val height=(width/ratio).coerceAtMost(maxH)
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(13.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(enabled=bitmap!=null){onOpen(message)},contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap,"Open encrypted photo",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else Icon(Icons.Default.BrokenImage,"Photo unavailable")
    }
    Spacer(Modifier.height(4.dp))
    Text("${message.width} × ${message.height}  ·  photo",style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VoiceBubble(message:ChatItem,playing:Boolean,onPlay:(ChatItem)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.widthIn(min=182.dp)) {
        FilledIconButton(onClick={onPlay(message)},modifier=Modifier.size(42.dp)) {
            Icon(if(playing)Icons.Default.Stop else Icons.Default.PlayArrow,if(playing)"Stop" else "Play voice note")
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            val segments= listOf(8,15,12,20,16,10,18,13,23,8,16,22,12,19,13)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                segments.forEachIndexed { i,v ->
                    Box(Modifier.width(3.dp).height(v.dp).clip(CircleShape).background(
                        if(playing && i%3==0)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha=.42f)))
                }
            }
            Spacer(Modifier.height(5.dp))
            Text("${message.durationMs/1000}s · voice note",style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(message.outgoing && message.status=="played") {
            Icon(Icons.Default.Mic,"Played",tint=Color(0xFF36AEFF),modifier=Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ReceiptIcon(message:ChatItem) {
    val v=ReceiptStates.visual(message.status,message.kind)
    val blue=Color(0xFF289EE9)
    val gray=MaterialTheme.colorScheme.onSurfaceVariant
    Crossfade(targetState=v,label="receipt") { visual -> when(visual) {
        ReceiptVisual.WAITING -> CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp,color=gray)
        ReceiptVisual.SENT -> Icon(Icons.Default.Check,"Sent",Modifier.size(16.dp),tint=gray)
        ReceiptVisual.DELIVERED -> Box(Modifier.size(20.dp).border(1.dp,gray.copy(alpha=.7f),CircleShape),contentAlignment=Alignment.Center) {
            Icon(Icons.Default.DoneAll,"Delivered",Modifier.size(15.dp),tint=gray)
        }
        ReceiptVisual.READ -> Icon(Icons.Default.DoneAll,if(message.kind==OgmCodec.VOICE)"Played" else "Seen",Modifier.size(19.dp),tint=blue)
        ReceiptVisual.FAILED -> Icon(Icons.Default.ErrorOutline,"Not sent",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.error)
    } }
}

@Composable
private fun GlassComposer(draft:String,onDraft:(String)->Unit,connected:Boolean,recording:Boolean,small:Boolean,dark:Boolean,
    tapMode:Boolean,onSend:()->Unit,onPhoto:()->Unit,onPress:()->Unit,onRelease:()->Unit,onCancel:()->Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        seconds=0
        while(recording) {delay(1000L);seconds++}
    }
    val micScale by animateFloatAsState(if(recording)1.07f else 1f,label="voice pulse")
    GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=if(small)18.dp else 22.dp) {
        AnimatedVisibility(recording,enter=fadeIn()+slideInVertically { it/3 },exit=fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.FiberManualRecord,null,tint=Color(0xFFFA6174),modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(9.dp))
                Text("Recording 0:${seconds.toString().padStart(2,'0')} · slide left to cancel",
                    style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=9.dp),
            verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            IconButton(onClick=onPhoto,enabled=connected&&!recording,modifier=Modifier.size(43.dp)) {
                Icon(Icons.Default.AddPhotoAlternate,"Attach photo",tint=MaterialTheme.colorScheme.primary)
            }
            TextField(draft,onDraft,modifier=Modifier.weight(1f).heightIn(min=50.dp,max=140.dp),
                enabled=connected&&!recording,maxLines=5,
                placeholder={Text(if(connected)"Message" else "Connect to send",style=MaterialTheme.typography.bodyMedium)},
                shape=RoundedCornerShape(23.dp),colors=TextFieldDefaults.colors(
                    focusedContainerColor=MaterialTheme.colorScheme.surface.copy(alpha=.72f),
                    unfocusedContainerColor=MaterialTheme.colorScheme.surface.copy(alpha=.72f),
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                keyboardActions=KeyboardActions(onSend={onSend()}))
            if(draft.isNotBlank()) FilledIconButton(onClick=onSend,enabled=connected,
                modifier=Modifier.size(50.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send,"Send message")
            } else if(tapMode) {
                FilledIconButton(onClick={if(recording)onRelease() else onPress()},
                    enabled=connected,modifier=Modifier.size(50.dp)) {
                    Icon(if(recording)Icons.Default.Stop else Icons.Default.Mic,
                        if(recording)"Stop and send voice" else "Start voice recording")
                }
            } else {
                val density=LocalDensity.current
                val cancelPx=with(density){78.dp.toPx()}
                val press by rememberUpdatedState(onPress)
                val release by rememberUpdatedState(onRelease)
                val cancel by rememberUpdatedState(onCancel)
                Box(Modifier.size(50.dp).scale(micScale).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription="Hold to record voice; release to send, swipe left to cancel" }
                    .pointerInput(connected) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            if(!connected) return@awaitEachGesture
                            press()
                            var shouldCancel=false
                            var released=false
                            while(!released) {
                                val change=awaitPointerEvent().changes.firstOrNull {it.id==down.id} ?: break
                                if(change.position.x-down.position.x < -cancelPx){shouldCancel=true;break}
                                if(!change.pressed)released=true
                            }
                            if(shouldCancel)cancel() else release()
                        }
                    },contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.Mic,"Hold to record",tint=MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(
    dark:Boolean,fastPhotos:Boolean,notifications:Boolean,privatePreview:Boolean,backgroundEnabled:Boolean,accessibleVoiceTap:Boolean,
    connected:Boolean,nodeAddress:String?,diagnostics:List<String>,onBack:()->Unit,actions:PremiumActions,
    onShowDiagnostics:()->Unit,onClear:()->Unit
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}
            Text("Settings",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(15.dp)) {
            item { GlassCard(dark=dark) {
                Column(Modifier.padding(18.dp)) {
                    Text("CONNECTED DEVICE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(nodeAddress ?: "No BLE device selected",style=MaterialTheme.typography.bodyMedium)
                    Text(if(connected)"Authenticated · offline transport" else "Disconnected",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick=actions.connect){Text("Connect")}
                        TextButton(onClick=actions.disconnect){Text("Disconnect")}
                    }
                    TextButton(onClick=actions.chooseNode){Text("Change node")}

                    TextButton(onClick=actions.editKey){Text("Update pairing key")}
                    TextButton(onClick=actions.forgetNode){Text("Forget node (keep key)")}
                }
            } }
            item { GlassCard(dark=dark) { Column(Modifier.padding(16.dp)) {
                ToggleSetting("Dark appearance","Midnight glass palette",dark,actions.changeDark)
                ToggleSetting("Fast photos","Smaller photos, quicker transfers",fastPhotos,actions.changeFastPhotos)
                ToggleSetting("Message notifications","Notify when chat is not visible",notifications,actions.changeNotifications)
                ToggleSetting("Notification preview","Show decrypted text outside the app",privatePreview,actions.changePrivatePreview)
                ToggleSetting("Keep BLE active","Persistent device connection notification; uses battery",backgroundEnabled,actions.changeBackground)
                ToggleSetting("Tap to record","Accessible alternative to press-and-hold",accessibleVoiceTap,actions.changeAccessibleVoiceTap)
            } } }
            item { GlassCard(dark=dark) { Column(Modifier.padding(16.dp)) {
                Text("Privacy & diagnostics",fontWeight=FontWeight.SemiBold)
                Text("History encrypted with Android Keystore, stored only on this phone. No cloud sync.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=onShowDiagnostics){Text("View BLE diagnostics")}
                TextButton(onClick=onClear){Text("Delete encrypted chat history")}
            } } }
            item { Text("OffGrid Messenger 2.4 · Offline · AES-256-GCM · Binary BLE",
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun ToggleSetting(title:String,description:String,checked:Boolean,onChanged:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked,onChanged)
    }
}

@Composable
private fun FullPhoto(item:ChatItem,onClose:()->Unit) {
    val bitmap=remember(item.id) { item.bytes?.let { data -> runCatching {
        BitmapFactory.decodeByteArray(data,0,data.size)?.asImageBitmap()
    }.getOrNull() } }
    Dialog(onDismissRequest=onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        Box(Modifier.fillMaxSize().background(Color(0xF5070B13)),contentAlignment=Alignment.Center) {
            bitmap?.let { pic ->
                Image(pic,"Encrypted photo preview",
                    modifier=Modifier.fillMaxWidth().fillMaxHeight(.88f).scale(scale)
                        .pointerInput(item.id){detectTransformGestures { _,_,zoom,_ -> scale=(scale*zoom).coerceIn(1f,4f) }},
                    contentScale=ContentScale.Fit)
            }
            IconButton(onClick=onClose,modifier=Modifier.align(Alignment.TopEnd).padding(top=28.dp,end=15.dp)) {
                Icon(Icons.Default.Close,"Close photo",tint=Color.White)
            }
        }
    }
}
