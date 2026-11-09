#!/usr/bin/env python3
"""Isolated Chromium browser UI test with a simulated microphone and serial transport.
Not a physical ESP32 or microphone codec-quality test.
"""
from pathlib import Path
from playwright.sync_api import sync_playwright
import re
ROOT=Path(__file__).resolve().parents[1]
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
         args=['--no-sandbox','--disable-dev-shm-usage','--autoplay-policy=no-user-gesture-required'])
    for width in (1100,390):
        page=browser.new_page(viewport={'width':width,'height':780})
        errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        html=(ROOT/'web/index.html').read_text()
        html=re.sub(r'<script[^>]+src=[^>]+></script>', '', html)
        html=re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', '', html)
        html=html.replace('</head>', '<style>'+(ROOT/'web/styles.css').read_text()+'</style></head>')
        page.set_content(html,wait_until='domcontentloaded')
        for asset in ['protocol.js','adaptive.js','app.js']:
            page.add_script_tag(content=(ROOT/'web'/asset).read_text())
        page.evaluate('''async()=>{
          window._txqueue=[];
          runTransfers=()=>{};
          verified=true; localMac='1C:DB:D4:3B:00:FC';
          secretKey={testOnlyMockKey:true};
          storeRecord=async()=>{};storeStatus=async()=>{};
          window._stopped=0;
          Object.defineProperty(navigator,'mediaDevices',{configurable:true,value:{
            async getUserMedia(){return {getTracks(){return [{stop(){window._stopped++;}}]}}}
          }});
          window.MediaRecorder=class {
            static isTypeSupported(type){return type==='audio/webm;codecs=opus'};
            constructor(stream,options){this.stream=stream;this.mimeType=options.mimeType;this.state='inactive';}
            start(){this.state='recording'}
            stop(){if(this.state==='inactive')return;
              this.state='inactive';
              const audio=new Blob([new Uint8Array(2000).fill(67)],{type:this.mimeType});
              this.ondataavailable?.({data:audio});
              this.onstop?.();
            }
          };
          refreshStatus();
        }''')
        if width<=720:page.evaluate("document.body.classList.add('mobile-chat')")
        assert page.locator('#voiceButton').is_enabled(), 'microphone must be enabled only when paired'
        page.locator('#voiceButton').click()
        page.wait_for_function("!!currentVoiceRecording && currentVoiceRecording.recorder.state==='recording'")
        assert page.locator('#voiceButton').get_attribute('aria-pressed')=='true'
        page.locator('#voiceButton').click()
        page.wait_for_function("records.some(r=>r.kind==='voice'&&r.dir==='out')")
        assert page.locator('.voice-clip audio').count()==1, 'voice bubble should have audio controls'
        assert page.locator('#voiceButton').get_attribute('aria-pressed')=='false'
        count=page.evaluate('''async()=>{
          const r=records.find(r=>r.kind==='voice');
          const packed=P.packVoice(r.mime,r.bytes);
          const transferId=P.newId();
          const plain=P.makeEnvelope(P.types.voice,r.id,packed,1700000000,r.durationMs,0);
          const frames=P.frames(transferId,plain,{batch:true});
          const parsed=P.parseEnvelope(P.concat(...frames.map(f=>P.parseFrame(f).bytes)));
          const audio=P.unpackVoice(parsed.body,parsed.width);
          if(audio.bytes.length!==2000 || audio.mime!=='audio/webm')throw Error('wrong voice payload');
          return frames.length;
        }''')
        assert count>=12
        assert page.evaluate('window._stopped')>=1
        # Receiver side: pre-authenticated payload to the message renderer.
        # Separate Node.js WebCrypto tests verify real AES-GCM authentication.
        page.evaluate("""async()=>{
          const id=P.newId(),data=P.packVoice('audio/webm',new Uint8Array(640).fill(17));
          const env=P.parseEnvelope(P.makeEnvelope(P.types.voice,id,data,1700000000,5000,0));
          await handleEnvelope(env,'1C:DB:D4:3B:12:34');
        }""")
        assert page.locator('.voice-clip audio').count()==2,'receiver voice should have audio controls'
        assert page.evaluate("() => [...document.querySelectorAll('.voice-clip audio')].every(a=>!a.autoplay)")
        # Escape cancels, prevents a new record, and releases the mic.
        page.locator('#voiceButton').click()
        page.wait_for_function('!!currentVoiceRecording')
        page.keyboard.press('Escape')
        page.wait_for_function('!currentVoiceRecording')
        assert page.evaluate("records.filter(r=>r.kind==='voice').length")==2
        assert not errors, 'Browser script errors: '+str(errors)
        print(f'PASS Chromium {width}px: record, stop, queued audio, playback bubble, {count} fragments, incoming playback, Esc discard, clean mic release')
        page.close()
    browser.close()
