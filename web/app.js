'use strict';
/* OffGrid Phase 1.4.3.1: adaptive peer PHY firmware with encrypted transport and in-app photo gallery.
   Keys are stored as non-extractable CryptoKey objects in IndexedDB. No external servers. */
const P=globalThis.OGP,$=id=>document.getElementById(id);
const MAX_CHARS=500,MAX_PHOTO=96*1024,STORE='OffGridMessengerV14';
const APP_VERSION='1.4.3.1';
const stateRank={sending:0,queued:0,sent:1,failed:1,delivered:2,read:3};
const encoder=new TextEncoder();
let port=null,reader=null,writer=null,readPromise=null,closing=false,verified=false,serialBuffer='',writeChain=Promise.resolve(),handshakeTimer=null;
let localMac=null,secretKey=null,peerMac=null,activeContact='Nearby node',firmwareMismatchNotified=false;
/* USB diagnostics: distinguish missing console output from incompatible or old firmware. */
const usbLog=[];
let usbWaitTimer=null,usbHandshakeStarted=0,usbLastRawAt=0,usbHandshakeIssue='',usbSawBeacon=false;
function logUsb(line){usbLog.push(`[${new Date().toLocaleTimeString()}] ${String(line).slice(0,240)}`);if(usbLog.length>75)usbLog.shift();}
function showUsbHint(message){usbHandshakeIssue=message;const area=$('usbDiagnostics');if(!area)return;area.classList.remove('hidden');$('usbDiagnosticsText').textContent=message;}
function clearUsbHint(){usbHandshakeIssue='';$('usbDiagnostics')?.classList.add('hidden');}
function updateUsbWait(){
 if(!port||verified)return;
 const elapsed=performance.now()-usbHandshakeStarted;
 if(elapsed<5000)return;
 if(!usbLastRawAt)showUsbHint('USB port opened, but no serial data. Check ESP-IDF USB Serial/JTAG console and reboot the node.');
 else if(usbSawBeacon)showUsbHint('ESP32 sends READY, but has not confirmed the STATUS command. Check the USB input path.');
 else if(!usbHandshakeIssue)showUsbHint('ESP32 serial output detected, but no compatible READY response. Open Diagnostics.');
}
function diagnoseUsb(){
 showDialog('USB diagnostics',body=>{
  body.append(el('p','',`State: ${verified?'connected':port?'waiting for READY':'disconnected'}. ${usbHandshakeIssue||'No active USB error.'}`));
  const log=el('div','usb-diagnostics-log',usbLog.join('\n')||'No serial output captured.');body.append(log);
  const controls=el('div','modal-actions');
  const copy=el('button','quiet-button','Copy diagnostics');copy.onclick=()=>navigator.clipboard?.writeText(usbLog.join('\n')+'\n'+usbHandshakeIssue).then(()=>toast('Copied USB diagnostic log')).catch(()=>toast('Clipboard unavailable'));
  const retry=el('button','primary-button','Send STATUS again');retry.onclick=()=>{if(!writer){toast('Connect your ESP32 first');return;}void writeLine('STATUS').then(()=>{logUsb('HOST> STATUS (manual)');toast('STATUS sent');}).catch(e=>toast(e.message));};
  controls.append(copy,retry);body.append(controls);
  body.append(el('p','',`Expected firmware response: READY|MAC|CHANNEL=6|…|PROTO=2|CAP=228|TXDONE=1|FROM=STATUS. If the device prints CAP=180 or no CAP field, it is running older firmware.`));
 });
}

