const assert=require('node:assert/strict');const cp=require('node:child_process');
const P=require('../web/protocol.js');
(async()=>{
 const jar=process.env.CROSSWIRE_JAR;assert(jar,'CROSSWIRE_JAR required');
 const key=Uint8Array.from({length:32},(_,i)=>i);
 const imported=await P.importKey(key);
 const kotlin=cp.execFileSync('java',['-jar',jar,'emit'],{encoding:'utf8'}).trim().split(/\n/);
 assert.equal(kotlin.length,2);
 for(const row of kotlin){
  const [type,frameStr]=row.split(':');const frames=frameStr.split(',').map(P.unhex);
  const metadata=frames.map(P.parseFrame);const payload=P.concat(...metadata.map(x=>x.bytes));
  const env=P.parseEnvelope(await P.open(imported,payload,metadata[0].transferId));
  assert.equal(env.kind,Number(type));assert.equal(P.hex(env.body.subarray(0,8)),'0102030405060708');
 }
 for(const type of [P.types.edit,P.types.delete]){
  const transfer=P.newId(),message=P.newId();const target=P.unhex('0102030405060708');
  const body=type===P.types.delete?target:P.concat(target,P.textBytes('Edited from browser ✓'));
  const secret=await P.seal(imported,P.makeEnvelope(type,message,body),transfer);
  const result=cp.execFileSync('java',['-jar',jar,'verify',transfer,P.hex(secret),String(type)],{encoding:'utf8'});
  assert(result.includes('PASS Kotlin'));
 }
 console.log('PASS real Kotlin ⇄ WebCrypto OGM4 authenticated edit/delete wire interoperability');
})().catch(e=>{console.error(e);process.exitCode=1;});
