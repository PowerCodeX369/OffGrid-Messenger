'use strict';
/* OffGrid Phase 1.4.5.1 USB FIX: proven broadcast radio + NVS keys, same encrypted app protocol.
   Keys are stored on each ESP32 and imported only into browser RAM over physical USB. No external servers. */
const P=globalThis.OGP,$=id=>document.getElementById(id);
const MAX_CHARS=500,MAX_PHOTO=96*1024,STORE='OffGridMessengerV14';
const APP_VERSION='1.4.10.3-selective-ack';
const stateRank={sending:0,queued:0,sent:1,failed:1,delivered:2,read:3};
const encoder=new TextEncoder();
let port=null,reader=null,writer=null,readPromise=null,closing=false,verified=false,serialBuffer='',writeChain=Promise.resolve(),handshakeTimer=null;
let localMac=null,secretKey=null,peerMac=null,activeContact='Nearby node',firmwareMismatchNotified=false;
let pendingNodeGet=null,pendingNodeSet=null;
/* USB diagnostics: distinguish missing console output from incompatible or old firmware. */
const usbLog=[];
let usbWaitTimer=null,usbHandshakeStarted=0,usbLastRawAt=0,usbHandshakeIssue='',usbSawBeacon=false;
function logUsb(line){usbLog.push(`[${new Date().toLocaleTimeString()}] ${String(line).slice(0,240)}`);if(usbLog.length>75)usbLog.shift();}
function showUsbHint(message){usbHandshakeIssue=message;const area=$('usbDiagnostics');if(!area)return;area.classList.remove('hidden');$('usbDiagnosticsText').textContent=message;}
function clearUsbHint(){usbHandshakeIssue='';$('usbDiagnostics')?.classList.add('hidden');}
function updateUsbWait(){
 if(!port||verified)return;
 const elapsed=performance.now()-usbHandshakeStarted;
 if(elapsed<5000||usbHandshakeIssue)return;
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
  body.append(el('p','',`Expected: READY|MAC|…|PROTO=2|CAP=180|STABLE=1|NVSKEY=1. Radio counters: last received peer ${radioTelemetry.peer}, TX queued ${radioTelemetry.txok}, TX errors ${radioTelemetry.txfail}, RX ${radioTelemetry.rx}. At stable broadcast rate, peer RSSI appears after a valid incoming radio packet.`));
 });
}

let db=null,records=[],recordMap=new Map(),photoUrls=new Map(),incoming=new Map(),recentTransfers=new Map();
let transferQueue=[],activeTransfer=null,runningTransfers=false;
/* Photo-only selective ACK. Groups are per sender MAC and ESP32 boot-session;
 * IDs remain arbitrary (do not assume sequential or shared with other traffic). */
const sackGroups=new Map();
const SACK_GROUP_MAX=6, SACK_FLUSH_MS=75;
let selectivePhotoAcks=true;

