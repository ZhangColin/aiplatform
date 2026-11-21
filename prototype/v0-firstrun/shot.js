const { chromium } = require('/opt/render/node_modules/playwright-core');
(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1366, height: 900 } });
  const base = 'http://localhost:8081';
  const home = await p.goto(base + '/', { waitUntil: 'networkidle', timeout: 30000 });
  console.log('home-status', home.status());
  const links = await p.$$eval('nav a, header a, aside a', as => as.map(a => a.getAttribute('href')).filter(Boolean).slice(0, 12));
  console.log('links', JSON.stringify(links));
  await p.screenshot({ path: '/workspace/.platform/shots/00-home.png', fullPage: false });
  let i = 1;
  const seen = new Set();
  for (const href of links) {
    const url = href.startsWith('http') ? href : base + href;
    if (seen.has(url)) continue; seen.add(url);
    try {
      const r = await p.goto(url, { waitUntil: 'networkidle', timeout: 20000 });
      await p.waitForTimeout(800);
      await p.screenshot({ path: `/workspace/.platform/shots/${String(i).padStart(2,'0')}-${url.replace(/[^a-z0-9]+/gi,'-').slice(0,40)}.png` });
      console.log('shot', i, url, r.status()); i++;
      if (i > 6) break;
    } catch (e) { console.log('skip', url, String(e).slice(0, 80)); }
  }
  await b.close();
})().catch(e => { console.error('ERR', e); process.exit(1); });
