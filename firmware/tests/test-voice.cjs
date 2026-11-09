const assert=require('node:assert/strict');
const P=require('../web/protocol.js');
(async()=>{
 const key=await P.importKey(P.randomBytes(32));
 assert.equal(P.VOICE_MAX_MS,120000);
 assert.equal(P.VOICE_MAX,384*1024);
 assert.equal(P.types.played,8);
 for(const type of ['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/mp4']){
  for(const [length,duration] of [[32000,12345],[250000,105000],[P.VOICE_MAX,120000]]){
   const bytes=P.randomBytes(length),packed=P.packVoice(type,bytes);
   const id=P.newId(),env=P.makeEnvelope(P.types.voice,id,packed,1723450000,duration&65535,Math.floor(duration/65536));
   const cipher=await P.seal(key,env,id),fs=P.frames(id,cipher,{batch:true});
   assert.equal(fs.length,Math.ceil(cipher.length/P.CHUNK));
   const reconstructed=P.concat(...fs.map(f=>P.parseFrame(f).bytes));
   const dec=P.parseEnvelope(await P.open(key,reconstructed,id));
   assert.equal(dec.kind,P.types.voice);
   const ms=dec.width+dec.height*65536;
   const msg=P.unpackVoice(dec.body,ms);
   assert.deepEqual(msg.bytes,bytes);
   assert.equal(msg.durationMs,duration);
   assert.equal(msg.mime,type.split(';')[0]);
   const damaged=reconstructed.slice();damaged[27]^=4;
   await assert.rejects(()=>P.open(key,damaged,id));
  }
 }
 assert.throws(()=>P.packVoice('application/zip',P.randomBytes(300)));
 assert.throws(()=>P.packVoice('audio/webm',P.randomBytes(P.VOICE_MAX+1)));
 assert.throws(()=>P.unpackVoice(P.packVoice('audio/webm',P.randomBytes(300)),120001));
 // Compatibility with Phase 1.5's original 16-bit duration field.
 const oldMs=29840,oldId=P.newId(),old=P.parseEnvelope(P.makeEnvelope(P.types.voice,oldId,P.packVoice('audio/webm',P.randomBytes(600)),1700000000,oldMs,0));
 assert.equal(P.unpackVoice(old.body,old.width+old.height*65536).durationMs,oldMs);
 // A receipt is bound to a genuine message id and authenticated by AES-GCM.
 const playedId=P.newId(),receiptId=P.newId();
 const receipt=P.makeEnvelope(P.types.played,receiptId,P.unhex(playedId));
 const cipher=await P.seal(key,receipt,receiptId),opened=P.parseEnvelope(await P.open(key,cipher,receiptId));
 assert.equal(opened.kind,P.types.played);assert.equal(P.hex(opened.body),playedId);
 const corrupted=cipher.slice();corrupted[30]^=0x80;
 await assert.rejects(()=>P.open(key,corrupted,receiptId));
 console.log('PASS: voice 120000 ms, 384 KiB limit, 2400+ fragments, original 30s compatibility, authenticated played receipt, tamper protection');
})().catch(e=>{console.error(e);process.exitCode=1;});