const radioByHex=new Map(),radioByWire=new Map();
const linkStats={samples:0,avgMs:0,cleanStreak:0,retries:0,mode:'starting',window:1,lastKBs:0};
let linkPreference='auto';
const photoFlow=new globalThis.OffGridAdaptive.PhotoFlowController();
let adaptivePhotos=true;
let queuedReceipts=new Set(),pendingReads=new Set(),theme='dark';
let imageMode='hq';
let radioTelemetry={peer:'NONE',rssi:null,phy:1,mode:'AUTO',delivered:0,retries:0,failures:0,txok:0,txfail:0,rx:0,hello:0,available:false};
let selectedPhotoId=null,viewerZoomed=false,viewerTouchX=null,viewerPreviousFocus=null;
/* Isolated benchmark state; measurements never enter message history. */
const speedDiagnostics={busy:false,serial:null,full:null,results:{},output:null,runButton:null,capable:false,sackCapable:false};
const benchToken=()=>crypto.getRandomValues(new Uint32Array(1))[0]||1;
const kbps=(bytes,ms)=>bytes/1024/Math.max(.001,ms/1000);
function diagnosticLine(line){
 const parts=line.split('|'),type=parts[0],test=speedDiagnostics.serial;
 if(type==='USBOUT'&&test?.kind==='usbOut'&&Number(parts[1])===test.token){
  const seq=Number(parts[2]),hex=parts[3]||'';
  if(seq===test.count&&/^[a-fA-F0-9]{320}$/.test(hex)){
   test.count++;test.bytes+=hex.length/2;
  }
  return true;
 }
 if(type==='USBOUT_DONE'){
  if(test?.kind==='usbOut'&&Number(parts[1])===test.token){
   const bytes=Number(parts[3]),elapsed=performance.now()-test.started;
   if(test.count===Number(parts[2])&&test.bytes===bytes)test.resolve({bytes,ms:elapsed,nodeMs:Number(parts[4]),packets:test.count});
   else test.reject(Error(`Received ${test.count} of ${parts[2]} USB packets`));
  }
  return true;
 }
 if(type==='USBIN_DONE'){
  if(test?.kind==='usbIn'&&Number(parts[1])===test.token){
   const bytes=Number(parts[3]),packets=Number(parts[2]);
   if(bytes===test.expectedBytes&&packets===test.expectedPackets)test.resolve({bytes,ms:performance.now()-test.started,nodeMs:Number(parts[4]),packets});
   else test.reject(Error(`USB input short: ${bytes}/${test.expectedBytes} bytes, ${packets}/${test.expectedPackets} packets`));
  }
  return true;
 }
 if(type==='RBENCH_START')return true;
 if(type==='RBENCH_ERROR'){
  if(test?.kind==='radio'&&Number(parts[1])===test.token)test.reject(Error(parts[2]||'Radio benchmark failed'));
  return true;
 }
 if(type==='RBENCH_DONE'){
  if(test?.kind==='radio'&&Number(parts[1])===test.token){
   test.resolve({requested:Number(parts[2]),ok:Number(parts[3]),lost:Number(parts[4]),retries:Number(parts[5]),
     bytes:Number(parts[6]),ms:Number(parts[7]),rttMs:Number(parts[8]),phy:Number(parts[9]),rssi:Number(parts[10])});
  }
  return true;
 }
 return false;
}
function diagPrint(message){
 if(speedDiagnostics.output){speedDiagnostics.output.textContent+=message+'\n';speedDiagnostics.output.scrollTop=speedDiagnostics.output.scrollHeight;}
}
function serialBenchmark(kind,timeoutMs,commandFactory){
 if(speedDiagnostics.serial)return Promise.reject(Error('Benchmark already running'));
 return new Promise((resolve,reject)=>{
  const token=benchToken(),test={kind,token,started:performance.now(),count:0,bytes:0,expectedBytes:0,expectedPackets:0};
  const stop=(error,value)=>{
   if(speedDiagnostics.serial!==test)return;
   clearTimeout(test.timer);speedDiagnostics.serial=null;
   error?reject(error):resolve(value);
  };
  test.resolve=value=>stop(null,value);test.reject=error=>stop(error);
  speedDiagnostics.serial=test;
  test.timer=setTimeout(()=>test.reject(Error(`${kind} diagnostic timed out`)),timeoutMs);
  Promise.resolve().then(()=>commandFactory(test)).catch(test.reject);
 });
}
async function runUsbInput(){
 return serialBenchmark('usbIn',60000,async test=>{
  const count=96,hex=P.hex(Uint8Array.from({length:160},(_,i)=>i));
  test.expectedPackets=count;test.expectedBytes=count*160;
  await writeLine('USBIN_BEGIN '+test.token);
  for(let i=0;i<count;i++)await writeLine(`USBIN ${test.token} ${i} ${hex}`);
  await writeLine('USBIN_END '+test.token);
 });
}
async function runUsbOutput(){
 return serialBenchmark('usbOut',60000,test=>writeLine(`USBOUT ${test.token} 96`));
}
async function runRadioBenchmark(){
 return serialBenchmark('radio',120000,test=>writeLine(`RBENCH ${test.token} 32`));
}
async function runFullBenchmark(batch=false){
 if(!secretKey)throw Error('Configure the same encryption key on both nodes first');
 return new Promise((resolve,reject)=>{
  const job={kind:'probe',control:false,batch,probeId:P.newId(),created:Date.now(),started:performance.now(),fragmentRTTs:[],size:8192};
  const finish=(err,value)=>{
   if(speedDiagnostics.full!==bench)return;
   clearTimeout(bench.timer);speedDiagnostics.full=null;
   err?reject(err):resolve(value);
  };
  const bench={job,resolve:value=>finish(null,value),reject:err=>finish(err)};
  speedDiagnostics.full=bench;
  bench.timer=setTimeout(()=>finish(Error('Remote browser did not authenticate and acknowledge test payload')),120000);
  transferQueue.push(job);runTransfers();
 });
}
function showSpeedDiagnostics(){
 showDialog('Connection speed diagnostics',body=>{
  body.append(el('p','','Tests USB upload/download, ESP-NOW-only, encrypted legacy transfer, and encrypted selective-ACK transfer. Flash both nodes with 1.4.10.3 and keep the peer browser connected. No test data enters chat history.'));
  const output=el('pre','diagnostic-results',speedDiagnostics.capable?'Ready. Connect both boards, then run one test at a time.\n':'Phase 1.4.9 diagnostic firmware not detected. Flash both boards before running.\n');body.append(output);
  const actions=el('div','diagnostic-actions'),run=el('button','primary-button','Run all tests'),copy=el('button','quiet-button','Copy results');
  speedDiagnostics.output=output;speedDiagnostics.runButton=run;
  run.onclick=()=>void runDiagnostics();
  copy.onclick=()=>navigator.clipboard.writeText(output.textContent).then(()=>toast('Results copied')).catch(()=>toast('Select the results to copy them'));
  actions.append(run,copy);body.append(actions);
  body.append(el('p','','USB/radio tests are unchanged. The two encrypted 8 KiB probes compare legacy per-fragment ACK and the new selective-batch ACK over the same hardware. Each requires a final authenticated acknowledgment. Throughput is delivered application data, not PHY speed.'));
 });
}
async function runDiagnostics(){
 if(speedDiagnostics.busy)return;
 if(!speedDiagnostics.capable){toast('This ESP32 needs the Phase 1.4.9 diagnostic firmware. Flash both nodes first.');return;}
 if(!verified||!writer){toast('Connect your ESP32 first');return;}
 if(runningTransfers||transferQueue.length||pendingRadio()){toast('Finish any active photo or text first');return;}
 speedDiagnostics.busy=true;if(speedDiagnostics.runButton)speedDiagnostics.runButton.disabled=true;
 speedDiagnostics.output.textContent='';speedDiagnostics.results={};
 const begin=performance.now();
 const tests=[['USB PC → ESP32',runUsbInput,'usbIn'],['USB ESP32 → PC',runUsbOutput,'usbOut'],
  ['ESP-NOW radio only',runRadioBenchmark,'radio'],
  ['Encrypted browser-to-browser (legacy)',()=>runFullBenchmark(false),'full']];
 if(speedDiagnostics.sackCapable)tests.push(['Encrypted browser-to-browser (selective ACK)',()=>runFullBenchmark(true),'sack']);
 diagPrint('OffGrid diagnostic release 1.4.10.3 selective ACK · '+new Date().toLocaleString());
 diagPrint(`Local node: ${localMac||'unknown'} · PHY: ${radioTelemetry.phy} Mbps · RSSI: ${radioTelemetry.rssi??'unknown'} dBm`);
 for(const [label,fn,key] of tests){
  if(!verified){diagPrint('Stopped: ESP32 disconnected.');break;}
  diagPrint('');diagPrint(`Running ${label}…`);
  try{
   const result=await fn();speedDiagnostics.results[key]=result;
   diagPrint(`${label}: ${kbps(result.bytes,result.ms).toFixed(2)} KiB/s · ${result.bytes} bytes in ${(result.ms/1000).toFixed(2)} s`);
   if(key==='usbIn'||key==='usbOut')diagPrint(`  ESP32 elapsed: ${result.nodeMs} ms · ${result.packets} packets`);
   if(key==='radio')diagPrint(`  ${result.ok}/${result.requested} delivered · lost ${result.lost} · retries ${result.retries} · mean MCU ACK ${result.rttMs} ms · PHY ${result.phy} Mbps`);
   if(key==='full'||key==='sack')diagPrint(`  ${result.frames} fragments · avg browser fragment ACK ${result.avgFragmentMs.toFixed(0)} ms · authenticated final ACK confirmed`);
   if(key==='sack'&&speedDiagnostics.results.full){
     const old=speedDiagnostics.results.full;
     diagPrint(`  Selective ACK speedup vs. legacy: ${(kbps(result.bytes,result.ms)/kbps(old.bytes,old.ms)).toFixed(2)}×`);
   }
  }catch(e){diagPrint(`${label}: FAILED — ${e.message}`);}
 }
 const d=speedDiagnostics.results;
 if(d.usbIn&&d.usbOut&&d.radio&&d.full){
  const minUsb=Math.min(kbps(d.usbIn.bytes,d.usbIn.ms),kbps(d.usbOut.bytes,d.usbOut.ms));
  const radio=kbps(d.radio.bytes,d.radio.ms),full=kbps(d.full.bytes,d.full.ms);
  diagPrint('');diagPrint('COMPARISON');
  if(minUsb>full*3&&radio>full*3)diagPrint('USB and radio are both considerably faster than the full path: investigate browser-level per-fragment ACK overhead.');
  else if(minUsb<radio*0.6)diagPrint('USB is markedly slower than the radio-only test: investigate USB serial transport and firmware console processing.');
  else if(radio<minUsb*0.6)diagPrint('Radio-only throughput is lower than USB throughput: inspect ESP-NOW retry/PHY performance.');
  else diagPrint('No single obvious bottleneck: compare timings and loss rates across repeated tests.');
 }
 diagPrint(`\nTotal diagnostic session: ${((performance.now()-begin)/1000).toFixed(1)} s`);
 speedDiagnostics.busy=false;if(speedDiagnostics.runButton)speedDiagnostics.runButton.disabled=false;
}

