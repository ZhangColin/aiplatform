const { chromium } = require('/opt/render/node_modules/playwright-core');
// 用法: node shot-after-run2.js <outdir>
const OUT = '/workspace/.platform/' + process.argv[2];
(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const base = 'http://localhost:8081';
  const fs = require('fs');
  fs.mkdirSync(OUT, { recursive: true });
  const snap = async n => p.screenshot({ path: `${OUT}/${n}.png`, fullPage: n === '00-home' });
  await p.goto(base + '/', { waitUntil: 'networkidle', timeout: 30000 });
  await p.waitForTimeout(1200);
  await snap('00-home');
  // 找错题重练入口并点进游戏页
  try {
    await p.click('text=错题重练', { timeout: 6000 });
    await p.waitForLoadState('networkidle', { timeout: 20000 }).catch(()=>{});
    await p.waitForTimeout(1200);
    await p.screenshot({ path: `${OUT}/10-review.png` });
  } catch (e) { console.log('no-review-click', String(e).slice(0, 60)); }
  for (const [n, href] of [['20-parent','/parent'],['21-pinyin','/pinyin'],['22-levels','/levels']]) {
    try { await p.goto(base + href, { waitUntil: 'networkidle', timeout: 20000 }); await p.waitForTimeout(900); await p.screenshot({ path: `${OUT}/${n}.png` }); } catch (e) { console.log('skip', n, String(e).slice(0, 50)); }
  }
  await b.close();
  console.log('DONE -> ' + OUT);
})().catch(e => { console.error('ERR', e); process.exit(1); });
