'use strict';
/* Run from project root: node tests/test-protocol.cjs */
const assert=require('node:assert/strict');
const P=require('../../web/protocol.js');
(async function run(){
 const key=await P.importKey(P.randomBytes(32));
 for(const [name,type,data] of [
  ['short text',P.types.text,P.textBytes('Hi from Node A')],
  ['500 Unicode characters',P.types.text,P.textBytes('🙂'.repeat(500))],
  ['96 KiB photo',P.types.image,P.concat(Uint8Array.of(0),P.randomBytes(96*1024))],
 ]){
   const msgId=P.newId(),transferId=P.newId();
   const plain=P.makeEnvelope(type,msgId,data),payload=await P.seal(key,plain,transferId);
   const packets=P.frames(transferId,payload),reconstituted=[];
   for(const pkt of packets){assert(pkt.length<=228);const rx=P.parseFrame(P.unhex(P.hex(pkt)));assert(rx);reconstituted[rx.index]=rx.bytes;}
   const opened=P.parseEnvelope(await P.open(key,P.concat(...reconstituted),transferId));
   assert.equal(opened.id,msgId);assert.equal(P.hex(opened.body),P.hex(data));
   assert.equal(opened.kind,type);
   const tampered=payload.slice();tampered[tampered.length-1]^=1;
   await assert.rejects(P.open(key,tampered,transferId),'GCM tamper must fail');
   await assert.rejects(P.open(await P.importKey(P.randomBytes(32)),payload,transferId),'Wrong key must fail');
   console.log('PASS',name,'—',data.length,'bytes,',packets.length,'acknowledged-sized frames');
 }
 const id=P.newId(),plain=P.textBytes('Sensitive local conversation');
 const sealed=await P.sealAtRest(key,plain,'NodeA',id);
 const recovered=await P.openAtRest(key,sealed,'NodeA',id);
 assert.equal(P.textFrom(recovered),'Sensitive local conversation');
 await assert.rejects(P.openAtRest(key,sealed,'NodeB',id));
 console.log('PASS encrypted local history with owner/message ID binding');
 const str=P.base64url(P.randomBytes(32));assert.equal(str.length,43);assert.equal(P.fromBase64url(str).length,32);
 console.log('PASS 256-bit pairing-key encoding');
})().catch(e=>{console.error(e);process.exitCode=1;});

/* Replay protection contract: app.js must ignore EDITs older than the last applied edit
 * and persist the edit timestamp, mirroring android MessageMutation.apply(sentAtMs). */
{
  const src=require('node:fs').readFileSync(require('node:path').join(__dirname,'../../web/app.js'),'utf8');
  require('node:assert/strict')(src.includes('if(env.ts<(r.editTs||0))return;'),'stale EDIT guard missing');
  require('node:assert/strict')(src.includes('editTs:r.editTs||0'),'editTs must be persisted');
  console.log('PASS stale/replayed EDIT guard present and persisted');
}