try{theme=localStorage.getItem('offgrid_theme')==='light'?'light':'dark';imageMode=localStorage.getItem('offgrid_image_mode')==='lossless'?'lossless':'hq';linkPreference=['auto','range','speed','lr500','lr250'].includes(localStorage.getItem('offgrid_link_preference'))?localStorage.getItem('offgrid_link_preference'):'auto';adaptivePhotos=localStorage.getItem('offgrid_adaptive_photos')!=='off';selectivePhotoAcks=localStorage.getItem('offgrid_sack_photos')!=='off';}catch{}
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
function chosenWindow(job=activeTransfer){
 // A deliberate boundary: text and control receipts never use speculative pipelining.
 // Only photo jobs can raise concurrency, starting at 1 and learning from real ACKs.
 const kind=job?.kind==='image'&&!job?.control?'image':'text';
 const fast=(kind==='image' && selectivePhotoAcks && speedDiagnostics.sackCapable && adaptivePhotos) ||
            (job?.kind==='probe' && job?.batch && speedDiagnostics.sackCapable);
 if(fast){
   // In LR mode, favor reliable small windows over PHY throughput.
   if(linkPreference==='lr250'||radioTelemetry.phy===0.25)return 1;
   if(linkPreference==='lr500'||radioTelemetry.phy===0.5)return 2;
   // C firmware MAX_PENDING=8; reserve two slots for scheduling margin.
   // A 6-slot window is large enough for one selective ACK group.
   const rssi=radioTelemetry.rssi;
   if(linkPreference==='range'||(Number.isFinite(rssi)&&rssi< -79))return 3;
   if(photoFlow.cooldown>0)return 3;
   return 6;
 }
 return photoFlow.window({kind,rssi:radioTelemetry.rssi,enabled:adaptivePhotos,
   profile:linkPreference,txFailures:0});
}
function photoBudget(){
 if(imageMode==='lossless')return MAX_PHOTO;
 // Image preparation happens before transmission; mid-transfer signal changes
 // alter the in-flight window, never mutate already-encrypted image bytes.
 if(linkPreference==='range')return 32*1024;
 if(['lr250','lr500'].includes(linkPreference))return 32*1024;
 if(linkPreference==='auto'&&radioTelemetry.rssi!==null&&radioTelemetry.rssi< -78)return 32*1024;
 if(linkStats.mode==='weak')return 32*1024;
 if(linkPreference==='speed'||(linkStats.mode==='strong'&&radioTelemetry.rssi!==null&&radioTelemetry.rssi>=-65))return 60*1024;
 return 48*1024;
}
function updateLinkBadge(){
 const node=$('linkModeLabel');if(!node)return;
 const speed=chosenWindow({kind:'image',control:false}),budget=Math.round(photoBudget()/1024);
 const name=linkPreference==='auto'?'Auto compression':linkPreference==='range'?'Smaller photos':linkPreference==='lr500'?'Long Range 500':linkPreference==='lr250'?'Long Range 250':'Higher-quality photos';
 let quality='learning';
 if(linkStats.samples>3)quality=linkStats.mode==='strong'?'strong':linkStats.mode==='weak'?'weak':'steady';
 const phy=radioTelemetry.available?`${radioTelemetry.phy<1?'LR ': 'adaptive '}${radioTelemetry.phy} Mbps PHY`:'radio discovery starting';
 const signal=radioTelemetry.available&&radioTelemetry.rssi!==null?`${radioTelemetry.rssi} dBm`:'signal —';
 node.textContent=`${name} · ${phy} · ${signal} · ${selectivePhotoAcks&&speedDiagnostics.sackCapable&&adaptivePhotos?"batch ACK":"legacy ACK"} · photo window ${speed} · ~${budget} KB${photoFlow.samples?` · ACK ${Math.round(photoFlow.emaMs)} ms`:``}${linkStats.lastKBs?` · ${linkStats.lastKBs.toFixed(1)} KB/s`:``}`;
 node.title=`ESP-NOW peer: ${radioTelemetry.peer}. TX successful: ${radioTelemetry.txok}, TX failures: ${radioTelemetry.txfail}, received: ${radioTelemetry.rx}, discovery beacons received: ${radioTelemetry.hello}. Reported PHY rate is not actual file throughput.`;
}
function recordLinkResult(rtt,attempts,isPhoto=false){
 if(isPhoto)photoFlow.success(rtt,attempts);
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
function recordLinkFailure(){photoFlow.penalty('radio or browser ACK failure');linkStats.cleanStreak=0;linkStats.mode='weak';linkStats.window=1;linkStats.retries++;updateLinkBadge();}
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
/* Message viewport follow mode.  A normal scrollTop assignment happens before
 * decoded image dimensions become available, so ResizeObserver keeps the newest
 * message visible as the chat grows.  A deliberate upward user scroll pauses
 * resizing-based auto-follow; a genuinely new message resumes it. */
const chatScrollState = {follow:true, frame:0, touchY:null};
function chatNearBottom(){
 const view=$('chatScroll');
 return view.scrollHeight - view.clientHeight - view.scrollTop < 88;
}
function scrollToNewest(force=false){
 if(force)chatScrollState.follow=true;
 if(!chatScrollState.follow || chatScrollState.frame)return;
 chatScrollState.frame=requestAnimationFrame(()=>{
  chatScrollState.frame=0;
  if(!chatScrollState.follow)return;
  const view=$('chatScroll');
  view.scrollTop=view.scrollHeight;
 });
}
function setupChatAutoScroll(){
 const view=$('chatScroll');
 // Wheel / touch / keyboard scrolling is a deliberate choice to read history.
 view.addEventListener('wheel',event=>{
  if(event.deltaY < -2)chatScrollState.follow=false;
  else if(chatNearBottom())chatScrollState.follow=true;
 },{passive:true});
 view.addEventListener('touchstart',event=>{
  chatScrollState.touchY=event.touches[0]?.clientY??null;
 },{passive:true});
 view.addEventListener('touchmove',event=>{
  if(chatScrollState.touchY===null)return;
  const y=event.touches[0]?.clientY??chatScrollState.touchY;
  if(y>chatScrollState.touchY+8)chatScrollState.follow=false;
  else if(chatNearBottom())chatScrollState.follow=true;
  chatScrollState.touchY=y;
 },{passive:true});
 view.addEventListener('scroll',()=>{
  if(chatNearBottom())chatScrollState.follow=true;
 },{passive:true});
 view.addEventListener('keydown',event=>{
  if(['PageUp','Home','ArrowUp'].includes(event.key))chatScrollState.follow=false;
  if(['End'].includes(event.key)){chatScrollState.follow=true;scrollToNewest(true);}
 });
 if('ResizeObserver' in window){
  new ResizeObserver(()=>scrollToNewest()).observe($('messages'));
 }
}
setupChatAutoScroll();
function makeBubble(record,animate=true){
 const row=document.createElement('div');row.className='message-row '+(record.dir==='out'?'out':'in')+(animate?' is-new':'');row.dataset.mid=record.id;row.dataset.status=record.status||'';
 const bubble=document.createElement('div');bubble.className='message-bubble';
 if(record.kind==='image'){
   const img=document.createElement('img');img.className='chat-photo';img.alt='Shared photograph';img.loading=animate?'eager':'lazy';img.addEventListener('load',()=>scrollToNewest());
   const mime=record.mime||'image/webp',url=URL.createObjectURL(new Blob([record.bytes],{type:mime}));photoUrls.set(record.id,url);img.src=url;
   img.tabIndex=0;img.setAttribute('role','button');img.setAttribute('aria-label','Open photograph in gallery');
   img.onclick=()=>openPhotoViewer(record.id);img.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();openPhotoViewer(record.id);}};
   bubble.append(img);
   const photoCaption=document.createElement('div');photoCaption.className='photo-caption';photoCaption.textContent=`${record.width||'?'} × ${record.height||'?'} · ${formatBytes(record.bytes?.length||0)}`;bubble.append(photoCaption);
 }else{const content=document.createElement('div');content.className='message-text';content.textContent=record.text;bubble.append(content);}
 const meta=document.createElement('div');meta.className='message-meta';const time=document.createElement('span');time.textContent=fmtTime(record.ts);meta.append(time);
 if(record.dir==='out'){const progress=document.createElement('span');progress.className='transfer-progress';progress.dataset.role='progress';progress.textContent=record.status==='sending'&&record.progress?record.progress+'%':'';meta.append(progress,createReceipt(record.status||'sending'));}
 bubble.append(meta);row.append(bubble);$('messages').append(row);updateBubble(record);
 if(animate)scrollToNewest(true);return row;
}
function updateBubble(r){const row=Array.from($('messages').children).find(el=>el.dataset.mid===r.id);if(!row)return;row.dataset.status=r.status||'';
 const receipt=row.querySelector('[data-role="tick"]');if(receipt){receipt.className='receipt-icon '+(r.status||'sending');receipt.setAttribute('aria-label',r.status||'Sending');}
 const prog=row.querySelector('[data-role="progress"]');if(prog)prog.textContent=r.status==='sending'&&r.progress?`${r.progress}%${r.kind==='image'&&r.speedKbps?' · '+r.speedKbps.toFixed(1)+' KB/s':''}`:'';
 if(r.dir==='out'){row.title=(r.status||'Sending')+(r.error?': '+r.error:'');row.classList.toggle('retryable',r.status==='failed');if(r.status==='failed')row.onclick=()=>retryMessage(r.id);else row.onclick=null;}
}
function revokePhotos(){closePhotoViewer();for(const url of photoUrls.values())URL.revokeObjectURL(url);photoUrls.clear();}
function renderHistory(){revokePhotos();$('messages').replaceChildren();for(const r of records)makeBubble(r,false);updateSidebar();scrollToNewest(true);}
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
/* KEYGET/KEYSET are local USB commands. Raw key material is never saved in
   IndexedDB/localStorage. The CryptoKey only lives in this tab's RAM. */
