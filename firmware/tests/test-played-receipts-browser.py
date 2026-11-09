#!/usr/bin/env python3
"""Chromium DOM + simulated transport. No real audio, radio, or microphone claims."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import re

ROOT=Path(__file__).resolve().parents[1]
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 for width in (1100,390):
  page=browser.new_page(viewport={'width':width,'height':850})
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
    window._saves=[];
    storeRecord=async(r)=>{window._saves.push(serializeRecord(r))};
    storeStatus=async()=>{};
    runTransfers=()=>{}; // Only inspect receipt queue, no fake delivery claims.
    refreshStatus();
  }''')
  assert page.evaluate('MAX_VOICE_MS')==120000
  assert page.locator('#voiceButton').get_attribute('title').endswith('2 minutes)')
  ids=page.evaluate('''async()=>{
    const inId=P.newId(),outId=P.newId(),textId=P.newId();
    const fakeVoice=P.packVoice('audio/webm',new Uint8Array(768).fill(43));
    await handleEnvelope(P.parseEnvelope(P.makeEnvelope(P.types.voice,inId,fakeVoice,1700000000,112000&65535,Math.floor(112000/65536))),'AA:BB:CC:DD:EE:FF');
    const outgoing={id:outId,kind:'voice',dir:'out',status:'read',bytes:new Uint8Array(768).fill(2),mime:'audio/webm',durationMs:98000,ts:Date.now(),played:false};
    await appendRecord(outgoing);
    const text={id:textId,kind:'text',dir:'out',status:'read',text:'test',ts:Date.now()};
    await appendRecord(text);
    return {inId,outId,textId};
  }''')
  assert page.locator('.voice-played-icon').count()==2
  state=page.evaluate('''({inId,outId})=>{
    const r=recordMap.get(inId),s=recordMap.get(outId);
    return {duration:r.durationMs,received:!!r.played,sent:!!s.played,receipt:transferQueue.some(j=>j.kind==='played')};
  }''',ids)
  assert state=={'duration':112000,'received':False,'sent':False,'receipt':False},state
  # User requests to play: still grey. Receipt only when playback starts.
  page.evaluate('''id=>{document.querySelector(`[data-mid="${id}"] audio`).dispatchEvent(new Event('play'));}''',ids['inId'])
  assert not page.evaluate("transferQueue.some(j=>j.kind==='played')")
  # Playback of your OWN outgoing voice note cannot imply the peer listened.
  page.evaluate('''id=>{document.querySelector(`[data-mid="${id}"] audio`).dispatchEvent(new Event('playing'));}''',ids['outId'])
  assert not page.evaluate('id=>!!recordMap.get(id).played',ids['outId'])
  page.evaluate('''id=>{document.querySelector(`[data-mid="${id}"] audio`).dispatchEvent(new Event('playing'));}''',ids['inId'])
  page.wait_for_function('''id=>recordMap.get(id).played && transferQueue.some(j=>j.kind==='played'&&j.targetId===id)''',arg=ids['inId'])
  assert page.locator(f'[data-mid="{ids["inId"]}"] .voice-played-icon').evaluate("e=>e.classList.contains('played')")
  assert page.locator(f'[data-mid="{ids["outId"]}"] .voice-played-icon').evaluate("e=>!e.classList.contains('played')")
  # Replays must never enqueue duplicates.
  page.evaluate('''id=>{const a=document.querySelector(`[data-mid="${id}"] audio`);a.dispatchEvent(new Event('playing'));a.dispatchEvent(new Event('playing'));}''',ids['inId'])
  assert page.evaluate("transferQueue.filter(j=>j.kind==='played').length")==1
  # A forged 'played' control with a text ID must have no effect on text messages.
  page.evaluate('''async id=>{await handleEnvelope(P.parseEnvelope(P.makeEnvelope(P.types.played,P.newId(),P.unhex(id))), 'AA:BB:CC:DD:EE:FF');}''',ids['textId'])
  assert not page.evaluate('id=>!!recordMap.get(id).played',ids['textId'])
  # Sender receives an authenticated-style control, separately from read tick.
  page.evaluate('''async id=>{await handleEnvelope(P.parseEnvelope(P.makeEnvelope(P.types.played,P.newId(),P.unhex(id))), 'AA:BB:CC:DD:EE:FF');}''',ids['outId'])
  assert page.locator(f'[data-mid="{ids["outId"]}"] .voice-played-icon.played').count()==1
  assert page.evaluate('id=>recordMap.get(id).status',ids['outId'])=='read'
  # Encrypted history payload includes both played and playedSent flags.
  assert page.evaluate('''id=>{const x=window._saves.findLast(r=>r.id===id);return x.played===true&&x.playedSent===false}''',ids['inId'])
  # After successful end-to-end receipt transfer, record playedSent. Re-render retains indicators.
  page.evaluate('''async id=>{const r=recordMap.get(id);r.playedSent=true;await storeRecord(r);renderHistory();}''',ids['inId'])
  assert page.locator('.voice-played-icon.played').count()==2
  assert not errors, errors
  print(f'PASS Chromium {width}px: play is not played, playing triggers once, blue mic on both ends, no text spoof, persistent indicator, 112s receive')
  page.close()
 browser.close()