let db=null,records=[],recordMap=new Map(),photoUrls=new Map(),incoming=new Map(),recentTransfers=new Map();
let transferQueue=[],activeTransfer=null,runningTransfers=false;
const radioByHex=new Map(),radioByWire=new Map();
const linkStats={samples:0,avgMs:0,cleanStreak:0,retries:0,mode:'starting',window:1,lastKBs:0};
let linkPreference='auto';
let queuedReceipts=new Set(),pendingReads=new Set(),theme='dark';
let imageMode='hq';
let radioTelemetry={peer:'NONE',rssi:null,phy:1,mode:'AUTO',delivered:0,retries:0,failures:0,available:false};
let selectedPhotoId=null,viewerZoomed=false,viewerTouchX=null,viewerPreviousFocus=null;
try{theme=localStorage.getItem('offgrid_theme')==='light'?'light':'dark';imageMode=localStorage.getItem('offgrid_image_mode')==='lossless'?'lossless':'hq';linkPreference=['auto','range','speed'].includes(localStorage.getItem('offgrid_link_preference'))?localStorage.getItem('offgrid_link_preference'):'auto';}catch{}
document.body.classList.toggle('light',theme==='light');
const macKey=()=>localMac||'unknown';
const nameKey=()=>`offgrid_phase14_name_${macKey()}`;
const fmtTime=t=>new Date(t||Date.now()).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});
const countChars=s=>Array.from(s).length;
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const formatBytes=n=>n<1024?`${n} B`:`${(n/1024).toFixed(1)} KB`;
function toast(text){const d=document.createElement('div');d.className='toast';d.textContent=text;$('toastHost').append(d);setTimeout(()=>d.remove(),4200);}
function isVisible(){return !document.hidden&&document.hasFocus()&&(innerWidth>590||document.body.classList.contains('mobile-chat'));}
function safeText(s){return typeof s==='string'?s:'';}
function openDb(){return new Promise((resolve,reject)=>{
 const req=indexedDB.open(STORE,1);
 req.onupgradeneeded=()=>{const x=req.result;if(!x.objectStoreNames.contains('keys'))x.createObjectStore('keys',{keyPath:'mac'});if(!x.objectStoreNames.contains('messages')){const st=x.createObjectStore('messages',{keyPath:'pk'});st.createIndex('owner','owner',{unique:false});}};
 req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);
});}
function txRequest(store,mode,make){return new Promise((resolve,reject)=>{
 const tx=db.transaction(store,mode),st=tx.objectStore(store);let req;
 try{req=make(st);}catch(e){reject(e);return;}
 tx.oncomplete=()=>resolve(req?.result);tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('Database transaction aborted'));
});}
const dbGet=(store,key)=>txRequest(store,'readonly',st=>st.get(key));
const dbPut=(store,value)=>txRequest(store,'readwrite',st=>st.put(value));
const dbDelete=(store,key)=>txRequest(store,'readwrite',st=>st.delete(key));
const listStored=()=>txRequest('messages','readonly',st=>st.index('owner').getAll(IDBKeyRange.only(macKey())));
async function wipeStored(){const rows=await listStored();await new Promise((resolve,reject)=>{const tx=db.transaction('messages','readwrite'),st=tx.objectStore('messages');for(const row of rows)st.delete(row.pk);tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);});}
function chosenWindow(){
 // Application-layer flow control only: does not change RF power, channel or the ESP-NOW PHY rate.
 if(linkPreference==='range')return 1;
 if(linkPreference==='speed')return linkStats.mode==='weak'?1:linkStats.cleanStreak>=4?3:2;
 if(linkStats.samples<5||linkStats.cleanStreak<5)return 1;
 if(linkStats.avgMs<380&&linkStats.cleanStreak>=14)return 3;
 if(linkStats.avgMs<850)return 2;
 return 1;
}
function photoBudget(){
 if(imageMode==='lossless')return MAX_PHOTO;
 if(linkStats.mode==='weak'||linkPreference==='range')return 48*1024;
 if(linkPreference==='speed'||linkStats.mode==='strong')return 72*1024;
 return 64*1024;
}
function updateLinkBadge(){
 const node=$('linkModeLabel');if(!node)return;
 const speed=chosenWindow(),budget=Math.round(photoBudget()/1024);
 const name=linkPreference==='auto'?'Auto':linkPreference==='range'?'Range first':'Speed first';
 let quality='learning';
 if(linkStats.samples>3)quality=linkStats.mode==='strong'?'strong':linkStats.mode==='weak'?'weak':'steady';
 const phy=radioTelemetry.available?`${radioTelemetry.phy} Mbps PHY`:'radio starting';
 const signal=radioTelemetry.available&&radioTelemetry.rssi!==null?`${radioTelemetry.rssi} dBm`:'signal —';
 node.textContent=`${name} · ${phy} · ${signal} · ${speed} in flight · ~${budget} KB photos${linkStats.lastKBs?` · last ${linkStats.lastKBs.toFixed(1)} KB/s`:``}`;
 node.title='Reported TX PHY rate and received peer RSSI. Mbps is PHY bitrate, not photo transfer throughput. ACK/retry pacing is separately estimated by the web client.';
}
function recordLinkResult(rtt,attempts){
 linkStats.samples++;
 linkStats.avgMs=linkStats.samples===1?rtt:linkStats.avgMs*0.78+rtt*0.22;
 if(attempts>1||rtt>1800){linkStats.cleanStreak=0;linkStats.retries++;linkStats.mode='weak';}
 else{
   linkStats.cleanStreak++;
   if(linkStats.cleanStreak>=12&&linkStats.avgMs<420)linkStats.mode='strong';
   else if(linkStats.cleanStreak>=5)linkStats.mode='steady';
 }
 linkStats.window=chosenWindow();updateLinkBadge();
}
function recordLinkFailure(){linkStats.cleanStreak=0;linkStats.mode='weak';linkStats.window=1;linkStats.retries++;updateLinkBadge();}
function pendingRadio(){return radioByHex.size>0;}
function clearSlot(slot){
 if(!slot)return;clearTimeout(slot.timer);
 if(radioByHex.get(slot.hex)===slot)radioByHex.delete(slot.hex);
 if(slot.wire&&radioByWire.get(slot.wire)===slot)radioByWire.delete(slot.wire);
}
function failPendingRadio(error){for(const slot of [...radioByHex.values()]){clearSlot(slot);slot.reject(error);}}
function refreshStatus(){
 const ready=verified&&!!secretKey;
 $('statusDot').className='status-dot'+(verified?' on':port?' wait':'');
 $('connectionState').textContent=verified?'Connected':port?'Checking firmware':'Disconnected';
 $('connectionDescription').textContent=verified?(localMac+' · channel 6 · protocol 2'):port?(usbHandshakeIssue||'Waiting for ESP32 READY'):'Connect your ESP32-C3 over USB';
 $('nodeAddress').textContent='Node: '+(localMac||'—');
 $('connectButton').disabled=!!port;$('disconnectButton').disabled=!port;
 $('messageInput').disabled=!ready;$('attachments').disabled=!ready;
 $('messageInput').placeholder=ready?'Message (up to 500 characters)':verified?'Pair a 256-bit key to begin':'Connect ESP32 to send messages';
 $('chatSubhead').textContent=ready?'End-to-end encrypted · ESP-NOW channel 6':verified?'Pair a key to start encrypted chat':'Offline · connect ESP32';
 $('securityIcon').textContent=ready?'🔒':'♢';
 $('securityLabel').textContent=ready?'AES-256-GCM enabled · manage pairing':verified?'Encryption key required · set up pairing':'Connect to configure encryption';
 $('composerStatus').textContent=ready?'Enter to send · images are compressed and sent in chunks':verified?'Pair the same key on both browsers to begin':'Connect an ESP32 using the sidebar';
 updateByteCount();updateLinkBadge();
}
function setName(name){activeContact=(name||'Nearby node').slice(0,36);$('contactSidebarName').textContent=activeContact;$('chatName').textContent=activeContact;const initial=Array.from(activeContact)[0]?.toUpperCase()||'N';$('contactAvatar').textContent=initial;$('headerAvatar').textContent=initial;}
function persistName(){try{localStorage.setItem(nameKey(),activeContact);}catch{}}
function updateSidebar(){const r=records[records.length-1];$('lastChatPreview').textContent=r?(r.dir==='out'?'You: ':'')+(r.kind==='image'?'📷 Photo':r.text||'Message'):'Your encrypted off-grid conversation';$('lastChatTime').textContent=r?fmtTime(r.ts):'';$('introCard').style.display=records.length?'none':'';}
function createReceipt(status){const wrap=document.createElement('span');wrap.className='receipt-icon '+(status||'sending');wrap.dataset.role='tick';wrap.setAttribute('aria-label',status||'Sending');
 wrap.innerHTML='<svg viewBox="0 0 26 16" width="26" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path class="tick-one" d="M1.5 8.1 6.1 12.5 16.4 2.1"/><path class="tick-two" d="M8.5 8.1 13.1 12.5 23.4 2.1"/></svg>';return wrap;}