function keyRequest(kind,command){
 return new Promise((resolve,reject)=>{
  if(!writer){reject(Error('USB disconnected'));return;}
  if(kind==='get'?pendingNodeGet:pendingNodeSet){reject(Error('Node key request already running'));return;}
  const slot={resolve,reject,timer:null};
  slot.timer=setTimeout(()=>{
   if(kind==='get'&&pendingNodeGet===slot)pendingNodeGet=null;
   if(kind==='set'&&pendingNodeSet===slot)pendingNodeSet=null;
   reject(Error('ESP32 did not respond to '+kind.toUpperCase()+' key command'));
  },5000);
  if(kind==='get')pendingNodeGet=slot;else pendingNodeSet=slot;
  writeLine(command).catch(err=>{
    clearTimeout(slot.timer);
    if(kind==='get'&&pendingNodeGet===slot)pendingNodeGet=null;
    if(kind==='set'&&pendingNodeSet===slot)pendingNodeSet=null;
    reject(err);
  });
 });
}
function finishNodeKeyRequest(kind,value,error){
 const slot=kind==='get'?pendingNodeGet:pendingNodeSet;
 if(!slot)return;
 if(kind==='get')pendingNodeGet=null;else pendingNodeSet=null;
 clearTimeout(slot.timer);
 if(error)slot.reject(error);else slot.resolve(value);
}
async function getNodeKey(){return await keyRequest('get','KEYGET');}
async function setNodeKey(raw){
 if(!(raw instanceof Uint8Array)||raw.length!==32)throw Error('Expected 32 key bytes');
 await keyRequest('set','KEYSET '+P.hex(raw));
}
/* Convert stored legacy browser-encrypted history to the node's key, without
   copying the key back into browser storage. Works for non-extractable keys. */
async function rewrapHistory(oldKey,newKey){
 const rows=await listStored();
 const replacements=[];
 for(const row of rows){
  const clear=await P.openAtRest(oldKey,row.seal,macKey(),row.id);
  const newSeal=await P.sealAtRest(newKey,clear,macKey(),row.id);
  replacements.push({...row,seal:newSeal});
 }
 for(const row of replacements)await dbPut('messages',row);
 return replacements.length;
}
async function adoptNodeKey(raw){
 const key=await P.importKey(raw);
 const legacy=await dbGet('keys',macKey());
 if(legacy?.key){
  const n=await rewrapHistory(legacy.key,key);
  await dbDelete('keys',macKey());
  if(n)toast(`Migrated ${n} browser-encrypted messages to the node key.`);
 }
 secretKey=key;
 await migrateLegacy();
 await hydrateHistory();
 refreshStatus();sendReadReceipts();
}

