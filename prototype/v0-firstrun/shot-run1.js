const { chromium } = require('/opt/render/node_modules/playwright-core');
(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const base = 'http://localhost:8081';
  await p.goto(base + '/', { waitUntil: 'networkidle', timeout: 30000 });
  try { await p.click('text=陈志远', { timeout: 8000 }); } catch (e) { console.log('chip-click-fallback'); await p.click('button:has-text("进入工作台")', { timeout: 8000 }); }
  await p.waitForLoadState('networkidle', { timeout: 30000 }).catch(()=>{});
  await p.waitForTimeout(1500);
  await p.screenshot({ path: '/workspace/.platform/shots/10-workbench.png' });
  const links = await p.$$eval('a[href^="/"]', as => Array.from(new Set(as.map(a => a.getAttribute('href')))).slice(0, 8));
  console.log('links', JSON.stringify(links));
  let i = 11;
  const seen = new Set();
  for (const href of links) {
    if (seen.has(href)) continue; seen.add(href);
    try {
      const r = await p.goto(base + href, { waitUntil: 'networkidle', timeout: 20000 });
      await p.waitForTimeout(900);
      await p.screenshot({ path: `/workspace/.platform/shots/${i}-${href.replace(/[^a-z0-9]+/gi,'-').slice(0,30)}.png` });
      console.log('shot', i, href, r.status()); i++;
      if (i > 15) break;
    } catch (e) { console.log('skip', href, String(e).slice(0, 60)); }
  }
  await b.close();
})().catch(e => { console.error('ERR', e); process.exit(1); });