function makeBubble(record,animate=true){
 const row=document.createElement('div');row.className='message-row '+(record.dir==='out'?'out':'in')+(animate?' is-new':'');row.dataset.mid=record.id;row.dataset.status=record.status||'';
 const bubble=document.createElement('div');bubble.className='message-bubble';
 if(record.kind==='image'){
   const img=document.createElement('img');img.className='chat-photo';img.alt='Shared photograph';img.loading='lazy';
   const mime=record.mime||'image/webp',url=URL.createObjectURL(new Blob([record.bytes],{type:mime}));photoUrls.set(record.id,url);img.src=url;
   img.tabIndex=0;img.setAttribute('role','button');img.setAttribute('aria-label','Open photograph in gallery');
   img.onclick=()=>openPhotoViewer(record.id);img.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();openPhotoViewer(record.id);}};
   bubble.append(img);
   const photoCaption=document.createElement('div');photoCaption.className='photo-caption';photoCaption.textContent=`${record.width||'?'} × ${record.height||'?'} · ${formatBytes(record.bytes?.length||0)}`;bubble.append(photoCaption);
 }else{const content=document.createElement('div');content.className='message-text';content.textContent=record.text;bubble.append(content);}
 const meta=document.createElement('div');meta.className='message-meta';const time=document.createElement('span');time.textContent=fmtTime(record.ts);meta.append(time);
 if(record.dir==='out'){const progress=document.createElement('span');progress.className='transfer-progress';progress.dataset.role='progress';progress.textContent=record.status==='sending'&&record.progress?record.progress+'%':'';meta.append(progress,createReceipt(record.status||'sending'));}
 bubble.append(meta);row.append(bubble);$('messages').append(row);updateBubble(record);
 $('chatScroll').scrollTop=$('chatScroll').scrollHeight;return row;
}
function updateBubble(r){const row=Array.from($('messages').children).find(el=>el.dataset.mid===r.id);if(!row)return;row.dataset.status=r.status||'';
 const receipt=row.querySelector('[data-role="tick"]');if(receipt){receipt.className='receipt-icon '+(r.status||'sending');receipt.setAttribute('aria-label',r.status||'Sending');}
 const prog=row.querySelector('[data-role="progress"]');if(prog)prog.textContent=r.status==='sending'&&r.progress?`${r.progress}%${r.kind==='image'&&r.speedKbps?' · '+r.speedKbps.toFixed(1)+' KB/s':''}`:'';
 if(r.dir==='out'){row.title=(r.status||'Sending')+(r.error?': '+r.error:'');row.classList.toggle('retryable',r.status==='failed');if(r.status==='failed')row.onclick=()=>retryMessage(r.id);else row.onclick=null;}
}
function revokePhotos(){closePhotoViewer();for(const url of photoUrls.values())URL.revokeObjectURL(url);photoUrls.clear();}
function renderHistory(){revokePhotos();$('messages').replaceChildren();for(const r of records)makeBubble(r,false);updateSidebar();}
/* In-app viewer. Photo blobs are already authenticated, decrypted and locally stored. */
function galleryPhotos(){return records.filter(r=>r.kind==='image'&&r.bytes?.length>0);}
function showSelectedPhoto(animated=true){
 const gallery=galleryPhotos(),index=gallery.findIndex(r=>r.id===selectedPhotoId);
 if(index<0){closePhotoViewer();return;}
 const photo=gallery[index],image=$('viewerImage');
 const url=photoUrls.get(photo.id);
 if(!url){closePhotoViewer();return;}
 viewerZoomed=false;$('viewerCanvas').classList.remove('zoomed');
 image.src=url;
 $('photoViewerCounter').textContent=`${index+1} of ${gallery.length}`;
 $('photoViewerInfo').textContent=`${photo.width||'?'} × ${photo.height||'?'} · ${formatBytes(photo.bytes.length)}`;
 $('photoViewerName').textContent=activeContact;
 $('photoViewerDate').textContent=fmtTime(photo.ts);
 $('viewerPrevious').disabled=index<=0;
 $('viewerNext').disabled=index>=gallery.length-1;
 $('viewerPrevious').classList.toggle('viewer-hidden',index<=0);
 $('viewerNext').classList.toggle('viewer-hidden',index>=gallery.length-1);
 $('viewerDownload').href=url;
 $('viewerDownload').download=`offgrid-${new Date(photo.ts).toISOString().slice(0,10)}.${photo.mime==='image/png'?'png':photo.mime==='image/jpeg'?'jpg':'webp'}`;
 if(animated){image.classList.remove('photo-enter');void image.offsetWidth;image.classList.add('photo-enter');}
}
function openPhotoViewer(id){
 if(!photoUrls.has(id))return;
 if($('photoViewer').classList.contains('hidden'))viewerPreviousFocus=document.activeElement;
 selectedPhotoId=id;$('photoViewer').classList.remove('hidden');
 document.body.classList.add('viewer-open');showSelectedPhoto(true);$('photoViewer').focus();
}
function closePhotoViewer(){
 if($('photoViewer').classList.contains('hidden'))return;
 $('photoViewer').classList.add('hidden');document.body.classList.remove('viewer-open');
 $('viewerImage').removeAttribute('src');selectedPhotoId=null;
 if(viewerPreviousFocus?.isConnected)viewerPreviousFocus.focus({preventScroll:true});
 viewerPreviousFocus=null;
}
function navigatePhoto(delta){
 if($('photoViewer').classList.contains('hidden'))return;
 const gallery=galleryPhotos();const index=gallery.findIndex(r=>r.id===selectedPhotoId);
 const next=index+delta;
 if(next>=0&&next<gallery.length){selectedPhotoId=gallery[next].id;showSelectedPhoto(true);}
}
function togglePhotoZoom(){
 if($('photoViewer').classList.contains('hidden'))return;
 viewerZoomed=!viewerZoomed;$('viewerCanvas').classList.toggle('zoomed',viewerZoomed);
}
function serializeRecord(r){return {id:r.id,kind:r.kind,dir:r.dir,ts:r.ts,text:r.text||'',mime:r.mime||'',width:r.width||0,height:r.height||0,bytes:r.bytes?P.base64url(r.bytes):'',readSent:!!r.readSent,deliveredAckSent:!!r.deliveredAckSent,peerMac:r.peerMac||null};}
async function storeRecord(r){if(!secretKey||!localMac)return;const payload=P.textBytes(JSON.stringify(serializeRecord(r)));const sealed=await P.sealAtRest(secretKey,payload,macKey(),r.id);await dbPut('messages',{pk:macKey()+'/'+r.id,owner:macKey(),id:r.id,ts:r.ts,dir:r.dir,status:r.status,seal:sealed});}
async function storeStatus(r){if(!secretKey||!localMac)return;const old=await dbGet('messages',macKey()+'/'+r.id);if(old){old.status=r.status;await dbPut('messages',old);}else await storeRecord(r);}
async function hydrateHistory(){records=[];recordMap.clear();const rows=await listStored();for(const row of rows){try{const bytes=await P.openAtRest(secretKey,row.seal,macKey(),row.id);const d=JSON.parse(P.textFrom(bytes));d.bytes=d.bytes?P.fromBase64urlRaw(d.bytes):null;d.status=row.status||'sent';if(d.status==='sending'||d.status==='queued')d.status='failed';d.progress=0;records.push(d);recordMap.set(d.id,d);}catch(e){console.warn('Cannot decrypt stored record',row.id,e.message);}}
 records.sort((a,b)=>a.ts-b.ts);renderHistory();}