function incomingKey(mac,session,transferId){return mac+':'+session+':'+transferId;}
function pruneIncoming(){const t=Date.now();for(const [k,v] of incoming){if(t-v.updated>4*60_000)incoming.delete(k);}for(const [k,t0] of recentTransfers){if(t-t0>12*60_000)recentTransfers.delete(k);}while(recentTransfers.size>512)recentTransfers.delete(recentTransfers.keys().next().value);}
function flushSelectiveAck(key){
 const group=sackGroups.get(key);
 if(!group)return;
 sackGroups.delete(key);
 if(group.timer)clearTimeout(group.timer);
 if(!verified||!writer)return;
 const ids=[...group.ids];
 // The legacy APPACK command is the on-demand fallback if the batch USB
 // write fails. Sender's firmware also handles lost batch ACKs by retrying
 // the original data; duplicate RX then generates the old single ACK.
 writeLine(`APPACKB ${group.mac} ${group.session} ${ids.join(' ')}`).catch(()=>{
  if(writer)for(const id of ids)writeLine(`APPACK ${group.mac} ${group.session} ${id}`).catch(()=>{});
 });
}
function queueSelectiveAck(mac,session,packetId){
 const key=`${mac}:${session}`;
 let group=sackGroups.get(key);
 if(!group){
  group={mac,session,ids:new Set(),timer:null};
  sackGroups.set(key,group);
  group.timer=setTimeout(()=>flushSelectiveAck(key),SACK_FLUSH_MS);
 }
 group.ids.add(packetId);
 if(group.ids.size>=SACK_GROUP_MAX)flushSelectiveAck(key);
}
function clearSelectiveAcks(){
 for(const group of sackGroups.values())if(group.timer)clearTimeout(group.timer);
 sackGroups.clear();
}
async function processRx(mac,session,packetId,rawHex){
 let fr;try{if(!/^[0-9A-Fa-f]{40,456}$/.test(rawHex))return;fr=P.parseFrame(P.unhex(rawHex));}catch{return;}if(!fr||!secretKey||!verified)return;
 const key=incomingKey(mac,session,fr.transferId);
 /* All normal messages and controls retain the original immediate APPACK.
    Bulk image frames use one selective acknowledgment for up to six radio IDs. */
 if(fr.bulk && speedDiagnostics.sackCapable)queueSelectiveAck(mac,session,packetId);
 else writeLine(`APPACK ${mac} ${session} ${packetId}`).catch(()=>{});
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
 if(env.kind===P.types.probe){
  /* Only a fully authenticated AES-GCM probe gets a browser-level ACK. */
  if(env.body.length!==8192)return;
  enqueueReceipt('probeAck',env.id);
  return;
 }
 if(env.kind===P.types.probeAck){
  const test=speedDiagnostics.full;
  if(test&&env.body.length===8&&P.hex(env.body)===test.job.probeId){
   const job=test.job,ms=performance.now()-job.started;
   test.resolve({bytes:job.size,ms,frames:job.fragmentRTTs.length,
    avgFragmentMs:job.fragmentRTTs.reduce((a,b)=>a+b,0)/Math.max(1,job.fragmentRTTs.length)});
  }
  return;
 }
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
 const transferId=P.newId(),id=job.control?P.newId():(job.kind==='probe'?job.probeId:job.record.id);
 let kind,body,width=0,height=0;
 if(job.control){kind=P.types[job.kind];body=P.unhex(job.targetId);}
 else if(job.kind==='probe'){kind=P.types.probe;body=P.randomBytes(job.size);}
 else if(job.record.kind==='text'){kind=P.types.text;body=P.textBytes(job.record.text);}
 else{kind=P.types.image;const r=job.record,mimeNum=({'image/webp':0,'image/png':1,'image/jpeg':2})[r.mime];if(mimeNum===undefined)throw Error('Unsupported image encoding');body=P.concat(Uint8Array.of(mimeNum),r.bytes);width=r.width;height=r.height;}
 const plain=P.makeEnvelope(kind,id,body,Math.floor(Date.now()/1000),width,height);
 const ciphertext=await P.seal(secretKey,plain,transferId);
 return P.frames(transferId,ciphertext,{batch: Boolean(speedDiagnostics.sackCapable &&
     ((job.kind==='image' && !job.control && selectivePhotoAcks && adaptivePhotos) ||
      (job.kind==='probe' && job.batch)))});
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
    },7000);
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
  while(next<chunks.length&&inflight.size<chosenWindow(job)){
   const i=next++;
   const op=oneFragment(chunks[i]).then(result=>({i,...result})).catch(error=>({i,error}));
   inflight.set(i,op);
  }
  if(!inflight.size)break;
  const result=await Promise.race(inflight.values());inflight.delete(result.i);
  if(result.error){recordLinkFailure();fatal=result.error;break;}
  completed++;recordLinkResult(result.ms,result.attempts,job.kind==='image'&&!job.control);
  if(job.kind==='probe')job.fragmentRTTs.push(result.ms);
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
    else if(job.kind==='probe'&&speedDiagnostics.full?.job===job){speedDiagnostics.full.reject(e);}
   }finally{if(job.control)queuedReceipts.delete(job.kind+':'+job.targetId);activeTransfer=null;}
  }
 }finally{runningTransfers=false;}
}
function parseLine(raw){const s=raw.trim();if(!s)return;
 if(diagnosticLine(s))return;
 // Record serial input for diagnosis, without exposing the 256-bit pairing key.
 usbLastRawAt=performance.now();
 if(s.startsWith('KEYDATA|'))logUsb('ESP32> KEYDATA|<redacted>');
 else logUsb('ESP32> '+s);
 if(s.startsWith('READY|'))usbSawBeacon=true;
 if(s.startsWith('FATAL|')){showUsbHint('ESP32 startup error: '+s.slice(6));return;}
 if(s.startsWith('KEYDATA|')){
  const h=s.slice(8);
  try{if(!/^[A-Fa-f0-9]{64}$/.test(h))throw Error('Invalid key response');finishNodeKeyRequest('get',P.unhex(h));}
  catch(e){finishNodeKeyRequest('get',null,e);}
  return;
 }
 if(s==='KEYEMPTY'){finishNodeKeyRequest('get',null);return;}
 if(s==='KEYSTORED'){finishNodeKeyRequest('set',true);return;}
 if(s.startsWith('KEYERROR|')){finishNodeKeyRequest('set',null,Error(s.slice(9)));return;}

 if(s.startsWith('LINK|')){
  const parts=s.split('|'),fields={};for(const bit of parts.slice(2)){const j=bit.indexOf('=');if(j>0)fields[bit.slice(0,j)]=bit.slice(j+1);}
  const phy=Number(fields.PHY),rssi=Number(fields.RSSI);
  if([0.25,0.5,1,6,12].includes(phy)){
   const newTxFail=Number(fields.TXFAIL)||0;
   if(newTxFail>radioTelemetry.txfail && photoFlow.samples)photoFlow.penalty('firmware reports radio TX failure');
   // Use current telemetry only; cumulative TX failures are not a permanent ban.
   radioTelemetry={peer:parts[1],phy,rssi:Number.isFinite(rssi)&&rssi>-120&&rssi<0?rssi:null,
     mode:fields.MODE||'AUTO',delivered:Number(fields.OK)||0,retries:Number(fields.RETRY)||0,
     failures:Number(fields.FAIL)||0,txok:Number(fields.TXOK)||0,txfail:Number(fields.TXFAIL)||0,rx:Number(fields.RX)||0,hello:Number(fields.HELLO)||0,available:true};
   updateLinkBadge();
  }
  return;
 }
 if(s.startsWith('RATE_ERROR|')){console.warn('Radio PHY configuration:',s);toast('ESP32 PHY rate could not be applied; falling back to safe rate.');return;}
 if(s.startsWith('RATE|')||s.startsWith('PEER|'))return;
 if(s.startsWith('READY|')){const parts=s.split('|');speedDiagnostics.capable=parts.includes('DIAG=1');speedDiagnostics.sackCapable=parts.includes('SACK=1');if(!parts.includes('PROTO=2')||!parts.includes('CAP=180')||!parts.includes('STABLE=1')||!parts.includes('NVSKEY=1')){if(!firmwareMismatchNotified){toast('Wrong ESP32 firmware protocol; open Diagnostics to see its READY response.');firmwareMismatchNotified=true;}showUsbHint('Incompatible READY. Expected PROTO=2, CAP=180, STABLE=1, NVSKEY=1, LR=1.');console.warn('Incompatible firmware READY:',s);return;}const mac=parts[1];if(!/^[0-9A-Fa-f:]{17}$/.test(mac))return;if(!parts.includes('LR=1'))toast('ESP32 Wi-Fi driver did not enable Long Range; normal messaging remains available.');if(verified&&localMac===mac)return;void onReady(mac);return;}
 if(s.startsWith('QUEUED|')){const [,session,id,hex]=s.split('|'),slot=radioByHex.get(hex);if(slot){slot.wire=`${session}:${id}`;radioByWire.set(slot.wire,slot);}return;}
 if(s.startsWith('RX|')){const [,mac,session,id,data]=s.split('|');if(mac&&session&&id&&data)void processRx(mac,session,id,data);return;}
 if(/^(SENT|DELIVERED|FAILED|RETRY|RADIO_ERROR)\|/.test(s)){const [type,session,id,extra]=s.split('|');if(type==='RADIO_ERROR'){console.warn('ESP-NOW:',extra);if(radioTelemetry.txfail<=2)toast('Radio send issue: '+extra+' — check Radio diagnostics in Settings.');}else onRadio(type,session,id,extra);return;}
 if(s.startsWith('ERROR|')){const reason=s.slice(6);if(pendingRadio()&&/^(QUEUE_FULL|EVENT_QUEUE_FULL|INVALID_HEX_OR_LENGTH|LINE_TOO_LONG|UNKNOWN_COMMAND)/.test(reason)){const slot=[...radioByHex.values()].findLast(x=>!x.wire)||[...radioByHex.values()].at(-1);if(slot){clearSlot(slot);slot.reject(Error('ESP32: '+reason));}}toast('ESP32: '+reason);}
}
function consume(data){serialBuffer+=new TextDecoder().decode(data);let i;while((i=serialBuffer.indexOf('\n'))>=0){parseLine(serialBuffer.slice(0,i));serialBuffer=serialBuffer.slice(i+1);}if(serialBuffer.length>8192)serialBuffer='';}
async function readLoop(){try{while(port?.readable&&!closing){reader=port.readable.getReader();try{while(!closing){const {value,done}=await reader.read();if(done)break;if(value)consume(value);}}finally{reader.releaseLock();reader=null;}if(!closing&&port?.readable)await wait(100);}}catch(e){if(!closing)toast('Serial disconnected: '+e.message);}finally{if(!closing){readPromise=null;void disconnect();}}}
async function onReady(mac){clearSelectiveAcks();firmwareMismatchNotified=false;localMac=mac.toUpperCase();verified=true;
 if(usbWaitTimer){clearInterval(usbWaitTimer);usbWaitTimer=null;}
 clearUsbHint();
 void writeLine('RATE '+linkPreference.toUpperCase()).catch(()=>{});
 if(handshakeTimer){clearInterval(handshakeTimer);handshakeTimer=null;}
 try{
   try{setName(localStorage.getItem(nameKey())||localStorage.getItem('offgrid_phase13_name_'+macKey().replace(/:/g,'-'))||'Nearby node');}catch{setName('Nearby node');}
   const raw=await getNodeKey();
   if(raw){
     await adoptNodeKey(raw);
     for(const r of records)if(r.dir==='in'&&!r.deliveredAckSent)enqueueReceipt('delivered',r.id);
     sendReadReceipts();
   }else{
     secretKey=null;records=[];recordMap.clear();renderHistory();
     refreshStatus();showPairing();
   }
   refreshStatus();
 }catch(e){toast('Node pairing initialization failed: '+e.message);console.error(e);refreshStatus();}
}
async function connect(){if(!navigator.serial){toast('Use Chromium with Web Serial on localhost or HTTPS.');return;}try{
 if(!db)db=await openDb();port=await navigator.serial.requestPort();await port.open({baudRate:115200});
    usbLog.length=0;usbHandshakeIssue='';usbSawBeacon=false;usbHandshakeStarted=performance.now();usbLastRawAt=0;clearUsbHint();
    writer=port.writable.getWriter();refreshStatus();readPromise=readLoop();
    logUsb('HOST> STATUS');await writeLine('STATUS');
    handshakeTimer=setInterval(()=>{if(!verified&&writer){logUsb('HOST> STATUS (retry)');writeLine('STATUS').catch(()=>{});}},1200);
    usbWaitTimer=setInterval(updateUsbWait,1200);
 }catch(e){toast('USB connection failed: '+e.message);await disconnect();}}
