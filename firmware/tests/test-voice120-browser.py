#!/usr/bin/env python3
"""Simulated 120 s capture test, no physical microphone or ESP32 required."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import re
ROOT=Path(__file__).resolve().parents[1]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 for width in (1100,390):
  page=browser.new_page(viewport={'width':width,'height':780})
  errors=[]
  page.on('pageerror',lambda e:errors.append(str(e)))
  html=(ROOT/'web/index.html').read_text()
  html=re.sub(r'<script[^>]+src=[^>]+></script>', '', html)
  html=re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', '', html)
  html=html.replace('</head>','<style>'+(ROOT/'web/styles.css').read_text()+'</style></head>')
  page.set_content(html,wait_until='domcontentloaded')
  for name in ('protocol.js','adaptive.js','app.js'):
   page.add_script_tag(content=(ROOT/'web'/name).read_text())
  page.evaluate('''()=>{
   verified=true;localMac='1C:DB:D4:3B:00:FC';secretKey={mock:true};
   storeRecord=async()=>{};storeStatus=async()=>{};runTransfers=()=>{};
   Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{
    async getUserMedia(){return {getTracks(){return [{stop(){}}]}}}
   }});
   window.MediaRecorder=class{
    static isTypeSupported(t){return t==='audio/webm;codecs=opus'}
    constructor(stream,options){this.mimeType=options.mimeType;this.state='inactive'}
    start(){this.state='recording'}
    stop(){if(this.state==='inactive')return;this.state='inactive';
     this.ondataavailable?.({data:new Blob([new Uint8Array(240000).fill(17)],{type:this.mimeType})});
     this.onstop?.();
    }
   };
   refreshStatus();
  }''')
  if width<=720:page.evaluate("document.body.classList.add('mobile-chat')")
  page.locator('#voiceButton').click()
  page.wait_for_function('currentVoiceRecording && currentVoiceRecording.recorder.state===\'recording\'')
  page.evaluate('currentVoiceRecording.started=Date.now()-120015')
  page.locator('#voiceButton').click()
  page.wait_for_function("records.some(r=>r.kind==='voice'&&r.dir==='out')")
  check=page.evaluate('''()=>{
   const r=records.find(r=>r.kind==='voice');
   const t=transferQueue.find(j=>j.kind==='voice');
   return {durationMs:r.durationMs,bytes:r.bytes.length,queued:!!t,limit:MAX_VOICE_MS};
  }''')
  assert check=={'durationMs':120000,'bytes':240000,'queued':True,'limit':120000},check
  assert not errors,errors
  print(f'PASS Chromium {width}px: simulated full 120s recording, 240 KiB encoded audio accepted, encryption job queued, time capped')
  page.close()
 browser.close()