/* Stored image data is base64url; unlike pairing keys, image byte length is arbitrary. */
P.fromBase64urlRaw=function(s){const fixed=s.replace(/-/g,'+').replace(/_/g,'/');const bin=atob(fixed+'='.repeat((4-fixed.length%4)%4));return Uint8Array.from(bin,c=>c.charCodeAt(0));};
async function appendRecord(r){records.push(r);recordMap.set(r.id,r);makeBubble(r);updateSidebar();try{await storeRecord(r);}catch(e){toast('Could not store encrypted chat history: '+e.message);} }
async function setState(r,status,error=''){
 if(!r)return;const prior=r.status||'sending';if(prior==='read'&&status!=='read')return;if(prior==='delivered'&&status==='failed')return;
 if(status!=='failed'&&(stateRank[status]??0)<(stateRank[prior]??0))return;
 r.status=status;r.error=error;r.progress=status==='sending'?r.progress:0;updateBubble(r);try{await storeStatus(r);}catch(e){console.warn('Status save failed',e);}
}
async function setProgress(r,pct){if(!r||['delivered','read'].includes(r.status))return;r.status='sending';r.progress=Math.max(0,Math.min(100,pct));updateBubble(r);}
function writeLine(line){const task=writeChain.then(async()=>{if(!writer)throw Error('USB not connected');await writer.write(encoder.encode(line+'\n'));});writeChain=task.catch(()=>{});return task;}
function incomingKey(mac,session,transferId){return mac+':'+session+':'+transferId;}
function pruneIncoming(){const t=Date.now();for(const [k,v] of incoming){if(t-v.updated>4*60_000)incoming.delete(k);}for(const [k,t0] of recentTransfers){if(t-t0>12*60_000)recentTransfers.delete(k);}while(recentTransfers.size>512)recentTransfers.delete(recentTransfers.keys().next().value);}
async function processRx(mac,session,packetId,rawHex){
 let fr;try{if(!/^[0-9A-Fa-f]{40,456}$/.test(rawHex))return;fr=P.parseFrame(P.unhex(rawHex));}catch{return;}if(!fr||!secretKey||!verified)return;
 const key=incomingKey(mac,session,fr.transferId);
 /* Acknowledge each valid fragment at the receiving browser, even on retries. */
 writeLine(`APPACK ${mac} ${session} ${packetId}`).catch(()=>{});
 if(recentTransfers.has(key))return;
 let state=incoming.get(key);
 if(!state){if(incoming.size>=5){const oldest=incoming.keys().next().value;incoming.delete(oldest);}state={count:fr.count,total:fr.length,chunks:Array(fr.count),received:0,updated:Date.now()};incoming.set(key,state);}
 if(state.count!==fr.count||state.total!==fr.length)return;
 if(!state.chunks[fr.index]){state.chunks[fr.index]=fr.bytes;state.received++;}state.updated=Date.now();
 if(state.received!==state.count)return;
 incoming.delete(key);
 const payload=P.concat(...state.chunks);
 if(payload.length!==state.total)return;
 try{
  const plain=await P.open(secretKey,payload,fr.transferId),env=P.parseEnvelope(plain);
  await handleEnvelope(env,mac);recentTransfers.set(key,Date.now());
 }catch(e){console.warn('Encrypted message rejected:',e);toast('Encrypted transfer rejected: wrong pairing key, corrupted data, or authentication failure.');}
}
async function handleEnvelope(env,mac){
 if(env.kind===P.types.delivered||env.kind===P.types.read){if(env.body.length!==8)return;const original=P.hex(env.body),r=recordMap.get(original);if(r&&r.dir==='out')await setState(r,env.kind===P.types.read?'read':'delivered');return;}
 if(env.kind!==P.types.text&&env.kind!==P.types.image)return;
 let r=recordMap.get(env.id);
 if(r){if(r.dir==='in'){enqueueReceipt('delivered',r.id);if(r.readSent)enqueueReceipt('read',r.id);}return;}
 if(env.kind===P.types.text){let text;try{text=P.textFrom(env.body);}catch{return;}if(countChars(text)>MAX_CHARS)return;r={id:env.id,kind:'text',dir:'in',ts:env.ts||Date.now(),text,status:'received',readSent:false,deliveredAckSent:false,peerMac:mac};}
 else{if(env.body.length<2||env.body.length>MAX_PHOTO+1)return;const mime=['image/webp','image/png','image/jpeg'][env.body[0]];if(!mime||!env.width||!env.height)return;r={id:env.id,kind:'image',dir:'in',ts:env.ts||Date.now(),mime,width:env.width,height:env.height,bytes:env.body.slice(1),status:'received',readSent:false,deliveredAckSent:false,peerMac:mac};}
 peerMac=mac;
 await appendRecord(r);
 enqueueReceipt('delivered',r.id);
 if(isVisible())sendReadReceipts();
}
function enqueueReceipt(kind,targetId){const key=kind+':'+targetId;if(queuedReceipts.has(key)||!secretKey)return;queuedReceipts.add(key);
 const job={kind,control:true,targetId,created:Date.now()};transferQueue.unshift(job);runTransfers();}
