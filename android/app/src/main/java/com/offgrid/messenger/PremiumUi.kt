package com.offgrid.messenger

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BlueGlow = Color(0xFF61D9FF)
private val VividBlue = Color(0xFF4386FF)
private val Midnight = Color(0xFF080E20)
private val Ink = Color(0xFF162437)

// Intentional stability choice: no backdrop snapshots or blur sources. The UI uses
// accessible Material surfaces; no duplicated photo/image layers are possible.

@Composable
fun OffGridPremiumTheme(dark: Boolean, content: @Composable () -> Unit) {
    // Explicit semantic colors: toggling dark appearance must update *text* as
    // well as the underlying surfaces (including placeholders and dialogs).
    val colors = if (dark) darkColorScheme(
        primary=Color(0xFF9DD0FF),onPrimary=Color(0xFF09223D),primaryContainer=Color(0xFF193B62),
        onPrimaryContainer=Color(0xFFE2F1FF),secondary=Color(0xFF85E0E4),onSecondary=Color(0xFF002C32),
        background=Color(0xFF0D1523),onBackground=Color(0xFFE5ECF6),
        surface=Color(0xFF142238),onSurface=Color(0xFFE5ECF6),
        surfaceVariant=Color(0xFF263850),onSurfaceVariant=Color(0xFFB6C6D9),
        outline=Color(0xFF8296B0),outlineVariant=Color(0xFF43536B),
        error=Color(0xFFFFB4B4),onError=Color(0xFF540000),
    ) else lightColorScheme(
        primary=Color(0xFF175BC5),onPrimary=Color.White,primaryContainer=Color(0xFFD8E8FF),
        onPrimaryContainer=Color(0xFF0B2F65),secondary=Color(0xFF007E90),onSecondary=Color.White,
        background=Color(0xFFF5F8FD),onBackground=Color(0xFF111A29),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF111A29),
        surfaceVariant=Color(0xFFE8F0FA),onSurfaceVariant=Color(0xFF3F5167),
        outline=Color(0xFF6B7C92),outlineVariant=Color(0xFFCFDAE8),
        error=Color(0xFFB3261E),onError=Color.White,
    )
    MaterialTheme(colorScheme=colors, shapes=Shapes(
        small=RoundedCornerShape(12.dp), medium=RoundedCornerShape(18.dp),
        large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(30.dp)
    )) {
        // A gradient Box does not set Material's LocalContentColor; without this,
        // unstyled Text/Icon composables can inherit black even in dark appearance.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            content()
        }
    }
}

/** Premium Material surface with stable color semantics.
 * We deliberately avoid any backdrop sampling: user's screenshots showed stale/duplicated
 * chat pixels behind toolbars and contact dialogs. Color and contrast must take priority.
 */
@Composable
private fun GlassCard(
    modifier:Modifier=Modifier, dark:Boolean, radius:Dp=22.dp,
    frost:Boolean=false, bottomRadius:Dp=radius,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(topStart=radius,topEnd=radius,bottomStart=bottomRadius,bottomEnd=bottomRadius)
    val surface=when {
        dark && frost -> Color(0xFF1B3048)
        dark -> Color(0xFF18283D)
        frost -> Color(0xFFF8FBFF)
        else -> Color(0xFFFFFFFF)
    }
    // Surface provides the correct LocalContentColor for *every* nested Text/Icon.
    // A gentle tonal edge and elevation replace blur without sacrificing readability.
    Surface(
        modifier=modifier,
        shape=shape,
        color=surface,
        contentColor=colors.onSurface,
        tonalElevation=if(radius==0.dp)1.dp else 2.dp,
        shadowElevation=if(radius==0.dp)0.dp else 3.dp,
        border=BorderStroke(1.dp,colors.outlineVariant.copy(alpha=if(dark).72f else .65f))
    ) { Column(content=content) }
}

private fun timeOf(millis:Long)=SimpleDateFormat("h:mm a",Locale.getDefault()).format(Date(millis))

// Single navigation hierarchy; moving back must always reverse the opening slide.
private fun navigationDepth(page:String):Int = when(page) {
    "chats" -> 0
    "chat" -> 1
    "settings" -> 2
    else -> 1
}

