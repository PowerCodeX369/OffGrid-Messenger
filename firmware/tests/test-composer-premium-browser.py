#!/usr/bin/env python3
"""Chromium UI tests for shared voice/send action and restrained transitions."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import re
ROOT=Path(__file__).resolve().parents[1]
html=(ROOT/'web/index.html').read_text()
html=re.sub(r'<script[^>]+src=[^>]+></script>', '', html)
html=re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', '', html)
html=html.replace('</head>', '<style>'+(ROOT/'web/styles.css').read_text()+'</style></head>')
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 for width in (1440,390):
  page=browser.new_page(viewport={'width':width,'height':820})
  errors=[];page.on('pageerror',lambda err:errors.append(str(err)))
  page.set_content(html,wait_until='domcontentloaded')
  for name in ('protocol.js','adaptive.js','app.js'):
   page.add_script_tag(content=(ROOT/'web'/name).read_text())
  page.evaluate('''()=>{verified=true;localMac='1C:DB:D4:3B:00:FC';secretKey={mock:true};storeRecord=async()=>{};runTransfers=()=>{};refreshStatus();}''')
  if width<591:page.locator('#contactButton').click()
  assert 'show-voice' in (page.locator('#composer').get_attribute('class') or '')
  assert page.locator('#voiceButton').get_attribute('aria-hidden')=='false'
  assert page.locator('#sendButton').get_attribute('aria-hidden')=='true'
  page.locator('#messageInput').fill('Test message')
  assert 'show-send' in page.locator('#composer').get_attribute('class')
  assert page.locator('#sendButton').get_attribute('aria-hidden')=='false'
  assert page.locator('#voiceButton').get_attribute('aria-hidden')=='true'
  assert page.locator('#sendButton').is_enabled()
  page.locator('#sendButton').click()
  page.wait_for_function('document.querySelector("#messageInput").value===""')
  assert 'show-voice' in page.locator('#composer').get_attribute('class')
  assert page.locator('#messages .message-row.out').count()==1
  page.locator('#messageInput').fill('   ')
  assert 'show-voice' in page.locator('#composer').get_attribute('class')
  page.locator('#messageInput').fill('Typing...')
  assert 'show-send' in page.locator('#composer').get_attribute('class')
  page.locator('#messageInput').fill('')
  assert 'show-voice' in page.locator('#composer').get_attribute('class')
  for theme in ('dark','light'):
   page.evaluate("v=>document.body.classList.toggle('light',v==='light')",theme)
   colors=page.evaluate('''()=>({bubble:getComputedStyle(document.querySelector('.message-bubble')).backgroundColor,app:getComputedStyle(document.body).backgroundColor})''')
   assert colors['bubble'] and colors['app'],colors
  assert not errors,errors
  print(f'PASS {width}px: mic↔send morph, text send and reset, blank input safety, both themes, no exceptions')
  page.close()
 browser.close()
