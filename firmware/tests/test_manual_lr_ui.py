from pathlib import Path
import re
from playwright.sync_api import sync_playwright
web=Path(__file__).resolve().parents[1]/'web'
html=(web/'index.html').read_text()
html=re.sub(r'<script[^>]*src="[^"]+"[^>]*></script>','',html)
html=re.sub(r'<link[^>]*rel="stylesheet"[^>]*>','',html)
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 pg=b.new_page(viewport={'width':1250,'height':850})
 errors=[]
 pg.on('pageerror',lambda e:errors.append(str(e)))
 pg.set_content(html)
 pg.add_style_tag(content=(web/'styles.css').read_text())
 pg.evaluate('''() => {
   const m=new Map(); Object.defineProperty(window,'localStorage',{configurable:true,value:{getItem:k=>m.get(k)||null,setItem:(k,v)=>m.set(k,String(v)),removeItem:k=>m.delete(k)}});
 }''')
 for file in ['protocol.js','adaptive.js','app.js']:
  pg.add_script_tag(content=(web/file).read_text())
 assert pg.locator('#lrForceToggle').count()==1
 assert not pg.locator('#lrForceToggle').is_checked()
 assert 'Auto mode' in pg.locator('#lrTestStatus').inner_text()
 pg.evaluate('''() => {
    window.__sent=[];
    verified=true;
    writer={write: async bytes => {window.__sent.push(new TextDecoder().decode(bytes).trim())}};
    radioTelemetry={peer:'1C:DB:D4:3B:36:10',phy:12,rssi:-35,mode:'AUTO',delivered:0,retries:0,failures:0,txok:0,txfail:0,rx:0,hello:2,available:true};
    updateLinkBadge();
 }''')
 pg.locator('label.lr-toggle').click()
 pg.wait_for_timeout(50)
 assert pg.locator('#lrForceToggle').is_checked()
 assert pg.locator('#lrRateSelect').is_visible()
 assert 'RATE LR500' in pg.evaluate('window.__sent')
 pg.evaluate('''()=>{radioTelemetry.phy=0.5;radioTelemetry.mode='LR500';updateLinkBadge()}''')
 assert 'Confirmed by ESP32 · LR 500 Kbps' in pg.locator('#lrTestStatus').inner_text(),pg.locator('#lrTestStatus').inner_text()
 pg.locator('#lrRateSelect').select_option('lr250')
 pg.wait_for_timeout(50)
 assert 'RATE LR250' in pg.evaluate('window.__sent')
 pg.evaluate('''()=>{radioTelemetry.phy=0.25;radioTelemetry.mode='LR250';updateLinkBadge()}''')
 assert 'Confirmed by ESP32 · LR 250 Kbps' in pg.locator('#lrTestStatus').inner_text()
 pg.locator('label.lr-toggle').click()
 pg.wait_for_timeout(50)
 assert not pg.locator('#lrForceToggle').is_checked()
 assert not pg.locator('#lrRateSelect').is_visible()
 assert 'RATE AUTO' in pg.evaluate('window.__sent')
 pg.get_by_role('button',name='Settings').click()
 pg.locator('.modal .setting-select').first.select_option('lr500')
 pg.wait_for_timeout(50)
 assert pg.locator('#lrForceToggle').is_checked()
 pg.locator('.modal .setting-select').first.select_option('auto')
 pg.wait_for_timeout(50)
 assert not pg.locator('#lrForceToggle').is_checked()
 for width in [1920,850,590,390]:
  pg.set_viewport_size({'width':width,'height':850})
  pg.wait_for_timeout(60)
  assert pg.evaluate('document.documentElement.scrollWidth - innerWidth')<2,(width,pg.evaluate('document.documentElement.scrollWidth - innerWidth'))
 assert not errors,errors
 print('ISOLATED_BROWSER_PASS')
 print(pg.evaluate('window.__sent'))
 b.close()