/** The presentation state is separated from BLE/OGM4 so the proven transport stays intact. */
data class PremiumActions(
    val sendText:(String)->Unit, val photo:()->Unit, val camera:()->Unit,
    val micPress:()->Unit, val micRelease:()->Unit, val micCancel:()->Unit,
    val playVoice:(ChatItem)->Unit, val markVisible:(Set<String>)->Unit,
    val chatVisibility:(Boolean)->Unit,
    val connect:()->Unit, val disconnect:()->Unit, val chooseNode:()->Unit,
    val forgetNode:()->Unit, val editKey:()->Unit,
    val changeDark:(Boolean)->Unit, val changeFastPhotos:(Boolean)->Unit,
    val changeNotifications:(Boolean)->Unit, val changePrivatePreview:(Boolean)->Unit,
    val changeBackground:(Boolean)->Unit, val changeAccessibleVoiceTap:(Boolean)->Unit,
    val clearHistory:()->Unit,
    val retry:(ChatItem)->Unit,
    val deleteForMe:(ChatItem)->Unit,val deleteForEveryone:(ChatItem)->Unit,
    val editMessage:(ChatItem,String)->Unit,val copyMessage:(ChatItem)->Unit,
    val chooseAvatar:()->Unit,val clearAvatar:()->Unit,val renameContact:(String)->Unit
)

