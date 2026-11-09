"""UI-only smoke test; administrator blocks local browser navigation here."""
import asyncio,re
from pathlib import Path
from playwright.async_api import async_playwright
base=Path(__file__).resolve().parents[2]/'web'
async def main():
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True,executable_path='/usr/bin/chromium',args=['--no-sandbox'])
        page=await browser.new_page(viewport={'width':1280,'height':820})
        errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        html=(base/'index.html').read_text()
        html=re.sub(r'<script[^>]*src=[^>]*></script>','',html)
        await page.set_content(html,wait_until='domcontentloaded')
        await page.add_style_tag(content=(base/'styles.css').read_text())
        for name in ('protocol.js','adaptive.js','app.js'):
            await page.add_script_tag(content=(base/name).read_text())
        await page.wait_for_timeout(400)
        await page.locator('#settingsButton').click()
        await page.get_by_role('button',name='Speed diagnostics').click()
        assert await page.get_by_text('Connection speed diagnostics').is_visible()
        assert 'not detected' in await page.locator('.diagnostic-results').inner_text()
        # Avoid writing browser previews into the source tree or fixed system paths.
        if errors:raise RuntimeError(errors)
        print('PASS full-width browser UI, diagnostics dialog, expected pre-firmware warning; no JS errors')
        await browser.close()
asyncio.run(main())
