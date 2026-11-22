const { chromium } = require('/opt/render/node_modules/playwright-core');
// 用法: node shot-after.js <outdir>   （容器内跑，写 /workspace/.platform/<outdir>/）
const OUT = '/workspace/.platform/' + process.argv[2];
(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
  const base = 'http://localhost:8081';
  const fs = require('fs');
  fs.mkdirSync(OUT, { recursive: true });
  const snap = async n => p.screenshot({ path: `${OUT}/${n}.png` });
  await p.goto(base + '/', { waitUntil: 'networkidle', timeout: 30000 });
  await snap('00-home');
  try { await p.click('text=陈志远', { timeout: 8000 }); } catch (e) { try { await p.click('button:has-text("进入工作台")', { timeout: 8000 }); } catch (e2) {} }
  await p.waitForLoadState('networkidle', { timeout: 30000 }).catch(()=>{});
  await p.waitForTimeout(1500);
  await snap('10-workbench');
  for (const [n, href] of [['11-board','/board'],['12-list','/list'],['13-stats','/stats'],['14-members','/members']]) {
    try { await p.goto(base + href, { waitUntil: 'networkidle', timeout: 20000 }); await p.waitForTimeout(800); await snap(n); } catch (e) { console.log('skip', n, String(e).slice(0,50)); }
  }
  await b.close();
  console.log('DONE -> ' + OUT);
})().catch(e => { console.error('ERR', e); process.exit(1); });
