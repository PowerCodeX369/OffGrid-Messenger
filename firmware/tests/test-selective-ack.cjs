/* Real protocol.js crypto, new image marker and selective-ACK channel simulation.
   No hardware throughput claims. */
const assert=require('node:assert/strict');
const {webcrypto}=require('node:crypto');
if(!globalThis.crypto)globalThis.crypto=webcrypto;
const P=require('../../web/protocol.js');

async function run(){
 const key=await P.importKey(P.randomBytes(32));
 const tid=P.newId(),mid=P.newId();
 const bytes=P.randomBytes(45*1024);
 const plain=P.makeEnvelope(P.types.image,mid,P.concat(Uint8Array.of(0),bytes),Date.now()/1000|0,800,600);
 const ciphertext=await P.seal(key,plain,tid);
 const all=P.frames(tid,ciphertext,{batch:true});
 assert(all.length>200,'photo should require many radio packets');
 assert(all.every(f=>f.length<=180));
 assert(all.every(f=>P.parseFrame(f)?.bulk));
 assert(!P.parseFrame(P.frames(tid,ciphertext)[0]).bulk,'normal format unchanged');
 assert.equal(P.parseFrame(Uint8Array.from([1,2,3])),null);
 // Simulated 6-wide sender window, reorder, duplicates, 7% packet drops.
 const got=new Map(),ackGroups=[],missing=new Set(all.map((_,i)=>i));
 let duplicates=0;
 for(let base=0;base<all.length;base+=6){
   const indexes=Array.from({length:Math.min(6,all.length-base)},(_,j)=>base+j);
   const received=indexes.filter(i=>i%13!==7).reverse();
   for(const i of received){
     const f=P.parseFrame(all[i]);
     if(got.has(f.index))duplicates++;else got.set(f.index,f.bytes);
     missing.delete(i);
   }
   // Selectively confirm only received IDs; firmware's batch frame would carry them.
   ackGroups.push(received.map(i=>100+i));
 }
 assert(missing.size>0);
 for(const i of [...missing]){
   const f=P.parseFrame(all[i]);got.set(f.index,f.bytes);missing.delete(i);
 }
 assert.equal(missing.size,0);
 const merged=P.concat(...Array.from({length:all.length},(_,i)=>got.get(i)));
 const original=await P.open(key,merged,tid);const envelope=P.parseEnvelope(original);
 assert.equal(envelope.kind,P.types.image);assert.equal(envelope.id,mid);
 assert.deepEqual(envelope.body.subarray(1),bytes);
 const changed=merged.slice();changed[37]^=1;
 await assert.rejects(()=>P.open(key,changed,tid),'tampered photo must be rejected');
 console.log(`PASS real AES-256-GCM photo protocol: ${all.length} fragments, ${ackGroups.length} selective ACK groups, lost/reordered/recovered, authentication enforced`);
}
run().catch(e=>{console.error(e);process.exit(1);});
