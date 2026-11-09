/* Browser-only simulated radio for automated smoke tests. Never load in production. */
(function(){
  const node=new URL(location.href).searchParams.get('testnode');
  if(!['A','B'].includes(node))return;
  const macs={A:'1C:DB:D4:3B:00:FC',B:'1C:DB:D4:3B:36:10'};
  const other=node==='A'?'B':'A';
  const ownMac=macs[node],otherMac=macs[other];
  const session=node==='A'?111123:222234;
  let seq=1,port=null;
  const channel=new BroadcastChannel('offgrid-fake-radio-v144');
  const kvKey='__testNvs_'+node;
  function push(line){port?._push(line);}
  function status(){push(`READY|${ownMac}|CHANNEL=6|SESSION=${session}|PROTO=2|CAP=180|STABLE=1|NVSKEY=1|FROM=STATUS`);
    push(`LINK|${otherMac}|RSSI=-35|PHY=1|MODE=STABLE|OK=1|RETRY=0|FAIL=0|TXOK=10|TXFAIL=0|RX=4|HELLO=4`);}
  channel.onmessage=event=>{
    const e=event.data;
    if(!port||e.to!==node)return;
    if(e.type==='data')push(`RX|${e.mac}|${e.session}|${e.id}|${e.hex}`);
    if(e.type==='ack')push(`DELIVERED|${e.session}|${e.id}`);
  };
  Object.defineProperty(navigator,'serial',{configurable:true,value:{
    async requestPort(){
      return {
        _push:null,
        async open(){
          let controller;
          this.readable=new ReadableStream({start(c){controller=c;}});
          this._push=line=>{controller.enqueue(new TextEncoder().encode(line+'\n'));};
          this.writable=new WritableStream({write(data){
            const text=new TextDecoder().decode(data).trim();
            if(text==='STATUS'){status();return;}
            if(text==='KEYGET'){const key=localStorage.getItem(kvKey);push(key?'KEYDATA|'+key:'KEYEMPTY');return;}
            if(text.startsWith('KEYSET ')){
              const raw=text.slice(7);
              if(!/^[A-F0-9]{64}$/i.test(raw)){push('KEYERROR|INVALID_HEX');return;}
              localStorage.setItem(kvKey,raw);push('KEYSTORED');return;
            }
            if(text.startsWith('RATE ')){push(`RATE|1|${text.slice(5)}`);return;}
            if(text.startsWith('MSGHEX ')){
              const hex=text.slice(7),id=seq++;
              push(`QUEUED|${session}|${id}|${hex}`);
              push(`SENT|${session}|${id}`);
              channel.postMessage({to:other,type:'data',mac:ownMac,session,id,hex});return;
            }
            if(text.startsWith('APPACK ')){
              const [mac,ss,id]=text.split(' ').slice(1);
              channel.postMessage({to:other,type:'ack',session:ss,id});return;
            }
          }});
          port=this;
          status();
        },
        async close(){port=null;this.readable=null;this.writable=null;}
      };
    }
  }});
})();