@Composable
fun PremiumMessenger(
    items:List<ChatItem>, connected:Boolean, status:String, nodeAddress:String?,
    dark:Boolean, fastPhotos:Boolean, notifications:Boolean, privatePreview:Boolean,
    backgroundEnabled:Boolean, recording:Boolean, playingId:String?, appVisible:Boolean,
    accessibleVoiceTap:Boolean,
    diagnostics:List<String>, actions:PremiumActions,
    contactName:String="Nearby node",avatarBytes:ByteArray?=null
) {
    var page by rememberSaveableString("chat")
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var enlargedPhoto by remember { mutableStateOf<ChatItem?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var showChatInfo by remember { mutableStateOf(false) }
    var chosenMessage by remember { mutableStateOf<ChatItem?>(null) }
    var confirmRemoteDelete by remember { mutableStateOf<ChatItem?>(null) }
    var editTarget by remember { mutableStateOf<ChatItem?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var renameDialog by remember { mutableStateOf(false) }
    var renameDraft by remember(contactName) { mutableStateOf(contactName) }
    var draft by rememberSaveableString("")
    val textColor=MaterialTheme.colorScheme.onSurface
    val bgStart by androidx.compose.animation.animateColorAsState(
        if(dark)Midnight else Color(0xFFEAF1FF),
        animationSpec=androidx.compose.animation.core.tween(360),label="theme background start")
    val bgMiddle by androidx.compose.animation.animateColorAsState(
        if(dark)Color(0xFF111D32) else Color(0xFFF9FBFF),
        animationSpec=androidx.compose.animation.core.tween(360),label="theme background middle")
    val bgEnd by androidx.compose.animation.animateColorAsState(
        if(dark)Color(0xFF091125) else Color(0xFFE6F2FF),
        animationSpec=androidx.compose.animation.core.tween(360),label="theme background end")
    val bg=listOf(bgStart,bgMiddle,bgEnd)
    BoxWithConstraints(Modifier.fillMaxSize()
        .background(Brush.linearGradient(bg)) // cover the system status-bar region too
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))) {
        val wide=maxWidth>=720.dp
        val micro=maxWidth<355.dp
        if(!wide && page=="chat") BackHandler { page="chats" }
        if(page=="settings") BackHandler { page=if(wide)"chat" else "chats" }
        LaunchedEffect(page,wide) { actions.chatVisibility(page=="chat" || (wide && page!="settings")) }
        // Chat/list content is drawn once. Menus are normal Material dialogs (platform
        // scrim + opaque surface), so overlays never duplicate or ghost photos.
        Row(Modifier.fillMaxSize()) {
            if(wide) {
                NavigationPane(
                    modifier=Modifier.widthIn(min=250.dp,max=314.dp).fillMaxHeight().navigationBarsPadding(),dark=dark,
                    items=items,connected=connected,status=status,selected=page,
                    contactName=contactName,avatarBytes=avatarBytes,
                    openChat={page="chat"},openSettings={page="settings"},onConnect=actions.connect
                )
                VerticalDivider(color=textColor.copy(alpha=.08f))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(targetState=page,modifier=Modifier.weight(1f).fillMaxWidth(),label="page navigation", transitionSpec={
                    // A deeper page enters from the right; Back reverses that same motion.
                    // Chats (0) -> conversation (1) -> settings (2) is our page hierarchy.
                    val direction=if(navigationDepth(targetState)<navigationDepth(initialState)) -1 else 1
                    (fadeIn(animationSpec=androidx.compose.animation.core.tween(240)) +
                        slideInHorizontally(animationSpec=androidx.compose.animation.core.tween(260)) { direction*(it/7) })
                        .togetherWith(fadeOut(animationSpec=androidx.compose.animation.core.tween(170)) +
                        slideOutHorizontally(animationSpec=androidx.compose.animation.core.tween(210)) { -direction*(it/8) })
                }) { destination ->
                when {
                    destination=="settings" -> SettingsPane(
                        dark=dark,fastPhotos=fastPhotos,notifications=notifications,
                        privatePreview=privatePreview,backgroundEnabled=backgroundEnabled,
                        accessibleVoiceTap=accessibleVoiceTap,
                        connected=connected,nodeAddress=nodeAddress, diagnostics=diagnostics,
                        onBack={page=if(wide)"chat" else "chats"}, actions=actions,
                        onShowDiagnostics={showDiagnostics=true},onClear={confirmErase=true}
                    )
                    destination=="chats" && !wide -> NavigationPane(
                        modifier=Modifier.fillMaxSize(),dark=dark,items=items,connected=connected,
                        status=status,selected=page,contactName=contactName,avatarBytes=avatarBytes,
                        openChat={page="chat"},openSettings={page="settings"},onConnect=actions.connect
                        )
                    else -> {
                        // Keep the chat viewport behind the pinned opaque toolbars,
                        // preserving scroll/inset behavior without expensive capture effects.
                        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                            ChatTimeline(items=items,filter=if(showSearch)query else "",dark=dark,
                                playingId=playingId,connected=connected,appVisible=appVisible,
                                openPhoto={enlargedPhoto=it},playVoice=actions.playVoice,
                                onVisible=actions.markVisible,onRetry=actions.retry,
                                onLongPress={chosenMessage=it},
                                topSpace=if(showSearch)150.dp else 94.dp,
                                bottomSpace=if(recording)170.dp else 112.dp,
                                modifier=Modifier.fillMaxSize())
                            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                                ChatTopBar(
                                    dark=dark,connected=connected,status=status,
                                    contactName=contactName,avatarBytes=avatarBytes,
                                    showBack=!wide,compact=micro,onBack={page="chats"},
                                    onInfo={showChatInfo=true},onSearch={showSearch=!showSearch}
                                )
                                AnimatedVisibility(showSearch) {
                                    GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=0.dp,frost=true) {
                                        OutlinedTextField(query,{query=it},
                                            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),
                                            singleLine=true,leadingIcon={Icon(Icons.Default.Search,null)},
                                            trailingIcon={IconButton(onClick={query="";showSearch=false}) {Icon(Icons.Default.Close,"Close search")}},
                                            placeholder={Text("Search this conversation")},shape=RoundedCornerShape(24.dp))
                                    }
                                }
                            }
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                                AnimatedVisibility(!connected,enter=fadeIn(),exit=fadeOut()) {
                                    GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=0.dp,frost=true) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp),
                                            verticalAlignment=Alignment.CenterVertically) {
                                            Text("Offline · connect your node",style=MaterialTheme.typography.labelMedium,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                                            TextButton(onClick=actions.connect){Text("Connect")}
                                        }
                                    }
                                }
                                GlassComposer(
                                    draft=draft,onDraft={draft=it.take(500)},connected=connected,
                                    recording=recording,small=micro,dark=dark,
                                    tapMode=accessibleVoiceTap,
                                    onSend={if(draft.isNotBlank()){actions.sendText(draft);draft=""}},
                                    onPhoto=actions.photo,onCamera=actions.camera,onPress=actions.micPress,
                                    onRelease=actions.micRelease,onCancel=actions.micCancel
                                )
                            }
                        }
                    }
                }
                }
            }
        } // End Row
    }
    chosenMessage?.let { message ->
        AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={chosenMessage=null},
            title={Text("Message options")},
            text={Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                if(message.kind==OgmCodec.TEXT && !message.deleted) {
                    TextButton(onClick={actions.copyMessage(message);chosenMessage=null}) {
                        Icon(Icons.Default.ContentCopy,null);Spacer(Modifier.width(8.dp));Text("Copy text")
                    }
                }
                if(message.outgoing && message.kind==OgmCodec.TEXT && !message.deleted && connected &&
                    message.status in listOf("delivered","read","played")) {
                    TextButton(onClick={editDraft=message.text;editTarget=message;chosenMessage=null}) {
                        Icon(Icons.Default.Edit,null);Spacer(Modifier.width(8.dp));Text("Edit message")
                    }
                }
                TextButton(onClick={actions.deleteForMe(message);chosenMessage=null}) {
                    Icon(Icons.Default.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("Delete for me")
                }
                if(message.outgoing && !message.deleted && connected &&
                    message.status in listOf("delivered","read","played")) {
                    TextButton(onClick={confirmRemoteDelete=message;chosenMessage=null}) {
                        Icon(Icons.Default.DeleteForever,null);Spacer(Modifier.width(8.dp));Text("Delete for everyone")
                    }
                }
            }},confirmButton={TextButton(onClick={chosenMessage=null}) {Text("Close")}})
    }
    editTarget?.let { target -> AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={editTarget=null},
        title={Text("Edit message")},text={OutlinedTextField(editDraft,{editDraft=it.take(500)},
            singleLine=false,maxLines=5,label={Text("Message text")})},
        confirmButton={TextButton(enabled=editDraft.isNotBlank(),onClick={
            actions.editMessage(target,editDraft.trim());editTarget=null
        }){Text("Save edit")}},dismissButton={TextButton(onClick={editTarget=null}){Text("Cancel")}})
    }
    confirmRemoteDelete?.let { target -> AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={confirmRemoteDelete=null},
        title={Text("Delete for everyone?")},
        text={Text("The recipient's updated OffGrid client will remove this message when it receives the encrypted delete command. Devices that were offline or use older versions may still retain it.")},
        confirmButton={TextButton(onClick={actions.deleteForEveryone(target);confirmRemoteDelete=null}){Text("Delete")}},
        dismissButton={TextButton(onClick={confirmRemoteDelete=null}){Text("Cancel")}})
    }
    if(showChatInfo) AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={showChatInfo=false},
        title={Text("Contact details")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){NodeAvatar(64.dp,dark,avatarBytes);Spacer(Modifier.width(12.dp));Text(contactName,style=MaterialTheme.typography.titleLarge)}
            Text("Private, device-to-device conversation",color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={showChatInfo=false;actions.chooseAvatar()}){Text("Change contact photo")}
            if(avatarBytes!=null)TextButton(onClick={showChatInfo=false;actions.clearAvatar()}){
                Text("Remove contact photo")
            }
            TextButton(onClick={renameDraft=contactName;renameDialog=true;showChatInfo=false}){Text("Rename contact")}
        }},confirmButton={TextButton(onClick={showChatInfo=false}){Text("Done")}})
    if(renameDialog) AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={renameDialog=false},title={Text("Contact name")},
        text={OutlinedTextField(renameDraft,{renameDraft=it.take(36)},singleLine=true)},
        confirmButton={TextButton(onClick={actions.renameContact(renameDraft.trim().ifEmpty {"Nearby node"});renameDialog=false}){Text("Save")}},
        dismissButton={TextButton(onClick={renameDialog=false}){Text("Cancel")}})
    enlargedPhoto?.let { FullPhoto(item=it,onClose={enlargedPhoto=null}) }
    if(showDiagnostics) AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={showDiagnostics=false},title={Text("BLE diagnostics")},
        text={Column(Modifier.heightIn(max=420.dp)) {
            diagnostics.takeLast(18).forEach { Text(it,style=MaterialTheme.typography.labelSmall) }
        }},confirmButton={TextButton(onClick={showDiagnostics=false}){Text("Close")}}
    )
    if(confirmErase) AlertDialog(
        containerColor=MaterialTheme.colorScheme.surface,
        titleContentColor=MaterialTheme.colorScheme.onSurface,
        textContentColor=MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation=2.dp,shape=RoundedCornerShape(26.dp),
        onDismissRequest={confirmErase=false},
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
private fun ChatTopBar(dark:Boolean,connected:Boolean,status:String,contactName:String,avatarBytes:ByteArray?,showBack:Boolean,compact:Boolean,onBack:()->Unit,onInfo:()->Unit,onSearch:()->Unit) {
    GlassCard(Modifier.fillMaxWidth(),dark=dark,radius=0.dp,frost=true) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=if(showBack)8.dp else 16.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically) {
            if(showBack) IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back to chats") }
            NodeAvatar(44.dp,dark,avatarBytes)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(contactName,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium,maxLines=1)
                Text(if(connected)"Encrypted · nearby" else status,
                    style=MaterialTheme.typography.labelSmall,
                    color=if(connected)Color(0xFF49BEA6) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(!compact) IconButton(onClick=onSearch){Icon(Icons.Default.Search,"Search messages")}
            IconButton(onClick=onInfo){Icon(Icons.Default.MoreVert,"Conversation details")}
        }
    }
}

