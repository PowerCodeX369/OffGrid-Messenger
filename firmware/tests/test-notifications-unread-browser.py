#!/usr/bin/env python3
"""Phase 1.6.1 Chromium tests with local DOM and mocked OS notifications.
No actual hardware, native OS notification delivery, or long-range radio test."""
from pathlib import Path
import re
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
html=(ROOT/'web/index.html').read_text()
html=re.sub(r'<script[^>]+src=[^>]+></script>', '', html)
html=re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', '', html)
html=html.replace('</head>', '<base href="http://localhost:8765/"><style>'+(ROOT/'web/styles.css').read_text()+'</style></head>')
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium', headless=True, args=['--no-sandbox','--disable-dev-shm-usage'])
 for width in (1330,390):
  page=browser.new_page(viewport={'width':width,'height':820})
  errors=[]
  page.on('pageerror', lambda error: errors.append(str(error)))
  page.set_content(html,wait_until='domcontentloaded')
  for name in ['protocol.js','adaptive.js','app.js']:
   page.add_script_tag(content=(ROOT/'web'/name).read_text())
  page.wait_for_timeout(150)
  assert page.locator('#notificationButton').count()==1
  assert page.locator('#unreadBadge').is_hidden()
  assert page.locator('#contactUnreadBadge').is_hidden()
  page.evaluate('''()=>{
    class FakeNotification {
      static permission='default'; static instances=[]; static requestCalls=0;
      static async requestPermission(){this.requestCalls++;this.permission='granted';return 'granted';}
      constructor(title,options){this.title=title;this.options=options;FakeNotification.instances.push(this);}
      close(){this.closed=true;}
    }
    Object.defineProperty(window,'Notification',{value:FakeNotification,configurable:true});
    Object.defineProperty(window,'isSecureContext',{value:true,configurable:true});
    verified=true;localMac='1C:DB:D4:3B:00:FC';secretKey={mock:true};
    window.saved=[];storeRecord=async(r)=>{window.saved.push(serializeRecord(r));};
    storeStatus=async()=>{};runTransfers=()=>{};
    window.simulateBackground=()=>{Object.defineProperty(document,'hidden',{get:()=>true,configurable:true});document.hasFocus=()=>false;document.dispatchEvent(new Event('visibilitychange'));};
    window.simulateForeground=()=>{Object.defineProperty(document,'hidden',{get:()=>false,configurable:true});document.hasFocus=()=>true;if(innerWidth<=590)document.body.classList.add('mobile-chat');document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('focus'));};
    window.simulateBackground();refreshStatus();
  }''')
  # Explicit click prompts; never requested at bootstrap.
  assert page.evaluate('Notification.requestCalls')==0
  page.locator('#notificationButton').click(force=True)
  page.wait_for_function('Notification.permission === "granted"')
  assert page.evaluate('Notification.requestCalls')==1
  assert page.evaluate('desktopNotificationsEnabled === true')
  assert page.locator('#notificationButton').get_attribute('aria-pressed')=='true'
  ids=page.evaluate('''async()=>{
     const a=P.newId(),b=P.newId();
     await handleEnvelope({kind:P.types.text,id:a,ts:Date.now(),body:P.textBytes('Hello, encrypted world!')},'AA:BB:CC:DD:EE:FF');
     await handleEnvelope({kind:P.types.voice,id:b,ts:Date.now(),width:1000,height:0,body:P.packVoice('audio/webm',new Uint8Array(900).fill(5))},'AA:BB:CC:DD:EE:FF');
     return {a,b};
  }''')
  assert page.locator('#unreadBadge').inner_text()=='2'
  assert page.locator('#contactUnreadBadge').inner_text()=='2'
  assert page.title()=='(2) OffGrid Messenger'
  assert page.evaluate('Notification.instances.length')==2
  assert page.evaluate('Notification.instances[0].options.body')=='New encrypted message'
  assert page.evaluate('Notification.instances[1].options.body')=='An encrypted voice note arrived'
  assert page.evaluate('''({a,b})=>[recordMap.get(a).seenLocally,recordMap.get(b).seenLocally]''',ids)==[False,False]
  # Duplicate packets cannot generate duplicate notification or unread.
  page.evaluate('''async ({a})=>{await handleEnvelope({kind:P.types.text,id:a,ts:Date.now(),body:P.textBytes('Duplicate')},'AA:BB:CC:DD:EE:FF');}''',ids)
  assert page.evaluate('Notification.instances.length')==2
  assert page.title()=='(2) OffGrid Messenger'
  # User focuses the open conversation, clearing unread locally and submitting read receipts.
  page.evaluate('window.simulateForeground()')
  assert page.locator('#unreadBadge').is_hidden()
  assert page.locator('#contactUnreadBadge').is_hidden()
  assert page.title()=='OffGrid Messenger'
  assert page.evaluate('''({a,b})=>recordMap.get(a).seenLocally&&recordMap.get(b).seenLocally''',ids)
  assert page.evaluate('serializeRecord(recordMap.get(records[0].id)).seenLocally === true')
  assert page.evaluate('transferQueue.some(x=>x.kind === "read")')
  # Foreground incoming messages must not pop OS notifications or get unread badges.
  page.evaluate('''async()=>{
    await handleEnvelope({kind:P.types.text,id:P.newId(),ts:Date.now(),body:P.textBytes('I am already in this chat')},'AA:BB:CC:DD:EE:FF');
  }''')
  assert page.evaluate('Notification.instances.length')==2
  assert page.locator('#unreadBadge').is_hidden()
  # User can turn notifications off without changing site permission.
  page.evaluate('document.body.classList.remove("mobile-chat")')
  page.locator('#notificationButton').click(force=True)
  assert not page.evaluate('desktopNotificationsEnabled')
  page.evaluate('window.simulateBackground()')
  page.evaluate('''async()=>{
    await handleEnvelope({kind:P.types.text,id:P.newId(),ts:Date.now(),body:P.textBytes('Muted')} ,'AA:BB:CC:DD:EE:FF');
  }''')
  assert page.evaluate('Notification.instances.length')==2
  assert page.locator('#unreadBadge').inner_text()=='1'
  assert not errors,errors
  # No page-wide horizontal overflow in both themes at desktop and mobile widths.
  for mode in ['dark','light']:
   page.evaluate('(mode)=>document.body.classList.toggle("light",mode==="light")',mode)
   page.wait_for_timeout(100)
   metrics=page.evaluate('''()=>({viewport:window.innerWidth,doc:document.documentElement.scrollWidth,body:document.body.scrollWidth,theme:getComputedStyle(document.body).backgroundColor})''')
   assert metrics['doc']<=metrics['viewport']+1,(width,mode,metrics)
  print(f'PASS {width}px: opt-in alerts, privacy, two unread badges, persistence, dedup, read reset, foreground suppression, toggle-off, themes')
  page.close()
 browser.close()
