const assert=require('node:assert/strict');
const P=require('../web/protocol.js');
(async()=>{
 const key=await P.importKey(P.randomBytes(32)),target=P.newId();
 for(const [kind,body] of [
  [P.types.edit,P.concat(P.unhex(target),P.textBytes('Revised message ✓'))],
  [P.types.delete,P.unhex(target)]
 ]){
  const mid=P.newId(),transfer=P.newId();
  const plain=P.makeEnvelope(kind,mid,body),secret=await P.seal(key,plain,transfer);
  const frames=P.frames(transfer,secret);
  const reassembled=P.concat(...frames.map(x=>P.parseFrame(x).bytes));
  const received=P.parseEnvelope(await P.open(key,reassembled,transfer));
  assert.equal(received.kind,kind);assert.equal(P.hex(received.body),P.hex(body));
  assert.equal(P.hex(received.body.subarray(0,8)),target);
  const tampered=secret.slice();tampered[tampered.length-1]^=1;
  await assert.rejects(()=>P.open(key,tampered,transfer));
 }
 console.log('PASS Web ↔ Android mutation envelope kinds, target IDs, AES-GCM integrity');
})().catch(e=>{console.error(e);process.exitCode=1});