async function disconnect(){if(closing)return;closing=true;
 speedDiagnostics.serial?.reject(Error('USB disconnected during benchmark'));
 speedDiagnostics.full?.reject(Error('USB disconnected during benchmark'));
 speedDiagnostics.capable=false;speedDiagnostics.sackCapable=false;clearSelectiveAcks();
 try{if(handshakeTimer)clearInterval(handshakeTimer);handshakeTimer=null;if(usbWaitTimer){clearInterval(usbWaitTimer);usbWaitTimer=null;}if(reader)try{await reader.cancel();}catch{}if(readPromise)try{await readPromise;}catch{}if(writer){try{writer.releaseLock();}catch{}writer=null;}if(port){try{await port.close();}catch{}port=null;}}
 finally{
  finishNodeKeyRequest('get',null,Error('Disconnected'));finishNodeKeyRequest('set',null,Error('Disconnected'));
  readPromise=null;serialBuffer='';verified=false;port=null;writer=null;clearUsbHint();if(pendingRadio())failPendingRadio(Error('Disconnected'));if(activeTransfer?.record)void setState(activeTransfer.record,'failed','Disconnected');
 transferQueue=[];runningTransfers=false;queuedReceipts.clear();pendingReads.clear();incoming.clear();localMac=null;secretKey=null;radioTelemetry.available=false;refreshStatus();closing=false;}}
