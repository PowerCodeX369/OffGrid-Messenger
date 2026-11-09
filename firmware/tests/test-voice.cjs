const assert=require('node:assert/strict');
const P=require('../web/protocol.js');
(async()=>{
 const key=await P.importKey(P.randomBytes(32));
 for(const type of ['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/mp4']){
  const bytes=P.randomBytes(32000),packed=P.packVoice(type,bytes);
  const id=P.newId(),env=P.makeEnvelope(P.types.voice,id,packed,1723450000,12345,0);
  const cipher=await P.seal(key,env,id),fs=P.frames(id,cipher,{batch:true});
  assert.equal(fs.length,Math.ceil(cipher.length/P.CHUNK));
  const reconstructed=P.concat(...fs.map(f=>P.parseFrame(f).bytes));
  const dec=P.parseEnvelope(await P.open(key,reconstructed,id));
  assert.equal(dec.kind,P.types.voice);
  const msg=P.unpackVoice(dec.body,dec.width);
  assert.deepEqual(msg.bytes,bytes);
  assert.equal(msg.durationMs,12345);
  assert.equal(msg.mime,type.split(';')[0]);
  const damaged=reconstructed.slice();damaged[27]^=4;
  await assert.rejects(()=>P.open(key,damaged,id));
 }
 assert.throws(()=>P.packVoice('application/zip',P.randomBytes(300)));
 assert.throws(()=>P.packVoice('audio/webm',P.randomBytes(P.VOICE_MAX+1)));
 assert.throws(()=>P.unpackVoice(P.packVoice('audio/webm',P.randomBytes(300)),31000));
 console.log('PASS: encrypted 3-codec voice wire format, 160B fragmentation, tamper rejection, payload+duration bounds');
})().catch(e=>{console.error(e);process.exitCode=1;});
