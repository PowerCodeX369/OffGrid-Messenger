from pathlib import Path
import re
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1];web=root/'web'
html=(web/'index.html').read_text()
html=re.sub(r'<script[^>]*src="[^"]+"[^>]*></script>','',html)
html=re.sub(r'<link[^>]*rel="stylesheet"[^>]*>','',html)
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 page=b.new_page(viewport={'width':950,'height':900})
 errs=[];page.on('pageerror',lambda e:errs.append(str(e)))
 page.set_content(html);page.add_style_tag(content=(web/'styles.css').read_text())
 for name in ['protocol.js','adaptive.js','app.js']:
  page.add_script_tag(content=(web/name).read_text())
 page.evaluate('''() => {
  window.__sent=[];
  verified=true;writer={write:async b=>{window.__sent.push(new TextDecoder().decode(b).trim())}};
  radioTelemetry={peer:'1C:DB:D4:3B:00:FC',phy:12,rssi:-35,mode:'AUTO',delivered:0,retries:0,failures:0,txok:0,txfail:0,rx:0,hello:2,available:true};
 }''')
 page.get_by_role('button',name='Settings').click()
 page.get_by_role('button',name='RF Lab · power & range test').click()
 assert page.locator('.rf-lab-status').count()==1
 page.locator('.rf-lab-actions').first.get_by_role('button',name='Apply simulated RSSI').click()
 page.wait_for_timeout(100)
 assert 'RFTEST SIM -85' in page.evaluate('window.__sent')
 page.evaluate('''() => {parseLine('RFTEST|ACTIVE=1|PWR=8|BASE=80|SIM=ON|LIVE=-35|PHY=0.5|MODE=AUTO|PEER=1|TTL=119'); parseLine('RFTEST_SIM|RSSI=-85');}''')
 text=page.locator('.rf-lab-status').inner_text()
 assert 'Measured RSSI: -35 dBm' in text,text
 assert 'Simulated -85 dBm' in text,text
 assert '2 dBm' in text,text
 page.locator('.rf-lab-actions').first.get_by_role('button',name='Use real RSSI').click()
 page.wait_for_timeout(50)
 assert 'RFTEST SIM OFF' in page.evaluate('window.__sent')
 page.locator('button').filter(has_text='Restore normal RF').click()
 page.wait_for_timeout(50)
 assert 'RFTEST OFF' in page.evaluate('window.__sent')
 page.get_by_role('button',name='Close dialog').click()
 assert not page.locator('.rf-lab-status').is_visible()
 for width in [1920,850,390]:
  page.set_viewport_size({'width':width,'height':850})
  page.wait_for_timeout(60)
  assert page.evaluate('document.documentElement.scrollWidth-innerWidth')<2,width
 assert not errs,errs
 print('RF_LAB_BROWSER_PASS')
 b.close()
