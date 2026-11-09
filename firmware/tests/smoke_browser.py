#!/usr/bin/env python3
"""Automated BROWSER simulation only. Does not compile ESP-IDF or run a real radio."""
import subprocess, time
from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
server=subprocess.Popen(['python3','-m','http.server','8877','--bind','127.0.0.1','--directory',str(root/'web')], stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 time.sleep(.35)
 with sync_playwright() as p:
  browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
  ctx=browser.new_context(permissions=['clipboard-read','clipboard-write'])
  mock=(root/'tests/mock-serial.js').read_text()
  ctx.add_init_script(script=mock)
  errors=[]
  a=ctx.new_page(); b=ctx.new_page()
  for name,page in [('A',a),('B',b)]:
   page.on('pageerror',lambda e,n=name:errors.append((n,str(e))))
   page.goto('http://127.0.0.1:8877/?testnode='+name,wait_until='networkidle')
   page.evaluate("localStorage.setItem('__testNvs_'+new URL(location.href).searchParams.get('testnode'), 'A1'.repeat(32))")
   page.reload(wait_until='networkidle')
  a.locator('#connectButton').click()
  b.locator('#connectButton').click()
  for page in (a,b):
   page.wait_for_function("document.querySelector('#connectionState').textContent === 'Connected' && !document.querySelector('#messageInput').disabled",timeout=12000)
  a.locator('#messageInput').fill('Hello from A')
  a.locator('#sendButton').click()
  b.wait_for_function("[...document.querySelectorAll('.message-row.in .message-text')].some(e => e.textContent === 'Hello from A')",timeout=20000)
  a.wait_for_function("[...document.querySelectorAll('.message-row.out')].some(e => e.dataset.status === 'delivered' || e.dataset.status === 'read')",timeout=20000)
  b.locator('#messageInput').fill('Hello from B')
  b.locator('#sendButton').click()
  a.wait_for_function("[...document.querySelectorAll('.message-row.in .message-text')].some(e => e.textContent === 'Hello from B')",timeout=20000)
  print('PASS: two-tab encrypted text exchange, node key retrieval, radio ACK statuses')
  if errors: print('BROWSER ERRORS',errors);raise AssertionError(errors)
  else: print('PASS: no browser exceptions')
  browser.close()
finally:
 server.terminate();server.wait(timeout=5)
