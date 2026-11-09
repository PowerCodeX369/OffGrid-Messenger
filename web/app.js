'use strict';
/* OffGrid Messenger v1.2: same ESP-NOW protocol 2, plus optional READ wire frames.
   Unencrypted prototype: do not send secrets or private location data. */
const $ = id => document.getElementById(id);
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', {fatal:false});
const MAX_BYTES = 180;
let port=null, reader=null, writer=null, readPromise=null, closing=false, connected=false, verified=false, serialBuffer='';
let handshakeTimer=null, writeChain=Promise.resolve(), localMac=null;
let pendingLocal=[], sentMessages=new Map(), seenIncoming=new Set(), unreadMessages=new Map();
let activeContact='Nearby node';
const stateRank={queued:0,sending:0,sent:1,failed:1,delivered:2,read:3};
let theme='dark';
try{theme=localStorage.getItem('offgrid_theme')==='light'?'light':'dark';}catch{}
document.body.classList.toggle('light',theme==='light');
const fmtTime=() => new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
const uid=(session,id)=>`${session}:${id}`;
const validMac=s=>/^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/i.test(s);
const fromHex=hex=>{if(hex.length%2||!/^[0-9a-f]*$/i.test(hex))throw Error('Malformed hex');return decoder.decode(Uint8Array.from(hex.match(/.{2}/g)?.map(v=>parseInt(v,16))||[]));};
const toHex=text=>Array.from(encoder.encode(text),b=>b.toString(16).padStart(2,'0')).join('').toUpperCase();
function notify(message){const toast=document.createElement('div');toast.className='toast';toast.textContent=message;$('toastHost').appendChild(toast);setTimeout(()=>toast.remove(),3500);}
function isChatVisible(){return !document.hidden && document.hasFocus() && (innerWidth>590||document.body.classList.contains('mobile-chat'));}
function setConnection(mode,detail=''){
  connected=mode!=='offline';verified=mode==='ready';
  $('statusDot').className='status-dot'+(mode==='ready'?' on':mode==='waiting'?' wait':'');
  $('connectionState').textContent=mode==='ready'?'Connected':mode==='waiting'?'Checking firmware':'Disconnected';
  $('connectionDescription').textContent=detail||(mode==='ready'?'USB connection established':mode==='waiting'?'Waiting for protocol handshake':'Connect your ESP32-C3 over USB');
  $('nodeAddress').textContent='Node: '+(localMac||'—');
  $('connectButton').disabled=connected;$('disconnectButton').disabled=!connected;
  $('messageInput').disabled=!verified;$('sendButton').disabled=!verified;
  $('messageInput').placeholder=verified?'Type a message':'Connect ESP32 to send messages';
  $('chatSubhead').textContent=mode==='ready'?'Connected over ESP-NOW · channel 6':mode==='waiting'?'Verifying radio firmware':'Offline · connect ESP32';
  $('composerStatus').textContent=mode==='ready'?'Enter to send · Shift+Enter for newline (180-byte limit)':mode==='waiting'?'Checking firmware protocol 2':'Connect an ESP32 using the sidebar';
}
function updatePreview(text, outgoing){$('lastChatPreview').textContent=(outgoing?'You: ':'')+text;$('lastChatTime').textContent=fmtTime();}
function messageBubble(text,direction,status=''){
  const row=document.createElement('div');row.className='message-row '+(direction==='out'?'out':'in');
  const b=document.createElement('div');b.className='message-bubble';
  const body=document.createElement('div');body.className='message-text';body.textContent=text;
  const meta=document.createElement('div');meta.className='message-meta';
  const time=document.createElement('span');time.textContent=fmtTime();meta.append(time);
  if(direction==='out'){
    const tick=document.createElement('span');tick.className='tick';tick.dataset.role='tick';
    const statusSpan=document.createElement('span');statusSpan.dataset.role='state';
    meta.append(tick,statusSpan);
  }
  b.append(body,meta);row.appendChild(b);$('messages').appendChild(row);
  if(direction==='out')setMessageState(row,status);
  $('chatScroll').scrollTop=$('chatScroll').scrollHeight;return row;
}
function setMessageState(row,newStatus){
  if(!row)return;
  const prior=row.dataset.status||'sending';
  if(newStatus!=='failed'&&newStatus!=='sending'&&(stateRank[newStatus]??0)<(stateRank[prior]??0))return;
  if(prior==='read' && newStatus!=='read')return;
  if(prior==='delivered'&&newStatus==='failed')return;
  row.dataset.status=newStatus;
  const tick=row.querySelector('[data-role="tick"]'),state=row.querySelector('[data-role="state"]');
  const statuses={sending:['◷','Sending'],queued:['◷','Queued'],sent:['✓','Sent'],delivered:['✓✓','Delivered'],read:['✓✓','Read'],failed:['!','Failed']};
  const [symbol,label]=statuses[newStatus]||statuses.sending;
  tick.textContent=symbol;tick.className='tick'+(newStatus==='read'?' read':newStatus==='failed'?' failed':newStatus==='queued'||newStatus==='sending'?' wait':'');
  state.textContent=label;row.title=label;
}
function sendLine(line){
  const job=writeChain.then(async()=>{if(!writer)throw Error('USB disconnected');await writer.write(encoder.encode(line+'\n'));});
  writeChain=job.catch(()=>{});return job;
}
function readUnread(){
  if(!verified||!isChatVisible())return;
  for(const [key,record] of unreadMessages){
    sendLine(`READ ${record.mac} ${record.session} ${record.id}`)
      .then(()=>unreadMessages.delete(key))
      .catch(()=>{});
  }
}
function acceptReceived(mac,session,id,hex){
  if(!validMac(mac)||!/^\d+$/.test(session)||!/^\d+$/.test(id)||typeof hex!=='string'||hex.length>MAX_BYTES*2)return;
  const key=`${mac}:${session}:${id}`;
  if(!seenIncoming.has(key)){
    let text;try{text=fromHex(hex);}catch{return;}
    seenIncoming.add(key);messageBubble(text,'in');updatePreview(text,false);
    unreadMessages.set(key,{mac,session,id});
    /* This is our *browser* ACK, so the sender's double tick is real. */
  }
  sendLine(`APPACK ${mac} ${session} ${id}`).catch(()=>{});
  readUnread();
}
function parseLine(raw){
  const line=raw.trim();if(!line)return;
  if(line.startsWith('READY|')){
    const parts=line.split('|');const proto=parts.find(p=>p.startsWith('PROTO='));
    if(proto!=='PROTO=2') {setConnection('waiting','Incompatible firmware (requires protocol 2)');notify('Flash both boards with Phase 1.2 firmware');return;}
    localMac=parts[1]||null;verified=true;
    if(handshakeTimer){clearInterval(handshakeTimer);handshakeTimer=null;}
    setConnection('ready',localMac+' · Protocol 2');updateByteCount();readUnread();return;
  }
  if(line.startsWith('QUEUED|')){
    const [,session,id,hex]=line.split('|');
    const index=pendingLocal.findIndex(v=>v.hex===hex);
    if(index>=0){const [item]=pendingLocal.splice(index,1);sentMessages.set(uid(session,id),item.row);setMessageState(item.row,'queued');}
    return;
  }
  if(line.startsWith('RX|')){const [,mac,session,id,hex]=line.split('|');acceptReceived(mac,session,id,hex);return;}
  if(line.startsWith('READ|')){
    const [,session,id]=line.split('|');setMessageState(sentMessages.get(uid(session,id)),'read');return;
  }
  if(/^(SENT|DELIVERED|FAILED|RETRY|RADIO_ERROR)\|/.test(line)){
    const [type,session,id,extra]=line.split('|');const row=sentMessages.get(uid(session,id));
    if(type==='SENT')setMessageState(row,'sent');
    if(type==='DELIVERED')setMessageState(row,'delivered');
    if(type==='FAILED')setMessageState(row,'failed');
    if(type==='RADIO_ERROR')notify('ESP-NOW radio issue: '+(extra||'unknown'));
    return;
  }
  if(line.startsWith('ERROR|'))notify('ESP32: '+line.substring(6));
}
function consume(bytes){serialBuffer+=decoder.decode(bytes,{stream:true});let end;while((end=serialBuffer.indexOf('\n'))>=0){parseLine(serialBuffer.slice(0,end));serialBuffer=serialBuffer.slice(end+1);}if(serialBuffer.length>8192)serialBuffer='';}
async function readLoop(){
  try{while(port?.readable){reader=port.readable.getReader();try{while(true){const {value,done}=await reader.read();if(done)break;if(value)consume(value);}}finally{reader.releaseLock();reader=null;}}}
  catch(e){if(!closing)notify('USB disconnected: '+e.message);}
  finally{if(!closing){readPromise=null;void disconnect();}}
}
async function connect(){
  if(!('serial' in navigator)){notify('Web Serial requires supported Chromium on localhost or HTTPS.');return;}
  try{
    port=await navigator.serial.requestPort();await port.open({baudRate:115200});writer=port.writable.getWriter();
    setConnection('waiting');readPromise=readLoop();await sendLine('STATUS');
    handshakeTimer=setInterval(()=>{if(!verified&&writer)sendLine('STATUS').catch(()=>{});},1200);
  }catch(e){notify('Could not connect: '+e.message);await disconnect();}
}
async function disconnect(){
  if(closing)return;closing=true;
  try{
    if(handshakeTimer){clearInterval(handshakeTimer);handshakeTimer=null;}
    if(reader)try{await reader.cancel();}catch{}
    if(readPromise)try{await readPromise;}catch{}
    if(writer){try{writer.releaseLock();}catch{}writer=null;}
    if(port){try{await port.close();}catch{}port=null;}
  }finally{
    readPromise=null;serialBuffer='';verified=false;connected=false;localMac=null;
    pendingLocal.forEach(p=>setMessageState(p.row,'failed'));pendingLocal=[];
    for(const row of sentMessages.values())if(['sending','queued','sent'].includes(row.dataset.status))setMessageState(row,'failed');
    unreadMessages.clear();setConnection('offline');closing=false;
  }
}
function sendText(){
  const input=$('messageInput'),text=input.value.trim();if(!text||!verified)return;
  const bytes=encoder.encode(text);if(bytes.length>MAX_BYTES){notify(`Message uses ${bytes.length} bytes; limit ${MAX_BYTES}.`);return;}
  if(text.includes('\n')||text.includes('\r')){notify('One-line messages in Phase 1.2; remove line breaks.');return;}
  const hex=toHex(text),row=messageBubble(text,'out','sending');const item={hex,row};pendingLocal.push(item);updatePreview(text,true);
  input.value='';updateByteCount();
  sendLine('MSGHEX '+hex).catch(e=>{pendingLocal=pendingLocal.filter(v=>v!==item);setMessageState(row,'failed');notify('Could not send: '+e.message);});
}
function updateByteCount(){
  const input=$('messageInput');const n=encoder.encode(input.value).length;
  $('byteCount').textContent=`${n}/${MAX_BYTES}`;$('byteCount').classList.toggle('over',n>MAX_BYTES);
  input.style.height='36px';input.style.height=Math.min(125,input.scrollHeight)+'px';
  $('sendButton').disabled=!verified||!input.value.trim()||n>MAX_BYTES;
}
function applyName(name){
  activeContact=name||'Nearby node';$('contactSidebarName').textContent=activeContact;$('chatName').textContent=activeContact;
  const initial=activeContact[0]?.toUpperCase()||'N';$('contactAvatar').textContent=initial;$('headerAvatar').textContent=initial;
}
function showDialog(title,render){$('modalTitle').textContent=title;const body=$('modalBody');body.replaceChildren();render(body);$('modalBackdrop').classList.remove('hidden');}
function closeDialog(){$('modalBackdrop').classList.add('hidden');}
function el(tag,props={},text){const element=document.createElement(tag);for(const [key,value] of Object.entries(props)){if(key==='className')element.className=value;else element.setAttribute(key,value);}if(text!==undefined)element.textContent=text;return element;}
function showRename(){
  showDialog('Contact name',body=>{
    body.append(el('p',{},'Give this contact a name, just like a chat contact. The name is stored only in this browser.'));
    const label=el('label',{for:'nameField'},'Display name');const field=el('input',{id:'nameField',maxlength:'36',type:'text'});field.value=activeContact;
    const actions=el('div',{className:'modal-actions'}),cancel=el('button',{className:'quiet-button'},'Cancel'),save=el('button',{className:'primary-button'},'Save name');
    cancel.onclick=closeDialog;save.onclick=()=>{const name=field.value.trim();if(!name)return;applyName(name);try{localStorage.setItem('offgrid_name',name);}catch{}closeDialog();};
    field.addEventListener('keydown',e=>{if(e.key==='Enter')save.click();});actions.append(cancel,save);body.append(label,field,actions);setTimeout(()=>{field.focus();field.select();},30);
  });
}
function showInfo(){showDialog('Chat information',body=>{
  const lines=[['Peer name',activeContact],['Your ESP32',localMac||'Not connected'],['Transport','USB Serial ↔ ESP-NOW'],['Radio channel','6'],['Protocol','2 + read receipts'],['Encryption','NOT ACTIVE — test only'],['History','In-memory (cleared on refresh)']];
  lines.forEach(([k,v])=>{const row=el('div',{className:'info-line'});row.append(el('span',{},k),el('strong',{},v));body.append(row);});
  body.append(el('p',{},'Double grey ticks mean the remote browser acknowledged the message. Blue ticks mean its chat was visible and it sent a read receipt. Keep both browser tabs running to test. Read receipts are best effort; missed receipts are not yet persisted.'));
});}
function showSettings(){showDialog('Settings',body=>{
  body.append(el('p',{},'Phase 1.2 contains the messaging experience. Encrypted pairing, persistent chat history and audio will be added in later phases.'));
  const btn=el('button',{className:'primary-button'},'Rename contact');btn.onclick=()=>showRename();body.append(btn);
  const info=el('p',{},'Read receipts: enabled when chat window is visible and focused. No remote servers, trackers or external libraries.');body.append(info);
});}
$('connectButton').addEventListener('click',connect);$('disconnectButton').addEventListener('click',disconnect);
$('sendButton').addEventListener('click',sendText);$('messageInput').addEventListener('input',updateByteCount);
$('messageInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendText();}});
$('themeToggle').addEventListener('click',()=>{theme=theme==='dark'?'light':'dark';document.body.classList.toggle('light',theme==='light');try{localStorage.setItem('offgrid_theme',theme);}catch{}});
$('renameButton').addEventListener('click',showRename);$('settingsButton').addEventListener('click',showSettings);$('infoButton').addEventListener('click',showInfo);
$('contactButton').addEventListener('click',()=>{document.body.classList.add('mobile-chat');readUnread();});
$('mobileBack').addEventListener('click',()=>document.body.classList.remove('mobile-chat'));
$('closeModal').addEventListener('click',closeDialog);$('modalBackdrop').addEventListener('click',e=>{if(e.target===$('modalBackdrop'))closeDialog();});
document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDialog();});
document.addEventListener('visibilitychange',readUnread);window.addEventListener('focus',readUnread);
try{applyName(localStorage.getItem('offgrid_name')||'Nearby node');}catch{applyName('Nearby node');}
setConnection('offline');updateByteCount();
console.info('OffGrid Messenger v1.2: Web Serial / ESP-NOW protocol 2 / READ support. Not encrypted.');
