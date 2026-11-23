// 主会话用：看追加修改之后连一连有没有真的画出连线。只配对两对就退出，不打完、不上报成绩，所以不写业务数据。
const { chromium } = require('/opt/render/node_modules/playwright-core');
const fs = require('fs');
let PINYIN_OF = {};
try {
  const curriculum = JSON.parse(fs.readFileSync('/workspace/src/data/curriculum.json', 'utf8'));
  PINYIN_OF = Object.fromEntries(curriculum.characters.map((c) => [c.hanzi, c.pinyin]));
} catch (e) { console.log('curriculum.json not readable:', String(e).slice(0, 120)); }

(async () => {
  const b = await chromium.launch({ executablePath: process.env.RENDER_CHROMIUM, args: ['--no-sandbox'] });
  const p = await b.newPage({ viewport: { width: 1366, height: 900 }, locale: 'zh-CN' });
  const r = await p.goto('http://localhost:8081/games/match', { waitUntil: 'networkidle', timeout: 45000 });
  console.log('status', r.status());
  const readColumns = () => p.evaluate(() => Array.from(document.querySelectorAll('section.grid > div.kid-card'))
    .map((col) => Array.from(col.querySelectorAll('button')).map((x) => x.innerText.replace(/\s+/g, ' ').trim())));
  const cols = await readColumns();
  const left = cols[0] || [];
  const right = cols[1] || [];
  console.log('columns:', cols.length, 'left:', left.length, 'right:', right.length);
  const rightList = right.map((t) => ({ hanzi: (t.match(/[一-鿿]/) || [''])[0] }));
  let done = 0;
  for (const lb of left) {
    const py = (lb.match(/[a-züāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹ]+/) || [''])[0];
    const target = rightList.find((x) => PINYIN_OF[x.hanzi] === py);
    if (!target) { console.log('  no right match for', py); continue; }
    await p.locator('section.grid > div.kid-card').first().locator('button', { hasText: py }).first().click();
    await p.waitForTimeout(120);
    await p.locator('section.grid > div.kid-card').nth(1).locator('button', { hasText: target.hanzi }).first().click();
    await p.waitForTimeout(900);
    done++;
    const info = await p.evaluate(() => ({ paths: document.querySelectorAll('svg path').length, svgs: document.querySelectorAll('svg').length }));
    console.log('pairs matched:', done, '| svg:', info.svgs, '| svg path:', info.paths);
    if (done === 2) break;
  }
  await p.screenshot({ path: '/tmp/linecheck/match-lines.png' });
  await b.close();
})();