@Composable
private fun NodeAvatar(size:Dp,dark:Boolean,avatarBytes:ByteArray?=null) {
    val picture=remember(avatarBytes) { avatarBytes?.let { raw ->
        runCatching { BitmapFactory.decodeByteArray(raw,0,raw.size)?.asImageBitmap() }.getOrNull()
    } }
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(
        listOf(if(dark) Color(0xFF184C70) else Color(0xFF2D83B4), Color(0xFF174C8A)))),
        contentAlignment=Alignment.Center) {
        if(picture!=null) Image(picture,"Contact photo",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(Icons.Default.Person,contentDescription="Contact",tint=Color.White,modifier=Modifier.size(size*.56f))
    }
}

@Composable
private fun NavigationPane(
    modifier:Modifier,dark:Boolean,items:List<ChatItem>,connected:Boolean,status:String,
    selected:String,contactName:String,avatarBytes:ByteArray?,openChat:()->Unit,openSettings:()->Unit,onConnect:()->Unit
) {
    GlassCard(modifier,dark=dark,radius=0.dp) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=10.dp,top=19.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("OffGrid",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.ExtraBold,
                color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
            IconButton(onClick=openSettings){Icon(Icons.Default.Settings,"Settings")}
        }
        Text("PRIVATE MESSENGER",style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(start=22.dp,bottom=17.dp))
        Spacer(Modifier.height(10.dp))
        // Exactly one conversation exists; no chat-list search or filtering.
        val latest=items.lastOrNull()
        Row(Modifier.fillMaxWidth().clickable(onClick=openChat).padding(16.dp),
            verticalAlignment=Alignment.CenterVertically) {
            NodeAvatar(55.dp,dark,avatarBytes)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(contactName,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    latest?.let { Text(timeOf(it.timestamp),style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(4.dp))
                Text(latest?.let { when(it.kind) {
                    OgmCodec.IMAGE -> "📷 Photo"
                    OgmCodec.VOICE -> "🎤 Voice message"
                    else -> it.text
                } } ?: "Encrypted off-grid conversation",
                    maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal=18.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))
        if(!connected) TextButton(onClick=onConnect,modifier=Modifier.padding(10.dp)) {
            Icon(Icons.Default.Bluetooth,null);Spacer(Modifier.width(6.dp));Text("Connect to node")
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Default.Lock,null,modifier=Modifier.size(16.dp),tint=MaterialTheme.colorScheme.primary)
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
    onRetry:(ChatItem)->Unit,onLongPress:(ChatItem)->Unit,modifier:Modifier,
    topSpace:Dp=94.dp,bottomSpace:Dp=112.dp
) {
    // Do not cache filtered results by list identity: messages mutate in a snapshot state list.
    val visibleItems=if(filter.isBlank())items else items.filter {
        it.text.contains(filter,ignoreCase=true) || (it.kind==OgmCodec.IMAGE && "photo".contains(filter,true)) ||
            (it.kind==OgmCodec.VOICE && "voice".contains(filter,true))
    }
    val messageCount=visibleItems.size
    val latestId=visibleItems.lastOrNull()?.id
    // Index zero is the info banner; the newest message is at index messageCount.
    val lazy=rememberLazyListState(initialFirstVisibleItemIndex=messageCount)
    var previousCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(messageCount,latestId,filter) {
        val previous=previousCount
        previousCount=messageCount
        if(messageCount==0)return@LaunchedEffect
        if(previous<0) {
            lazy.scrollToItem(messageCount) // First open: never animate through saved history.
        } else if(messageCount>previous) {
            // Follow every new incoming or outgoing message, even if the user scrolled up.
            // Avoid a long, janky animation through older history.
            if(messageCount-lazy.firstVisibleItemIndex>18) lazy.scrollToItem(messageCount)
            else lazy.animateScrollToItem(messageCount)
        }
    }
    LaunchedEffect(lazy,visibleItems,connected,appVisible) {
        snapshotFlow { lazy.layoutInfo.visibleItemsInfo.map { it.index } }.collect { indices ->
            if(connected && appVisible) onVisible(indices.mapNotNull { visibleItems.getOrNull(it-1)?.takeIf { entry -> !entry.outgoing && !entry.deleted }?.id }.toSet())
        }
    }
    LazyColumn(modifier,state=lazy,contentPadding=PaddingValues(start=12.dp,end=12.dp,top=topSpace,bottom=bottomSpace),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(bottom=18.dp),contentAlignment=Alignment.Center) {
                GlassCard(dark=dark,radius=17.dp) {
                    Row(Modifier.padding(horizontal=14.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock,null,modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.secondary)
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
            // No disappearing-item overlay: deleted bubbles are immediately removed.
            ChatBubble(message,dark,playingId==message.id,openPhoto,playVoice,onRetry,onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(message:ChatItem,dark:Boolean,playing:Boolean,openPhoto:(ChatItem)->Unit,
                       playVoice:(ChatItem)->Unit,onRetry:(ChatItem)->Unit,onLongPress:(ChatItem)->Unit) {
    val incoming=if(dark) Color(0xFF1B2942) else Color(0xFFFFFFFF)
    val outgoing=if(dark) Color(0xFF173D62) else Color(0xFFDDEEFF)
    val shape=RoundedCornerShape(topStart=19.dp,topEnd=19.dp,
        bottomStart=if(message.outgoing)19.dp else 6.dp,
        bottomEnd=if(message.outgoing)6.dp else 19.dp)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(message.outgoing)Arrangement.End else Arrangement.Start) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bound = if(message.kind==OgmCodec.VOICE)
                (maxWidth*.70f).coerceAtMost(225.dp)
                else (maxWidth*.84f).coerceAtMost(350.dp)
            Column(Modifier.align(if(message.outgoing)Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max=bound).clip(shape)
                .combinedClickable(onClick={},onLongClick={onLongPress(message)})
                .background(Brush.linearGradient(if(message.outgoing)
                    listOf(outgoing,outgoing.copy(alpha=.88f)) else listOf(incoming,incoming.copy(alpha=.94f))))
                .border(1.dp,if(dark)Color.White.copy(alpha=.09f) else Color(0xFFCCDFEF),shape)
                .padding(10.dp)) {
                when {
                    message.deleted -> Text(message.text,style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,fontStyle=FontStyle.Italic)
                    message.kind==OgmCodec.IMAGE -> ResponsivePhoto(message,bound,openPhoto,onLongPress)
                    message.kind==OgmCodec.VOICE -> VoiceBubble(message,playing,playVoice)
                    else -> Column {
                        Text(message.text,style=MaterialTheme.typography.bodyLarge,
                            color=MaterialTheme.colorScheme.onSurface)
                        if(message.edited)Text("edited",style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(message.outgoing && !message.deleted && message.progress in 1..99 && message.kind!=OgmCodec.TEXT) {
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
@OptIn(ExperimentalFoundationApi::class)
private fun ResponsivePhoto(message:ChatItem,maxBubble:Dp,onOpen:(ChatItem)->Unit,
                            onLongPress:(ChatItem)->Unit) {
    // Avoid main-thread BitmapFactory.decodeByteArray in scrolling LazyColumn:
    // photos are loaded off-thread and downsampled for the bubble only.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue=null,key1=message.id,key2=message.bytes
    ) {
        val bytes=message.bytes
        value=if(bytes==null)null else withContext(Dispatchers.Default) {
            runCatching {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>800) sample*=2
                val options=BitmapFactory.Options().apply {inSampleSize=sample}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val ratio=(message.width.toFloat()/message.height.coerceAtLeast(1)).coerceIn(.2f,5f)
    val maxH=286.dp
    val maxW=(maxBubble-20.dp).coerceAtLeast(100.dp)
    val width= minOf(maxW, maxH*ratio).coerceAtLeast(90.dp)
    val height=(width/ratio).coerceAtMost(maxH)
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(13.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .combinedClickable(enabled=bitmap!=null,onClick={onOpen(message)},onLongClick={onLongPress(message)}),
        contentAlignment=Alignment.Center) {
        val photo=bitmap // delegated Compose state cannot be smart-cast directly
        if(photo!=null) Image(photo,"Open encrypted photo",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else if(message.bytes!=null) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        else Icon(Icons.Default.BrokenImage,"Photo unavailable")
    }
    Spacer(Modifier.height(4.dp))
    Text("${message.width} × ${message.height}  ·  photo",style=MaterialTheme.typography.labelSmall,
        color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VoiceBubble(message:ChatItem,playing:Boolean,onPlay:(ChatItem)->Unit) {
    // A deliberate compact voice bubble: no weight-based expansion to the full
    // chat width. The waveform and duration live immediately beside each other.
    Row(modifier=Modifier.widthIn(min=162.dp,max=208.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        FilledIconButton(onClick={onPlay(message)},modifier=Modifier.size(39.dp)) {
            Icon(if(playing)Icons.Default.Stop else Icons.Default.PlayArrow,
                if(playing)"Stop" else "Play voice note",modifier=Modifier.size(19.dp))
        }
        Column(modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            val bars=listOf(8,11,16,12,20,15,9,17,23,14,10,19,12,16,9,13,17,11,7)
            Row(horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
                bars.forEachIndexed { index,height ->
                    Box(Modifier.width(2.dp).height(height.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(
                            alpha=if(playing && index<=9)1f else .54f)))
                }
            }
            Text("${message.durationMs/60000}:${((message.durationMs/1000)%60).toString().padStart(2,'0')}",
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
        }
        if(message.outgoing && message.status=="played") {
            Icon(Icons.Default.Mic,"Voice listened to",tint=Color(0xFF36AEFF),modifier=Modifier.size(16.dp))
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
        ReceiptVisual.READ -> Icon(Icons.Default.DoneAll,
            if(message.kind==OgmCodec.VOICE && message.status=="played")"Read and played" else "Read",
            Modifier.size(19.dp),tint=blue)
        ReceiptVisual.FAILED -> Icon(Icons.Default.ErrorOutline,"Not sent",Modifier.size(18.dp),tint=MaterialTheme.colorScheme.error)
    } }
}

@Composable
private fun GlassComposer(draft:String,onDraft:(String)->Unit,connected:Boolean,recording:Boolean,small:Boolean,dark:Boolean,
    tapMode:Boolean,onSend:()->Unit,onPhoto:()->Unit,onCamera:()->Unit,onPress:()->Unit,onRelease:()->Unit,onCancel:()->Unit) {
    var seconds by remember { mutableIntStateOf(0) }
    var slide by remember { mutableFloatStateOf(0f) }
    var locked by remember { mutableStateOf(false) }
    var cancelArmed by remember { mutableStateOf(false) }
    var trashFlash by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    val keyboardVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val lowerRadius by animateDpAsState(if(keyboardVisible)3.dp else 22.dp,
        animationSpec=androidx.compose.animation.core.tween(240),label="keyboard composer corners")
    val haptics=LocalHapticFeedback.current
    LaunchedEffect(recording) {
        seconds=0
        if(!recording){slide=0f;locked=false;cancelArmed=false}
        while(recording) {delay(1000L);seconds++}
    }
    LaunchedEffect(trashFlash) {if(trashFlash){delay(650);trashFlash=false}}
    val micScale by animateFloatAsState(if(recording)1.15f else 1f,label="voice pulse")
    val canceled=(!locked && cancelArmed) || trashFlash
    val trashScale by animateFloatAsState(if(trashFlash)1.45f else if(canceled)1.17f else 1f,
        animationSpec=androidx.compose.animation.core.spring(),label="trash bounce")
    // Small symmetric gutters avoid display waterfall / strongly rounded Pixel corners.
    GlassCard(Modifier.fillMaxWidth().padding(horizontal=5.dp),dark=dark,
        radius=if(small)18.dp else 23.dp,bottomRadius=lowerRadius,frost=true) {
        AnimatedVisibility(recording||trashFlash,enter=fadeIn()+slideInVertically { it/2 },exit=fadeOut()) {
            Row(Modifier.fillMaxWidth().heightIn(min=45.dp).padding(horizontal=16.dp,vertical=5.dp),
                verticalAlignment=Alignment.CenterVertically) {
                AnimatedContent(targetState=canceled,label="voice cancel feedback") { isCancel ->
                    if(isCancel) Icon(Icons.Default.Delete,"Discard recording",tint=MaterialTheme.colorScheme.error,
                        modifier=Modifier.size(26.dp).scale(trashScale))
                    else Icon(Icons.Default.FiberManualRecord,null,tint=Color(0xFFE84E63),
                        modifier=Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(when {trashFlash -> "Recording discarded";canceled -> "Release to cancel";
                        locked -> "0:${seconds.toString().padStart(2,'0')}  ·  Recording locked";
                        else -> "0:${seconds.toString().padStart(2,'0')}  ·  ← Cancel   ↑ Lock" },
                    style=MaterialTheme.typography.labelMedium,
                    color=if(canceled)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier=Modifier.weight(1f))
                if(recording && locked) Icon(Icons.Default.Lock,"Recording locked",
                    modifier=Modifier.size(22.dp),tint=MaterialTheme.colorScheme.primary)
                else if(recording && !canceled) Icon(Icons.Default.ArrowUpward,"Swipe up to lock",
                    modifier=Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=9.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            if(recording && locked) {
                Row(Modifier.weight(1f).heightIn(min=50.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock,"Locked",tint=MaterialTheme.colorScheme.primary,
                        modifier=Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Recording locked",style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurface,maxLines=1)
                }
            } else {
            Box(contentAlignment=Alignment.Center,modifier=Modifier.size(43.dp)) {
                IconButton(onClick={attachmentMenu=true},enabled=connected&&!recording,
                    modifier=Modifier.size(43.dp)) {
                    Icon(Icons.Default.AddPhotoAlternate,"Attach photo or take picture",
                        modifier=Modifier.size(23.dp),tint=MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded=attachmentMenu,onDismissRequest={attachmentMenu=false}) {
                    DropdownMenuItem(text={Text("Choose photo")},
                        leadingIcon={Icon(Icons.Default.Collections,"Gallery")},
                        onClick={attachmentMenu=false;onPhoto()})
                    DropdownMenuItem(text={Text("Take picture")},
                        leadingIcon={Icon(Icons.Default.PhotoCamera,"Camera")},
                        onClick={attachmentMenu=false;onCamera()})
                }
            }
            TextField(draft,onDraft,modifier=Modifier.weight(1f).heightIn(min=50.dp,max=140.dp),
                enabled=connected&&!recording,maxLines=5,
                placeholder={Text(if(connected)"Message" else "Connect to send",style=MaterialTheme.typography.bodyMedium)},
                shape=RoundedCornerShape(23.dp),colors=TextFieldDefaults.colors(
                    focusedTextColor=MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor=MaterialTheme.colorScheme.onSurface,
                    focusedPlaceholderColor=MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor=MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor=MaterialTheme.colorScheme.primary,
                    focusedContainerColor=MaterialTheme.colorScheme.surface.copy(alpha=.82f),
                    unfocusedContainerColor=MaterialTheme.colorScheme.surface.copy(alpha=.82f),
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),
                keyboardActions=KeyboardActions(onSend={onSend()}))
            }
            if(draft.isNotBlank()) FilledIconButton(onClick=onSend,enabled=connected,
                modifier=Modifier.size(50.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send,"Send message")
            } else if(recording && locked) {
                IconButton(onClick={onCancel();trashFlash=true},modifier=Modifier.size(44.dp)) {
                    Icon(Icons.Default.DeleteOutline,"Discard locked recording",tint=MaterialTheme.colorScheme.error)
                }
                FilledIconButton(onClick=onRelease,modifier=Modifier.size(50.dp)) {
                    Icon(Icons.Default.Send,"Stop and send locked recording")
                }
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
                    .background(if(canceled)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    .semantics {contentDescription="Hold to record. Slide left to cancel; slide up to lock recording. Release sends unless locked."}
                    .pointerInput(connected,cancelPx) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            if(!connected)return@awaitEachGesture
                            press();haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            var cancelled=false
                            var finished=false
                            while(!finished) {
                                val change=awaitPointerEvent().changes.firstOrNull {it.id==down.id}
                                if(change==null) { cancelled=!locked;break }
                                val dx=change.position.x-down.position.x
                                val dy=change.position.y-down.position.y
                                slide=dx/density.density
                                if(!locked && !cancelled && VoiceGestureRules.classify(
                    dx/density.density,dy/density.density,locked,cancelled)==VoiceGestureRules.Action.LOCK) {
                                    locked=true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if(VoiceGestureRules.classify(
                    dx/density.density,dy/density.density,locked,cancelled)==VoiceGestureRules.Action.CANCEL) {
                                    if(!cancelled)haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cancelled=true
                                    cancelArmed=true
                                }
                                if(!change.pressed) finished=true
                            }
                            when {
                                cancelled -> {cancel();trashFlash=true}
                                locked -> Unit // persistent recording; send/discard buttons take over
                                else -> release()
                            }
                            slide=0f
                        }
                    },contentAlignment=Alignment.Center) {
                    Icon(if(canceled)Icons.Default.Delete else Icons.Default.Mic,
                        "Hold to record",tint=MaterialTheme.colorScheme.onPrimary)
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
    // Settings scrolls below a pinned opaque Material header; no blur snapshot.
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding=PaddingValues(start=18.dp,end=18.dp,top=94.dp,bottom=18.dp),
            verticalArrangement=Arrangement.spacedBy(15.dp)) {
            item { GlassCard(Modifier.fillMaxWidth(),dark=dark) {
                Column(Modifier.padding(18.dp)) {
                    Text("CONNECTED DEVICE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(nodeAddress ?: "No BLE device selected",style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurface,
                        maxLines=2,overflow=TextOverflow.Ellipsis)
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
            item { GlassCard(Modifier.fillMaxWidth(),dark=dark) { Column(Modifier.padding(16.dp)) {
                ToggleSetting("Dark appearance","Midnight glass palette",dark,actions.changeDark)
                ToggleSetting("Fast photos","Smaller photos, quicker transfers",fastPhotos,actions.changeFastPhotos)
                ToggleSetting("Message notifications","Notify when chat is not visible",notifications,actions.changeNotifications)
                ToggleSetting("Notification preview","Show decrypted text outside the app",privatePreview,actions.changePrivatePreview)
                ToggleSetting("Keep BLE active","Persistent device connection notification; uses battery",backgroundEnabled,actions.changeBackground)
                ToggleSetting("Tap to record","Accessible alternative to press-and-hold",accessibleVoiceTap,actions.changeAccessibleVoiceTap)
            } } }
            item { GlassCard(Modifier.fillMaxWidth(),dark=dark) { Column(Modifier.padding(16.dp)) {
                Text("Privacy & diagnostics",fontWeight=FontWeight.SemiBold)
                Text("History encrypted with Android Keystore, stored only on this phone. No cloud sync.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=onShowDiagnostics){Text("View BLE diagnostics")}
                TextButton(onClick=onClear){Text("Delete encrypted chat history")}
            } } }
            item { Text("OffGrid Messenger 2.4.3 · Offline · AES-256-GCM · Binary BLE",
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(8.dp)) }
        }
        GlassCard(Modifier.fillMaxWidth().align(Alignment.TopCenter),dark=dark,radius=0.dp,frost=true) {
            Row(Modifier.fillMaxWidth().heightIn(min=62.dp).padding(horizontal=12.dp,vertical=7.dp),
                verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}
                Text("Settings",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ToggleSetting(title:String,description:String,checked:Boolean,onChanged:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=11.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,fontWeight=FontWeight.Medium,
                color=MaterialTheme.colorScheme.onSurface)
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
