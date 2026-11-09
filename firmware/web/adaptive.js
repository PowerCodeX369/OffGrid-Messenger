/* OffGrid 1.4.6 Safe Adaptive Photo Flow Control.
 * No firmware/radio changes. One-frame text and receipts are inviolable.
 * RSSI and browser-to-browser application ACK latency drive a conservative
 * congestion window for photos only. No claims about PHY bitrate or distance.
 */
(function(root){
  'use strict';
  class PhotoFlowController {
    constructor(){ this.emaMs=0;this.clean=0;this.samples=0;this.cooldown=0;this.failures=0;this.lastReason='warming up'; }
    success(ms,attempts=1){
      if(!Number.isFinite(ms)||ms<0)return;
      ms=Math.max(1,ms);
      this.samples++;
      this.emaMs=this.emaMs===0?ms:this.emaMs*.80+ms*.20;
      if(attempts>1||ms>1900){this.penalty('retry or slow ACK');return;}
      if(this.cooldown>0)this.cooldown--;
      if(ms<=1000){this.clean=Math.min(this.clean+1,200);this.lastReason='confirmed clean ACKs';}
      else {this.clean=Math.max(0,this.clean-2);this.lastReason='ACK latency elevated';}
    }
    penalty(reason='lost ACK'){
      this.clean=0;
      this.cooldown=12;
      this.failures++;
      this.lastReason=reason;
    }
    window({kind='image',rssi=null,enabled=true,profile='auto',txFailures=0}={}){
      // Always preserve the verified one-at-a-time path for text and receipts.
      if(kind!=='image'||!enabled||profile==='range')return 1;
      // Unknown signal is not evidence of a strong link.
      if(!Number.isFinite(rssi)||rssi< -76||rssi>0||txFailures>0&&this.clean<16)return 1;
      if(this.cooldown>0)return 1;
      if(rssi>=-58&&this.clean>=28&&this.emaMs<=500)return 3;
      if(rssi>=-73&&this.clean>=9&&this.emaMs<=900)return 2;
      return 1;
    }
    status(rssi,enabled,profile,txFailures){
      const w=this.window({rssi,enabled,profile,txFailures});
      return {window:w,ackMs:this.samples?Math.round(this.emaMs):null,clean:this.clean,cooldown:this.cooldown,reason:this.lastReason};
    }
  }
  const api={PhotoFlowController};
  root.OffGridAdaptive=api;
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this);
