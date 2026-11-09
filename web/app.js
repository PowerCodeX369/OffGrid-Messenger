'use strict';
const $=id=>document.getElementById(id);
let port=null,reader=null,writer=null,readLoopPromise=null,closing=false,serialBuffer='';
const encoder=new TextEncoder(),decoder=new TextDecoder('utf-8',{fatal:false});
let pendingLocal=[];
const messages=new Map(),seenIncoming=new Set(),pendingReceipts=new Map();
let writeChain=Promise.resolve();
function status(connected,label){$('status').textContent=label;$('dot').classList.toggle('online',connected);$('connect').disabled=connected;$('disconnect').disabled=!connected;$('message').disabled=!connected;$('send').disabled=!connected;}
function bubble(text,kind='system',stateLabel=''){
  const el=document.createElement('div');el.className='bubble '+kind;
  const body=document.createElement('div');body.textContent=text;el.appendChild(body);
  const meta=document.createElement('div');meta.className='meta';
  const time=document.createElement('span');time.textContent=new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});meta.appendChild(time);
  if(kind==='mine'){const state=document.createElement('span');state.className='msg-state';state.textContent=stateLabel;meta.appendChild(state);el.dataset.state=stateLabel;}
  el.appendChild(meta);$('messages').appendChild(el);$('messages').scrollTop=$('messages').scrollHeight;return el;
}
function setBubbleState(el,state){if(!el)return;const s=el.querySelector('.msg-state');if(s)s.textContent=state;el.dataset.state=state;}
function idKey(session,id){return `${session}:${id}`;}
function fromHex(hex){if(hex.length%2||!/^[0-9a-f]*$/i.test(hex))throw new Error('Invalid hex');const bytes=Uint8Array.from(hex.match(/.{2}/g)?.map(s=>parseInt(s,16))||[]);return decoder.decode(bytes);}
function hexFromString(s){return [...encoder.encode(s)].map(b=>b.toString(16).padStart(2,'0')).join('').toUpperCase();}
function validMac(s){return /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/i.test(s);}
function sendCommand(line){const task=writeChain.then(async()=>{if(!writer)throw new Error('USB not connected');await writer.write(encoder.encode(line+'\n'));});writeChain=task.catch(()=>{});return task;}
function requestAck(mac,session,id){const cmd=`APPACK ${mac} ${session} ${id}`;sendCommand(cmd).catch(e=>bubble('Could not confirm incoming message: '+e.message));}
function parseLine(raw){
  const line=raw.trim();if(!line)return;
  if(line.startsWith('READY|')){const parts=line.split('|');status(true,`Connected • ${parts[1]} • channel 6 • protocol 2`);$('device').textContent=parts[1]||'Unknown';return;}
  if(line.startsWith('QUEUED|')){
    const [,session,id,hex]=line.split('|');const idx=pendingLocal.findIndex(p=>p.hex===hex);
    if(idx>=0){const [item]=pendingLocal.splice(idx,1);messages.set(idKey(session,id),item.el);setBubbleState(item.el,'◷ Queued');}
    return;
  }
  if(line.startsWith('RX|')){
    const [,mac,session,id,hex]=line.split('|');if(!validMac(mac)||!/^\d+$/.test(session)||!/^\d+$/.test(id))return;
    const key=`${mac}:${session}:${id}`;
    if(!seenIncoming.has(key)){
      try{bubble(fromHex(hex),'theirs');seenIncoming.add(key);}catch{bubble('Received malformed data');return;}
    }
    requestAck(mac,session,id);return;
  }
  if(/^(SENT|DELIVERED|FAILED|RETRY|RADIO_ERROR)\|/.test(line)){
    const [type,session,id,extra]=line.split('|');const el=messages.get(idKey(session,id));
    if(type==='SENT')setBubbleState(el,'✓ Sent');
    if(type==='DELIVERED')setBubbleState(el,'✓✓ Delivered');
    if(type==='FAILED')setBubbleState(el,'! Failed');
    if(type==='RADIO_ERROR')$('status').textContent='Radio issue: '+extra;
    if(type==='RETRY'&&el&&el.dataset.state!=='✓✓ Delivered')setBubbleState(el,'↻ Retrying '+(Number(extra)-1));
    return;
  }
  if(line.startsWith('ERROR|'))bubble('Device: '+line.slice(6));
}
function consumeChunk(bytes){serialBuffer+=decoder.decode(bytes,{stream:true});while(serialBuffer.includes('\n')){const at=serialBuffer.indexOf('\n');parseLine(serialBuffer.slice(0,at));serialBuffer=serialBuffer.slice(at+1);}if(serialBuffer.length>8192)serialBuffer='';}
async function readLoop(){
  try{while(port?.readable){reader=port.readable.getReader();try{while(true){const r=await reader.read();if(r.done)break;if(r.value)consumeChunk(r.value);}}finally{reader.releaseLock();reader=null;}}}
  catch(e){if(!closing)bubble('USB disconnected: '+e.message);}
  finally{if(!closing){readLoopPromise=null;void disconnect();}}
}
async function connect(){
  if(!('serial' in navigator)){bubble('Web Serial needs a supported Chromium browser on localhost.');return;}
  try{port=await navigator.serial.requestPort();await port.open({baudRate:115200});writer=port.writable.getWriter();status(true,'USB connected • checking firmware');readLoopPromise=readLoop();await sendCommand('STATUS');}
  catch(e){bubble('Connection failed: '+e.message);await disconnect();}
}
async function disconnect(){
  if(closing)return;closing=true;
  try{if(reader)try{await reader.cancel();}catch{};if(readLoopPromise)try{await readLoopPromise;}catch{};
    if(writer){try{writer.releaseLock();}catch{}writer=null;}
    if(port){try{await port.close();}catch{}port=null;}
  }finally{readLoopPromise=null;serialBuffer='';pendingLocal.forEach(p=>setBubbleState(p.el,'! Disconnected'));pendingLocal=[];status(false,'Disconnected');$('device').textContent='—';closing=false;}
}
$('connect').addEventListener('click',connect);$('disconnect').addEventListener('click',disconnect);
$('sendForm').addEventListener('submit',async e=>{
  e.preventDefault();const text=$('message').value.trim();if(!text)return;
  if(text.includes('\n')||text.includes('\r')){bubble('Single-line messages only in Phase 1.1');return;}
  const bytes=encoder.encode(text);if(bytes.length>180){bubble('Maximum 180 UTF-8 bytes per message');return;}
  const hex=hexFromString(text),el=bubble(text,'mine','◷ Sending');
  const entry={hex,el};pendingLocal.push(entry);$('message').value='';
  try{await sendCommand('MSGHEX '+hex);}catch(err){pendingLocal=pendingLocal.filter(p=>p!==entry);setBubbleState(el,'! USB error');bubble('Send failed: '+err.message);}
});