function sendReadReceipts(){if(!secretKey||!verified||!isVisible())return;for(const r of records){if(r.dir==='in'&&!r.readSent&&!pendingReads.has(r.id)){pendingReads.add(r.id);enqueueReceipt('read',r.id);}}}
function makeJob(r){return {kind:r.kind,control:false,record:r,created:Date.now()};}
async function sealJob(job){
 const transferId=P.newId(),id=job.control?P.newId():job.record.id;
 let kind,body,width=0,height=0;
 if(job.control){kind=P.types[job.kind];body=P.unhex(job.targetId);}
 else if(job.record.kind==='text'){kind=P.types.text;body=P.textBytes(job.record.text);}
 else{kind=P.types.image;const r=job.record,mimeNum=({'image/webp':0,'image/png':1,'image/jpeg':2})[r.mime];if(mimeNum===undefined)throw Error('Unsupported image encoding');body=P.concat(Uint8Array.of(mimeNum),r.bytes);width=r.width;height=r.height;}
 const plain=P.makeEnvelope(kind,id,body,Math.floor(Date.now()/1000),width,height);
 const ciphertext=await P.seal(secretKey,plain,transferId);
 return P.frames(transferId,ciphertext);
}
async function oneFragment(frame){
 const hex=P.hex(frame),started=performance.now();let lastErr;
 for(let attempt=0;attempt<3;attempt++){
  try{
   await new Promise((resolve,reject)=>{
    if(radioByHex.has(hex)){reject(Error('Duplicate fragment already outstanding'));return;}
    const slot={hex,resolve,reject,wire:null,timer:null};radioByHex.set(hex,slot);
    slot.timer=setTimeout(()=>{
      if(radioByHex.get(hex)===slot){clearSlot(slot);reject(Error('Radio/browser acknowledgment timeout'));}
    },14500);
    writeLine('MSGHEX '+hex).catch(e=>{if(radioByHex.get(hex)===slot){clearSlot(slot);reject(e);}});
   });
   return {ms:Math.max(1,performance.now()-started),attempts:attempt+1};
  }catch(e){lastErr=e;if(!verified||!writer)throw e;await wait(280+attempt*230);}
 }
 throw lastErr||Error('Fragment failed');
}
function onRadio(type,session,id,extra){
 const slot=radioByWire.get(`${session}:${id}`);if(!slot)return;
 if(type==='DELIVERED'||type==='FAILED'){
  clearSlot(slot);
  if(type==='DELIVERED')slot.resolve();else slot.reject(Error(extra||'No remote browser acknowledgment'));
 }
}
async function transferFragments(chunks,job){
 const transferStart=performance.now();
 let next=0,completed=0,fatal=null;const inflight=new Map();
 while((completed<chunks.length)&&!fatal){
  while(next<chunks.length&&inflight.size<chosenWindow()){
   const i=next++;
   const op=oneFragment(chunks[i]).then(result=>({i,...result})).catch(error=>({i,error}));
   inflight.set(i,op);
  }
  if(!inflight.size)break;
  const result=await Promise.race(inflight.values());inflight.delete(result.i);
  if(result.error){recordLinkFailure();fatal=result.error;break;}
  completed++;recordLinkResult(result.ms,result.attempts);
  if(job.record){
    const elapsed=Math.max(0.001,(performance.now()-transferStart)/1000);
    job.record.speedKbps=((completed/chunks.length)*(job.record.kind==='image'?job.record.bytes.length:P.textBytes(job.record.text).length))/1024/elapsed;
    await setProgress(job.record,Math.round(completed*100/chunks.length));
  }
 }
 if(fatal){await Promise.allSettled([...inflight.values()]);throw fatal;}
 if(job.record && job.record.kind==='image'){
   linkStats.lastKBs=job.record.bytes.length/1024/Math.max(.001,(performance.now()-transferStart)/1000);
   updateLinkBadge();
 }
}
async function runTransfers(){
 if(runningTransfers||!verified||!secretKey)return;runningTransfers=true;
 try{
  while(verified&&secretKey&&transferQueue.length){
   const job=transferQueue.shift();activeTransfer=job;
   try{
    const chunks=await sealJob(job);
    await transferFragments(chunks,job);
    if(job.control){
      const r=recordMap.get(job.targetId);if(r&&r.dir==='in'){
       if(job.kind==='read')r.readSent=true;
       if(job.kind==='delivered')r.deliveredAckSent=true;
       await storeRecord(r);
      }
    }else if(job.record){await setState(job.record,'sent');}
   }catch(e){
    if(job.control){if(job.kind==='read')pendingReads.delete(job.targetId);console.warn('Receipt send failed',job.kind,e.message);}
    else if(job.record){await setState(job.record,'failed',e.message);toast('Transfer failed. Click its bubble to retry.');}
   }finally{if(job.control)queuedReceipts.delete(job.kind+':'+job.targetId);activeTransfer=null;}
  }
 }finally{runningTransfers=false;}
}
function parseLine(raw){const s=raw.trim();if(!s)return;
 if(s.startsWith('LINK|')){
  const parts=s.split('|'),fields={};for(const bit of parts.slice(2)){const j=bit.indexOf('=');if(j>0)fields[bit.slice(0,j)]=bit.slice(j+1);}
  const phy=Number(fields.PHY),rssi=Number(fields.RSSI);
  if([1,6,12].includes(phy)){
   radioTelemetry={peer:parts[1],phy,rssi:Number.isFinite(rssi)&&rssi>-120&&rssi<0?rssi:null,
     mode:fields.MODE||'AUTO',delivered:Number(fields.OK)||0,retries:Number(fields.RETRY)||0,
     failures:Number(fields.FAIL)||0,available:true};
   updateLinkBadge();
  }
  return;
 }
 if(s.startsWith('RATE_ERROR|')){console.warn('Radio PHY configuration:',s);toast('ESP32 PHY rate could not be applied; falling back to safe rate.');return;}
 if(s.startsWith('RATE|')||s.startsWith('PEER|'))return;
 if(s.startsWith('READY|')){const parts=s.split('|');if(!parts.includes('PROTO=2')||!parts.includes('CAP=228')){if(!firmwareMismatchNotified){toast('Flash Phase 1.4.3.1 firmware on BOTH boards (CAP=228).');firmwareMismatchNotified=true;}console.warn('Incompatible firmware READY:',s);return;}const mac=parts[1];if(!/^[0-9A-Fa-f:]{17}$/.test(mac))return;if(verified&&localMac===mac)return;void onReady(mac);return;}
 if(s.startsWith('QUEUED|')){const [,session,id,hex]=s.split('|'),slot=radioByHex.get(hex);if(slot){slot.wire=`${session}:${id}`;radioByWire.set(slot.wire,slot);}return;}
 if(s.startsWith('RX|')){const [,mac,session,id,data]=s.split('|');if(mac&&session&&id&&data)void processRx(mac,session,id,data);return;}
 if(/^(SENT|DELIVERED|FAILED|RETRY|RADIO_ERROR)\|/.test(s)){const [type,session,id,extra]=s.split('|');if(type==='RADIO_ERROR')console.warn('ESP-NOW:',extra);else onRadio(type,session,id,extra);return;}
 if(s.startsWith('ERROR|')){const reason=s.slice(6);if(pendingRadio()&&/^(QUEUE_FULL|EVENT_QUEUE_FULL|INVALID_HEX_OR_LENGTH|LINE_TOO_LONG|UNKNOWN_COMMAND)/.test(reason)){const slot=[...radioByHex.values()].findLast(x=>!x.wire)||[...radioByHex.values()].at(-1);if(slot){clearSlot(slot);slot.reject(Error('ESP32: '+reason));}}toast('ESP32: '+reason);}
}
function consume(data){serialBuffer+=new TextDecoder().decode(data);let i;while((i=serialBuffer.indexOf('\n'))>=0){parseLine(serialBuffer.slice(0,i));serialBuffer=serialBuffer.slice(i+1);}if(serialBuffer.length>8192)serialBuffer='';}
async function readLoop(){try{while(port?.readable&&!closing){reader=port.readable.getReader();try{while(!closing){const {value,done}=await reader.read();if(done)break;if(value)consume(value);}}finally{reader.releaseLock();reader=null;}if(!closing&&port?.readable)await wait(100);}}catch(e){if(!closing)toast('Serial disconnected: '+e.message);}finally{if(!closing){readPromise=null;void disconnect();}}}
async function onReady(mac){firmwareMismatchNotified=false;localMac=mac.toUpperCase();verified=true;
 void writeLine('RATE '+linkPreference.toUpperCase()).catch(()=>{});if(handshakeTimer){clearInterval(handshakeTimer);handshakeTimer=null;}try{
 const saved=await dbGet('keys',macKey());secretKey=saved?.key||null;
 try{setName(localStorage.getItem(nameKey())||localStorage.getItem('offgrid_phase13_name_'+macKey().replace(/:/g,'-'))||'Nearby node');}catch{setName('Nearby node');}
 if(secretKey){await migrateLegacy();await hydrateHistory();for(const r of records){if(r.dir==='in'&&!r.deliveredAckSent)enqueueReceipt('delivered',r.id);}sendReadReceipts();}
 else{records=[];recordMap.clear();renderHistory();showPairing();}
 refreshStatus();
 }catch(e){toast('Storage initialization failed: '+e.message);console.error(e);refreshStatus();}}
