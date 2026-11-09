const fs=require('fs');const assert=require('node:assert/strict');
const P=require('./reference_web_protocol.cjs');
const {webcrypto}=require('node:crypto');globalThis.crypto=webcrypto;
(async()=>{
 const lines=fs.readFileSync(process.argv[2],'utf8').trim().split('\n');
 const key=await P.importKey(new Uint8Array(Array.from({length:32},(_,i)=>i)));
 for(const [prefix,kind,codec,width,height,size] of [['JPEG',2,2,1000,800,2048],['VOICE',7,2,78002&65535,78002>>>16,4024]]){
  const line=lines.find(x=>x.startsWith(prefix+':'));assert(line,`missing ${prefix}`);
  const buffers=line.slice(prefix.length+1).split(',').map(P.unhex);
  const parsed=buffers.map(P.parseFrame);assert(parsed.every(Boolean));
  assert.equal(parsed.length,Math.ceil(parsed[0].length/160));
  const joined=P.concat(...parsed.map(x=>x.bytes));
  const decrypted=await P.open(key,joined,parsed[0].transferId);
  const env=P.parseEnvelope(decrypted);
  assert.equal(env.kind,kind);assert.equal(env.width,width);assert.equal(env.height,height);
  assert.equal(env.body.length,size+1);assert.equal(env.body[0],codec);
  if(kind===7){const voice=P.unpackVoice(env.body,env.width+env.height*65536);assert.equal(voice.mime,'audio/mp4');assert.equal(voice.durationMs,78002)}
  console.log('PASS web decrypts Kotlin '+prefix+' AES-GCM media and reassembles '+buffers.length+' OGC4 frames');
 }
})().catch(e=>{console.error(e);process.exit(1)});
