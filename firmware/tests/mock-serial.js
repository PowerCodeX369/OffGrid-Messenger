/* Browser-only fake serial bridge for developer E2E tests. Never load in production UI. */
(function(){
  const side=new URLSearchParams(location.search).get('testnode');
  if(!['A','B'].includes(side))return;
  const node=side;
  const mac=node==='A'?'1C:DB:D4:3B:00:FC':'1C:DB:D4:3B:36:10';
  const other=node==='A'?'B':'A';
  const channel=new BroadcastChannel('offgrid-fake-radio-v14');
  let currentPort=null,seq=1;
  channel.onmessage=(ev)=>{
    const e=ev.data;
    if(!currentPort||!currentPort._push||e.to!==node)return;
    if(e.type==='radio')currentPort._push(`RX|${e.mac}|${e.session}|${e.id}|${e.hex}`);
    if(e.type==='ack')currentPort._push(`DELIVERED|${e.session}|${e.id}`);
  };
  Object.defineProperty(navigator,'serial',{configurable:true,value:{
    requestPort:async()=>{
      const p={
        _push:null,
        async open(){
          let ctrl=null;
          this.readable=new ReadableStream({start(c){ctrl=c;}});
          this._push=(line)=>{ctrl.enqueue(new TextEncoder().encode(line+'\n'));};
          this.writable=new WritableStream({write(data){
            const text=new TextDecoder().decode(data).trim();
            if(text==='STATUS')p._push(`READY|${mac}|CHANNEL=6|SESSION=${node==='A'?333:444}|PROTO=2|CAP=228|TXDONE=1`);
            else if(text.startsWith('MSGHEX ')){
              const hex=text.slice(7),id=seq++,session=node==='A'?333:444;
              p._push(`QUEUED|${session}|${id}|${hex}`);
              p._push(`SENT|${session}|${id}`);
              channel.postMessage({to:other,type:'radio',mac,session,id,hex});
            }
            else if(text.startsWith('APPACK ')){
              const [,otherMac,session,id]=text.split(' ').slice(1);
              channel.postMessage({to:other,type:'ack',session,id});
            }
          }});
          currentPort=this;
        },
        async close(){currentPort=null;this.readable=null;this.writable=null;}
      };
      return p;
    }
  }});
})();
