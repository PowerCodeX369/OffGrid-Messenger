'use strict';
const $ = id => document.getElementById(id);
let port = null, reader = null, writer = null, currentRead = null;
const encoder = new TextEncoder();
const decoder = new TextDecoder();
let serialBuffer = '';

function state(connected, text) {
  $('status').textContent = text;
  $('dot').classList.toggle('online', connected);
  $('connect').disabled = connected;
  $('disconnect').disabled = !connected;
  $('message').disabled = !connected;
  $('send').disabled = !connected;
}
function addBubble(content, kind='system') {
  const bubble = document.createElement('div');
  bubble.className = 'bubble ' + kind;
  const body = document.createElement('div');
  body.textContent = content; // IMPORTANT: no HTML injection
  bubble.appendChild(body);
  const meta = document.createElement('div');
  meta.className = 'meta';
  meta.textContent = new Date().toLocaleTimeString();
  bubble.appendChild(meta);
  $('messages').appendChild(bubble);
  $('messages').scrollTop = $('messages').scrollHeight;
}
function parseLine(raw) {
  const line = raw.trim();
  if (!line) return;
  if (line.startsWith('RX|')) {
    const firstSep = line.indexOf('|',3);
    if (firstSep > 0) addBubble(line.slice(firstSep + 1),'theirs');
  } else if (line.startsWith('READY|')) {
    const parts = line.split('|');
    state(true, 'Connected to ' + (parts[1] || 'ESP32') + ' • Channel 6');
    addBubble('ESP32 ready: ' + (parts[1] || 'unknown'));
  } else if (line.startsWith('PEER|')) {
    addBubble(line.replaceAll('|',' · '));
  } else if (line.startsWith('ERROR|')) {
    addBubble(line.slice(6));
  }
}
function consumeChunk(chunk) {
  serialBuffer += decoder.decode(chunk, {stream:true});
  while(serialBuffer.includes('\n')) {
    const idx=serialBuffer.indexOf('\n');
    parseLine(serialBuffer.slice(0,idx));
    serialBuffer=serialBuffer.slice(idx+1);
  }
  if(serialBuffer.length>4096)serialBuffer='';
}
async function receiveLoop() {
  try {
    while (port?.readable) {
      reader=port.readable.getReader();
      try {
        while (true) {
          const {value,done}=await reader.read();
          if(done)break;
          if(value)consumeChunk(value);
        }
      } finally {reader.releaseLock();reader=null;}
    }
  } catch(error) {addBubble('Serial connection lost: '+error.message);}
  finally {await closePort();}
}
async function openPort(){
  if(!('serial' in navigator)){addBubble('Web Serial is unavailable. Use recent Chrome/Chromium on localhost.');return;}
  try {
    port=await navigator.serial.requestPort();
    await port.open({baudRate:115200});
    writer=port.writable.getWriter();
    state(true,'USB connected • waiting for ESP32');
    addBubble('Serial connected. Send PING or a message.');
    currentRead=receiveLoop();
  } catch(e){addBubble('Could not connect: '+e.message);await closePort();}
}
let closing=false;
async function closePort(){
  if(closing)return;
  closing=true;
  try{
    if(reader){try{await reader.cancel();}catch{}}
    if(currentRead && reader){/* read loop will release lock */}
    if(writer){try{writer.releaseLock();}catch{}writer=null;}
    if(port){try{await port.close();}catch{}port=null;}
  } finally {state(false,'Disconnected');closing=false;}
}
async function sendCommand(command){
  if(!writer)throw new Error('Not connected');
  await writer.write(encoder.encode(command+'\n'));
}
$('connect').addEventListener('click',openPort);
$('disconnect').addEventListener('click',closePort);
$('sendForm').addEventListener('submit',async event=>{
  event.preventDefault();
  const message=$('message').value.trim();
  if(!message)return;
  if(encoder.encode(message).length>180){addBubble('Message exceeds 180 UTF-8 bytes');return;}
  try{
    await sendCommand('MSG '+message);
    addBubble(message,'mine');
    $('message').value='';
  }catch(e){addBubble('Send failed: '+e.message);}
});
