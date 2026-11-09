/* Execute the actual browser's selective ACK scheduling functions without a DOM.
   Headless browser navigation is unavailable in this environment. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../web/app.js'),'utf8');
const start=source.indexOf('function flushSelectiveAck(key){');
const end=source.indexOf('async function processRx(',start);
assert(start>0 && end>start);
let writes=[];
const env={
  sackGroups:new Map(),SACK_GROUP_MAX:6,SACK_FLUSH_MS:35,
  verified:true,writer:{},setTimeout,clearTimeout,
  writeLine:async line=>{writes.push(line);}
};
vm.createContext(env);
vm.runInContext(source.slice(start,end),env);
const addr='1C:DB:D4:3B:00:FC', s='12345';
(async()=>{
  for(const id of [1,3,5,2,6,4])env.queueSelectiveAck(addr,s,String(id));
  await new Promise(r=>setTimeout(r,5));
  assert.deepEqual(writes,[`APPACKB ${addr} ${s} 1 3 5 2 6 4`]);
  for(const id of [7,7,8])env.queueSelectiveAck(addr,s,String(id));
  await new Promise(r=>setTimeout(r,65));
  assert.equal(writes.length,2);
  assert.equal(writes[1],`APPACKB ${addr} ${s} 7 8`);
  // Two sender boots must never be grouped in the same ACK frame.
  env.queueSelectiveAck(addr,'456','11');
  env.queueSelectiveAck(addr,'457','12');
  await new Promise(r=>setTimeout(r,65));
  assert.equal(writes.length,4);
  assert(writes.at(-2).includes('456 11') && writes.at(-1).includes('457 12'));
  env.clearSelectiveAcks();assert.equal(env.sackGroups.size,0);
  console.log('PASS actual browser scheduling: 6-ID flush, timeout tail flush, deduplication, session isolation and cleanup');
})().catch(err=>{console.error(err);process.exit(1);});