async function connect(){if(!navigator.serial){toast('Use Chromium with Web Serial on localhost or HTTPS.');return;}try{
 if(!db)db=await openDb();port=await navigator.serial.requestPort();await port.open({baudRate:115200});writer=port.writable.getWriter();refreshStatus();readPromise=readLoop();await writeLine('STATUS');handshakeTimer=setInterval(()=>{if(!verified&&writer)writeLine('STATUS').catch(()=>{});},1200);
 }catch(e){toast('USB connection failed: '+e.message);await disconnect();}}
async function disconnect(){if(closing)return;closing=true;
 try{if(handshakeTimer)clearInterval(handshakeTimer);handshakeTimer=null;if(reader)try{await reader.cancel();}catch{}if(readPromise)try{await readPromise;}catch{}if(writer){try{writer.releaseLock();}catch{}writer=null;}if(port){try{await port.close();}catch{}port=null;}}
 finally{readPromise=null;serialBuffer='';verified=false;port=null;writer=null;if(pendingRadio())failPendingRadio(Error('Disconnected'));if(activeTransfer?.record)void setState(activeTransfer.record,'failed','Disconnected');
 transferQueue=[];runningTransfers=false;queuedReceipts.clear();pendingReads.clear();incoming.clear();localMac=null;secretKey=null;radioTelemetry.available=false;refreshStatus();closing=false;}}
async function sendText(){const input=$('messageInput'),text=input.value.trim();if(!verified||!secretKey||!text)return;if(countChars(text)>MAX_CHARS){toast('Maximum 500 characters per text message.');return;}
 const r={id:P.newId(),kind:'text',dir:'out',ts:Date.now(),text,status:'sending',progress:0};await appendRecord(r);input.value='';updateByteCount();transferQueue.push(makeJob(r));runTransfers();}
function updateByteCount(){const input=$('messageInput'),n=countChars(input.value);$('byteCount').textContent=`${n}/${MAX_CHARS}`;$('byteCount').classList.toggle('over',n>MAX_CHARS);input.style.height='36px';input.style.height=Math.min(120,input.scrollHeight)+'px';$('sendButton').disabled=!verified||!secretKey||!input.value.trim()||n>MAX_CHARS;}
function convertCanvas(canvas,type,q){return new Promise(resolve=>canvas.toBlob(resolve,type,q));}
async function preparePhoto(file){if(!file||!file.type.startsWith('image/'))throw Error('Select a picture.');if(file.size>25*1024*1024)throw Error('Original photo is too large to process (25 MB maximum).');
 let bitmap;try{bitmap=await createImageBitmap(file);}catch{throw Error('Your browser cannot decode this photo format. Try JPEG or PNG.');}
 try{
  const originalW=bitmap.width,originalH=bitmap.height;if(!originalW||!originalH)throw Error('Invalid image dimensions');
  if(originalW*originalH>40_000_000)throw Error('Image resolution too large to process safely.');const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d',{alpha:true});if(!ctx)throw Error('Canvas unavailable');
  if(imageMode==='lossless'){
   if(originalW*originalH>20_000_000)throw Error('Lossless photo is too large. Choose High quality in Settings.');
   canvas.width=originalW;canvas.height=originalH;ctx.drawImage(bitmap,0,0);
   const blob=await convertCanvas(canvas,'image/png');if(!blob||blob.size>MAX_PHOTO)throw Error('Lossless PNG exceeds 96 KB; try High quality mode or a smaller image. No downscaling was performed.');
   return {bytes:new Uint8Array(await blob.arrayBuffer()),mime:'image/png',width:originalW,height:originalH};
  }
  let candidate=null;
  const targetBytes=photoBudget();
  for(const maxEdge of [1800,1600,1440,1280,1152,1024,900,800,700,600,520]){
   const scale=Math.min(1,maxEdge/Math.max(originalW,originalH));const w=Math.max(1,Math.round(originalW*scale)),h=Math.max(1,Math.round(originalH*scale));
   canvas.width=w;canvas.height=h;ctx.drawImage(bitmap,0,0,w,h);
   for(const q of [0.91,0.88,0.85,0.82,0.79,0.76]){
    let blob=await convertCanvas(canvas,'image/webp',q);
    if(!blob||blob.type!=='image/webp')blob=await convertCanvas(canvas,'image/jpeg',q);
    if(!blob)throw Error('Cannot encode photo');
    if(blob.size<=targetBytes){candidate={bytes:new Uint8Array(await blob.arrayBuffer()),mime:blob.type,width:w,height:h};break;}
   }if(candidate)break;
  }
  if(!candidate)throw Error(`Photo could not fit the ${Math.round(targetBytes/1024)} KB link target. Try a simpler photo, or change Link mode to Speed first.`);
  return candidate;
 }finally{bitmap.close?.();}
}
async function sendPhoto(file){if(!verified||!secretKey)return;toast('Preparing photo and stripping metadata…');
 try{const photo=await preparePhoto(file);if(!verified||!secretKey)throw Error('Disconnected while preparing image');
 const r={id:P.newId(),kind:'image',dir:'out',ts:Date.now(),mime:photo.mime,width:photo.width,height:photo.height,bytes:photo.bytes,status:'sending',progress:0};
 await appendRecord(r);transferQueue.push(makeJob(r));runTransfers();toast(`Photo prepared: ${photo.width}×${photo.height}, ${formatBytes(photo.bytes.length)}. Encrypted transfer queued.`);
 }catch(e){toast('Photo not sent: '+e.message);}finally{$('photoPicker').value='';}}
