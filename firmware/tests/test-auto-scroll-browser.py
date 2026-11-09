#!/usr/bin/env python3
"""Real Chromium UI tests for OffGrid Messenger 1.4.10.4 chat scrolling.
Does not test real ESP-NOW hardware or encrypted radio transfers.
"""
from pathlib import Path
import re
from playwright.sync_api import sync_playwright
import subprocess
import time
ROOT=Path(__file__).resolve().parents[1]
server=subprocess.Popen(['python3','-m','http.server','8878','--bind','127.0.0.1','--directory',str(ROOT/'web')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
    time.sleep(0.5)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
        page=browser.new_page(viewport={'width':1100,'height':550})
        errors=[]
        page.on('pageerror',lambda e: errors.append(str(e)))
        # This environment blocks loopback page navigation. Render the same local app
        # as inline HTML/CSS/JS instead; no network access or hardware required.
        html=(ROOT/'web/index.html').read_text()
        html=re.sub(r'<script[^>]+src=[^>]+></script>', '', html)
        html=re.sub(r'<link[^>]+rel="stylesheet"[^>]*>', '', html)
        css=(ROOT/'web/styles.css').read_text()
        html=html.replace('</head>','<style>'+css+'</style></head>')
        page.set_content(html,wait_until='domcontentloaded',timeout=30000)
        for asset in ['protocol.js','adaptive.js','app.js']:
            page.add_script_tag(content=(ROOT/'web'/asset).read_text())
        page.wait_for_timeout(300)
        page.evaluate('''() => {
          for(let i=0;i<48;i++)makeBubble({id:'scroll-'+i,kind:'text',dir:'in',ts:Date.now(),text:'Message '+i+' — testing scroll behavior and message length.'},true);
        }''')
        is_bottom="(() => {const e=document.querySelector('#chatScroll'); return Math.abs(e.scrollHeight-e.clientHeight-e.scrollTop)<4})()"
        page.wait_for_function(is_bottom,timeout=4000)
        print('PASS: new incoming messages auto scroll to the latest message')
        page.evaluate("document.querySelector('#messages').lastElementChild.style.minHeight='310px'")
        page.wait_for_function(is_bottom,timeout=4000)
        print('PASS: late content resize remains pinned to bottom')
        area=page.locator('#chatScroll')
        before=page.evaluate("document.querySelector('#chatScroll').scrollTop")
        area.hover()
        page.mouse.wheel(0,-650)
        page.wait_for_timeout(250)
        assert page.evaluate("document.querySelector('#chatScroll').scrollTop") < before-100, 'Expected upward scroll gesture' 
        pos=page.evaluate("document.querySelector('#chatScroll').scrollTop")
        page.evaluate("document.querySelector('#messages').lastElementChild.style.minHeight='510px'")
        page.wait_for_timeout(250)
        pos2=page.evaluate("document.querySelector('#chatScroll').scrollTop")
        assert abs(pos2-pos) < 5,(pos,pos2)
        print('PASS: manual scroll-up is not interrupted by late image/layout changes')
        page.evaluate("makeBubble({id:'newest',kind:'text',dir:'in',ts:Date.now(),text:'Newest incoming'},true)")
        page.wait_for_function(is_bottom,timeout=4000)
        print('PASS: a new incoming message resumes auto-follow')
        page.set_viewport_size({'width':390,'height':650})
        page.locator('#contactButton').click()
        page.wait_for_function(is_bottom,timeout=4000)
        print('PASS: narrow viewport/chat open scrolls to latest message')
        assert not errors,errors
        print('PASS: no JavaScript browser exceptions')
        browser.close()
finally:
    server.terminate();server.wait(timeout=5)
