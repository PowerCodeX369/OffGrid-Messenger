/* OffGrid OGM4 browser application layer. No external dependencies. */
(function(root){
'use strict';
const enc=new TextEncoder(), dec=new TextDecoder('utf-8',{fatal:true});
const HEX='0123456789ABCDEF';
/* Only 160-byte radio chunks are ever sent to ESP32; large voice notes are
 * reassembled and authenticated in the browsers, not buffered by the MCUs. */
const CHUNK=160, FRAME_HEADER=20, FRAME_MAX=180, MAX_BLOB=384*1024+1, MAX_PACKET=MAX_BLOB+24+28;
const types=Object.freeze({text:1,image:2,delivered:3,read:4,probe:5,probeAck:6,voice:7,played:8,edit:9,delete:10});
const randomBytes=(n)=>{const b=new Uint8Array(n);for(let i=0;i<n;i+=65536)crypto.getRandomValues(b.subarray(i,Math.min(i+65536,n)));return b;};
const hex=(bytes)=>Array.from(bytes,b=>HEX[b>>4]+HEX[b&15]).join('');
function unhex(value){if(typeof value!=='string'||value.length%2||!/^[a-f0-9]*$/i.test(value))throw Error('Invalid hex');const a=new Uint8Array(value.length/2);for(let i=0;i<a.length;i++)a[i]=parseInt(value.slice(i*2,i*2+2),16);return a;}
const concat=(...arrays)=>{const n=arrays.reduce((v,a)=>v+a.byteLength,0),out=new Uint8Array(n);let p=0;for(const a of arrays){out.set(a,p);p+=a.byteLength;}return out;};
const newId=()=>hex(randomBytes(8));
function base64url(bytes){let s='';for(let i=0;i<bytes.length;i+=0x4000)s+=String.fromCharCode(...bytes.subarray(i,i+0x4000));return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');}
function fromBase64url(s){if(!/^[A-Za-z0-9_-]{43}$/.test(s))throw Error('Pairing key must be a 43-character base64url string');const a=atob(s.replace(/-/g,'+').replace(/_/g,'/')+'=');if(a.length!==32)throw Error('Pairing key must be 256 bits');return Uint8Array.from(a,c=>c.charCodeAt(0));}
async function importKey(raw){if(!(raw instanceof Uint8Array)||raw.length!==32)throw Error('Expected a 32-byte key');return crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']);}
function aad(id,marker='OGM4'){return concat(enc.encode(marker),typeof id==='string'?unhex(id):id);}
async function seal(key,plain,transferId){const iv=randomBytes(12),body=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:aad(transferId),tagLength:128},key,plain));return concat(iv,body);}
async function open(key,payload,transferId){if(payload.length<28)throw Error('Ciphertext truncated');return new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:payload.subarray(0,12),additionalData:aad(transferId),tagLength:128},key,payload.subarray(12)));}
async function sealAtRest(key,plain,owner,mid){const iv=randomBytes(12),additionalData=enc.encode('OGM4-STORE|'+owner+'|'+mid),cipher=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData,tagLength:128},key,plain));return {iv,cipher};}
async function openAtRest(key,sealed,owner,mid){return new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:sealed.iv,additionalData:enc.encode('OGM4-STORE|'+owner+'|'+mid),tagLength:128},key,sealed.cipher));}
function makeEnvelope(kind,id,body,ts=Math.floor(Date.now()/1000),width=0,height=0){
  if(!Object.values(types).includes(kind))throw Error('Invalid message type');
  if(typeof id!=='string'||id.length!==16)throw Error('Invalid message ID');
  if(body.length>MAX_BLOB)throw Error('Payload too large');
  const h=new Uint8Array(24),v=new DataView(h.buffer);
  h[0]=0x4f;h[1]=0x47;h[2]=1;h[3]=kind;h.set(unhex(id),4);
  v.setUint32(12,ts>>>0,false);v.setUint16(16,width,false);v.setUint16(18,height,false);v.setUint32(20,body.length,false);
  return concat(h,body);
}
function parseEnvelope(data){
  if(data.length<24||data[0]!==0x4f||data[1]!==0x47||data[2]!==1)throw Error('Invalid envelope');
  const v=new DataView(data.buffer,data.byteOffset,data.byteLength),kind=data[3],id=hex(data.subarray(4,12));
  const size=v.getUint32(20,false);
  if(!Object.values(types).includes(kind)||size!==data.length-24||size>MAX_BLOB)throw Error('Invalid payload length/type');
  return {kind,id,ts:v.getUint32(12,false)*1000,width:v.getUint16(16,false),height:v.getUint16(18,false),body:data.subarray(24)};
}
function frames(transferId,payload,options={}){
  const batch=Boolean(options.batch);
  if(payload.length>MAX_PACKET)throw Error('Encrypted payload exceeds transfer limit');
  const id=unhex(transferId),total=Math.ceil(payload.length/CHUNK);if(!total||total>65535)throw Error('Invalid packet count');
  const result=[];
  for(let i=0;i<total;i++){
    const piece=payload.subarray(i*CHUNK,(i+1)*CHUNK),f=new Uint8Array(FRAME_HEADER+piece.length),v=new DataView(f.buffer);
    f.set(batch?[79,71,66,53]:[79,71,67,52],0);f.set(id,4);v.setUint16(12,i,false);v.setUint16(14,total,false);v.setUint32(16,payload.length,false);f.set(piece,20);result.push(f);
  }
  return result;
}
function parseFrame(data){
  if(data.length<FRAME_HEADER||data.length>FRAME_MAX||data[0]!==79||data[1]!==71)return null;
  const bulk=data[2]===66&&data[3]===53;
  if(!bulk&&(data[2]!==67||data[3]!==52))return null;
  const v=new DataView(data.buffer,data.byteOffset,data.byteLength),index=v.getUint16(12,false),count=v.getUint16(14,false),length=v.getUint32(16,false);
  if(!count||index>=count||length<28||length>MAX_PACKET||count!==Math.ceil(length/CHUNK))return null;
  const expect=index===count-1?length-index*CHUNK:CHUNK;
  if(data.length!==20+expect)return null;
  return {transferId:hex(data.subarray(4,12)),index,count,length,bytes:data.subarray(20),bulk};
}