function retryMessage(id){const r=recordMap.get(id);if(!r||r.dir!=='out'||r.status!=='failed'||!verified||!secretKey)return;r.status='sending';r.progress=0;r.error='';updateBubble(r);void storeStatus(r);transferQueue.push(makeJob(r));runTransfers();}
function showDialog(title,render){$('modalTitle').textContent=title;const content=$('modalBody');content.replaceChildren();render(content);$('modalBackdrop').classList.remove('hidden');}
function closeDialog(){$('modalBackdrop').classList.add('hidden');}
function el(tag,cl='',text=''){const out=document.createElement(tag);if(cl)out.className=cl;out.textContent=text;return out;}
async function installPairKey(raw){if(!verified||!localMac)throw Error('Connect a node first');if(runningTransfers||pendingRadio()||transferQueue.length)throw Error('Finish any active message or photo transfer before changing keys');if(secretKey&&!confirm('Replace the pairing key? This will delete this node’s old chat history, which cannot be decrypted with the new key.'))return false;
 const key=await P.importKey(raw);
 if(secretKey){await wipeStored();records=[];recordMap.clear();renderHistory();}
 await dbPut('keys',{mac:macKey(),key});secretKey=key;await migrateLegacy();await hydrateHistory();refreshStatus();sendReadReceipts();return true;}
function showPairing(){if(!verified){toast('Connect your ESP32 first.');return;}showDialog('End-to-end encryption',body=>{
  body.append(el('p','security-intro','Both browser clients must use the same randomly generated 256-bit key. Key bytes never enter the ESP32. Share the key directly with your other browser.'));
  const indicator=el('div','pair-status',secretKey?'🔒 Pairing key installed for this ESP32':'⚠ No key configured for this ESP32');body.append(indicator);
  const field=document.createElement('textarea');field.className='pair-key-field';field.rows=2;field.placeholder='Paste a 43-character pairing key here';field.autocomplete='off';field.spellcheck=false;body.append(field);
  const actions=el('div','pair-actions'),gen=el('button','primary-button','Generate new key'),join=el('button','quiet-button','Install pasted key'),copy=el('button','quiet-button','Copy key');copy.disabled=true;actions.append(gen,join,copy);body.append(actions);
  const help=el('p','','Generate on one node, copy the key, then paste and install it on the other node. Keep the key private. After closing, the raw key is not recoverable from this browser.');body.append(help);
  gen.onclick=async()=>{if(runningTransfers||pendingRadio()||transferQueue.length){toast('Finish active transfers before changing the encryption key.');return;}if(secretKey&&!confirm('Generating a different key will replace your current key and delete old messages. Continue?'))return;
    const raw=P.randomBytes(32),value=P.base64url(raw);field.value=value;try{
      if(secretKey){await wipeStored();records=[];recordMap.clear();renderHistory();}
      const key=await P.importKey(raw);await dbPut('keys',{mac:macKey(),key});secretKey=key;refreshStatus();indicator.textContent='🔒 New key installed. Copy it into the second browser.';copy.disabled=false;await migrateLegacy();await hydrateHistory();
    }catch(e){toast('Could not install key: '+e.message);} };
  copy.onclick=async()=>{try{await navigator.clipboard.writeText(field.value.trim());toast('Pairing key copied. Paste it into the second browser.');}catch{field.focus();field.select();toast('Select and copy the key manually.');}};
  join.onclick=async()=>{try{const raw=P.fromBase64url(field.value.trim());if(await installPairKey(raw)){closeDialog();toast('Pairing key installed. All new messages are encrypted.');}}catch(e){toast('Invalid key: '+e.message);}};
 });}
async function migrateLegacy(){const oldKey='offgrid_phase13_history_'+macKey().replace(/:/g,'-');let raw=null;try{raw=localStorage.getItem(oldKey);}catch{}if(!raw)return;
 try{const old=JSON.parse(raw);if(!Array.isArray(old))return;for(const m of old.slice(-250)){
   if(typeof m.text!=='string'||!['in','out'].includes(m.direction))continue;
   const id=P.newId();const r={id,kind:'text',dir:m.direction,ts:m.ts||Date.now(),text:m.text,status: m.direction==='in'?'received':(m.status==='read'?'read':m.status==='delivered'?'delivered':'sent'),readSent:true,deliveredAckSent:true};await storeRecord(r);
  }localStorage.removeItem(oldKey);toast('Previous Phase 1.3 text history migrated into encrypted local storage.');
 }catch(e){console.warn('Legacy migration failed',e);}}
function showRename(){showDialog('Contact name',body=>{body.append(el('p','','This is a local nickname for the remote node.'));const field=document.createElement('input');field.value=activeContact;field.maxLength=36;body.append(field);const save=el('button','primary-button','Save contact name');save.onclick=()=>{setName(field.value.trim());persistName();closeDialog();};body.append(el('div','modal-actions')).append(save);setTimeout(()=>{field.focus();field.select();},40);});}
async function clearHistory(){if(!verified||!secretKey)return;if(runningTransfers||pendingRadio()||transferQueue.length){toast('Finish active transfers before clearing history.');return;}if(!confirm('Delete encrypted chat history for this ESP32 from this browser? This cannot be undone.'))return;
 await wipeStored();records=[];recordMap.clear();revokePhotos();renderHistory();toast('Local chat history cleared.');closeDialog();}