async function sendText(){const input=$('messageInput'),text=input.value.trim();if(!verified||!secretKey||!text)return;
 if(countChars(text)>MAX_CHARS){toast('Maximum 500 characters per text message.');return;}
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
async function installPairKey(raw){
 if(!verified||!localMac)throw Error('Connect a node first');
 if(runningTransfers||pendingRadio()||transferQueue.length)throw Error('Finish active transfers before changing keys');
 if(secretKey&&!confirm('Replace the key stored on this ESP32? Both ESP32 nodes must use the SAME key. Encrypted history will be migrated locally when possible.'))return false;
 const key=await P.importKey(raw);
 /* Verify that existing history can be decrypted before changing the node key. */
 const current=secretKey||(await dbGet('keys',macKey()))?.key||null;
 let rows=[];
 if(current){rows=await listStored();for(const row of rows)await P.openAtRest(current,row.seal,macKey(),row.id);}
 await setNodeKey(raw);
 if(current&&rows.length)await rewrapHistory(current,key);
 await dbDelete('keys',macKey());
 secretKey=key;
 await migrateLegacy();await hydrateHistory();refreshStatus();sendReadReceipts();return true;
}
function showPairing(){
 if(!verified){toast('Connect your ESP32 first.');return;}
 showDialog('Node pairing key',body=>{
  body.append(el('p','security-intro','The 256-bit AES key is stored in this ESP32’s NVS memory, not browser storage. Connected browsers retrieve it over the local USB cable into RAM. Pair both ESP32 nodes ONCE with the same key. Physical USB/flash access to a node can expose the key.'));
  const indicator=el('div','pair-status',secretKey?'🔒 Key stored on this ESP32':'⚠ No key stored on this ESP32');body.append(indicator);
  const field=document.createElement('textarea');field.className='pair-key-field';field.rows=2;field.placeholder='Paste the same 43-character key on the second node';field.autocomplete='off';field.spellcheck=false;body.append(field);
  const actions=el('div','pair-actions'),gen=el('button','primary-button','Generate and save'),join=el('button','quiet-button','Save pasted key'),copy=el('button','quiet-button','Copy key'),reveal=el('button','quiet-button','Show node key');
  copy.disabled=true;actions.append(gen,join,copy,reveal);body.append(actions);
  const help=el('p','','One-time setup: Generate on Board A, copy and install on Board B. Future browsers and computers connected by USB automatically load the node key. A full flash erase removes it; normal firmware flashing usually preserves NVS.');body.append(help);
  gen.onclick=async()=>{
   const raw=P.randomBytes(32);
   try{if(await installPairKey(raw)){
     field.value=P.base64url(raw);copy.disabled=false;
     indicator.textContent='🔒 Key saved on ESP32 NVS. Copy it to the other ESP32 once.';
     toast('256-bit key saved in ESP32 nonvolatile memory.');
   }}catch(e){toast('Could not save node key: '+e.message);}
  };
  join.onclick=async()=>{try{
    const raw=P.fromBase64url(field.value.trim());
    if(await installPairKey(raw)){closeDialog();toast('Pairing key saved on ESP32. Future browser sessions load it automatically.');}
   }catch(e){toast('Could not install key: '+e.message);}};
  reveal.onclick=async()=>{try{const raw=await getNodeKey();if(!raw){toast('This node has no saved key.');return;}field.value=P.base64url(raw);copy.disabled=false;toast('Key available for pairing the second board. Keep it private.');}catch(e){toast('Could not read node key: '+e.message);}};
  copy.onclick=async()=>{try{await navigator.clipboard.writeText(field.value.trim());toast('Copied. Clear your clipboard after pairing.');}catch{field.focus();field.select();toast('Select and copy manually.');}};
 });
}
async function migrateLegacy(){const oldKey='offgrid_phase13_history_'+macKey().replace(/:/g,'-');let raw=null;try{raw=localStorage.getItem(oldKey);}catch{}if(!raw)return;
 try{const old=JSON.parse(raw);if(!Array.isArray(old))return;for(const m of old.slice(-250)){
   if(typeof m.text!=='string'||!['in','out'].includes(m.direction))continue;
   const id=P.newId();const r={id,kind:'text',dir:m.direction,ts:m.ts||Date.now(),text:m.text,status: m.direction==='in'?'received':(m.status==='read'?'read':m.status==='delivered'?'delivered':'sent'),readSent:true,deliveredAckSent:true};await storeRecord(r);
  }localStorage.removeItem(oldKey);toast('Previous Phase 1.3 text history migrated into encrypted local storage.');
 }catch(e){console.warn('Legacy migration failed',e);}}
function showRename(){showDialog('Contact name',body=>{body.append(el('p','','This is a local nickname for the remote node.'));const field=document.createElement('input');field.value=activeContact;field.maxLength=36;body.append(field);const save=el('button','primary-button','Save contact name');save.onclick=()=>{setName(field.value.trim());persistName();closeDialog();};body.append(el('div','modal-actions')).append(save);setTimeout(()=>{field.focus();field.select();},40);});}
async function clearHistory(){if(!verified||!secretKey)return;if(runningTransfers||pendingRadio()||transferQueue.length){toast('Finish active transfers before clearing history.');return;}if(!confirm('Delete encrypted chat history for this ESP32 from this browser? This cannot be undone.'))return;
 await wipeStored();records=[];recordMap.clear();revokePhotos();renderHistory();toast('Local chat history cleared.');closeDialog();}
function showInfo(){showDialog('Chat information',body=>{[['Contact',activeContact],['Your ESP32',localMac||'Disconnected'],['Protocol','ESP-NOW firmware protocol 2 / encrypted app protocol OGM4'],['Security',secretKey?'AES-256-GCM, 256-bit random shared key':'Not paired'],['Photo target',`${Math.round(photoBudget()/1024)} KB optimized, 96 KB absolute`],['Radio PHY',radioTelemetry.available?`${radioTelemetry.phy} Mbps TX, RSSI ${radioTelemetry.rssi===null?'unknown':radioTelemetry.rssi+' dBm'}`:'Waiting for telemetry'],['Link profile',`${linkPreference}; adaptive PHY; photo window ${chosenWindow({kind:'image',control:false})}; auto ${adaptivePhotos?'on':'off'}`],['Key storage','ESP32 NVS, browser RAM only'],['History','AES-GCM encrypted IndexedDB']].forEach(([a,b])=>{const line=el('div','info-line');line.append(el('span','',a),el('strong','',b));body.append(line);});body.append(el('p','','Grey single tick = transmitted; grey double tick = peer decrypted and confirmed; blue double tick = peer app reported it was viewed. Browser history is stored encrypted at rest.'));});}
function showSettings(){showDialog('Settings',body=>{body.append(el('p','','Experimental Phase 1.4.11: Espressif LR 500/250 kbps with existing 1/6/12 Mbps adaptive modes. 1.4.10.2 remains the frozen baseline. Test text before photos.'));
 const linkLabel=el('label','','Transfer profile');body.append(linkLabel);const linkSelect=document.createElement('select');linkSelect.className='setting-select';
 [['auto','Auto · 12 / 6 / 1 Mbps, LR500 / LR250 when weak'],['range','Standard 1 Mbps · conservative non-LR'],['speed','Speed first · prefer faster radio rates'],['lr500','Manual LR 500 kbps · outdoor distance test'],['lr250','Manual LR 250 kbps · maximum sensitivity test']].forEach(([v,t])=>{const opt=document.createElement('option');opt.value=v;opt.textContent=t;linkSelect.append(opt);});
 linkSelect.value=linkPreference;linkSelect.onchange=()=>{linkPreference=linkSelect.value;try{localStorage.setItem('offgrid_link_preference',linkPreference);}catch{}if(verified)void writeLine('RATE '+linkPreference.toUpperCase()).catch(()=>{});updateLinkBadge();};body.append(linkSelect);
 const flowLabel=el('label','','Photo transfer throughput');body.append(flowLabel);const flowSelect=document.createElement('select');flowSelect.className='setting-select';
 [['on','Automatic · up to 6 in-flight batch fragments on strong links'],['off','Stable · legacy one-fragment transfer (instant rollback)']].forEach(([v,t])=>{const opt=document.createElement('option');opt.value=v;opt.textContent=t;flowSelect.append(opt);});
 flowSelect.value=adaptivePhotos?'on':'off';flowSelect.onchange=()=>{adaptivePhotos=flowSelect.value==='on';try{localStorage.setItem('offgrid_adaptive_photos',adaptivePhotos?'on':'off');}catch{}updateLinkBadge();};body.append(flowSelect);
 const sackLabel=el('label','','Photo receipt protocol (new in 1.4.10.3)');body.append(sackLabel);
 const sackSelect=document.createElement('select');sackSelect.className='setting-select';
 [['on','Selective batch ACK · up to 6 fragments per confirmation'],['off','Legacy · acknowledge every fragment (instant fallback)']].forEach(([v,t])=>{
   const opt=document.createElement('option');opt.value=v;opt.textContent=t;sackSelect.append(opt);
 });
 sackSelect.value=selectivePhotoAcks?'on':'off';
 sackSelect.onchange=()=>{selectivePhotoAcks=sackSelect.value==='on';try{localStorage.setItem('offgrid_sack_photos',selectivePhotoAcks?'on':'off');}catch{}updateLinkBadge();};
 body.append(sackSelect);
 body.append(el('p','','Batch ACK requires 1.4.10.3 firmware on both boards and the new web app on both browsers. It only applies to NEW photo sends. Set Legacy before sending a photo to revert without flashing. Ordinary text, read/delivery receipts and encryption stay unchanged.'));
 body.append(el('p','','Photo batching is isolated from text messaging and blue ticks. If transfers regress, disable Selective batch ACK or switch Photo transfer throughput to Stable. A full image is Delivered only after the receiver authenticates its AES-GCM payload.'));

 body.append(el('p','','Link quality comes from real browser acknowledgments and ESP32 RSSI, not distance. 1 Mbps broadcast discovers nearby nodes; saved peer MAC enables LR recovery probes if a link becomes unreachable at normal rates. Automatic PHY adapts using RSSI and retries; manual LR500/LR250 are for outdoor testing. Both boards need Phase 1.4.11 firmware. Text/receipts keep the same encryption and acknowledgment protocol.')); 
 const modeLabel=el('label','','Image optimization');body.append(modeLabel);const select=document.createElement('select');select.className='setting-select';
 [['hq','Optimized WebP/JPEG (adaptive 32–60 KB target, may resize)'],['lossless','Lossless PNG + metadata stripping (reject if >96 KB)']].forEach(([v,t])=>{const opt=document.createElement('option');opt.value=v;opt.textContent=t;select.append(opt);});select.value=imageMode;select.onchange=()=>{imageMode=select.value;try{localStorage.setItem('offgrid_image_mode',imageMode);}catch{}updateLinkBadge();};body.append(select);
 body.append(el('p','','Metadata stripping does not make photos tiny. High-quality mode can reduce dimensions and introduce slight loss; lossless PNG keeps the canvas pixels exact but may not fit through this radio link.'));
 const buttons=el('div','settings-actions'),rename=el('button','quiet-button','Rename contact'),key=el('button','quiet-button','Node pairing key'),diagnostics=el('button','quiet-button','USB diagnostics'),speedTest=el('button','primary-button','Speed diagnostics'),clear=el('button','soft-button','Clear history');rename.onclick=showRename;key.onclick=showPairing;diagnostics.onclick=diagnoseUsb;speedTest.onclick=showSpeedDiagnostics;clear.onclick=()=>void clearHistory();buttons.append(speedTest,rename,key,diagnostics,clear);body.append(buttons);
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
$('contactButton').onclick=()=>{document.body.classList.add('mobile-chat');scrollToNewest(true);sendReadReceipts();};$('mobileBack').onclick=()=>document.body.classList.remove('mobile-chat');
$('closeModal').onclick=closeDialog;$('modalBackdrop').onclick=e=>{if(e.target===$('modalBackdrop'))closeDialog();};document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDialog();});
document.addEventListener('visibilitychange',sendReadReceipts);window.addEventListener('focus',sendReadReceipts);
window.addEventListener('beforeunload',revokePhotos);
setInterval(pruneIncoming,45000);
(async()=>{try{db=await openDb();}catch(e){toast('IndexedDB unavailable. Encrypted storage cannot work: '+e.message);}refreshStatus();})();
console.info('OffGrid Phase 1.4.10.4 — auto-scroll maintenance on selective-ACK web app; unchanged ESP-IDF firmware.');
