const P=require('./reference_web_protocol.cjs');const {webcrypto}=require('node:crypto');globalThis.crypto=webcrypto;
(async()=>{
const key=await P.importKey(Uint8Array.from({length:32},(_,i)=>i));
const cases=[['JPEG',P.types.image,P.concat(Uint8Array.of(2),Uint8Array.from({length:4096},(_,i)=>i*7%256)),780,520],['VOICE',P.types.voice,P.packVoice('audio/webm',Uint8Array.from({length:2018},(_,i)=>i*13%256)),119000&65535,119000>>>16]];
for(const [name,kind,body,w,h] of cases){
 const id=P.newId(),tid=P.newId(),plain=P.makeEnvelope(kind,id,body,Math.floor(Date.now()/1000),w,h);
 const sealed=await P.seal(key,plain,tid);
 console.log(`${name}|${P.frames(tid,sealed,{batch:true}).map(P.hex).join(',')}|OK`);
}
})().catch(e=>{console.error(e);process.exit(1)});