function showInfo(){showDialog('Chat information',body=>{[['Contact',activeContact],['Your ESP32',localMac||'Disconnected'],['Protocol','ESP-NOW firmware protocol 2 / encrypted app protocol OGM4'],['Security',secretKey?'AES-256-GCM, 256-bit random shared key':'Not paired'],['Photo target',`${Math.round(photoBudget()/1024)} KB optimized, 96 KB absolute`],['Radio PHY',radioTelemetry.available?`${radioTelemetry.phy} Mbps TX, RSSI ${radioTelemetry.rssi===null?'unknown':radioTelemetry.rssi+' dBm'}`:'Waiting for telemetry'],['Link profile',`${linkPreference}; adaptive PHY and 1–3 fragments in flight`],['Storage','AES-GCM encrypted local IndexedDB']].forEach(([a,b])=>{const line=el('div','info-line');line.append(el('span','',a),el('strong','',b));body.append(line);});body.append(el('p','','Grey single tick = transmitted; grey double tick = peer decrypted and confirmed; blue double tick = peer app reported it was viewed. Browser history is stored encrypted at rest.'));});}
function showSettings(){showDialog('Settings',body=>{body.append(el('p','','Phase 1.4.3.1: callback-paced radio TX, larger fragments and adaptive radio PHY (1/6/12 Mbps), reliable encrypted chat, and an in-app photo gallery.'));
 const linkLabel=el('label','','Transfer profile');body.append(linkLabel);const linkSelect=document.createElement('select');linkSelect.className='setting-select';
 [['auto','Auto · adapts 1–3 fragments in flight based on ACK latency/retries'],['range','Range first · 1 fragment in flight, ~48 KB images'],['speed','Speed first · up to 3 fragments in flight, ~72 KB images']].forEach(([v,t])=>{const opt=document.createElement('option');opt.value=v;opt.textContent=t;linkSelect.append(opt);});
 linkSelect.value=linkPreference;linkSelect.onchange=()=>{linkPreference=linkSelect.value;try{localStorage.setItem('offgrid_link_preference',linkPreference);}catch{}if(verified)void writeLine('RATE '+linkPreference.toUpperCase()).catch(()=>{});updateLinkBadge();};body.append(linkSelect);
 body.append(el('p','','Link quality is estimated from receiver acknowledgments and retries, not measured distance. Phase 1.4.3.1 firmware adapts unicast transmit PHY between 1/6/12 Mbps using RSSI and successful acknowledgments; Range first locks 1 Mbps. Image size and packet concurrency also adapt. Actual range depends on interference and antenna orientation.')); 
 const modeLabel=el('label','','Image optimization');body.append(modeLabel);const select=document.createElement('select');select.className='setting-select';
 [['hq','Optimized WebP/JPEG (adaptive 48–72 KB target, may resize)'],['lossless','Lossless PNG + metadata stripping (reject if >96 KB)']].forEach(([v,t])=>{const opt=document.createElement('option');opt.value=v;opt.textContent=t;select.append(opt);});select.value=imageMode;select.onchange=()=>{imageMode=select.value;try{localStorage.setItem('offgrid_image_mode',imageMode);}catch{}updateLinkBadge();};body.append(select);
 body.append(el('p','','Metadata stripping does not make photos tiny. High-quality mode can reduce dimensions and introduce slight loss; lossless PNG keeps the canvas pixels exact but may not fit through this radio link.'));
 const buttons=el('div','settings-actions'),rename=el('button','quiet-button','Rename contact'),key=el('button','quiet-button','Encryption key'),clear=el('button','soft-button','Clear history');rename.onclick=showRename;key.onclick=showPairing;clear.onclick=()=>void clearHistory();buttons.append(rename,key,clear);body.append(buttons);
 });}
$('viewerClose').onclick=closePhotoViewer;
$('viewerPrevious').onclick=()=>navigatePhoto(-1);
$('viewerNext').onclick=()=>navigatePhoto(1);
$('viewerZoom').onclick=togglePhotoZoom;
$('viewerImage').onclick=togglePhotoZoom;
$('viewerStage').addEventListener('click',e=>{if(e.target===$('viewerStage'))closePhotoViewer();});
$('viewerStage').addEventListener('touchstart',e=>{viewerTouchX=e.changedTouches[0]?.clientX??null;},{passive:true});
$('viewerStage').addEventListener('touchend',e=>{
 if(viewerTouchX===null)return;const diff=(e.changedTouches[0]?.clientX??viewerTouchX)-viewerTouchX;viewerTouchX=null;
 if(Math.abs(diff)>=55&&!viewerZoomed)navigatePhoto(diff<0?1:-1);
},{passive:true});
document.addEventListener('keydown',e=>{
 if($('photoViewer').classList.contains('hidden'))return;
 if(e.key==='Escape'){e.preventDefault();closePhotoViewer();}
 if(e.key==='ArrowLeft'){e.preventDefault();navigatePhoto(-1);}
 if(e.key==='ArrowRight'){e.preventDefault();navigatePhoto(1);}
});
$('connectButton').onclick=()=>void connect();$('disconnectButton').onclick=()=>void disconnect();$('usbDiagnosticsButton').onclick=diagnoseUsb;$('sendButton').onclick=()=>void sendText();
$('messageInput').addEventListener('input',updateByteCount);$('messageInput').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();void sendText();}});
$('attachments').onclick=()=>$('photoPicker').click();$('photoPicker').addEventListener('change',e=>{if(e.target.files?.length)void sendPhoto(e.target.files[0]);});
$('securityButton').onclick=showPairing;$('renameButton').onclick=showRename;$('settingsButton').onclick=showSettings;$('infoButton').onclick=showInfo;
$('themeToggle').onclick=()=>{theme=theme==='dark'?'light':'dark';document.body.classList.toggle('light',theme==='light');try{localStorage.setItem('offgrid_theme',theme);}catch{}};
$('contactButton').onclick=()=>{document.body.classList.add('mobile-chat');sendReadReceipts();};$('mobileBack').onclick=()=>document.body.classList.remove('mobile-chat');
$('closeModal').onclick=closeDialog;$('modalBackdrop').onclick=e=>{if(e.target===$('modalBackdrop'))closeDialog();};document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDialog();});
document.addEventListener('visibilitychange',sendReadReceipts);window.addEventListener('focus',sendReadReceipts);
window.addEventListener('beforeunload',revokePhotos);
setInterval(pruneIncoming,45000);
(async()=>{try{db=await openDb();}catch(e){toast('IndexedDB unavailable. Encrypted storage cannot work: '+e.message);}refreshStatus();})();
console.info('OffGrid Phase 1.4.3.1 — callback-paced radio TX, 208-byte encrypted fragments, adaptive PHY and encrypted gallery.');