/* Voice messages carry a one-byte authenticated codec tag inside the GCM
 * envelope. Durations travel in the existing envelope's width field (ms).
 * No unencrypted audio is ever sent to ESP-NOW or stored in browser history. */
const VOICE_MAX=384*1024,VOICE_MAX_MS=120000;
const voiceCodecs=Object.freeze([
  {id:0,mime:'audio/webm',preferred:'audio/webm;codecs=opus'},
  {id:1,mime:'audio/ogg',preferred:'audio/ogg;codecs=opus'},
  {id:2,mime:'audio/mp4',preferred:'audio/mp4'}
]);
function voiceCodecForMime(mime){
  if(typeof mime!=='string')return null;
  const name=mime.toLowerCase().split(';')[0].trim();
  return voiceCodecs.find(c=>c.mime===name)||null;
}
function packVoice(mime,data){
  const codec=voiceCodecForMime(mime),bytes=asBytes(data);
  if(!codec||bytes.length<256||bytes.length>VOICE_MAX)throw Error('Unsupported audio format or voice message too large');
  return concat(Uint8Array.of(codec.id),bytes);
}
function unpackVoice(body,durationMs){
  if(!body||body.length<257||body.length>VOICE_MAX+1||!Number.isInteger(durationMs)||durationMs<100||durationMs>VOICE_MAX_MS)
    throw Error('Invalid voice message');
  const codec=voiceCodecs.find(c=>c.id===body[0]);
  if(!codec)throw Error('Unrecognized audio format');
  return {mime:codec.mime,bytes:body.slice(1),durationMs};
}

const textBytes=s=>enc.encode(s);
const textFrom=bytes=>dec.decode(bytes);
const asBytes=x=>x instanceof Uint8Array?x:new Uint8Array(x);
const api={CHUNK,FRAME_MAX,MAX_BLOB,MAX_PACKET,VOICE_MAX,VOICE_MAX_MS,voiceCodecs,voiceCodecForMime,packVoice,unpackVoice,types,hex,unhex,concat,newId,randomBytes,base64url,fromBase64url,importKey,seal,open,sealAtRest,openAtRest,makeEnvelope,parseEnvelope,frames,parseFrame,textBytes,textFrom,asBytes};
root.OGP=api;if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this);
